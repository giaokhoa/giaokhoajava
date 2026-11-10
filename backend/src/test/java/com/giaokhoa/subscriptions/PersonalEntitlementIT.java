package com.giaokhoa.subscriptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.KeycloakSessionFixture;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Chiều gói của US6 ở tầng HTTP: gói personal là nguồn entitlement duy nhất cho kho/lớp cá nhân —
 * chưa có gói thì tạo học liệu personal bị từ chối nhưng lịch sử practice vẫn đọc được; operator
 * kích hoạt gói thì tạo được ngay, không cần xác minh nghề nghiệp; membership center không thay gói
 * personal và gói personal không thay quyền tại center.
 *
 * <p>Nhánh CREATE_RESOURCE cho personal owner trong SampleActionController đã có sẵn (kiểm tra kỳ
 * gói còn hiệu lực bên trong fence), nên các khẳng định ở đây khóa hành vi hiện hành; phần lớp
 * personal thuộc về PersonalClassAuthorizationIT.
 */
@SpringBootTest
class PersonalEntitlementIT extends KeycloakSessionFixture {
    private static final String OPERATOR_GROUP = "/gk/platform/operators";
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static @Nullable String operatorGroupId;

    @BeforeAll
    static void seedRealmFoundation() {
        operatorGroupId = ensureGroup(OPERATOR_GROUP);
        ensureApiClient();
    }

    @Test
    void createResourceInPersonalWorkspaceWithoutPlanIsDenied() throws Exception {
        KeycloakLogin user = createVerifiedUser("entitlement-no-plan");
        try {
            UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
            UUID personal = personalWorkspaceOf(userId);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        sampleAction(session, personal, createResourceJson());
                assertEquals(
                        403,
                        response.getStatus(),
                        "Chưa có gói cá nhân thì tạo học liệu personal phải bị từ chối, body: "
                                + response.getContentAsString());
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void practiceHistoryRemainsAvailableWithoutPlan() throws Exception {
        KeycloakLogin user = createVerifiedUser("entitlement-practice");
        try {
            UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
            UUID personal = personalWorkspaceOf(userId);
            UUID attemptId = seedPracticeAttempt(personal, userId);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        requestWhenReady(
                                mockMvc(),
                                get("/api/me/practice-history").cookie(session.cookie()));
                assertEquals(
                        200,
                        response.getStatus(),
                        "Chưa mua gói vẫn đọc được lịch sử làm đề hệ thống của chính mình");
                JsonNode history = JSON.readTree(response.getContentAsString());
                boolean found = false;
                for (JsonNode entry : history) {
                    if (attemptId.toString().equals(entry.get("id").stringValue())) {
                        found = true;
                    }
                }
                assertTrue(found, "Lịch sử practice phải chứa lượt làm đã ghi của actor");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void operatorActivationEnablesPersonalResourceCreation() throws Exception {
        KeycloakLogin user = createVerifiedUser("entitlement-activated");
        KeycloakLogin operator = createOperator();
        try {
            UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
            UUID personal = personalWorkspaceOf(userId);
            seedAccount(operator.subject(), "SELF_REGISTERED", null, false);

            try (var userSession = openSession(user)) {
                assertEquals(
                        403,
                        sampleAction(userSession, personal, createResourceJson()).getStatus(),
                        "Trước khi kích hoạt gói, tạo học liệu personal bị từ chối");

                try (var operatorSession = openSession(operator)) {
                    MockHttpServletResponse confirm =
                            postRaw(
                                    operatorSession,
                                    "/api/platform/subscriptions",
                                    subscriptionJson(personal, -1, 365));
                    assertEquals(
                            202,
                            confirm.getStatus(),
                            "Operator xác nhận kỳ gói personal phải trả operation, body: "
                                    + confirm.getContentAsString());
                    UUID operationId =
                            UUID.fromString(
                                    JSON.readTree(confirm.getContentAsString())
                                            .get("operationId")
                                            .stringValue());
                    awaitOperationSucceeded(jdbc, operationId);
                }
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                                Integer.class,
                                personal),
                        "Kỳ gói personal chỉ ghi khi operation SUCCEEDED");

                MockHttpServletResponse created =
                        sampleAction(userSession, personal, createResourceJson());
                assertEquals(
                        201,
                        created.getStatus(),
                        "Sau khi operator kích hoạt gói, tạo học liệu personal được phép ngay,"
                                + " body: "
                                + created.getContentAsString());
                JsonNode body = JSON.readTree(created.getContentAsString());
                assertEquals("SUCCEEDED", body.get("state").stringValue());
            }
        } finally {
            deleteUser(user.subject());
            deleteUser(operator.subject());
        }
    }

    @Test
    void centerMembershipDoesNotOpenPersonalAuthoring() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("entitlement-center-owner");
        KeycloakLogin teacherLogin = createVerifiedUser("entitlement-center-teacher");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacherId =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(ownerId);
            addMembership(center, teacherId, "TEACHER");
            seedSubscription(center, -1, 365, ownerId);
            UUID teacherPersonal = personalWorkspaceOf(teacherId);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership WHERE workspace_id = ?"
                                    + " AND user_id = ? AND state = 'ACTIVE'",
                            Integer.class,
                            center,
                            teacherId),
                    "Fixture phải có membership center hiện hành cho giáo viên");

            try (var teacher = openSession(teacherLogin)) {
                MockHttpServletResponse response =
                        sampleAction(teacher, teacherPersonal, createResourceJson());
                assertEquals(
                        403,
                        response.getStatus(),
                        "Membership center còn hiệu lực không mở quyền soạn học liệu personal"
                                + " khi chưa có gói, body: "
                                + response.getContentAsString());
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    @Test
    void personalPlanDoesNotOpenCenterClassCreation() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("entitlement-plan-owner");
        KeycloakLogin userLogin = createVerifiedUser("entitlement-plan-user");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID userId =
                    seedAccount(userLogin.subject(), "SELF_REGISTERED", userLogin.email(), true);
            UUID center = seedCenter(ownerId);
            addMembership(center, userId, "STUDENT");
            seedSubscription(center, -1, 365, ownerId);
            UUID personal = personalWorkspaceOf(userId);
            seedSubscription(personal, -1, 365, userId);

            try (var user = openSession(userLogin)) {
                MockHttpServletResponse created =
                        sampleAction(user, personal, createResourceJson());
                assertEquals(
                        201,
                        created.getStatus(),
                        "Gói personal còn hiệu lực phải mở được kho soạn cá nhân, body: "
                                + created.getContentAsString());

                MockHttpServletResponse atCenter =
                        postRaw(
                                user,
                                "/api/workspaces/" + center + "/classes",
                                "{\"name\":\"Lớp trái quyền\"}");
                assertEquals(
                        403,
                        atCenter.getStatus(),
                        "Gói personal không thay quyền owner/manager tại center");
            }
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM school_class WHERE workspace_id = ?",
                            Integer.class,
                            center),
                    "Lời gọi bị từ chối không được để lại lớp tại center");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(userLogin.subject());
        }
    }

    // ---------- HTTP ----------

    private static String createResourceJson() {
        return "{\"action\":\"CREATE_RESOURCE\",\"subjectId\":\""
                + UUID.randomUUID()
                + "\",\"kind\":\"EXAM\",\"label\":\"Học liệu cá nhân\"}";
    }

    private MockHttpServletResponse sampleAction(RealSession session, UUID workspaceId, String json)
            throws Exception {
        return postRaw(session, "/api/workspaces/" + workspaceId + "/samples/actions", json);
    }

    private MockHttpServletResponse postRaw(RealSession session, String uri, String json)
            throws Exception {
        return mockMvc()
                .perform(
                        post(uri)
                                .cookie(session.cookie())
                                .with(csrf())
                                .header("Idempotency-Key", "personal-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn()
                .getResponse();
    }

    private String subscriptionJson(UUID workspaceId, int fromOffsetDays, int untilOffsetDays) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(fromOffsetDays);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(untilOffsetDays);
        return "{\"workspaceId\":\""
                + workspaceId
                + "\",\"validFrom\":\""
                + validFrom
                + "\",\"expiresAt\":\""
                + expiresAt
                + "\",\"confirmationRef\":\"personal-confirm-"
                + UUID.randomUUID()
                + "\"}";
    }

    // ---------- Dữ kiện ----------

    private UUID seedPracticeAttempt(UUID personalWorkspaceId, UUID userId) {
        UUID systemWorkspace = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,state) VALUES (?,'SYSTEM','ACTIVE')",
                    systemWorkspace);
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    systemWorkspace,
                    userId,
                    UUID.randomUUID());
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state,locked_at)"
                            + " VALUES (?,?,1,'Bản hệ thống','Nội dung','Đáp án','LOCKED',?)",
                    versionId,
                    resourceId,
                    Timestamp.from(Instant.now()));
            jdbc.update(
                    "INSERT INTO system_practice_attempt_sample(id,personal_workspace_id,"
                            + "user_id,system_resource_id,version_id,attempted_at,content_stub)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    attemptId,
                    personalWorkspaceId,
                    userId,
                    resourceId,
                    versionId,
                    Timestamp.from(Instant.now()),
                    "Bài làm mẫu");
            return null;
        });
        return attemptId;
    }

    private UUID seedAccount(
            String keycloakSub, String origin, @Nullable String email, boolean emailVerified) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    keycloakSub,
                    "personal-user-" + actor,
                    email,
                    emailVerified,
                    origin);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId),
                "personal workspace");
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state)"
                            + " VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "center-org-" + center,
                    "Center test",
                    ownerUserId);
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    ownerMembership,
                    workspace,
                    ownerUserId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')",
                    ownerMembership);
            return null;
        });
        return workspace;
    }

    private UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            for (String role : roles) {
                jdbc.update(
                        "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                        membership,
                        role);
            }
            return null;
        });
        return membership;
    }

    private void seedSubscription(
            UUID workspaceId, int validFromOffsetDays, int expiresOffsetDays, UUID confirmedBy) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(validFromOffsetDays);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresOffsetDays);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    validFrom,
                    expiresAt,
                    "personal-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    // ---------- Phiên ----------

    // ---------- Keycloak TEST realm qua Admin API ----------

    private KeycloakLogin createOperator() {
        KeycloakLogin login = createVerifiedUser("personal-operator");
        joinGroup(login.subject(), Objects.requireNonNull(operatorGroupId, "operatorGroupId"));
        return login;
    }

    private void joinGroup(String userId, String groupId) {
        withMasterToken(token -> {
            HTTP.put()
                    .uri(admin("/users/" + userId + "/groups/" + groupId))
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    // Token master được giữ trong bộ nhớ tới gần hết hạn thay vì xin mới cho mỗi lần gọi
    // Admin API; hạ hạn về EPOCH là token cũ hết được dùng.
    private static String cachedMasterToken = "";
    private static Instant masterTokenValidUntil = Instant.EPOCH;

    private static String cachedMasterToken() {
        if (Instant.now().isBefore(masterTokenValidUntil)) {
            return cachedMasterToken;
        }
        return fetchMasterToken();
    }

    private static synchronized String fetchMasterToken() {
        if (Instant.now().isBefore(masterTokenValidUntil)) {
            return cachedMasterToken;
        }
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
        String token =
                Objects.requireNonNull((String) response.get("access_token"), "access_token");
        long expiresIn =
                response.get("expires_in") instanceof Number number ? number.longValue() : 60;
        cachedMasterToken = token;
        masterTokenValidUntil = Instant.now().plusSeconds(Math.max(5, expiresIn - 10));
        return token;
    }

    private static synchronized void invalidateMasterToken() {
        masterTokenValidUntil = Instant.EPOCH;
    }

    private static <T> T withMasterToken(java.util.function.Function<String, T> call) {
        try {
            return call.apply(cachedMasterToken());
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw ex;
            }
            invalidateMasterToken();
            return call.apply(cachedMasterToken());
        }
    }

    // ---------- Nền realm: group operator và client resource-server ----------

    private static String ensureGroup(String groupPath) {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : groupPath.substring(1).split("/")) {
            path.append('/').append(segment);
            String currentPath = path.toString();
            String found = null;
            try {
                Map<String, Object> group =
                        withMasterToken(token ->
                                HTTP.get()
                                        .uri(admin("/group-by-path" + currentPath))
                                        .headers(headers -> headers.setBearerAuth(token))
                                        .retrieve()
                                        .body(MAP));
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
                // Cây /gk là object nền của realm: chỉ tạo khi thiếu, không xoá ở teardown.
                found = createResource(admin(endpoint), Map.of("name", segment));
            }
            parentId = found;
        }
        return Objects.requireNonNull(parentId, "operator group");
    }

    private static void ensureApiClient() {
        String audience = fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        List<Map<String, Object>> matches =
                withMasterToken(token ->
                        HTTP.get()
                                .uri(admin("/clients?clientId=" + audience))
                                .headers(headers -> headers.setBearerAuth(token))
                                .retrieve()
                                .body(LIST));
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
                withMasterToken(token ->
                        HTTP.get()
                                .uri(uri)
                                .headers(headers -> headers.setBearerAuth(token))
                                .retrieve()
                                .body(LIST));
        if (result == null) {
            throw new IllegalStateException("Keycloak list not readable: " + uri);
        }
        return result;
    }

    private static String createResource(String uri, Map<String, ?> body) {
        var response =
                withMasterToken(token ->
                        HTTP.post()
                                .uri(uri)
                                .headers(headers -> headers.setBearerAuth(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .toEntity(String.class));
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a location for " + uri);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void deleteResource(String uri) {
        withMasterToken(token -> {
            HTTP.delete()
                    .uri(uri)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }
}
