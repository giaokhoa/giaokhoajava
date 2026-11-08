package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.giaokhoa.support.OidcFormLogin;
import com.giaokhoa.support.RealmFoundation;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Policy graph Keycloak 26.8.1 native dùng một lần trên integration TEST realm thuộc allowlist.
 * Direct Grant client chỉ dành cho test: không bao giờ là giaokhoa-web, không bao giờ là đường auth
 * của ứng dụng.
 */
final class NativeRevocationFixture implements AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String REDIRECT_URI = "http://127.0.0.1:18888/callback";
    private static final int SINGLE_CLIENT = 1;
    private static final int PAGE_SIZE = 1000;
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final String base;
    private final String adminToken;
    private final String clientName;
    private final String clientSecret;
    private @Nullable String clientId;
    private @Nullable String userId;
    private @Nullable String groupA;
    private @Nullable String groupB;
    private @Nullable String resourceA;
    private @Nullable String resourceB;

    NativeRevocationFixture() {
        base = required("TEST_KEYCLOAK_BASE_URL");
        // Hai làn stack TEST chuyên dụng đã kiểm chứng (cùng realm, chỉ khác cổng): làn 1
        // dùng 18081, làn 2 dùng 18082.
        if (!("http://127.0.0.1:18081".equals(base) || "http://127.0.0.1:18082".equals(base))
                || !"giaokhoa-it".equals(required("TEST_KEYCLOAK_REALM"))) {
            throw new IllegalArgumentException(
                    "Revocation native fixture is limited to TEST Keycloak");
        }
        clientName = "revocation-" + UUID.randomUUID();
        clientSecret = "test-secret-" + UUID.randomUUID();
        adminToken = masterToken();
        boolean provisioned = false;
        try {
            provision();
            provisioned = true;
        } finally {
            if (!provisioned) {
                close();
            }
        }
    }

    /**
     * Worker không thể bịa ra directory group. Seed native identity eligibility projection cho user
     * chỉ-dùng-cho-TEST này trước khi yêu cầu writer thật áp một business graph.
     */
    void provisionEligibilityForWorkerFixture() {
        String deepest =
                RealmFoundation.ensureEligibilityTree(
                        http, base + "/admin/realms/giaokhoa-it", () -> adminToken);
        addToGroup(deepest);
    }

    String userSubject() {
        return Objects.requireNonNull(userId);
    }

    String userToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", clientName);
        form.add("client_secret", clientSecret);
        form.add("username", clientName + "-user");
        form.add("password", clientSecret);
        Map<String, Object> tokens =
                http.post()
                        .uri(base + "/realms/giaokhoa-it/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        assertNotNull(tokens);
        return Objects.requireNonNull((String) tokens.get("access_token"), "access_token");
    }

    record WebTokens(String accessToken, String idToken, String refreshToken) {}

    WebTokens webTokensViaCodePkce() {
        // Authorization Code+PKCE thật của Keycloak cho confidential TEST client của ứng dụng,
        // qua bản dùng chung OidcFormLogin. HTTP form login là đủ ở đây; acceptance đầu-cuối
        // bao phủ thêm callback của trình duyệt/BFF.
        OidcFormLogin.WebTokens tokens =
                OidcFormLogin.webTokensViaCodePkce(
                        http,
                        base,
                        required("TEST_KEYCLOAK_REALM"),
                        REDIRECT_URI,
                        clientName + "-user",
                        clientSecret);
        String claims =
                new String(
                        Base64.getUrlDecoder().decode(tokens.idToken().split("[.]")[1]),
                        StandardCharsets.UTF_8);
        assertEquals(
                Objects.requireNonNull(userId), JSON.readTree(claims).get("sub").stringValue());
        return new WebTokens(tokens.accessToken(), tokens.idToken(), tokens.refreshToken());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    boolean containsGroupClaim(String accessToken) {
        String payload =
                new String(
                        Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                        StandardCharsets.UTF_8);
        return JSON.readTree(payload).has("groups");
    }

    boolean allowed(String userToken, String target) {
        return allowed(userToken, target, "enter");
    }

    boolean allowed(String userToken, String target, String scope) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:uma-ticket");
        form.add("audience", clientName);
        form.add("permission", ("a".equals(target) ? resourceA : resourceB) + "#" + scope);
        form.add("response_mode", "decision");
        var response =
                http.post()
                        .uri(base + "/realms/giaokhoa-it/protocol/openid-connect/token")
                        .headers(headers -> headers.setBearerAuth(userToken))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .onStatus(status -> status == HttpStatus.FORBIDDEN, (_, _) -> {})
                        .toEntity(MAP);
        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            return false;
        }
        assertEquals(200, response.getStatusCode().value());
        return NativeGateClient.requireDecision(response.getBody());
    }

    record GraphInventory(long resources, long policies, long permissions) {}

    GraphInventory graphInventory() {
        String root = "/clients/" + clientId + "/authz/resource-server";
        return new GraphInventory(
                countNative(root + "/resource"),
                countNative(root + "/policy"),
                countNative(root + "/permission/scope"));
    }

    private long countNative(String path) {
        return getList(path).stream()
                .filter(item -> String.valueOf(item.get("name")).startsWith(clientName + "-"))
                .count();
    }

    /**
     * Fixture lỗi phục vụ khôi phục của reconciliation worker: native resource tồn tại trong API
     * audience của ứng dụng TEST, nhưng scope permission/policy chưa được provision. Không bao giờ
     * đụng vào DEV.
     */
    PartialApplicationGraph partiallyProvisionApplicationResource(String targetKey, String scope) {
        String audience = required("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        if (!"giaokhoa-it-api".equals(audience)) {
            throw new IllegalArgumentException(
                    "Revocation partial graph requires TEST API audience");
        }
        var matches =
                getList("/clients?clientId=" + encode(audience)).stream()
                        .filter(item -> audience.equals(item.get("clientId")))
                        .toList();
        if (matches.size() > SINGLE_CLIENT) {
            throw new IllegalStateException("Duplicate TEST API audience clients");
        }
        boolean ownedClient = matches.isEmpty();
        String appClient =
                ownedClient
                        ? create(
                                "/clients",
                                Map.of(
                                        "clientId",
                                        audience,
                                        "protocol",
                                        "openid-connect",
                                        "enabled",
                                        true,
                                        "publicClient",
                                        false,
                                        "standardFlowEnabled",
                                        false,
                                        "directAccessGrantsEnabled",
                                        false,
                                        "authorizationServicesEnabled",
                                        true,
                                        "serviceAccountsEnabled",
                                        true,
                                        "fullScopeAllowed",
                                        false,
                                        "authorizationSettings",
                                        Map.of(
                                                "policyEnforcementMode", "ENFORCING",
                                                "allowRemoteResourceManagement", true,
                                                "decisionStrategy", "UNANIMOUS")))
                        : String.valueOf(matches.getFirst().get("id"));
        String root = "/clients/" + appClient + "/authz/resource-server";
        String scopeId = null;
        String resourceId = null;
        boolean ownedScope = false;
        boolean created = false;
        try {
            if (ownedClient) {
                removeDefaults(root);
            }
            var scopes =
                    getList(root + "/scope").stream()
                            .filter(item -> scope.equals(item.get("name")))
                            .toList();
            if (scopes.size() > SINGLE_CLIENT) {
                throw new IllegalStateException("Duplicate native content.read scope");
            }
            ownedScope = scopes.isEmpty();
            scopeId =
                    ownedScope
                            ? create(root + "/scope", Map.of("name", scope))
                            : String.valueOf(scopes.getFirst().get("id"));
            String name = targetKey;
            resourceId =
                    create(
                            root + "/resource",
                            Map.of(
                                    "name",
                                    name,
                                    "ownerManagedAccess",
                                    false,
                                    "scopes",
                                    List.of(Map.of("id", scopeId, "name", scope))));
            created = true;
            return new PartialApplicationGraph(
                    appClient, name, resourceId, scopeId, scope, ownedClient, ownedScope);
        } finally {
            if (!created) {
                if (ownedClient) {
                    delete("/clients/" + appClient);
                } else {
                    if (resourceId != null) {
                        delete(root + "/resource/" + resourceId);
                    }
                    if (ownedScope && scopeId != null) {
                        delete(root + "/scope/" + scopeId);
                    }
                }
            }
        }
    }

    final class PartialApplicationGraph implements AutoCloseable {
        private final String appClient;
        private final String name;
        private final String resource;
        private final String scopeId;
        private final String scopeName;
        private final boolean ownedClient;
        private final boolean ownedScope;
        private final List<String> ownedResources = new java.util.ArrayList<>();
        private final List<String> ownedPolicies = new java.util.ArrayList<>();
        private java.util.function.Supplier<List<String>> projectionPolicyNames = List::of;

        PartialApplicationGraph(
                String appClient,
                String name,
                String resource,
                String scopeId,
                String scopeName,
                boolean ownedClient,
                boolean ownedScope) {
            this.appClient = appClient;
            this.name = name;
            this.resource = resource;
            this.scopeId = scopeId;
            this.scopeName = scopeName;
            this.ownedClient = ownedClient;
            this.ownedScope = ownedScope;
            ownedResources.add(resource);
        }

        String resourceId() {
            return resource;
        }

        void cleanupProjectedPolicies(java.util.function.Supplier<List<String>> names) {
            // Graph writer production ghi đúng các native policy name thuộc sở hữu trong
            // authz_projection.
            // Đọc chúng lúc đóng, sau khi worker đã có cơ hội ghi chúng.
            projectionPolicyNames = names;
        }

        String addAuthorizedResource(String targetKey) {
            String nextResource =
                    create(
                            root() + "/resource",
                            Map.of(
                                    "name",
                                    targetKey,
                                    "ownerManagedAccess",
                                    false,
                                    "scopes",
                                    List.of(Map.of("id", scopeId, "name", scopeName))));
            ownedResources.add(nextResource);
            authorizeOwner(nextResource, targetKey);
            return nextResource;
        }

        void authorizeOwner() {
            authorizeOwner(resource, name);
        }

        private void authorizeOwner(String id, String targetKey) {
            // User policy là projection native của recipient/owner thật đã đăng nhập.
            String sourcePolicy =
                    create(
                            root() + "/policy/user",
                            Map.of(
                                    "name", "revocation-owner-" + targetKey,
                                    "type", "user",
                                    "logic", "POSITIVE",
                                    "decisionStrategy", "UNANIMOUS",
                                    "users", List.of(userId)));
            ownedPolicies.add(sourcePolicy);
            create(
                    root() + "/permission/scope",
                    Map.of(
                            "name",
                            "revocation-scope-" + targetKey,
                            "type",
                            "scope",
                            "decisionStrategy",
                            "UNANIMOUS",
                            "resources",
                            List.of(id),
                            "scopes",
                            List.of(scopeId),
                            "policies",
                            List.of(sourcePolicy)));
        }

        boolean allowed(String userToken, String resourceId) {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:uma-ticket");
            form.add("audience", required("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
            form.add("permission", resourceId + "#" + scopeName);
            form.add("response_mode", "decision");
            var response =
                    http.post()
                            .uri(base + "/realms/giaokhoa-it/protocol/openid-connect/token")
                            .headers(headers -> headers.setBearerAuth(userToken))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .onStatus(status -> status == HttpStatus.FORBIDDEN, (_, _) -> {})
                            .toEntity(MAP);
            if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
                return false;
            }
            assertEquals(200, response.getStatusCode().value());
            return NativeGateClient.requireDecision(response.getBody());
        }

        int resourceCount() {
            return (int)
                    getList(root() + "/resource").stream()
                            .filter(item -> name.equals(item.get("name")))
                            .count();
        }

        long policyCount() {
            return getList(root() + "/policy").stream()
                    .filter(item -> !DEFAULT_POLICY.equals(item.get("name")))
                    .count();
        }

        /**
         * Chỉ đếm policy mà permission của chính resource này tham chiếu. Client dùng chung nên đếm
         * toàn cục nhạy lịch sử realm: writer upsert policy theo tên xác định nên retry có thể
         * không tạo thêm policy nào mà vẫn đúng (xem Sổ B13).
         */
        long scopedPolicyCount() {
            List<Map<String, Object>> permissions =
                    getList(root() + "/resource/" + resource + "/permissions");
            return referencedPolicyIds(List.of(), permissions).size();
        }

        long permissionCount() {
            // Representation của permission không mang association; các permission của một
            // resource được đọc từ association endpoint của chính resource đó.
            return getList(root() + "/resource/" + resource + "/permissions").size();
        }

        /**
         * Các policy liên kết từ bất kỳ permission hoặc policy nào khác; representation đơn thuần
         * che giấu các liên kết, nên chúng được đọc từ association endpoint của từng holder.
         */
        private java.util.Set<String> referencedPolicyIds(
                List<Map<String, Object>> policies, List<Map<String, Object>> permissions) {
            java.util.Set<String> referenced = new java.util.HashSet<>();
            List<Map<String, Object>> holders = new java.util.ArrayList<>(permissions);
            holders.addAll(policies);
            for (Map<String, Object> holder : holders) {
                for (Map<String, Object> linked :
                        getList(root() + "/policy/" + holder.get("id") + "/associatedPolicies")) {
                    referenced.add(String.valueOf(linked.get("id")));
                }
            }
            return referenced;
        }

        private String root() {
            return "/clients/" + appClient + "/authz/resource-server";
        }

        @Override
        public void close() {
            if (ownedClient) {
                delete("/clients/" + appClient);
                return;
            }
            // Xóa permission trước, gồm cả những permission writer thật đã provision trên
            // resource duy nhất của ta. Không bao giờ xóa shared policy/scope hay API resource
            // không liên quan.
            for (String id : ownedResources) {
                for (Map<String, Object> permission :
                        getList(root() + "/resource/" + id + "/permissions")) {
                    delete(root() + "/permission/scope/" + permission.get("id"));
                }
            }
            for (String id : ownedPolicies) {
                delete(root() + "/policy/" + id);
            }
            // Chỉ các policy được projection này nêu tên mới bị thu gom rác.
            // Shared policy vẫn còn được resource không liên quan tham chiếu thì được giữ lại.
            List<String> projected = projectionPolicyNames.get();
            int remaining = projected.size();
            while (remaining > 0) {
                remaining--;
                boolean deleted = false;
                List<Map<String, Object>> policies = getList(root() + "/policy");
                List<Map<String, Object>> permissions = getList(root() + "/permission/scope");
                java.util.Set<String> referenced = referencedPolicyIds(policies, permissions);
                for (Map<String, Object> policy : policies) {
                    if (!projected.contains(policy.get("name"))) {
                        continue;
                    }
                    String id = String.valueOf(policy.get("id"));
                    boolean stillInUse = referenced.contains(id);
                    if (!stillInUse) {
                        delete(root() + "/policy/" + id);
                        deleted = true;
                        break;
                    }
                }
                if (!deleted) {
                    break; // Không còn policy nào do test sở hữu có thể xóa an toàn nữa.
                }
            }
            for (String id : ownedResources) {
                delete(root() + "/resource/" + id);
            }
            if (ownedScope && getList(root() + "/resource").isEmpty()) {
                // Scope có thể được worker dùng chung cho account resource khác trong client.
                delete(root() + "/scope/" + scopeId);
            }
        }
    }

    String resourceId(String target) {
        return Objects.requireNonNull("a".equals(target) ? resourceA : resourceB);
    }

    void revoke(String target) {
        String group = "a".equals(target) ? groupA : groupB;
        http.delete()
                .uri(admin("/users/" + userId + "/groups/" + group))
                .headers(headers -> headers.setBearerAuth(adminToken))
                .retrieve()
                .toBodilessEntity();
    }

    private void provision() {
        clientId =
                create(
                        "/clients",
                        Map.of(
                                "clientId", clientName,
                                "secret", clientSecret,
                                "protocol", "openid-connect",
                                "enabled", true,
                                "publicClient", false,
                                "standardFlowEnabled", false,
                                "directAccessGrantsEnabled", true,
                                "authorizationServicesEnabled", true,
                                "serviceAccountsEnabled", true,
                                "fullScopeAllowed", false));
        String scope =
                create(
                        "/clients/" + clientId + "/authz/resource-server/scope",
                        Map.of("name", "enter"));
        String contentScope =
                create(
                        "/clients/" + clientId + "/authz/resource-server/scope",
                        Map.of("name", "content.read"));
        // Một authorization server mới tạo có thể có các mặc định wildcard: xóa chúng.
        removeDefaults();
        String createdGroupA = create("/groups", Map.of("name", clientName + "-a"));
        groupA = createdGroupA;
        String createdGroupB = create("/groups", Map.of("name", clientName + "-b"));
        groupB = createdGroupB;
        userId =
                create(
                        "/users",
                        Map.of(
                                "username",
                                clientName + "-user",
                                "enabled",
                                true,
                                "email",
                                clientName + "@example.invalid",
                                "emailVerified",
                                true,
                                "firstName",
                                "Synthetic",
                                "lastName",
                                "User",
                                "requiredActions",
                                List.of(),
                                "credentials",
                                List.of(
                                        Map.of(
                                                "type",
                                                "password",
                                                "value",
                                                clientSecret,
                                                "temporary",
                                                false))));
        addToGroup(createdGroupA);
        addToGroup(createdGroupB);
        String createdResourceA = resource("a", scope, contentScope);
        resourceA = createdResourceA;
        String createdResourceB = resource("b", scope, contentScope);
        resourceB = createdResourceB;
        permission("a", createdResourceA, scope, createdGroupA);
        permission("b", createdResourceB, scope, createdGroupB);
        permission("a-content", createdResourceA, contentScope, createdGroupA);
        permission("b-content", createdResourceB, contentScope, createdGroupB);
    }

    private void removeDefaults() {
        removeDefaults("/clients/" + clientId + "/authz/resource-server");
    }

    private void removeDefaults(String root) {
        for (Map<String, Object> permission : getList(root + "/permission/resource")) {
            if (DEFAULT_PERMISSION.equals(permission.get("name"))) {
                delete(root + "/permission/resource/" + permission.get("id"));
            }
        }
        for (Map<String, Object> resource : getList(root + "/resource")) {
            if (DEFAULT_RESOURCE.equals(resource.get("name"))) {
                delete(root + "/resource/" + resource.get("_id"));
            }
        }
        for (Map<String, Object> policy : getList(root + "/policy")) {
            if (DEFAULT_POLICY.equals(policy.get("name"))) {
                delete(root + "/policy/" + policy.get("id"));
            }
        }
    }

    private String resource(String name, String scope, String contentScope) {
        return create(
                "/clients/" + clientId + "/authz/resource-server/resource",
                Map.of(
                        "name",
                        clientName + "-" + name,
                        "ownerManagedAccess",
                        false,
                        "scopes",
                        List.of(
                                Map.of("id", scope, "name", "enter"),
                                Map.of("id", contentScope, "name", "content.read"))));
    }

    private void permission(String suffix, String resource, String scope, String group) {
        String root = "/clients/" + clientId + "/authz/resource-server";
        String groupPolicy =
                create(
                        root + "/policy/group",
                        Map.of(
                                "name", clientName + "-members-" + suffix,
                                "type", "group",
                                "logic", "POSITIVE",
                                "decisionStrategy", "UNANIMOUS",
                                "groups", List.of(Map.of("id", group, "extendChildren", false))));
        create(
                root + "/permission/scope",
                Map.of(
                        "name",
                        clientName + "-enter-" + suffix,
                        "type",
                        "scope",
                        "decisionStrategy",
                        "UNANIMOUS",
                        "resources",
                        List.of(resource),
                        "scopes",
                        List.of(scope),
                        "policies",
                        List.of(groupPolicy)));
    }

    void setEmailVerified(boolean verified) {
        http.put()
                .uri(admin("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("emailVerified", verified))
                .retrieve()
                .toBodilessEntity();
    }

    private void addToGroup(String group) {
        http.put()
                .uri(admin("/users/" + userId + "/groups/" + group))
                .headers(headers -> headers.setBearerAuth(adminToken))
                .retrieve()
                .toBodilessEntity();
    }

    private String masterToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", required("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", required("TEST_KEYCLOAK_MASTER_PASSWORD"));
        Map<String, Object> response =
                http.post()
                        .uri(base + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        assertNotNull(response);
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    private String create(String path, Map<String, ?> body) {
        var response =
                http.post()
                        .uri(admin(path))
                        .headers(headers -> headers.setBearerAuth(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location != null) {
            String value = location.getPath();
            return value.substring(value.lastIndexOf('/') + 1);
        }
        // POST của Authorization Services trả một ID trong JSON thay vì trong Location header.
        String createdBody =
                Objects.requireNonNull(
                        response.getBody(), "Keycloak must return a created ID for " + path);
        var created = JSON.readTree(createdBody);
        var id = created.has("id") ? created.get("id") : created.get("_id");
        assertNotNull(id, "Keycloak must return a created ID for " + path);
        return id.stringValue();
    }

    private List<Map<String, Object>> getList(String path) {
        boolean paginated =
                path.endsWith("/resource")
                        || path.endsWith("/policy")
                        || path.endsWith("/permission/scope");
        if (!paginated) {
            return getListPage(path);
        }
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (int first = 0; ; first += PAGE_SIZE) {
            List<Map<String, Object>> page =
                    getListPage(path + "?first=" + first + "&max=" + PAGE_SIZE);
            all.addAll(page);
            if (page.size() < PAGE_SIZE) {
                return all;
            }
        }
    }

    private List<Map<String, Object>> getListPage(String path) {

        List<Map<String, Object>> result =
                http.get()
                        .uri(admin(path))
                        .headers(headers -> headers.setBearerAuth(adminToken))
                        .retrieve()
                        .body(LIST);
        assertNotNull(result);
        return result;
    }

    private void delete(String path) {
        http.delete()
                .uri(admin(path))
                .headers(headers -> headers.setBearerAuth(adminToken))
                .retrieve()
                .toBodilessEntity();
    }

    private String admin(String path) {
        return base + "/admin/realms/giaokhoa-it" + path;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }

    @Override
    public void close() {
        if (userId != null) {
            delete("/users/" + userId);
        }
        if (groupA != null) {
            delete("/groups/" + groupA);
        }
        if (groupB != null) {
            delete("/groups/" + groupB);
        }
        if (clientId != null) {
            delete("/clients/" + clientId);
        }
    }
}
