package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.support.IntegrationEnvironment;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Kiểm chứng đường publish của grant platform trên Keycloak và PostgreSQL thật: seeder ghi đúng một
 * projection bundle trên partition PLATFORM, reconcile áp graph để PDP quyết định theo membership
 * hiện hành của group operator — operator đủ điều kiện được cả năm scope, operator chưa eligible
 * chỉ mở được account.recover theo ngoại lệ của hợp đồng, người ngoài group bị từ chối; seed lại
 * không sinh revision, rút desired về tập rỗng gỡ mọi root, gỡ membership từ chối ngay quyết định
 * sau bằng cùng token, và drift trên root permission được phát hiện rồi áp lại.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlatformProjectionIT extends IntegrationEnvironment {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final String GRANT_CLIENT = "platform-projection-it";
    private static final String GRANT_SECRET = "platform-projection-it-secret";
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");

    @BeforeAll
    static void seedRealmPrerequisites() {
        ensureGroupPath(NativePolicyTemplates.ELIGIBILITY_GROUP);
        ensureGroupPath(NativePolicyTemplates.PLATFORM_OPERATORS_GROUP);
        ensureApiClient();
        ensureGrantClient();
    }

    // Class này tự lái reconciler đồng bộ (seeder + reconcilePartition) nên không cần worker
    // thật: mock để worker nền không tự xử lý operation song song với đường lái trực tiếp
    // của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectionReconciler reconciler;
    @Autowired PlatformProjectionSeeder seeder;
    @Autowired PolicyGraphWriter writer;

    @Test
    void publishGrantsOperatorsByCurrentGroupMembership() {
        publishPlatform();
        UUID partitionId = platformPartitionId();
        assertEquals(
                1,
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authz_projection WHERE partition_id = ?",
                                Integer.class,
                                partitionId)),
                "Bundle platform là đúng một projection cho target platform");
        assertEquals(
                "platform",
                jdbc.queryForObject(
                        "SELECT target_key FROM authz_projection WHERE partition_id = ?",
                        String.class,
                        partitionId));
        String resourceId =
                writer.findResourceId(NativePolicyTemplates.platformKey()).orElseThrow();

        TestUser operator = newUser("platformit-op", true, true);
        TestUser recoverOnly = newUser("platformit-recover", false, true);
        TestUser outsider = newUser("platformit-outsider", true, false);
        try {
            String operatorToken = userToken(operator);
            for (String scope : PlatformPolicyProjector.managedScopes()) {
                assertTrue(
                        allowed(operatorToken, resourceId, scope),
                        "Operator đủ điều kiện phải được scope " + scope);
            }
            String recoverToken = userToken(recoverOnly);
            assertTrue(
                    allowed(recoverToken, resourceId, "account.recover"),
                    "account.recover là ngoại lệ ngoài eligibility gate");
            assertFalse(
                    allowed(recoverToken, resourceId, "center.create"),
                    "Operator chưa eligible bị chặn ở scope nghiệp vụ");
            String outsiderToken = userToken(outsider);
            for (String scope : PlatformPolicyProjector.managedScopes()) {
                assertFalse(
                        allowed(outsiderToken, resourceId, scope),
                        "Người ngoài group operator bị từ chối scope " + scope);
            }
        } finally {
            operator.delete();
            recoverOnly.delete();
            outsider.delete();
        }
    }

    @Test
    void reseedingKeepsTheSameRevisionAndGraph() {
        publishPlatform();
        UUID partitionId = platformPartitionId();
        long revision =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT desired_revision FROM authz_partition WHERE id = ?",
                                Long.class,
                                partitionId));
        seeder.seed();
        assertEquals(
                revision,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        partitionId),
                "Seed lại khi desired đã khớp không được sinh revision mới");
        assertEquals(
                "READY",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?",
                        String.class,
                        partitionId));
    }

    @Test
    void emptyDesiredRetractsEveryRootAndReseedingRestoresTheGrant() {
        publishPlatform();
        UUID partitionId = platformPartitionId();
        String resourceId =
                writer.findResourceId(NativePolicyTemplates.platformKey()).orElseThrow();
        TestUser operator = newUser("platformit-retract", true, true);
        try {
            String token = userToken(operator);
            assertTrue(allowed(token, resourceId, "center.create"));

            // Hợp đồng rút toàn bộ grant được ghi thành desired của tập rỗng trên cùng bundle.
            reconciler.fencePartition(partitionId);
            jdbc.update(
                    "UPDATE authz_projection SET desired_hash = ? WHERE target_key = 'platform'",
                    ProjectionReconciler.desiredHash(
                            NativePolicyTemplates.platformKey(),
                            PlatformPolicyProjector.bundleScope(),
                            List.of()));
            reconciler.reconcilePartition(partitionId);
            assertEquals(
                    0,
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM authz_projection"
                                            + " WHERE partition_id = ?",
                                    Integer.class,
                                    partitionId)),
                    "Projection đã retract bị gỡ khỏi partition");
            for (String scope : PlatformPolicyProjector.managedScopes()) {
                assertFalse(
                        allowed(token, resourceId, scope),
                        "Root đã retract phải từ chối scope " + scope);
            }

            seeder.seed();
            reconciler.reconcilePartition(partitionId);
            assertTrue(
                    allowed(token, resourceId, "center.create"),
                    "Seed lại theo hợp đồng hiện hành khôi phục grant");
        } finally {
            operator.delete();
        }
    }

    @Test
    void leavingTheOperatorGroupDeniesTheSameTokenOnTheNextDecision() {
        publishPlatform();
        String resourceId =
                writer.findResourceId(NativePolicyTemplates.platformKey()).orElseThrow();
        TestUser operator = newUser("platformit-leave", true, true);
        try {
            String token = userToken(operator);
            assertTrue(allowed(token, resourceId, "center.create"));
            leaveGroup(operator.subject(), NativePolicyTemplates.PLATFORM_OPERATORS_GROUP);
            assertFalse(
                    allowed(token, resourceId, "center.create"),
                    "Quyết định sau khi rời group phải từ chối dù dùng cùng token");
            assertFalse(allowed(token, resourceId, "account.recover"));
        } finally {
            operator.delete();
        }
    }

    @Test
    void driftInARootPermissionIsDetectedAndRepaired() {
        publishPlatform();
        UUID partitionId = platformPartitionId();
        assertTrue(reconciler.verifyStoredGraph(partitionId));
        deleteRootPermission(
                NativePolicyTemplates.rootPermissionName(
                        NativePolicyTemplates.platformKey(), "center.list"));
        assertFalse(
                reconciler.verifyStoredGraph(partitionId),
                "Root permission bị xóa tay phải bị phát hiện là drift");
        reconciler.fencePartition(partitionId);
        assertTrue(reconciler.tryReconcilePartition(partitionId));
        assertTrue(reconciler.verifyStoredGraph(partitionId));
        String resourceId =
                writer.findResourceId(NativePolicyTemplates.platformKey()).orElseThrow();
        TestUser operator = newUser("platformit-drift", true, true);
        try {
            assertTrue(allowed(userToken(operator), resourceId, "center.list"));
        } finally {
            operator.delete();
        }
    }

    // ---------- Trạng thái platform ----------

    private void publishPlatform() {
        seeder.seed();
        reconciler.reconcilePartition(platformPartitionId());
        assertEquals(
                "READY",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE kind = 'PLATFORM'"
                                + " AND owner_key = 'platform'",
                        String.class));
    }

    private UUID platformPartitionId() {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE kind = 'PLATFORM'"
                                + " AND owner_key = 'platform'",
                        UUID.class),
                "platform partition");
    }

    // ---------- Quyết định PDP thật bằng token của user ----------

    private boolean allowed(String userToken, String resourceId, String scope) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:uma-ticket");
        form.add("audience", fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
        form.add("permission", resourceId + "#" + scope);
        form.add("response_mode", "decision");
        var response =
                HTTP.post()
                        .uri(BASE + "/realms/" + REALM + "/protocol/openid-connect/token")
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
        Map<String, Object> body = response.getBody();
        return body != null && Boolean.TRUE.equals(body.get("result"));
    }

    private String userToken(TestUser user) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", GRANT_CLIENT);
        form.add("client_secret", GRANT_SECRET);
        form.add("username", user.username());
        form.add("password", user.password());
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("User token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private record TestUser(String subject, String username, String password) {
        void delete() {
            deleteResource(admin("/users/" + subject));
        }
    }

    private TestUser newUser(String prefix, boolean eligible, boolean operator) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "platformit-password-" + UUID.randomUUID();
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("email", username + "@example.test");
        body.put("emailVerified", true);
        body.put("requiredActions", List.of());
        body.put(
                "credentials",
                List.of(Map.of("type", "password", "value", password, "temporary", false)));
        var response =
                HTTP.post()
                        .uri(admin("/users"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        String subject = path.substring(path.lastIndexOf('/') + 1);
        if (eligible) {
            joinGroup(subject, NativePolicyTemplates.ELIGIBILITY_GROUP);
        }
        if (operator) {
            joinGroup(subject, NativePolicyTemplates.PLATFORM_OPERATORS_GROUP);
        }
        return new TestUser(subject, username, password);
    }

    private void joinGroup(String subject, String groupPath) {
        HTTP.put()
                .uri(admin("/users/" + subject + "/groups/" + groupId(groupPath)))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private void leaveGroup(String subject, String groupPath) {
        HTTP.delete()
                .uri(admin("/users/" + subject + "/groups/" + groupId(groupPath)))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private static String groupId(String path) {
        Map<String, Object> group =
                HTTP.get()
                        .uri(admin("/group-by-path" + path))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(MAP);
        if (group == null || group.get("id") == null) {
            throw new IllegalStateException("Realm group is not provisioned: " + path);
        }
        return String.valueOf(group.get("id"));
    }

    private void deleteRootPermission(String permissionName) {
        String root = "/clients/" + apiClientId() + "/authz/resource-server";
        for (Map<String, Object> permission :
                getList(admin(root + "/permission/scope?name=" + permissionName))) {
            if (permissionName.equals(permission.get("name"))) {
                deleteResource(admin(root + "/permission/scope/" + permission.get("id")));
                return;
            }
        }
        throw new IllegalStateException("Root permission not found: " + permissionName);
    }

    private static String apiClientId() {
        String audience = fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + audience))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (matches == null || matches.isEmpty()) {
            throw new IllegalStateException("Resource server client is missing: " + audience);
        }
        return String.valueOf(matches.getFirst().get("id"));
    }

    /**
     * Các group nền của hợp đồng là object realm dùng chung: chỉ tạo khi thiếu và không bao giờ xóa
     * ở teardown, để một class test không phá graph của class khác đang chiếu lên cùng group.
     */
    private static void ensureGroupPath(String groupPath) {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : groupPath.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            path.append('/').append(segment);
            String currentPath = path.toString();
            String found = null;
            try {
                Map<String, Object> group =
                        HTTP.get()
                                .uri(admin("/group-by-path" + currentPath))
                                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                                .retrieve()
                                .body(MAP);
                if (group != null && currentPath.equals(group.get("path"))) {
                    found = String.valueOf(group.get("id"));
                }
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    throw ex;
                }
            }
            if (found == null) {
                String endpoint =
                        parentId == null ? "/groups" : "/groups/" + parentId + "/children";
                found = createResource(admin(endpoint), Map.of("name", segment));
            }
            parentId = found;
        }
    }

    /**
     * Resource server client mà policy graph writer resolve theo audience: test-realm.json đã khai
     * client này nên bình thường chỉ cần dùng chung; giữ đường tạo khi thiếu làm lưới an toàn cho
     * realm dựng từ file cũ. Client là object realm theo tên cố định dùng chung toàn suite nên
     * không bao giờ xóa ở teardown, kể cả khi chính class này tạo ra.
     */
    private static void ensureApiClient() {
        String audience = fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + audience))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        String clientId =
                createResource(
                        admin("/clients"),
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
                                        "policyEnforcementMode",
                                        "ENFORCING",
                                        "allowRemoteResourceManagement",
                                        true,
                                        "decisionStrategy",
                                        "UNANIMOUS")));
        removeClientDefaults(clientId);
    }

    /**
     * Client riêng của class này chỉ để lấy token user bằng password grant cho quyết định PDP: chỉ
     * tạo khi thiếu và không bao giờ xóa ở teardown, để lần chạy sau dùng lại cùng client.
     */
    private static void ensureGrantClient() {
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + GRANT_CLIENT))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        createResource(
                admin("/clients"),
                Map.of(
                        "clientId",
                        GRANT_CLIENT,
                        "secret",
                        GRANT_SECRET,
                        "protocol",
                        "openid-connect",
                        "enabled",
                        true,
                        "publicClient",
                        false,
                        "standardFlowEnabled",
                        false,
                        "directAccessGrantsEnabled",
                        true,
                        "serviceAccountsEnabled",
                        false,
                        "fullScopeAllowed",
                        false));
    }

    private static void removeClientDefaults(String clientId) {
        String root = "/clients/" + clientId + "/authz/resource-server";
        for (Map<String, Object> permission : getList(admin(root + "/permission/resource"))) {
            if (DEFAULT_PERMISSION.equals(permission.get("name"))) {
                deleteResource(admin(root + "/permission/resource/" + permission.get("id")));
            }
        }
        for (Map<String, Object> resource : getList(admin(root + "/resource"))) {
            if (DEFAULT_RESOURCE.equals(resource.get("name"))) {
                deleteResource(admin(root + "/resource/" + resource.get("_id")));
            }
        }
        for (Map<String, Object> policy : getList(admin(root + "/policy"))) {
            if (DEFAULT_POLICY.equals(policy.get("name"))) {
                deleteResource(admin(root + "/policy/" + policy.get("id")));
            }
        }
    }

    private static List<Map<String, Object>> getList(String uri) {
        List<Map<String, Object>> result =
                HTTP.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (result == null) {
            throw new IllegalStateException("Keycloak list not readable: " + uri);
        }
        return result;
    }

    private static String createResource(String uri, Map<String, ?> body) {
        var response =
                HTTP.post()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a location for " + uri);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private static void deleteResource(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                .retrieve()
                .toBodilessEntity();
    }

    private String masterToken() {
        return masterTokenStatic();
    }

    private static String masterTokenStatic() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("Master token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    static String fixture(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }
}
