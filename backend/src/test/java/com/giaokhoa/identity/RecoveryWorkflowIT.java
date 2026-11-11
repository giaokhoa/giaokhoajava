package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.identity.model.RecoveryRequest;
import com.giaokhoa.identity.service.InitialCredentialService;
import com.giaokhoa.identity.service.InitialCredentialService.Handoff;
import com.giaokhoa.identity.service.RecoveryService;
import com.giaokhoa.identity.service.RecoveryService.Decision;
import com.giaokhoa.identity.service.RecoveryService.RequestView;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Workflow khôi phục danh tính và bàn giao credential ban đầu trên stack TEST thật: RecoveryService
 * ép phạm vi gửi yêu cầu và thứ tự REQUESTED → VERIFIED → RESOLVED/REJECTED trên schema dùng chung
 * của suite, InitialCredentialService tạo identity trên Keycloak 26.8.1 thật rồi mới ghi directory,
 * và mọi lỗi sau identity-create đều reconcile sạch. Endpoint HTTP thuộc task sau; class này khóa
 * hành vi service mà endpoint sẽ gọi.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecoveryWorkflowIT extends IntegrationEnvironment {
    private static final String DIRECT_CLIENT = "recovery-workflow-direct";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE =
            IdentityFlowIT.fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = IdentityFlowIT.fixture("TEST_KEYCLOAK_REALM");
    private static @Nullable String ownedDirectClientId;

    private final List<String> createdKeycloakUsers = new ArrayList<>();

    @AfterAll
    static void removeFixtures() {
        if (ownedDirectClientId != null) {
            try {
                deleteStatic(adminStatic("/clients/" + ownedDirectClientId));
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    throw ex;
                }
            }
        }
    }

    // Worker không thuộc phạm vi kiểm thử của class này (mọi khẳng định đều qua service đồng
    // bộ): mock để worker nền không tự xử lý operation song song với các lời gọi service
    // trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RecoveryService recoveryService;
    @Autowired InitialCredentialService handoff;

    @AfterEach
    void removeKeycloakUsers() {
        for (String userId : createdKeycloakUsers) {
            try {
                deleteUser(userId);
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    throw ex;
                }
            }
        }
        createdKeycloakUsers.clear();
    }

    // ---------- RecoveryService: gửi yêu cầu đúng phạm vi ----------

    @Test
    void submitForSelfCreatesRequestedRequest() {
        UUID actor = seedAccount("SELF_REGISTERED");

        RequestView view = recoveryService.submit(actor, actor, null);

        assertEquals(actor, view.requesterUserId());
        assertEquals(actor, view.targetUserId());
        assertNull(view.workspaceId());
        assertEquals(RecoveryRequest.State.REQUESTED, view.state());
        assertNull(view.verifiedBy());
        assertNull(view.resolvedAt());
        assertNotNull(view.createdAt());
    }

    @DisplayName("Submit kèm lý do giữ lý do đó cho hàng đợi của operator")
    @Test
    void submitKeepsReasonForOperator() {
        UUID actor = seedAccount("SELF_REGISTERED");

        RequestView created = recoveryService.submit(actor, actor, null, "  Quên mật khẩu  ");

        assertEquals("Quên mật khẩu", created.reason());
        // Hàng đợi chứa yêu cầu của cả class nên chỉ tìm đúng yêu cầu vừa gửi.
        List<RequestView> listed = recoveryService.listRequests();
        RequestView found =
                listed.stream()
                        .filter(view -> view.id().equals(created.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Quên mật khẩu", found.reason());
    }

    @Test
    void submitForMissingTargetReturnsNotFound() {
        UUID actor = seedAccount("SELF_REGISTERED");

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.submit(actor, UUID.randomUUID(), null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("RECOVERY_TARGET_NOT_FOUND", ex.getCode());
    }

    @DisplayName("Submit hộ người dùng khác mà không có workspace trả NOT_FOUND")
    @Test
    void submitForOtherWithoutWorkspace404() {
        UUID actor = seedAccount("SELF_REGISTERED");
        UUID other = seedAccount("CENTER_ISSUED");

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.submit(actor, other, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("RECOVERY_TARGET_NOT_FOUND", ex.getCode());
    }

    @Test
    void centerManagerSubmitsForStudentMember() {
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        UUID student = seedAccount("CENTER_ISSUED");
        addMembership(center, student, "STUDENT");

        RequestView view = recoveryService.submit(owner, student, center);

        assertEquals(RecoveryRequest.State.REQUESTED, view.state());
        assertEquals(center, view.workspaceId());
        assertEquals(student, view.targetUserId());
    }

    @DisplayName("Thành viên là học sinh không thể submit hộ thành viên khác")
    @Test
    void studentCannotSubmitForAnother() {
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        UUID student = seedAccount("CENTER_ISSUED");
        UUID otherStudent = seedAccount("CENTER_ISSUED");
        addMembership(center, student, "STUDENT");
        addMembership(center, otherStudent, "STUDENT");

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.submit(student, otherStudent, center));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @DisplayName("Manager của center khác không thể submit cho center này")
    @Test
    void foreignManagerCannotSubmitHere() {
        UUID ownerA = seedAccount("SELF_REGISTERED");
        UUID centerA = seedCenter(ownerA);
        UUID student = seedAccount("CENTER_ISSUED");
        addMembership(centerA, student, "STUDENT");
        UUID ownerB = seedAccount("SELF_REGISTERED");
        seedCenter(ownerB);

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.submit(ownerB, student, centerA));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ---------- RecoveryService: operator xác minh và resolve ----------

    @DisplayName("Verify chuyển REQUESTED sang VERIFIED và từ chối verify lặp lại")
    @Test
    void verifyTransitionsAndRejectsRepeat() {
        UUID actor = seedAccount("SELF_REGISTERED");
        UUID operator = seedAccount("SELF_REGISTERED");
        RequestView submitted = recoveryService.submit(actor, actor, null);

        RequestView verified =
                recoveryService.verify(operator, submitted.id(), " Đã đối chiếu qua kênh hỗ trợ ");

        assertEquals(RecoveryRequest.State.VERIFIED, verified.state());
        assertEquals(operator, verified.verifiedBy());
        assertEquals("Đã đối chiếu qua kênh hỗ trợ", verified.verificationNote());
        assertNull(verified.resolvedAt());

        HttpProblemException repeat =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.verify(operator, submitted.id(), null));
        assertEquals(HttpStatus.CONFLICT, repeat.getStatus());
        assertEquals("RECOVERY_INVALID_STATE", repeat.getCode());

        HttpProblemException blankNote =
                assertThrows(
                        HttpProblemException.class,
                        () -> recoveryService.verify(operator, submitted.id(), "  "));
        assertEquals(HttpStatus.BAD_REQUEST, blankNote.getStatus());
    }

    @DisplayName("Hoàn tất request đã VERIFIED sau khi operator xác nhận khôi phục và bàn giao")
    @Test
    void completeAfterOperatorConfirms() {
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        UUID manager = seedAccount("CENTER_ISSUED");
        addMembership(center, manager, "MANAGER");
        UUID operator = seedAccount("SELF_REGISTERED");
        RequestView submitted = recoveryService.submit(owner, manager, center);
        recoveryService.verify(operator, submitted.id(), "Đã xác minh qua điện thoại");

        RequestView resolved =
                recoveryService.resolve(
                        operator,
                        submitted.id(),
                        Decision.COMPLETE,
                        "Đã khôi phục và bàn giao qua kênh riêng");

        assertEquals(RecoveryRequest.State.RESOLVED, resolved.state());
        assertEquals(operator, resolved.verifiedBy());
        assertEquals("Đã khôi phục và bàn giao qua kênh riêng", resolved.verificationNote());
        assertNotNull(resolved.resolvedAt());
        // Resolve không bao giờ chạm vào danh tính của tài khoản đích: origin và trạng thái
        // danh tính của quản lý giữ nguyên, không có mật khẩu nào được cấp lại qua workflow.
        Map<String, Object> account =
                jdbc.queryForMap(
                        "SELECT origin, identity_state FROM user_account WHERE id = ?", manager);
        assertEquals("CENTER_ISSUED", account.get("origin"));
        assertEquals("ACTIVE", account.get("identity_state"));
    }

    @Test
    void resolveVerifiedRejectMarksRejected() {
        UUID actor = seedAccount("SELF_REGISTERED");
        UUID operator = seedAccount("SELF_REGISTERED");
        RequestView submitted = recoveryService.submit(actor, actor, null);
        recoveryService.verify(operator, submitted.id(), null);

        RequestView resolved =
                recoveryService.resolve(operator, submitted.id(), Decision.REJECT, null);

        assertEquals(RecoveryRequest.State.REJECTED, resolved.state());
        assertNotNull(resolved.resolvedAt());
    }

    @DisplayName("Approve chỉ xác minh (verify) và giữ quá trình khôi phục ở trạng thái pending")
    @Test
    void approveOnlyVerifies() {
        UUID actor = seedAccount("CENTER_ISSUED");
        UUID operator = seedAccount("SELF_REGISTERED");
        RequestView submitted = recoveryService.submit(actor, actor, null);

        RequestView resolved =
                recoveryService.resolve(
                        operator, submitted.id(), Decision.APPROVE, "Xác minh tại quầy hỗ trợ");

        assertEquals(RecoveryRequest.State.VERIFIED, resolved.state());
        assertEquals(operator, resolved.verifiedBy());
        assertEquals("Xác minh tại quầy hỗ trợ", resolved.verificationNote());
        assertNull(resolved.resolvedAt());
    }

    @Test
    void resolveTerminalRequestConflicts() {
        UUID actor = seedAccount("SELF_REGISTERED");
        UUID operator = seedAccount("SELF_REGISTERED");
        RequestView submitted = recoveryService.submit(actor, actor, null);
        recoveryService.resolve(operator, submitted.id(), Decision.REJECT, null);

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                recoveryService.resolve(
                                        operator,
                                        submitted.id(),
                                        Decision.APPROVE,
                                        "Xác minh lại"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("RECOVERY_INVALID_STATE", ex.getCode());
    }

    @DisplayName("Danh sách request trả các request đã submit theo thứ tự cũ nhất trước")
    @Test
    void listRequestsOldestFirst() throws Exception {
        UUID actor = seedAccount("SELF_REGISTERED");
        RequestView first = recoveryService.submit(actor, actor, null);
        // Sleep ngắn có chủ đích để hai request có mốc thời gian tạo khác nhau cho khẳng định
        // thứ tự ở dưới — không phải chờ một điều kiện nên không dùng Awaitility.
        Thread.sleep(100);
        RequestView second = recoveryService.submit(actor, actor, null);

        List<RequestView> listed = recoveryService.listRequests();

        List<UUID> ids = listed.stream().map(view -> view.id()).toList();
        assertTrue(ids.contains(first.id()));
        assertTrue(ids.contains(second.id()));
        assertTrue(ids.indexOf(first.id()) < ids.indexOf(second.id()));
    }

    // ---------- InitialCredentialService ----------

    @DisplayName("Cấp phát học sinh mới bàn giao credential tạm thời đúng một lần")
    @Test
    void issueHandsOffCredentialOnce() {
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        String username = "issued-student-" + UUID.randomUUID();

        Handoff result = handoff.issueNewStudent(owner, center, username, "Học Sinh Mới", null);
        String keycloakSub = trackAppAccount(result.issued().userId());

        assertEquals(username, result.username());
        assertTrue(result.temporaryPassword().length() >= 20);
        assertTrue(result.issued().accountCreated());
        Map<String, Object> keycloakUser = getUser(keycloakSub);
        assertEquals(Boolean.TRUE, keycloakUser.get("enabled"));
        assertNull(keycloakUser.get("email"));
        Object actions = keycloakUser.get("requiredActions");
        assertTrue(
                actions instanceof List<?> list && list.contains("UPDATE_PASSWORD"),
                "Credential tạm phải buộc đổi mật khẩu ở lần đăng nhập đầu");
        Map<String, Object> account =
                jdbc.queryForMap(
                        "SELECT origin, identity_state FROM user_account WHERE id = ?",
                        result.issued().userId());
        assertEquals("CENTER_ISSUED", account.get("origin"));
        assertEquals("ACTIVE", account.get("identity_state"));
        Map<String, Object> membership =
                jdbc.queryForMap(
                        "SELECT state FROM center_membership WHERE id = ?",
                        result.issued().membershipId());
        assertEquals("ACTIVE", membership.get("state"));
        List<String> roles =
                jdbc.queryForList(
                        "SELECT role FROM membership_role WHERE membership_id = ?",
                        String.class,
                        result.issued().membershipId());
        assertEquals(List.of("STUDENT"), roles);
    }

    @DisplayName("Cấp phát thất bại sau khi đã tạo identity thì xóa user Keycloak mồ côi")
    @Test
    void issueFailureDeletesOrphanUser() {
        UUID outsider = seedAccount("SELF_REGISTERED");
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        String username = "orphan-student-" + UUID.randomUUID();

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                handoff.issueNewStudent(
                                        outsider, center, username, "Học Sinh Mồ Côi", null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(
                findUserIdsByUsername(username).isEmpty(),
                "User Keycloak vừa tạo phải bị xoá khi ghi directory thất bại");
        List<UUID> accounts =
                jdbc.queryForList(
                        "SELECT id FROM user_account WHERE username = ?", UUID.class, username);
        assertTrue(accounts.isEmpty());
    }

    @DisplayName(
            "Cấp phát với username trong app DB đã bị chiếm thì bồi hoàn (compensate) user vừa tạo")
    @Test
    void issueWithSquattedNameCompensates() {
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        String username = "squatted-" + UUID.randomUUID();
        UUID squatter = seedAccount("SELF_REGISTERED", username);

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                handoff.issueNewStudent(
                                        owner, center, username, "Học Sinh Bị Chiếm Tên", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("USERNAME_TAKEN", ex.getCode());
        assertTrue(
                findUserIdsByUsername(username).isEmpty(),
                "User Keycloak vừa tạo phải bị xoá để giải phóng username");
        Map<String, Object> untouched =
                jdbc.queryForMap("SELECT origin FROM user_account WHERE id = ?", squatter);
        assertEquals("SELF_REGISTERED", untouched.get("origin"));
    }

    @DisplayName("Cấp phát với identity Keycloak đã tồn tại gây conflict và giữ nguyên mật khẩu")
    @Test
    void issueWithExistingIdentityConflicts() {
        ensureDirectGrantClient();
        UUID owner = seedAccount("SELF_REGISTERED");
        UUID center = seedCenter(owner);
        String username = "existing-" + UUID.randomUUID();
        String password = "existing-password-" + UUID.randomUUID();
        // Realm TEST bắt buộc email trong hồ sơ người dùng: danh tính có sẵn không email sẽ bị
        // Keycloak từ chối cấp token ("Account is not fully set up") nên probe mật khẩu không
        // chạy được; fixture dùng email đã xác minh để probe đúng hành vi cần khóa.
        String existingSub =
                createUser(
                        keycloakUser(username, username + "@example.com", true, password, false));
        createdKeycloakUsers.add(existingSub);
        assertEquals(200, grantStatus(username, password));

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                handoff.issueNewStudent(
                                        owner, center, username, "Danh Tính Đã Có", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("USERNAME_TAKEN", ex.getCode());
        Map<String, Object> keycloakUser = getUser(existingSub);
        assertEquals(Boolean.TRUE, keycloakUser.get("enabled"));
        assertEquals(
                200,
                grantStatus(username, password),
                "Mật khẩu của danh tính đã có không được bị đặt lại qua đường cấp mới");
        List<UUID> accounts =
                jdbc.queryForList(
                        "SELECT id FROM user_account WHERE keycloak_sub = ?",
                        UUID.class,
                        existingSub);
        assertTrue(accounts.isEmpty(), "Danh tính đã có không bị nhận nuôi vào directory");
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedAccount(String origin) {
        return seedAccount(origin, "user-" + UUID.randomUUID());
    }

    private UUID seedAccount(String origin, String username) {
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,NULL,FALSE,?,'ACTIVE')",
                    user,
                    "sub-" + user,
                    username,
                    origin);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    user);
            return null;
        });
        return user;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state) VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "workflow-org-" + center,
                    "Workflow test center",
                    ownerUserId);
            UUID ownerMembership = UUID.randomUUID();
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

    private void addMembership(UUID workspaceId, UUID userId, String... roles) {
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
    }

    private String trackAppAccount(UUID userId) {
        String sub =
                jdbc.queryForObject(
                        "SELECT keycloak_sub FROM user_account WHERE id = ?", String.class, userId);
        String keycloakSub = Objects.requireNonNull(sub, "keycloak_sub");
        createdKeycloakUsers.add(keycloakSub);
        return keycloakSub;
    }

    private <T> @Nullable T tx(java.util.function.Supplier<@Nullable T> work) {
        return new TransactionTemplate(transactionManager).execute(_ -> work.get());
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> keycloakUser(
            String username,
            @Nullable String email,
            boolean emailVerified,
            String password,
            boolean temporary) {
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("emailVerified", emailVerified);
        body.put("requiredActions", List.of());
        if (email != null) {
            body.put("email", email);
        }
        body.put(
                "credentials",
                List.of(
                        Map.of(
                                "type", "password",
                                "value", password,
                                "temporary", temporary)));
        return body;
    }

    private String createUser(Map<String, Object> body) {
        ResponseEntity<String> response =
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
        return path.substring(path.lastIndexOf('/') + 1);
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

    private List<String> findUserIdsByUsername(String username) {
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/users") + "?username=" + username + "&exact=true")
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (matches == null) {
            return List.of();
        }
        return matches.stream().map(user -> (String) user.get("id")).toList();
    }

    private void deleteUser(String userId) {
        deleteWithConflictRetry(() -> deleteStatic(admin("/users/" + userId)));
    }

    private String admin(String path) {
        return adminStatic(path);
    }

    private static String adminStatic(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private static void deleteStatic(String uri) {
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
        form.add("username", IdentityFlowIT.fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", IdentityFlowIT.fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
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

    // ---------- Direct Access Grants cho probe mật khẩu ----------

    private static void ensureDirectGrantClient() {
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(adminStatic("/clients?clientId=" + DIRECT_CLIENT))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        var response =
                HTTP.post()
                        .uri(adminStatic("/clients"))
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
}
