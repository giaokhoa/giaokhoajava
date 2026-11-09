package com.giaokhoa.authorization.policy;

import com.giaokhoa.authorization.keycloak.KeycloakProvisionerFactory;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ghi một {@link PolicyGraph} vào native authorization graph của Keycloak trên resource server,
 * dùng credential của provisioner. Mọi đối tượng được tra theo template name xác định của chúng
 * trước khi tạo, nên áp cùng một graph hai lần sẽ hội tụ mà không trùng lặp; root permission được
 * đối chiếu và cập nhật khi một revision mới thay đổi tập policy của nó. Graph được đọc lại trước
 * khi kết quả được trả về: một request đã gửi không bao giờ được coi là bằng chứng. Leaf policy
 * được dùng chung giữa các graph và không bao giờ bị xóa tại đây.
 */
@Component
public class PolicyGraphWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POSITIVE = "POSITIVE";
    private static final String NEGATIVE = "NEGATIVE";
    private static final String UNANIMOUS = "UNANIMOUS";
    private static final String AFFIRMATIVE = "AFFIRMATIVE";
    private static final int SINGLE = 1;
    private static final int PAGE_SIZE = 1000;
    private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final String baseUrl;
    private final String realm;
    private final OAuth2AuthorizedClientManager manager;
    private final String audienceClientId;
    private final @Nullable ResourceMutationCoordinator mutationCoordinator;

    @Autowired
    public PolicyGraphWriter(
            @Value("${giaokhoa.keycloak.admin-base-url}") String baseUrl,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                    String issuerUri,
            @Value("${giaokhoa.keycloak.provisioner.client-id}") String clientId,
            @Value("${giaokhoa.keycloak.provisioner.client-secret}") String clientSecret,
            @Value("${giaokhoa.keycloak.authorization-audience}") String audienceClientId,
            ResourceMutationCoordinator mutationCoordinator) {
        this(
                RestClient.builder(),
                baseUrl,
                KeycloakProvisionerFactory.realmOf(issuerUri),
                KeycloakProvisionerFactory.clientManager(
                        baseUrl.replaceAll("/+$", ""),
                        KeycloakProvisionerFactory.realmOf(issuerUri),
                        clientId,
                        clientSecret),
                audienceClientId,
                mutationCoordinator);
    }

    /** Constructor cho unit test; production luôn cung cấp distributed coordinator. */
    PolicyGraphWriter(
            RestClient.Builder builder,
            String baseUrl,
            OAuth2AuthorizedClientManager manager,
            String audienceClientId) {
        this(builder, baseUrl, "giaokhoa", manager, audienceClientId, null);
    }

    PolicyGraphWriter(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            OAuth2AuthorizedClientManager manager,
            String audienceClientId) {
        this(builder, baseUrl, realm, manager, audienceClientId, null);
    }

    PolicyGraphWriter(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            OAuth2AuthorizedClientManager manager,
            String audienceClientId,
            @Nullable ResourceMutationCoordinator mutationCoordinator) {
        this.http = builder.build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.manager = manager;
        this.audienceClientId = audienceClientId;
        this.mutationCoordinator = mutationCoordinator;
    }

    /** Thứ một lần apply thành công để lại: projection resource ID và các policy name của nó. */
    public record AppliedGraph(String resourceId, List<String> policyNames) {
        public AppliedGraph {
            policyNames = List.copyOf(policyNames);
        }
    }

    /** Tạo hoặc cập nhật toàn bộ graph của một cặp target/scope, rồi xác minh bằng cách đọc lại. */
    public AppliedGraph apply(PolicyGraph graph) {
        ResourceMutationCoordinator coordinator = mutationCoordinator;
        if (coordinator == null) {
            return applyLocked(graph); // unit test dùng Keycloak mock trong bộ nhớ
        }
        return coordinator.withResourceLock(graph.targetKey(), () -> applyLocked(graph));
    }

    private AppliedGraph applyLocked(PolicyGraph graph) {
        String root = resourceServerRoot();
        String scopeId = ensureScope(root, graph.scope());
        String resourceId = ensureResource(root, graph.targetKey(), scopeId, graph.scope());
        Map<String, Map<String, Object>> policies = policyIndex(root);

        Set<String> policyNames = new TreeSet<>();
        List<String> rootPolicies = new ArrayList<>();
        for (PolicyGraph.Gate gate : graph.commonGates()) {
            rootPolicies.addAll(gatePolicies(root, policies, gate, policyNames));
        }
        List<String> sourceNodes = new ArrayList<>();
        for (PolicyGraph.Source source : graph.sources()) {
            List<String> leaves = new ArrayList<>();
            for (PolicyGraph.Gate gate : source.gates()) {
                leaves.addAll(gatePolicies(root, policies, gate, policyNames));
            }
            if (leaves.size() == SINGLE) {
                sourceNodes.add(leaves.getFirst());
            } else {
                String name = NativePolicyTemplates.sourcePolicyName(source.descriptorHash());
                sourceNodes.add(ensureAggregate(root, policies, name, UNANIMOUS, leaves));
                policyNames.add(name);
            }
        }
        if (sourceNodes.size() > SINGLE) {
            String name =
                    NativePolicyTemplates.anySourcePolicyName(graph.targetKey(), graph.scope());
            rootPolicies.add(ensureAggregate(root, policies, name, AFFIRMATIVE, sourceNodes));
            policyNames.add(name);
        } else {
            rootPolicies.add(sourceNodes.getFirst());
        }

        String permissionName =
                NativePolicyTemplates.rootPermissionName(graph.targetKey(), graph.scope());
        String permissionId =
                ensureRootPermission(root, permissionName, resourceId, scopeId, rootPolicies);
        verifyReadBack(root, permissionId, resourceId, scopeId, rootPolicies, graph.targetKey());
        return new AppliedGraph(resourceId, List.copyOf(policyNames));
    }

    /**
     * Xóa root permission của một cặp target/scope, và cùng với nó OR aggregate riêng của cặp: theo
     * cách dựng, aggregate đó chỉ được tham chiếu bởi chính root permission này. Các leaf policy
     * dùng chung ở lại cho các graph vẫn dùng chúng.
     */
    public void retractRoot(String targetKey, String scope) {
        ResourceMutationCoordinator coordinator = mutationCoordinator;
        if (coordinator == null) {
            retractRootLocked(targetKey, scope); // unit test dùng Keycloak mock trong bộ nhớ
        } else {
            coordinator.withResourceLock(
                    targetKey,
                    () -> {
                        retractRootLocked(targetKey, scope);
                        return null;
                    });
        }
    }

    private void retractRootLocked(String targetKey, String scope) {
        // Cùng phép validation template cố định như graph: target lạ không bao giờ tới được
        // Keycloak.
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        if (scope == null || !type.scopes().contains(scope)) {
            throw new IllegalArgumentException(
                    "Scope is not fixed for target " + targetKey + ": " + scope);
        }
        String root = resourceServerRoot();
        String permissionName = NativePolicyTemplates.rootPermissionName(targetKey, scope);
        for (Map<String, Object> permission : list(root + "/permission/scope")) {
            if (permissionName.equals(permission.get("name"))) {
                delete(root + "/permission/scope/" + permission.get("id"));
            }
        }
        String anyName = NativePolicyTemplates.anySourcePolicyName(targetKey, scope);
        for (Map<String, Object> policy : list(root + "/policy")) {
            if (anyName.equals(policy.get("name"))) {
                delete(root + "/policy/" + policy.get("id"));
            }
        }
    }

    /**
     * Kiểm tra drift chỉ đọc phục vụ reconciliation: chỉ true khi resource đã ghi vẫn tồn tại dưới
     * ID đã ghi của nó, mọi policy đã ghi vẫn hiện diện, và root permission của cặp vẫn tham chiếu
     * resource đó với đúng tập policy đã ghi được liên kết — cùng tập policy mà đường apply xác
     * minh khi đọc lại. Mọi khác biệt đều bị coi là drift ngoài hệ thống (out-of-band). Nội dung
     * bên trong một policy vẫn còn liên kết không được đối chiếu; đó là việc của chính graph đã
     * ghi, được thực thi khi graph được áp (lại).
     */
    public boolean isGraphIntact(
            String targetKey, String scope, String resourceId, List<String> policyNames) {
        String root = resourceServerRoot();
        boolean resourceFound = false;
        for (Map<String, Object> resource : list(root + "/resource")) {
            if (targetKey.equals(resource.get("name"))) {
                resourceFound = resourceId.equals(String.valueOf(resource.get("_id")));
                break;
            }
        }
        if (!resourceFound) {
            return false;
        }
        Map<String, Map<String, Object>> policies = policyIndex(root);
        Set<String> recordedPolicyIds = new HashSet<>();
        for (String name : policyNames) {
            Map<String, Object> policy = policies.get(name);
            if (policy == null) {
                return false;
            }
            recordedPolicyIds.add(String.valueOf(policy.get("id")));
        }
        String permissionName = NativePolicyTemplates.rootPermissionName(targetKey, scope);
        for (Map<String, Object> permission : list(root + "/permission/scope")) {
            if (permissionName.equals(permission.get("name"))) {
                String permissionId = String.valueOf(permission.get("id"));
                return associatedIds(root, permissionId, "resources", "_id").contains(resourceId)
                        && associatedIds(root, permissionId, "associatedPolicies", "id")
                                .equals(recordedPolicyIds);
            }
        }
        return false;
    }

    /**
     * Tra cứu chỉ đọc cho đường decision: resource native được đặt tên theo target key, nên
     * resource mà một lời gọi PDP phải nêu tên là resource được ghi dưới tên đó, nếu tồn tại.
     */
    public Optional<String> findResourceId(String targetKey) {
        String root = resourceServerRoot();
        for (Map<String, Object> resource : list(root + "/resource")) {
            if (targetKey.equals(resource.get("name"))) {
                return Optional.of(String.valueOf(resource.get("_id")));
            }
        }
        return Optional.empty();
    }

    private List<String> gatePolicies(
            String root,
            Map<String, Map<String, Object>> policies,
            PolicyGraph.Gate gate,
            Set<String> policyNames) {
        if (gate instanceof PolicyGraph.Gate.GroupMembership group) {
            String groupId = realmGroupId(group.groupPath());
            String name = NativePolicyTemplates.groupPolicyName(group.groupPath());
            policyNames.add(name);
            return List.of(
                    ensurePolicy(
                            root,
                            policies,
                            name,
                            "group",
                            Map.of(
                                    "name",
                                    name,
                                    "type",
                                    "group",
                                    "logic",
                                    POSITIVE,
                                    "decisionStrategy",
                                    UNANIMOUS,
                                    "groups",
                                    List.of(Map.of("id", groupId, "extendChildren", false)))));
        }
        if (gate instanceof PolicyGraph.Gate.Actor actor) {
            String name = NativePolicyTemplates.userPolicyName(actor.keycloakUserId());
            policyNames.add(name);
            return List.of(
                    ensurePolicy(
                            root,
                            policies,
                            name,
                            "user",
                            Map.of(
                                    "name", name,
                                    "type", "user",
                                    "logic", POSITIVE,
                                    "decisionStrategy", UNANIMOUS,
                                    "users", List.of(actor.keycloakUserId()))));
        }
        if (gate instanceof PolicyGraph.Gate.Validity validity) {
            // [start, end): not-before POSITIVE tại điểm bắt đầu, not-before NEGATIVE tại điểm kết
            // thúc.
            // Một notOnOrAfter đơn thuần vẫn cho phép đúng khoảnh khắc kết thúc.
            List<String> ids = new ArrayList<>();
            Instant validFrom = validity.validFrom();
            if (validFrom != null) {
                String name = NativePolicyTemplates.validFromPolicyName(validFrom);
                policyNames.add(name);
                ids.add(
                        ensurePolicy(
                                root,
                                policies,
                                name,
                                "time",
                                Map.of(
                                        "name",
                                        name,
                                        "type",
                                        "time",
                                        "logic",
                                        POSITIVE,
                                        "notBefore",
                                        NativePolicyTemplates.KEYCLOAK_UTC.format(validFrom))));
            }
            Instant expiresAt = validity.expiresAt();
            if (expiresAt != null) {
                String name = NativePolicyTemplates.validUntilPolicyName(expiresAt);
                policyNames.add(name);
                ids.add(
                        ensurePolicy(
                                root,
                                policies,
                                name,
                                "time",
                                Map.of(
                                        "name",
                                        name,
                                        "type",
                                        "time",
                                        "logic",
                                        NEGATIVE,
                                        "notBefore",
                                        NativePolicyTemplates.KEYCLOAK_UTC.format(expiresAt))));
            }
            return ids;
        }
        throw new IllegalStateException("Unsupported gate: " + gate);
    }

    private Map<String, Object> aggregate(
            String name, String decisionStrategy, List<String> policyIds) {
        return Map.of(
                "name", name,
                "type", "aggregate",
                "logic", POSITIVE,
                "decisionStrategy", decisionStrategy,
                "policies", policyIds);
    }

    private String ensurePolicy(
            String root,
            Map<String, Map<String, Object>> policies,
            String name,
            String type,
            Map<String, Object> payload) {
        Map<String, Object> existing = policies.get(name);
        if (existing == null) {
            try {
                String id = create(root + "/policy/" + type, payload);
                existing = Map.of("id", id, "type", type);
                policies.put(name, existing);
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                    throw ex;
                }
                // Một apply đồng thời đã tạo cùng policy name xác định đó trước.
                existing = policyIndex(root).get(name);
                if (existing == null) {
                    throw new IllegalStateException(
                            "Keycloak policy not visible after creation: " + name, ex);
                }
                policies.put(name, existing);
            }
        }
        String id = existingPolicyId(existing, name, type);
        String endpoint = root + "/policy/" + type + "/" + id;
        Map<String, Object> current = get(endpoint);
        if (!sameLeafPolicy(current, payload, type)) {
            Map<String, Object> update = new LinkedHashMap<>(payload);
            update.put("id", id);
            put(endpoint, update);
            current = get(endpoint);
        }
        if (!sameLeafPolicy(current, payload, type)) {
            throw new IllegalStateException("Native leaf policy read-back mismatch: " + name);
        }
        return id;
    }

    private static String existingPolicyId(Map<String, Object> existing, String name, String type) {
        if (!type.equals(existing.get("type"))) {
            throw new IllegalStateException(
                    "Native policy name collision with another type: " + name);
        }
        return String.valueOf(existing.get("id"));
    }

    private static boolean sameLeafPolicy(
            Map<String, Object> actual, Map<String, Object> desired, String type) {
        if (desired.get("name") == null
                || !Objects.equals(desired.get("name"), actual.get("name"))
                || !type.equals(actual.get("type"))
                || desired.get("logic") == null
                || !Objects.equals(desired.get("logic"), actual.get("logic"))
                || (actual.containsKey("decisionStrategy")
                        && desired.containsKey("decisionStrategy")
                        && !Objects.equals(
                                desired.get("decisionStrategy"), actual.get("decisionStrategy")))) {
            return false;
        }
        return switch (type) {
            case "group" -> {
                if (!(actual.get("groups") instanceof List<?> groups)
                        || groups.size() != SINGLE
                        || !(groups.getFirst() instanceof Map<?, ?> group)) {
                    yield false;
                }
                if (!(desired.get("groups") instanceof List<?> expectedGroups)
                        || expectedGroups.size() != SINGLE
                        || !(expectedGroups.getFirst() instanceof Map<?, ?> expected)) {
                    yield false;
                }
                yield expected.get("id") != null
                        && Objects.equals(expected.get("id"), group.get("id"))
                        && Boolean.FALSE.equals(group.get("extendChildren"));
            }
            case "user" ->
                    new HashSet<>(ids(actual.get("users")))
                            .equals(new HashSet<>(ids(desired.get("users"))));
            case "time" -> {
                if (desired.get("notBefore") == null
                        || !Objects.equals(desired.get("notBefore"), actual.get("notBefore"))) {
                    yield false;
                }
                boolean hasUnexpectedBoundary = false;
                for (String field :
                        List.of(
                                "notOnOrAfter",
                                "dayMonth",
                                "dayMonthEnd",
                                "month",
                                "monthEnd",
                                "hour",
                                "hourEnd",
                                "minute",
                                "minuteEnd")) {
                    Object value = actual.get(field);
                    if (value != null && !"".equals(value)) {
                        hasUnexpectedBoundary = true;
                        break;
                    }
                }
                yield !hasUnexpectedBoundary;
            }
            default -> throw new IllegalStateException("Unsupported leaf policy type: " + type);
        };
    }

    /**
     * Aggregate đặt tên theo target của chúng được đối chiếu, vì một revision mới có thể nối lại
     * chúng.
     */
    private String ensureAggregate(
            String root,
            Map<String, Map<String, Object>> policies,
            String name,
            String decisionStrategy,
            List<String> policyIds) {
        Map<String, Object> payload = aggregate(name, decisionStrategy, policyIds);
        Map<String, Object> existing = policies.get(name);
        if (existing == null) {
            try {
                String id = create(root + "/policy/aggregate", payload);
                Map<String, Object> created = new LinkedHashMap<>(payload);
                created.put("id", id);
                policies.put(name, created);
                existing = created;
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                    throw ex;
                }
                // Một apply đồng thời đã tạo cùng aggregate xác định đó trước; phép đối chiếu
                // bên dưới hòa giải nó như mọi aggregate có sẵn.
                existing = policyIndex(root).get(name);
                if (existing == null) {
                    throw new IllegalStateException(
                            "Keycloak policy not visible after creation: " + name, ex);
                }
                policies.put(name, existing);
            }
        }
        if (!"aggregate".equals(existing.get("type"))) {
            throw new IllegalStateException(
                    "Native policy name collision with another type: " + name);
        }
        String id = String.valueOf(existing.get("id"));
        String endpoint = root + "/policy/aggregate/" + id;
        Map<String, Object> current = get(endpoint);
        boolean same =
                sameAggregate(current, decisionStrategy, policyIds, associatedPolicies(endpoint));
        if (!same) {
            Map<String, Object> update = new LinkedHashMap<>(payload);
            update.put("id", id);
            put(endpoint, update);
            current = get(endpoint);
            same =
                    sameAggregate(
                            current, decisionStrategy, policyIds, associatedPolicies(endpoint));
        }
        if (!same) {
            throw new IllegalStateException("Native aggregate read-back mismatch: " + name);
        }
        return id;
    }

    private static boolean sameAggregate(
            Map<String, Object> actual,
            String strategy,
            List<String> policies,
            Set<String> associatedPolicies) {
        return POSITIVE.equals(actual.get("logic"))
                && strategy.equals(actual.get("decisionStrategy"))
                && associatedPolicies.equals(new HashSet<>(policies));
    }

    private Set<String> associatedPolicies(String path) {
        return relatedIds(path, "associatedPolicies");
    }

    private Set<String> relatedIds(String path, String relation) {
        return new HashSet<>(ids(get(uri(path + "/" + relation), MAP_LIST)));
    }

    private String ensureScope(String root, String scope) {
        for (Map<String, Object> existing : list(root + "/scope")) {
            if (scope.equals(existing.get("name"))) {
                return String.valueOf(existing.get("id"));
            }
        }
        try {
            return create(root + "/scope", Map.of("name", scope));
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw ex;
            }
            // Một apply đồng thời đã tạo cùng scope đó trước.
            for (Map<String, Object> raced : list(root + "/scope")) {
                if (scope.equals(raced.get("name"))) {
                    return String.valueOf(raced.get("id"));
                }
            }
            throw new IllegalStateException(
                    "Keycloak scope not visible after creation: " + scope, ex);
        }
    }

    private String ensureResource(String root, String targetKey, String scopeId, String scopeName) {
        for (Map<String, Object> existing : list(root + "/resource")) {
            if (targetKey.equals(existing.get("name"))) {
                return resourceWithScope(root, existing, targetKey, scopeId, scopeName);
            }
        }
        try {
            return create(
                    root + "/resource",
                    Map.of(
                            "name",
                            targetKey,
                            "ownerManagedAccess",
                            false,
                            "scopes",
                            List.of(Map.of("id", scopeId, "name", scopeName))));
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw ex;
            }
            // Một apply đồng thời đã tạo cùng resource name xác định đó trước.
            for (Map<String, Object> raced : list(root + "/resource")) {
                if (targetKey.equals(raced.get("name"))) {
                    return resourceWithScope(root, raced, targetKey, scopeId, scopeName);
                }
            }
            throw new IllegalStateException(
                    "Keycloak resource not visible after creation: " + targetKey, ex);
        }
    }

    private String resourceWithScope(
            String root,
            Map<String, Object> existing,
            String targetKey,
            String scopeId,
            String scopeName) {
        String id = String.valueOf(existing.get("_id"));
        Map<String, Object> current = get(root + "/resource/" + id);
        List<String> scopeIds = ids(current.get("scopes"), "id");
        if (!scopeIds.contains(scopeId)) {
            scopeIds.add(scopeId);
            Map<String, String> scopeNames = new LinkedHashMap<>();
            if (current.get("scopes") instanceof List<?> currentScopes) {
                for (Object item : currentScopes) {
                    if (item instanceof Map<?, ?> scope && scope.get("name") != null) {
                        scopeNames.put(
                                String.valueOf(scope.get("id")), String.valueOf(scope.get("name")));
                    }
                }
            }
            scopeNames.put(scopeId, scopeName);
            // Bất biến của Keycloak: entry scope chỉ có id vẫn được chấp nhận (204) nhưng scope
            // mới không được gắn; mọi entry phải mang cả id và name mới được giữ.
            List<Map<String, String>> scopes = new ArrayList<>();
            for (String existingScope : scopeIds) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", existingScope);
                String name = scopeNames.get(existingScope);
                if (name != null) {
                    entry.put("name", name);
                }
                scopes.add(entry);
            }
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("_id", id);
            update.put("name", targetKey);
            update.put("ownerManagedAccess", false);
            update.put("scopes", scopes);
            put(root + "/resource/" + id, update);
        }
        return id;
    }

    private String ensureRootPermission(
            String root, String name, String resourceId, String scopeId, List<String> policyIds) {
        Map<String, Object> desired =
                Map.of(
                        "name", name,
                        "type", "scope",
                        "logic", POSITIVE,
                        "decisionStrategy", UNANIMOUS,
                        "resources", List.of(resourceId),
                        "scopes", List.of(scopeId),
                        "policies", policyIds);
        for (Map<String, Object> existing : list(root + "/permission/scope")) {
            if (name.equals(existing.get("name"))) {
                return reconcileRootPermission(
                        root, existing, desired, resourceId, scopeId, policyIds);
            }
        }
        try {
            return create(root + "/permission/scope", desired);
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw ex;
            }
            // Một apply đồng thời đã tạo cùng root permission xác định đó trước.
            for (Map<String, Object> raced : list(root + "/permission/scope")) {
                if (name.equals(raced.get("name"))) {
                    return reconcileRootPermission(
                            root, raced, desired, resourceId, scopeId, policyIds);
                }
            }
            throw new IllegalStateException(
                    "Keycloak permission not visible after creation: " + name, ex);
        }
    }

    /** Một root permission hiện có được cập nhật tại chỗ khi một revision mới nối lại nó. */
    private String reconcileRootPermission(
            String root,
            Map<String, Object> existing,
            Map<String, Object> desired,
            String resourceId,
            String scopeId,
            List<String> policyIds) {
        String id = String.valueOf(existing.get("id"));
        String endpoint = root + "/permission/scope/" + id;
        Map<String, Object> current = get(endpoint);
        Set<String> policies = associatedPolicies(endpoint);
        Set<String> resources = relatedIds(endpoint, "resources");
        Set<String> scopes = relatedIds(endpoint, "scopes");
        boolean same =
                POSITIVE.equals(current.get("logic"))
                        && UNANIMOUS.equals(current.get("decisionStrategy"))
                        && policies.equals(new HashSet<>(policyIds))
                        && resources.equals(Set.of(resourceId))
                        && scopes.equals(Set.of(scopeId));
        if (!same) {
            Map<String, Object> update = new LinkedHashMap<>(desired);
            update.put("id", id);
            put(root + "/permission/scope/" + id, update);
        }
        return id;
    }

    private void verifyReadBack(
            String root,
            String permissionId,
            String resourceId,
            String scopeId,
            List<String> policyIds,
            String targetKey) {
        String endpoint = root + "/permission/scope/" + permissionId;
        Map<String, Object> permission = get(endpoint);
        Set<String> policies = associatedPolicies(endpoint);
        Set<String> permissionResources = relatedIds(endpoint, "resources");
        Set<String> permissionScopes = relatedIds(endpoint, "scopes");
        Map<String, Object> resource = get(root + "/resource/" + resourceId);
        boolean verified =
                POSITIVE.equals(permission.get("logic"))
                        && UNANIMOUS.equals(permission.get("decisionStrategy"))
                        && policies.equals(new HashSet<>(policyIds))
                        && permissionResources.equals(Set.of(resourceId))
                        && permissionScopes.equals(Set.of(scopeId))
                        && targetKey.equals(resource.get("name"))
                        && ids(resource.get("scopes"), "id").contains(scopeId);
        if (!verified) {
            throw new IllegalStateException(
                    "Native policy graph read-back does not match the desired graph for "
                            + targetKey);
        }
    }

    /** Lấy các liên kết native phục vụ kiểm tra tính toàn vẹn graph của projection reconciler. */
    private Set<String> associatedIds(
            String root, String policyId, String association, String idKey) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> linked :
                get(root + "/policy/" + policyId + "/" + association, MAP_LIST)) {
            out.add(String.valueOf(linked.get(idKey)));
        }
        return out;
    }

    private Map<String, Map<String, Object>> policyIndex(String root) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> policy : list(root + "/policy")) {
            index.put(String.valueOf(policy.get("name")), policy);
        }
        return index;
    }

    private String realmGroupId(String path) {
        URI uri = URI.create(baseUrl + "/admin/realms/" + realm + "/group-by-path" + path);
        try {
            Map<String, Object> group = get(uri, MAP);
            if (!path.equals(group.get("path")) || group.get("id") == null) {
                throw new IllegalStateException("Keycloak returned a group for an unexpected path");
            }
            return String.valueOf(group.get("id"));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                // Group là projection của directory; graph writer không bao giờ tự bịa ra chúng.
                throw new IllegalStateException("Realm group is not provisioned: " + path, ex);
            }
            throw ex;
        }
    }

    private String resourceServerRoot() {
        // Resolve ở mọi lần gọi, không bao giờ cache: client có thể bị tạo lại (realm re-import,
        // vòng đời fixture), và một ID cache đã cũ biến mọi lần gọi sau đó thành 404.
        URI uri =
                UriComponentsBuilder.fromUriString(baseUrl)
                        .pathSegment("admin", "realms", realm, "clients")
                        .queryParam("clientId", audienceClientId)
                        .build()
                        .encode()
                        .toUri();
        List<Map<String, Object>> clients = get(uri, MAP_LIST);
        if (clients.size() != SINGLE || clients.getFirst().get("id") == null) {
            throw new IllegalStateException(
                    "Expected exactly one resource server client: " + audienceClientId);
        }
        return baseUrl
                + "/admin/realms/"
                + realm
                + "/clients/"
                + clients.getFirst().get("id")
                + "/authz/resource-server";
    }

    private String accessToken() {
        var client =
                manager.authorize(
                        OAuth2AuthorizeRequest.withClientRegistrationId("giaokhoa-provisioner")
                                .principal("giaokhoa-provisioner")
                                .build());
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("Keycloak provisioner cannot obtain an access token");
        }
        return client.getAccessToken().getTokenValue();
    }

    /**
     * Mọi trang đều được lấy: một tên vắng mặt trong kết quả thực sự không tồn tại trong Keycloak.
     */
    private List<Map<String, Object>> list(String path) {
        List<Map<String, Object>> all = new ArrayList<>();
        int first = 0;
        while (true) {
            List<Map<String, Object>> page =
                    get(path + "?first=" + first + "&max=" + PAGE_SIZE, MAP_LIST);
            all.addAll(page);
            if (page.size() < PAGE_SIZE) {
                return all;
            }
            first += page.size();
        }
    }

    private Map<String, Object> get(String path) {
        return get(uri(path), MAP);
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> type) {
        T body =
                http.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(accessToken()))
                        .retrieve()
                        .body(type);
        if (body == null) {
            throw new IllegalStateException("Empty Keycloak authorization response");
        }
        return body;
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type) {
        return get(uri(path), type);
    }

    /** Path hoặc là tuyệt đối (đã chứa base URL) hoặc tương đối theo realm. */
    private URI uri(String path) {
        return URI.create(path.startsWith("http") ? path : baseUrl + path);
    }

    private String create(String path, Map<String, Object> payload) {
        var response =
                http.post()
                        .uri(uri(path))
                        .headers(headers -> headers.setBearerAuth(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location != null) {
            String value = location.getPath();
            return value.substring(value.lastIndexOf('/') + 1);
        }
        // POST của Authorization Services trả ID đã tạo trong thân JSON thay vào đó.
        if (response.getBody() == null) {
            throw new IllegalStateException("Keycloak returned no created ID for " + path);
        }
        var created = JSON.readTree(response.getBody());
        var id = created.has("id") ? created.get("id") : created.get("_id");
        if (id == null) {
            throw new IllegalStateException("Keycloak returned no created ID for " + path);
        }
        return id.stringValue();
    }

    private void put(String path, Map<String, Object> payload) {
        http.put()
                .uri(uri(path))
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private void delete(String path) {
        http.delete()
                .uri(uri(path))
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private static List<String> ids(@Nullable Object field) {
        return ids(field, null);
    }

    /**
     * Các ID của một trường JSON hoặc là danh sách ID thuần hoặc danh sách đối tượng mang key ID.
     */
    private static List<String> ids(@Nullable Object field, @Nullable String key) {
        List<String> out = new ArrayList<>();
        if (field instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && key != null) {
                    out.add(String.valueOf(map.get(key)));
                } else if (item instanceof Map<?, ?> map) {
                    Object id = map.containsKey("id") ? map.get("id") : map.get("_id");
                    out.add(String.valueOf(id));
                } else if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
