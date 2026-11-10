package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.PlatformProjectionSeeder;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.support.KeycloakSessionFixture;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Contract US1 ở tầng khôi phục danh tính: account không email chỉ được khôi phục bằng quyền hành
 * trên server của nền tảng (operator), người thường không đặt lại được mật khẩu của người khác;
 * account có email đã xác minh nhận action email đặt lại mật khẩu; endpoint recovery của hợp đồng
 * HTTP xác thực vai trò và đăng nhập trước khi chạm nghiệp vụ.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class RecoveryFlowIT extends KeycloakSessionFixture {
    private static final String DIRECT_CLIENT = "recovery-flow-direct";
    private static final String WEB_CLIENT = "giaokhoa-it-web";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";

    private static @Nullable String ownedDirectClientId;

    @BeforeAll
    static void seedPlatformRealmPrerequisites() {
        ensureGroupPath(NativePolicyTemplates.ELIGIBILITY_GROUP);
        ensureGroupPath(NativePolicyTemplates.PLATFORM_OPERATORS_GROUP);
        ensureApiClient();
    }

    @AfterAll
    static void removeFixtures() {
        if (ownedDirectClientId != null) {
            try {
                deleteStatic(admin("/clients/" + ownedDirectClientId));
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    throw ex;
                }
            }
        }
    }

    @Autowired RecoveryService recoveryService;
    @Autowired PlatformProjectionSeeder platformSeeder;
    @Autowired ProjectionReconciler projectionReconciler;

    @DisplayName(
            "Credential của operator reset mật khẩu của user đã được cấp phát mà không đổi identity")
    @Test
    void operatorResetKeepsIdentity() {
        String subject =
                createUser(issuedUser("recovery-issued", "recovery-temp-" + UUID.randomUUID()));

        try {
            String newPassword = "recovery-new-" + UUID.randomUUID();
            int status = adminResetPassword(subject, newPassword);

            assertEquals(204, status, "Operator đặt lại được mật khẩu của account không email");
            Map<String, Object> after = getUser(subject);
            assertEquals(subject, after.get("id"), "subject của danh tính không đổi");
            assertNull(after.get("email"), "Mật khẩu mới không ép account phải có email");
            Object actions = after.get("requiredActions");
            assertFalse(
                    actions instanceof List<?> list && list.contains("UPDATE_PASSWORD"),
                    "Operator đặt mật khẩu permanent thì không còn required action đổi mật khẩu");
        } finally {
            deleteUser(subject);
        }
    }

    @DisplayName("Credential của người dùng thường không thể reset mật khẩu của người dùng khác")
    @Test
    void ordinaryUserCannotResetOthers() {
        String actorSubject = null;
        String targetSubject = null;
        ensureDirectGrantClient();
        try {
            String actorPassword = "recovery-actor-" + UUID.randomUUID();
            actorSubject =
                    createUser(
                            keycloakUser(
                                    "recovery-actor",
                                    "recovery-actor@example.test",
                                    true,
                                    actorPassword));
            targetSubject =
                    createUser(issuedUser("recovery-target", "recovery-temp-" + UUID.randomUUID()));

            String actorToken = directGrantToken("recovery-actor", actorPassword);
            int status;
            try {
                HTTP.put()
                        .uri(admin("/users/" + targetSubject + "/reset-password"))
                        .headers(headers -> headers.setBearerAuth(actorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "type",
                                        "password",
                                        "value",
                                        "recovery-stolen-" + UUID.randomUUID(),
                                        "temporary",
                                        false))
                        .retrieve()
                        .toBodilessEntity();
                status = 204;
            } catch (HttpStatusCodeException ex) {
                status = ex.getStatusCode().value();
            }

            assertEquals(
                    403,
                    status,
                    "Người dùng thường, kể cả quản lý trung tâm, không tự đổi được mật khẩu"
                            + " của danh tính dùng chung");
        } finally {
            if (actorSubject != null) {
                deleteUser(actorSubject);
            }
            if (targetSubject != null) {
                deleteUser(targetSubject);
            }
        }
    }

    @DisplayName("Sau khi operator reset, user đã xác minh chỉ đăng nhập được bằng mật khẩu mới")
    @Test
    void resetUserLogsInWithNewPasswordOnly() {
        ensureDirectGrantClient();
        String oldPassword = "recovery-old-" + UUID.randomUUID();
        String username = "recovery-verified";
        String subject =
                createUser(
                        keycloakUser(
                                username, "recovery-verified@example.test", true, oldPassword));
        try {
            assertEquals(200, grantStatus(username, oldPassword));

            String newPassword = "recovery-new-" + UUID.randomUUID();
            assertEquals(204, adminResetPassword(subject, newPassword));

            assertEquals(
                    400,
                    grantStatus(username, oldPassword),
                    "Mật khẩu cũ không còn đăng nhập được sau khi khôi phục");
            assertEquals(200, grantStatus(username, newPassword));
        } finally {
            deleteUser(subject);
        }
    }

    @DisplayName("User có email đã xác minh được gửi email hành động mật khẩu (password action)")
    @Test
    void verifiedUserGetsPasswordEmail() {
        String subject =
                createUser(
                        keycloakUser(
                                "recovery-email",
                                "recovery-email@example.test",
                                true,
                                "recovery-email-" + UUID.randomUUID()));

        try {
            var response =
                    HTTP.put()
                            .uri(
                                    admin("/users/" + subject + "/execute-actions-email")
                                            + "?client_id="
                                            + WEB_CLIENT)
                            .headers(headers -> headers.setBearerAuth(masterToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(List.of("UPDATE_PASSWORD"))
                            .retrieve()
                            .toEntity(String.class);

            assertEquals(
                    204,
                    response.getStatusCode().value(),
                    "Account có email đã xác minh nhận được action email đặt lại mật khẩu");
        } finally {
            deleteUser(subject);
        }
    }

    @DisplayName("Endpoint yêu cầu khôi phục tạo request cho chính actor")
    @Test
    void recoveryEndpointCreatesRequest() throws Exception {
        String username = "recovery-request-" + UUID.randomUUID();
        String password = "recovery-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedActor(subject);

            try (var session = openSession(username, password, subject)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "recovery-flow-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"targetUserId\":\""
                                                                + actor
                                                                + "\",\"reason\":\"Quên mật khẩu\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void submitWithoutReasonIsRejected() throws Exception {
        String username = "recovery-no-reason-" + UUID.randomUUID();
        String password = "recovery-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedActor(subject);

            try (var session = openSession(username, password, subject)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "recovery-no-reason-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"targetUserId\":\"" + actor + "\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(400, response.getStatus());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void rejectedRequestPollShowsRejectionCode() throws Exception {
        String requesterName = "recovery-reject-" + UUID.randomUUID();
        String operatorName = "recovery-operator-" + UUID.randomUUID();
        String password = "recovery-password-" + UUID.randomUUID();
        String requesterSubject =
                createUser(
                        keycloakUser(
                                requesterName, requesterName + "@example.test", true, password));
        String operatorSubject =
                createUser(
                        keycloakUser(operatorName, operatorName + "@example.test", true, password));
        try {
            UUID requester = seedActor(requesterSubject);
            UUID operator = seedActor(operatorSubject);
            RecoveryService.RequestView created =
                    recoveryService.submit(requester, requester, null, "Quên mật khẩu");
            recoveryService.resolve(
                    operator, created.id(), RecoveryService.Decision.REJECT, "Không xác minh được");

            try (var session = openSession(requesterName, password, requesterSubject)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        get("/api/me/operations/" + created.id())
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("FAILED", body.get("state").stringValue());
                assertTrue(body.get("completed").booleanValue());
                assertEquals("RECOVERY_REJECTED", body.get("errorCode").stringValue());
            }
        } finally {
            deleteUser(requesterSubject);
            deleteUser(operatorSubject);
        }
    }

    @DisplayName("Các endpoint khôi phục của platform đòi hỏi operator scope")
    @Test
    void recoveryEndpointsNeedOperatorScope() throws Exception {
        String username = "recovery-plain-" + UUID.randomUUID();
        String password = "recovery-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            try (var session = openSession(username, password, subject)) {
                assertEquals(
                        200,
                        mockMvc()
                                .perform(get("/api/session").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus());
                MockHttpServletResponse list =
                        requestWhenReady(
                                mockMvc(),
                                get("/api/platform/recovery-requests").cookie(session.cookie()));
                MockHttpServletResponse resolve =
                        mockMvc()
                                .perform(
                                        post("/api/platform/recovery-requests/"
                                                        + UUID.randomUUID()
                                                        + "/resolve")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "recovery-resolve-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("If-Match", "\"0\"")
                                                .content("{\"decision\":\"RESET_PASSWORD\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, list.getStatus(), "Queue khôi phục chỉ dành cho operator");
                assertEquals(403, resolve.getStatus());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @DisplayName("Operator của platform liệt kê và resolve yêu cầu khôi phục qua HTTP")
    @Test
    void operatorResolvesRequestOverHttp() throws Exception {
        publishPlatform();
        String requesterName = "recovery-queue-requester-" + UUID.randomUUID();
        String operatorName = "recovery-queue-operator-" + UUID.randomUUID();
        String password = "recovery-password-" + UUID.randomUUID();
        String requesterSubject =
                createUser(
                        keycloakUser(
                                requesterName, requesterName + "@example.test", true, password));
        String operatorSubject =
                createUser(
                        keycloakUser(operatorName, operatorName + "@example.test", true, password));
        try {
            joinGroup(operatorSubject, NativePolicyTemplates.ELIGIBILITY_GROUP);
            joinGroup(operatorSubject, NativePolicyTemplates.PLATFORM_OPERATORS_GROUP);
            UUID requester = seedActor(requesterSubject);
            seedActor(operatorSubject);
            RecoveryService.RequestView created =
                    recoveryService.submit(requester, requester, null, "Quên mật khẩu");

            try (var session = openSession(operatorName, password, operatorSubject)) {
                // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
                // của operator trước khi chạm endpoint platform.
                MockHttpServletResponse workspaces = getWorkspacesWhenReady(session);
                assertEquals(200, workspaces.getStatus(), workspaces.getContentAsString());

                MockHttpServletResponse list =
                        mockMvc()
                                .perform(
                                        get("/api/platform/recovery-requests")
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();
                assertEquals(200, list.getStatus(), list.getContentAsString());
                JsonNode item = null;
                for (JsonNode candidate : JSON.readTree(list.getContentAsString())) {
                    if (created.id().toString().equals(candidate.get("id").stringValue())) {
                        item = candidate;
                        break;
                    }
                }
                assertNotNull(item, "Hàng đợi platform phải chứa yêu cầu vừa gửi");
                assertEquals("REQUESTED", item.get("state").stringValue());

                String path = "/api/platform/recovery-requests/" + created.id() + "/resolve";
                MockHttpServletResponse approved =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "recovery-approve-" + UUID.randomUUID())
                                                .header("If-Match", "\"0\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"decision\":\"APPROVE\",\"verificationNote\":\"Đã xác minh đúng chủ tài khoản\"}"))
                                .andReturn()
                                .getResponse();
                assertEquals(202, approved.getStatus(), approved.getContentAsString());
                JsonNode approval = JSON.readTree(approved.getContentAsString());
                assertEquals("PENDING", approval.get("state").stringValue());
                assertFalse(approval.get("completed").booleanValue());

                MockHttpServletResponse completed =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "recovery-complete-" + UUID.randomUUID())
                                                .header("If-Match", "\"1\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"decision\":\"COMPLETE\",\"verificationNote\":\"Đã khôi phục và bàn giao qua kênh riêng\"}"))
                                .andReturn()
                                .getResponse();
                assertEquals(200, completed.getStatus(), completed.getContentAsString());
                JsonNode receipt = JSON.readTree(completed.getContentAsString());
                assertEquals("SUCCEEDED", receipt.get("state").stringValue());
                assertTrue(receipt.get("completed").booleanValue());
                assertEquals(requester.toString(), receipt.get("targetId").stringValue());
            }
        } finally {
            deleteUser(requesterSubject);
            deleteUser(operatorSubject);
        }
    }

    @DisplayName("Yêu cầu khôi phục ẩn danh bị chuyển hướng tới trang đăng nhập")
    @Test
    void anonymousRequestRedirectsToLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                post("/api/me/recovery-requests")
                                        .with(csrf())
                                        .header(
                                                "Idempotency-Key",
                                                "recovery-anon-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                        .andReturn()
                        .getResponse();

        assertEquals(401, response.getStatus());
        JsonNode problem = JSON.readTree(response.getContentAsString());
        assertEquals(
                "UNAUTHORIZED",
                problem.get("code").stringValue(),
                "Khôi phục ẩn danh bị từ chối bằng 401 ProblemDetail");
        assertFalse(response.getContentAsString().contains("targetUserId"));
    }

    @DisplayName("Request ẩn danh với cookie giả mạo vẫn không được xác thực")
    @Test
    void forgedCookieStaysUnauthenticated() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                post("/api/me/recovery-requests")
                                        .cookie(
                                                new Cookie(
                                                        "SESSION", "forged-" + UUID.randomUUID()))
                                        .with(csrf())
                                        .header(
                                                "Idempotency-Key",
                                                "recovery-forged-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                        .andReturn()
                        .getResponse();

        assertEquals(401, response.getStatus());
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedActor(String keycloakSub) {
        UUID actor = UUID.randomUUID();
        // Trigger của DB bắt user_account và personal workspace tồn tại trong cùng một giao dịch.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    jdbc.update(
                            "INSERT INTO user_account(id,keycloak_sub,username,email,"
                                    + "email_verified,origin,identity_state)"
                                    + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                            actor,
                            keycloakSub,
                            "recovery-user-" + actor,
                            keycloakSub + "@example.test");
                    jdbc.update(
                            "INSERT INTO workspace(id,kind,owner_user_id,state)"
                                    + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                            UUID.randomUUID(),
                            actor);
                });
        return actor;
    }

    // ---------- Graph platform và group nền trên realm dùng chung ----------

    private void publishPlatform() {
        platformSeeder.seed();
        UUID partitionId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM authz_partition WHERE kind = 'PLATFORM'"
                                        + " AND owner_key = 'platform'",
                                UUID.class),
                        "platform partition");
        projectionReconciler.reconcilePartition(partitionId);
        assertEquals(
                "READY",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?",
                        String.class,
                        partitionId));
    }

    private void joinGroup(String subject, String groupPath) {
        HTTP.put()
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
     * Resource server client mà policy graph writer resolve theo audience: test-realm.json không
     * khai client này, các integration test tự tạo khi thiếu (theo mẫu PlatformProjectionIT) để
     * class chạy độc lập; client là object realm dùng chung nên tạo xong để lại, không xóa.
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

    private static void removeClientDefaults(String clientId) {
        String root = "/clients/" + clientId + "/authz/resource-server";
        for (Map<String, Object> permission : getList(admin(root + "/permission/resource"))) {
            if (DEFAULT_PERMISSION.equals(permission.get("name"))) {
                deleteStatic(admin(root + "/permission/resource/" + permission.get("id")));
            }
        }
        for (Map<String, Object> resource : getList(admin(root + "/resource"))) {
            if (DEFAULT_RESOURCE.equals(resource.get("name"))) {
                deleteStatic(admin(root + "/resource/" + resource.get("_id")));
            }
        }
        for (Map<String, Object> policy : getList(admin(root + "/policy"))) {
            if (DEFAULT_POLICY.equals(policy.get("name"))) {
                deleteStatic(admin(root + "/policy/" + policy.get("id")));
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

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> issuedUser(String username, String temporaryPassword) {
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("emailVerified", false);
        body.put("requiredActions", List.of("UPDATE_PASSWORD"));
        body.put(
                "credentials",
                List.of(Map.of("type", "password", "value", temporaryPassword, "temporary", true)));
        return body;
    }

    private Map<String, Object> getUser(String userId) {
        Map<String, Object> user =
                HTTP.get()
                        .uri(admin("/users/" + userId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(MAP);
        if (user == null) {
            throw new IllegalStateException("Keycloak user not readable: " + userId);
        }
        return user;
    }

    private int adminResetPassword(String userId, String newPassword) {
        try {
            var response =
                    HTTP.put()
                            .uri(admin("/users/" + userId + "/reset-password"))
                            .headers(headers -> headers.setBearerAuth(masterToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(
                                    Map.of(
                                            "type",
                                            "password",
                                            "value",
                                            newPassword,
                                            "temporary",
                                            false))
                            .retrieve()
                            .toBodilessEntity();
            return response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().value();
        }
    }

    private static void deleteStatic(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                .retrieve()
                .toBodilessEntity();
    }

    // ---------- Direct Access Grants cho probe mật khẩu ----------

    private static void ensureDirectGrantClient() {
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + DIRECT_CLIENT))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        var response =
                HTTP.post()
                        .uri(admin("/clients"))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "clientId", DIRECT_CLIENT,
                                        "protocol", "openid-connect",
                                        "enabled", true,
                                        "publicClient", true,
                                        "standardFlowEnabled", false,
                                        "directAccessGrantsEnabled", true,
                                        "fullScopeAllowed", false))
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created client location");
        }
        String path = location.getPath();
        ownedDirectClientId = path.substring(path.lastIndexOf('/') + 1);
    }

    private int grantStatus(String username, String password) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "password");
            form.add("client_id", DIRECT_CLIENT);
            form.add("username", username);
            form.add("password", password);
            var response =
                    HTTP.post()
                            .uri(BASE + "/realms/" + REALM + "/protocol/openid-connect/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .toBodilessEntity();
            return response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().value();
        }
    }

    private String directGrantToken(String username, String password) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", DIRECT_CLIENT);
        form.add("username", username);
        form.add("password", password);
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("Direct grant token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }
}
