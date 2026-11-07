package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kiểm chứng tầng service của quản trị center: tạo tenant qua operation tới SUCCEEDED mới có
 * center, Organization và group membership thật trên Keycloak TEST; replay không nhân bản tenant;
 * đổi role chạm row membership và chiếu lại group; tiền điều kiện của đổi role; owner cuối cùng
 * không bị gỡ; thu hồi giữ lịch sử và gỡ user khỏi mọi group của workspace; kỳ gói còn hiệu lực
 * chiếu thành time policy trên các scope bị gate và bundle đã áp được verify là nguyên vẹn.
 * Endpoint HTTP thuộc task controller riêng nên test này gọi thẳng service, không qua MockMvc.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CenterServiceIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "center_service_" + UUID.randomUUID().toString().replace("-", "");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static final List<String> OWNED_GROUP_IDS = new ArrayList<>();
    private static @Nullable String ownedApiClientId;
    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + SCHEMA + ",public");
    }

    @BeforeAll
    static void seedRealmPrerequisites() {
        seedEligibilityGroup();
        ensureApiClient();
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        if (ownedApiClientId != null) {
            deleteResource(admin("/clients/" + ownedApiClientId));
        }
        for (int i = OWNED_GROUP_IDS.size() - 1; i >= 0; i--) {
            deleteResource(admin("/groups/" + OWNED_GROUP_IDS.get(i)));
        }
        OWNED_GROUP_IDS.clear();
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CenterService centers;
    @Autowired IdempotencyService idempotency;
    @Autowired ProjectionReconciler reconciler;

    @Test
    void createCenterProvisionsTenantOwnerAndProjection() throws Exception {
        Fixture operator = newAccount("centersvc-operator");
        Fixture owner = newAccount("centersvc-owner");
        try {
            AuthorizationOperation operation =
                    centers.createCenter(
                            operator.userId(),
                            "Trung tâm kiểm chứng",
                            owner.userId(),
                            "create-" + UUID.randomUUID(),
                            hash("{\"name\":\"Trung tâm kiểm chứng\"}"));
            awaitOperationSucceeded(operation.getId());

            List<Map<String, Object>> centerRows =
                    jdbc.queryForList(
                            "SELECT id, workspace_id, keycloak_organization_id FROM center"
                                    + " WHERE owner_user_id = ?",
                            owner.userId());
            assertEquals(1, centerRows.size(), "Đúng một center cho owner sau SUCCEEDED");
            UUID workspaceId =
                    Objects.requireNonNull((UUID) centerRows.getFirst().get("workspace_id"));
            String organizationId =
                    Objects.requireNonNull(
                            (String) centerRows.getFirst().get("keycloak_organization_id"));
            assertNotNull(organizationId, "Center phải ghi Organization đã provision");
            assertTrue(
                    organizationExists(organizationId), "Organization tồn tại thật trên Keycloak");
            assertEquals(
                    Set.of("OWNER"),
                    activeRoles(workspaceId, owner.userId()),
                    "Owner có đúng role OWNER");
            assertEquals(
                    "READY",
                    jdbc.queryForObject(
                            "SELECT state FROM authz_partition WHERE kind = 'WORKSPACE'"
                                    + " AND owner_key = ?",
                            String.class,
                            "workspace:" + workspaceId),
                    "Workspace partition READY sau khi operation SUCCEEDED");
            assertEquals(
                    1,
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM authz_projection WHERE target_key = ?"
                                            + " AND state = 'READY'"
                                            + " AND applied_hash = desired_hash",
                                    Integer.class,
                                    "workspace:" + workspaceId)),
                    "Projection của workspace đã áp đúng desired hash");
            Set<String> groups = groupPathsOf(owner.subject());
            assertTrue(
                    groups.contains("/gk/workspaces/" + workspaceId + "/members"),
                    "Owner ở trong group members của workspace");
            assertTrue(
                    groups.contains("/gk/workspaces/" + workspaceId + "/owners"),
                    "Owner ở trong group owners của workspace");
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
        }
    }

    @Test
    void createCenterReplayCreatesSingleTenant() throws Exception {
        Fixture operator = newAccount("centersvc-replay-op");
        Fixture owner = newAccount("centersvc-replay-owner");
        try {
            String key = "replay-" + UUID.randomUUID();
            String payloadHash = hash("{\"name\":\"Trung tâm replay\"}");
            AuthorizationOperation first =
                    centers.createCenter(
                            operator.userId(),
                            "Trung tâm replay",
                            owner.userId(),
                            key,
                            payloadHash);
            AuthorizationOperation replay =
                    centers.createCenter(
                            operator.userId(),
                            "Trung tâm replay",
                            owner.userId(),
                            key,
                            payloadHash);
            assertEquals(
                    first.getId(), replay.getId(), "Replay cùng key trả đúng operation đã ghi");
            awaitOperationSucceeded(first.getId());
            assertEquals(
                    1,
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM center WHERE owner_user_id = ?",
                                    Integer.class,
                                    owner.userId())),
                    "Replay không tạo tenant thứ hai");
            assertEquals(
                    1,
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM center_membership m"
                                            + " JOIN membership_role r"
                                            + " ON r.membership_id = m.id"
                                            + " WHERE m.user_id = ? AND r.role = 'OWNER'",
                                    Integer.class,
                                    owner.userId())),
                    "Replay không tạo owner thứ hai");

            HttpProblemException conflict =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.createCenter(
                                            operator.userId(),
                                            "Trung tâm khác",
                                            owner.userId(),
                                            key,
                                            hash("{\"name\":\"Trung tâm khác\"}")));
            assertEquals(409, conflict.getStatus().value(), "Cùng key khác payload là 409");
            UUID workspaceId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT workspace_id FROM center WHERE owner_user_id = ?",
                                    UUID.class,
                                    owner.userId()));
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE owner_user_id = ?",
                                    String.class,
                                    owner.userId()));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
        }
    }

    @Test
    void replaceRolesTouchesMembershipRowAndProjectsGroups() throws Exception {
        Fixture operator = newAccount("centersvc-roles-op");
        Fixture owner = newAccount("centersvc-roles-owner");
        Fixture member = newAccount("centersvc-roles-member");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID membershipId = addMembership(workspaceId, member.userId(), "STUDENT");
            long versionBefore =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    membershipId));

            AuthorizationOperation operation =
                    centers.replaceRoles(
                            owner.userId(),
                            workspaceId,
                            membershipId,
                            Set.of(MembershipRole.Role.MANAGER),
                            "\"" + versionBefore + "\"",
                            "roles-" + UUID.randomUUID(),
                            hash("{\"roles\":[\"MANAGER\"]}"));
            awaitOperationSucceeded(operation.getId());

            assertEquals(
                    Set.of("MANAGER"),
                    activeRoles(workspaceId, member.userId()),
                    "Tập role được thay toàn bộ");
            long versionAfter =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    membershipId));
            assertTrue(
                    versionAfter > versionBefore,
                    "Đổi role phải chạm row membership để version tăng");
            Set<String> groups = groupPathsOf(member.subject());
            assertTrue(groups.contains("/gk/workspaces/" + workspaceId + "/managers"));
            assertFalse(
                    groups.contains("/gk/workspaces/" + workspaceId + "/roles/students"),
                    "Role cũ bị gỡ khỏi group tiền đề tương ứng");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
            member.delete();
        }
    }

    @Test
    void lastOwnerCannotBeRemovedByRoleChangeOrRevoke() throws Exception {
        Fixture operator = newAccount("centersvc-lastop-op");
        Fixture owner = newAccount("centersvc-lastop-owner");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID ownerMembership =
                    jdbc.queryForObject(
                            "SELECT id FROM center_membership WHERE workspace_id = ?"
                                    + " AND user_id = ?",
                            UUID.class,
                            workspaceId,
                            owner.userId());
            long version =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    Objects.requireNonNull(ownerMembership)));

            HttpProblemException byRoles =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            workspaceId,
                                            Objects.requireNonNull(ownerMembership),
                                            Set.of(MembershipRole.Role.MANAGER),
                                            "\"" + version + "\"",
                                            "lastop-roles-" + UUID.randomUUID(),
                                            hash("{\"roles\":[\"MANAGER\"]}")));
            assertEquals(400, byRoles.getStatus().value(), "Gỡ OWNER khỏi owner bị từ chối");

            HttpProblemException byRevoke =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.revokeMembership(
                                            owner.userId(),
                                            workspaceId,
                                            Objects.requireNonNull(ownerMembership),
                                            "lastop-revoke-" + UUID.randomUUID(),
                                            hash("{}")));
            assertEquals(400, byRevoke.getStatus().value(), "Thu hồi owner bị từ chối");
            assertEquals(
                    Set.of("OWNER"),
                    activeRoles(workspaceId, owner.userId()),
                    "Owner vẫn nguyên sau hai lời gọi bị từ chối");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
        }
    }

    @Test
    void revokeMembershipRemovesGroupsAndKeepsHistory() throws Exception {
        Fixture operator = newAccount("centersvc-revoke-op");
        Fixture owner = newAccount("centersvc-revoke-owner");
        Fixture member = newAccount("centersvc-revoke-member");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID membershipId = addMembership(workspaceId, member.userId(), "STUDENT");
            Object joinedAt =
                    jdbc.queryForObject(
                            "SELECT joined_at FROM center_membership WHERE id = ?",
                            Object.class,
                            membershipId);

            AuthorizationOperation operation =
                    centers.revokeMembership(
                            owner.userId(),
                            workspaceId,
                            membershipId,
                            "revoke-" + UUID.randomUUID(),
                            hash("{}"));
            awaitOperationSucceeded(operation.getId());

            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT state, joined_at, revoked_at FROM center_membership"
                                    + " WHERE id = ?",
                            membershipId);
            assertEquals("REVOKED", row.get("state"));
            assertEquals(joinedAt, row.get("joined_at"), "joined_at là lịch sử bất biến");
            assertNotNull(row.get("revoked_at"), "Thu hồi ghi revoked_at");
            Set<String> groups = groupPathsOf(member.subject());
            assertTrue(
                    groups.stream().noneMatch(path -> path.contains(workspaceId.toString())),
                    "User bị gỡ khỏi mọi group của workspace sau thu hồi");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
            member.delete();
        }
    }

    @Test
    void replaceRolesRejectsBrokenPreconditions() throws Exception {
        Fixture operator = newAccount("centersvc-precond-op");
        Fixture owner = newAccount("centersvc-precond-owner");
        Fixture member = newAccount("centersvc-precond-member");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID membershipId = addMembership(workspaceId, member.userId(), "STUDENT");
            long version =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    membershipId));
            String payload = "{\"roles\":[\"TEACHER\"]}";

            HttpProblemException missingIfMatch =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            workspaceId,
                                            membershipId,
                                            Set.of(MembershipRole.Role.TEACHER),
                                            null,
                                            "precond-missing-" + UUID.randomUUID(),
                                            hash(payload)));
            assertEquals(428, missingIfMatch.getStatus().value(), "Thiếu If-Match là 428");
            assertEquals("PRECONDITION_REQUIRED", missingIfMatch.getCode());

            HttpProblemException staleVersion =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            workspaceId,
                                            membershipId,
                                            Set.of(MembershipRole.Role.TEACHER),
                                            "\"" + (version + 100) + "\"",
                                            "precond-stale-" + UUID.randomUUID(),
                                            hash(payload)));
            assertEquals(409, staleVersion.getStatus().value(), "Version cũ là 409");
            assertEquals("VERSION_CONFLICT", staleVersion.getCode());

            HttpProblemException wrongWorkspace =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            UUID.randomUUID(),
                                            membershipId,
                                            Set.of(MembershipRole.Role.TEACHER),
                                            "\"" + version + "\"",
                                            "precond-wrongws-" + UUID.randomUUID(),
                                            hash(payload)));
            assertEquals(404, wrongWorkspace.getStatus().value(), "Sai workspace là 404");
            assertEquals("MEMBERSHIP_NOT_FOUND", wrongWorkspace.getCode());

            HttpProblemException missingMembership =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            workspaceId,
                                            UUID.randomUUID(),
                                            Set.of(MembershipRole.Role.TEACHER),
                                            "\"" + version + "\"",
                                            "precond-missingm-" + UUID.randomUUID(),
                                            hash(payload)));
            assertEquals(
                    404, missingMembership.getStatus().value(), "Membership không tồn tại là 404");
            assertEquals("MEMBERSHIP_NOT_FOUND", missingMembership.getCode());

            HttpProblemException grantOwner =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    centers.replaceRoles(
                                            owner.userId(),
                                            workspaceId,
                                            membershipId,
                                            Set.of(MembershipRole.Role.OWNER),
                                            "\"" + version + "\"",
                                            "precond-owner-" + UUID.randomUUID(),
                                            hash("{\"roles\":[\"OWNER\"]}")));
            assertEquals(400, grantOwner.getStatus().value(), "Cấp mới OWNER qua đổi role là 400");
            assertEquals("OWNER_ROLE_NOT_ASSIGNABLE", grantOwner.getCode());

            assertEquals(
                    Set.of("STUDENT"),
                    activeRoles(workspaceId, member.userId()),
                    "Mọi lời gọi bị từ chối đều không đổi role");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
            member.delete();
        }
    }

    @Test
    void subscriptionPeriodAddsNativeTimePoliciesToGatedScopes() throws Exception {
        Fixture operator = newAccount("centersvc-period-op");
        Fixture owner = newAccount("centersvc-period-owner");
        Fixture member = newAccount("centersvc-period-member");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID membershipId = addMembership(workspaceId, member.userId(), "STUDENT");
            Instant validFrom =
                    Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
            Instant expiresAt = validFrom.plus(Duration.ofDays(30));
            addSubscriptionPeriod(workspaceId, operator.userId(), validFrom, expiresAt);
            long version =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    membershipId));

            AuthorizationOperation operation =
                    centers.replaceRoles(
                            owner.userId(),
                            workspaceId,
                            membershipId,
                            Set.of(MembershipRole.Role.MANAGER),
                            "\"" + version + "\"",
                            "period-roles-" + UUID.randomUUID(),
                            hash("{\"roles\":[\"MANAGER\"]}"));
            awaitOperationSucceeded(operation.getId());

            String root = resourceServerRoot();
            Map<String, Map<String, Object>> policiesByName = new HashMap<>();
            for (Map<String, Object> policy : getListFrom(root + "/policy?first=0&max=1000")) {
                policiesByName.put(String.valueOf(policy.get("name")), policy);
            }
            Map<String, Object> fromPolicy =
                    policiesByName.get(NativePolicyTemplates.validFromPolicyName(validFrom));
            Map<String, Object> untilPolicy =
                    policiesByName.get(NativePolicyTemplates.validUntilPolicyName(expiresAt));
            assertNotNull(fromPolicy, "Graph phải có time policy mở cửa sổ của kỳ gói");
            assertNotNull(untilPolicy, "Graph phải có time policy đóng cửa sổ của kỳ gói");
            String fromId = String.valueOf(fromPolicy.get("id"));
            String untilId = String.valueOf(untilPolicy.get("id"));
            Map<String, Object> fromDetail = getMapFrom(root + "/policy/time/" + fromId);
            assertEquals("POSITIVE", fromDetail.get("logic"));
            assertEquals(
                    NativePolicyTemplates.KEYCLOAK_UTC.format(validFrom),
                    fromDetail.get("notBefore"),
                    "Biên mở là not-before POSITIVE tại valid_from");
            Map<String, Object> untilDetail = getMapFrom(root + "/policy/time/" + untilId);
            assertEquals("NEGATIVE", untilDetail.get("logic"));
            assertEquals(
                    NativePolicyTemplates.KEYCLOAK_UTC.format(expiresAt),
                    untilDetail.get("notBefore"),
                    "Biên đóng là not-before NEGATIVE tại expires_at: cửa sổ là [from, until)");

            String permissionName =
                    NativePolicyTemplates.rootPermissionName(
                            NativePolicyTemplates.workspaceKey(workspaceId), "membership.invite");
            String permissionId = null;
            for (Map<String, Object> permission :
                    getListFrom(root + "/permission/scope?first=0&max=1000")) {
                if (permissionName.equals(permission.get("name"))) {
                    permissionId = String.valueOf(permission.get("id"));
                }
            }
            assertNotNull(permissionId, "Root permission của scope bị gate kỳ gói phải tồn tại");
            Set<String> reachable = reachablePolicyIds(root, permissionId);
            assertTrue(reachable.contains(fromId), "Time policy mở phải tới được từ root");
            assertTrue(reachable.contains(untilId), "Time policy đóng phải tới được từ root");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
            member.delete();
        }
    }

    @Test
    void storedBundleVerifiesIntactWithManagerAndActivePeriod() throws Exception {
        Fixture operator = newAccount("centersvc-verify-op");
        Fixture owner = newAccount("centersvc-verify-owner");
        Fixture member = newAccount("centersvc-verify-member");
        try {
            UUID workspaceId = createCenterAndAwait(operator, owner);
            UUID membershipId = addMembership(workspaceId, member.userId(), "MANAGER");
            Instant validFrom =
                    Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
            addSubscriptionPeriod(
                    workspaceId, operator.userId(), validFrom, validFrom.plus(Duration.ofDays(30)));
            long version =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT version FROM center_membership WHERE id = ?",
                                    Long.class,
                                    membershipId));

            AuthorizationOperation operation =
                    centers.replaceRoles(
                            owner.userId(),
                            workspaceId,
                            membershipId,
                            Set.of(MembershipRole.Role.MANAGER),
                            "\"" + version + "\"",
                            "verify-roles-" + UUID.randomUUID(),
                            hash("{\"roles\":[\"MANAGER\"]}"));
            awaitOperationSucceeded(operation.getId());

            UUID partitionId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                            + " AND owner_key = ?",
                                    UUID.class,
                                    "workspace:" + workspaceId));
            assertTrue(
                    reconciler.verifyStoredGraph(partitionId),
                    "Bundle vừa áp ở trạng thái có manager và kỳ gói còn hiệu lực phải được"
                            + " verify là nguyên vẹn, không phải drift");
            String organizationId =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT keycloak_organization_id FROM center"
                                            + " WHERE workspace_id = ?",
                                    String.class,
                                    workspaceId));
            cleanupWorkspaceDirectory(workspaceId, organizationId);
        } finally {
            operator.delete();
            owner.delete();
            member.delete();
        }
    }

    // ---------- Dữ kiện ----------

    private record Fixture(UUID userId, String subject) {
        void delete() {
            deleteResource(admin("/users/" + subject));
        }
    }

    private Fixture newAccount(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
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
                List.of(
                        Map.of(
                                "type",
                                "password",
                                "value",
                                "centersvc-password-" + UUID.randomUUID(),
                                "temporary",
                                false)));
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
        UUID userId = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    userId,
                    subject,
                    username,
                    username + "@example.test",
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    userId);
            return null;
        });
        return new Fixture(userId, subject);
    }

    private UUID createCenterAndAwait(Fixture operator, Fixture owner) throws Exception {
        AuthorizationOperation operation =
                centers.createCenter(
                        operator.userId(),
                        "Trung tâm " + UUID.randomUUID(),
                        owner.userId(),
                        "create-" + UUID.randomUUID(),
                        hash("{\"name\":\"fixture\"}"));
        awaitOperationSucceeded(operation.getId());
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT workspace_id FROM center WHERE owner_user_id = ?",
                        UUID.class,
                        owner.userId()));
    }

    private UUID addMembership(UUID workspaceId, UUID userId, String role) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                    membership,
                    role);
            return null;
        });
        return membership;
    }

    private void addSubscriptionPeriod(
            UUID workspaceId, UUID confirmedBy, Instant validFrom, Instant expiresAt) {
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    java.sql.Timestamp.from(validFrom),
                    java.sql.Timestamp.from(expiresAt),
                    "it-period-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    private String resourceServerRoot() {
        List<Map<String, Object>> matches =
                getListFrom(admin("/clients?clientId=" + fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE")));
        if (matches.isEmpty()) {
            throw new IllegalStateException("Resource server client is missing");
        }
        return admin("/clients/" + matches.getFirst().get("id") + "/authz/resource-server");
    }

    private List<Map<String, Object>> getListFrom(String uri) {
        List<Map<String, Object>> result =
                HTTP.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(LIST_MAP);
        if (result == null) {
            throw new IllegalStateException("Keycloak list not readable: " + uri);
        }
        return result;
    }

    private Map<String, Object> getMapFrom(String uri) {
        Map<String, Object> result =
                HTTP.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(MAP);
        if (result == null) {
            throw new IllegalStateException("Keycloak object not readable: " + uri);
        }
        return result;
    }

    /** Mọi policy tới được từ một policy qua associatedPolicies, gồm chính policy đó. */
    private Set<String> reachablePolicyIds(String root, String policyId) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(policyId));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            for (Map<String, Object> linked :
                    getListFrom(root + "/policy/" + current + "/associatedPolicies")) {
                pending.addLast(String.valueOf(linked.get("id")));
            }
        }
        return seen;
    }

    private Set<String> activeRoles(UUID workspaceId, UUID userId) {
        return Set.copyOf(
                jdbc.queryForList(
                        "SELECT r.role FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ? AND m.state = 'ACTIVE'",
                        String.class,
                        workspaceId,
                        userId));
    }

    private String hash(String body) throws Exception {
        return idempotency.payloadHash("POST", "/api/test", JSON.readTree(body));
    }

    private void awaitOperationSucceeded(UUID operationId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            List<String> states =
                    jdbc.queryForList(
                            "SELECT state FROM authorization_operation WHERE id = ?",
                            String.class,
                            operationId);
            if (!states.isEmpty()) {
                String state = states.getFirst();
                boolean succeeded = "SUCCEEDED".equals(state);
                if (succeeded) {
                    return;
                }
                boolean failed = "FAILED".equals(state);
                if (failed) {
                    fail("Operation FAILED: " + operationId);
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for the operation");
            }
        }
        fail("Operation did not succeed in time: " + operationId);
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    /**
     * Group eligibility chung mà mọi policy graph đều gate qua (common gate của PolicyGraph):
     * test-realm.json không khai group này, các integration test tự tạo khi thiếu (theo mẫu
     * IdentityFlowIT); chỉ xóa những group do chính class này tạo, group đã có sẵn thì để lại.
     */
    private static void seedEligibilityGroup() {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : List.of("gk", "identity", "eligible")) {
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
                OWNED_GROUP_IDS.add(found);
            }
            parentId = found;
        }
    }

    /**
     * Resource server client mà policy graph writer resolve theo audience: test-realm.json không
     * khai client này, các integration test tự tạo khi thiếu (theo mẫu IdentityFlowIT) để class
     * chạy độc lập; client đã có sẵn (do class khác tạo trước trong cùng lượt toàn suite) thì dùng
     * chung và không xóa ở cuối.
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
        ownedApiClientId = clientId;
        removeClientDefaults(clientId);
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

    private boolean organizationExists(String organizationId) {
        try {
            HTTP.get()
                    .uri(admin("/organizations/" + organizationId))
                    .headers(headers -> headers.setBearerAuth(masterToken()))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            return false;
        }
    }

    private Set<String> groupPathsOf(String subject) {
        List<Map<String, Object>> groups =
                HTTP.get()
                        .uri(admin("/users/" + subject + "/groups"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(LIST_MAP);
        if (groups == null) {
            return Set.of();
        }
        return Set.copyOf(groups.stream().map(group -> String.valueOf(group.get("path"))).toList());
    }

    private void cleanupWorkspaceDirectory(UUID workspaceId, String organizationId) {
        deleteResource(admin("/organizations/" + organizationId));
        Map<String, Object> group =
                HTTP.get()
                        .uri(admin("/group-by-path/gk/workspaces/" + workspaceId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(MAP);
        if (group != null && group.get("id") != null) {
            deleteResource(admin("/groups/" + group.get("id")));
        }
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
