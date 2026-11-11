package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.identity.service.IdentityService;
import com.giaokhoa.identity.service.RecoveryService;
import com.giaokhoa.support.HttpTestClient;
import com.giaokhoa.support.KeycloakSessionFixture;
import com.giaokhoa.support.RealmFoundation;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.service.WorkspaceDirectoryService;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Contract ở tầng danh tính: profile Keycloak hiện hành lái eligibility qua synchronizer và worker
 * production. Quan hệ thư mục (membership, role, origin) giữ đúng ranh giới từng workspace. Các
 * endpoint identity của hợp đồng HTTP được lái bằng phiên Code+PKCE thật.
 *
 * <p>Phần hành vi native của role trong PDP (quản lý ở A, học sinh ở B) thuộc kiểm chứng native của
 * hợp đồng quyết định quyền. Lớp kiểm thử này khóa phần danh tính và thư mục của hợp đồng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class IdentityFlowE2EIT extends KeycloakSessionFixture {
    private static final String ELIGIBLE_GROUP = "/gk/identity/eligible";

    @BeforeAll
    static void seedRealmPrerequisites() {
        seedEligibilityGroup();
        ensureApiClient();
    }

    @Autowired IdentityEligibilityService synchronizer;

    @Autowired WorkspaceDirectoryService directory;

    @Test
    void unverifiedSelfRegisteredUserHasNoEligibilityUntilEmailVerified() {
        String subject =
                createUser(keycloakUser("flow-unverified", "unverified@example.test", false));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "unverified@example.test", false);

            synchronizer.synchronizeEligibility(actor);

            assertFalse(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Chưa xác minh email thì không được vào nhóm eligibility");
            assertEquals(
                    Boolean.FALSE,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor));

            setEmailVerified(subject, true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent(), "Vừa xác minh email phải ghi một thay đổi eligibility");
            awaitSucceeded(granted.get().getId());
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Đã xác minh email thì được chiếu vào nhóm eligibility");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void changingEmailToAnUnverifiedOneRevokesEligibility() {
        String subject = createUser(keycloakUser("flow-email-change", "before@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "before@example.test", true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            assertTrue(groupPathsOf(subject).contains(ELIGIBLE_GROUP));

            updateProfile(subject, "after@example.test", false);
            Optional<AuthorizationOperation> revoked = synchronizer.synchronizeEligibility(actor);

            assertTrue(revoked.isPresent(), "Đổi sang email chưa xác minh phải ghi thay đổi");
            awaitSucceeded(revoked.get().getId());
            assertFalse(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Email hiện hành chưa xác minh thì mất eligibility");
            assertEquals(
                    "after@example.test",
                    jdbc.queryForObject(
                            "SELECT email FROM user_account WHERE id = ?", String.class, actor));
            assertEquals(
                    Boolean.FALSE,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void verifiedUserBaselineIsNeutral() {
        String subject = createUser(keycloakUser("flow-neutral", "neutral@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "neutral@example.test", true);

            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());

            assertEquals(
                    List.of(ELIGIBLE_GROUP),
                    groupPathsOf(subject),
                    "Tài khoản trung tính chỉ thuộc nhóm eligibility, không vai trò toàn hệ thống nào");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership WHERE user_id = ?",
                            Integer.class,
                            actor));
            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, actor));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void neutralUserWorkspacesEndpointListsOnlyPersonalWorkspace() throws Exception {
        String username = "flow-workspaces-" + UUID.randomUUID();
        String password = "flow-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            UUID personalWorkspace =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id FROM workspace WHERE owner_user_id = ?"
                                            + " AND kind = 'PERSONAL'",
                                    UUID.class,
                                    actor),
                            "personal workspace");

            webContext.getBean(IdentityService.class).bootstrap(subject);
            awaitIdentity(actor);
            try (var session = openSession(username, password, subject)) {
                HttpTestClient.Response response =
                        realHttp().get("/api/me/workspaces", session.cookie());

                assertEquals(200, response.status());
                JsonNode body = JSON.readTree(response.body());
                assertTrue(body.isArray(), "Danh sách workspace là một mảng");
                assertEquals(1, body.size(), "Tài khoản trung tính chỉ có workspace cá nhân");
                assertEquals(personalWorkspace.toString(), body.get(0).get("id").stringValue());
                assertEquals("PERSONAL", body.get(0).get("kind").stringValue());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void centerIssuedUserWithoutEmailIsEligibleWithoutVerification() {
        String username = "flow-issued-" + UUID.randomUUID();
        Map<String, Object> issued = new HashMap<>();
        issued.put("username", username);
        issued.put("enabled", true);
        issued.put("firstName", "Synthetic");
        issued.put("lastName", "User");
        issued.put("emailVerified", false);
        issued.put("requiredActions", List.of("UPDATE_PASSWORD"));
        issued.put(
                "credentials",
                List.of(
                        Map.of(
                                "type",
                                "password",
                                "value",
                                "flow-temp-" + UUID.randomUUID(),
                                "temporary",
                                true)));
        String subject = createUser(issued);
        try {
            Map<String, Object> representation = getUser(subject);
            assertNull(representation.get("email"));
            assertEquals(
                    List.of("UPDATE_PASSWORD"),
                    representation.get("requiredActions"),
                    "Học sinh được cấp không email chỉ có một required action đổi mật khẩu,"
                            + " không bị ép xác minh email hay bổ sung profile");

            UUID actor = seedAccount(subject, "CENTER_ISSUED", null, false);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);

            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Account do backend cấp được eligibility mà không cần email đã xác minh");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void existingIdentityJoinsSecondCenterWithoutChangingOrigin() {
        String subject = createUser(keycloakUser("flow-invitee", "invitee@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "invitee@example.test", true);
            UUID owner =
                    seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, actor, "STUDENT");

            addMembership(centerB, actor, "STUDENT");

            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, actor),
                    "Vào thêm trung tâm không đổi origin của danh tính đã có");
            assertEquals(
                    subject,
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            actor));
            assertEquals(Set.of("STUDENT"), activeRoles(centerA, actor));
            assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void oppositeRolesInTwoCentersStaySeparated() {
        UUID actor =
                seedAccount("flow-opposite-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID owner = seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID centerA = seedCenter(owner);
        UUID centerB = seedCenter(owner);
        addMembership(centerA, actor, "MANAGER");
        addMembership(centerB, actor, "STUDENT");

        assertEquals(Set.of("MANAGER"), activeRoles(centerA, actor));
        assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));

        revokeMembership(centerA, actor);

        assertEquals(Set.of(), activeRoles(centerA, actor), "Quyền quản lý ở A đã bị thu hồi");
        assertEquals(
                Set.of("STUDENT"),
                activeRoles(centerB, actor),
                "Vai trò học sinh ở B không bị quyền ở A kéo theo");
    }

    @Test
    void leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter() {
        String subject = createUser(keycloakUser("flow-leaver", "leaver@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "leaver@example.test", true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            UUID owner =
                    seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, actor, "MANAGER");
            addMembership(centerB, actor, "STUDENT");

            revokeMembership(centerA, actor);

            assertEquals(
                    subject,
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            actor),
                    "Rời một trung tâm không xóa hay đổi danh tính");
            assertEquals(
                    "ACTIVE",
                    jdbc.queryForObject(
                            "SELECT identity_state FROM user_account WHERE id = ?",
                            String.class,
                            actor));
            assertEquals(
                    "ACTIVE",
                    directory.listWorkspaces(actor).stream()
                            .filter(workspace -> workspace.kind() == Workspace.Kind.PERSONAL)
                            .map(workspace -> workspace.state())
                            .findFirst()
                            .orElse("MISSING"));
            assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Eligibility là thuộc tính của danh tính, không mất khi rời một trung tâm");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void anonymousCallerReceivesProblemUnauthorized() {
        HttpTestClient.Response response =
                realHttp()
                        .get(
                                "/api/me/workspaces",
                                null,
                                Map.of("Accept", MediaType.APPLICATION_JSON_VALUE));

        assertEquals(401, response.status());
        assertTrue(
                Objects.requireNonNull(response.header("Content-Type"), "Content-Type")
                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        JsonNode body = JSON.readTree(response.body());
        assertEquals("UNAUTHORIZED", body.get("code").stringValue());
        assertFalse(
                body.get("traceId").stringValue().isBlank(),
                "API chưa đăng nhập nhận 401 ProblemDetail, không redirect và không dữ liệu nghiệp vụ");
    }

    @Test
    void forgedSessionCookieDoesNotAuthenticate() {
        HttpTestClient.Response response =
                realHttp()
                        .get(
                                "/api/me/workspaces",
                                new Cookie("SESSION", "forged-" + UUID.randomUUID()),
                                Map.of("Accept", MediaType.APPLICATION_JSON_VALUE));

        assertEquals(401, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("UNAUTHORIZED", body.get("code").stringValue());
    }

    @Test
    void sessionEndpointDescribesAnonymousState() {
        HttpTestClient.Response response = realHttp().get("/api/session", null);

        assertEquals(200, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.get("authenticated").booleanValue());
        assertFalse(
                body.get("csrf").get("token").stringValue().isBlank(),
                "Phiên ẩn danh vẫn nhận CSRF token để bắt đầu đăng nhập");
        assertFalse(body.has("user"), "Chưa đăng nhập thì không có thông tin người dùng");
    }

    @Test
    void lostVerificationBlocksWorkspaceMetadataAndHistoryOnTheSameSession() throws Exception {
        String username = "regression-eligibility-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = webContext.getBean(IdentityService.class).bootstrap(subject).userId();
            awaitIdentity(actor);
            UUID center = seedCenter(actor);
            try (var session = openSession(username, password, subject)) {
                assertEquals(200, realHttp().get("/api/me/workspaces", session.cookie()).status());
                assertEquals(
                        200, realHttp().get("/api/me/practice-history", session.cookie()).status());
                updateProfile(subject, "changed-" + UUID.randomUUID() + "@example.test", false);
                for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                    // Qua fence trước: còn 503 AUTHORIZATION_NOT_READY nghĩa là eligibility
                    // đang đồng bộ lại sau khi hồ sơ đổi; trạng thái cuối phải là PDP từ chối
                    // dứt khoát 403, không lẫn hai khả năng như khẳng định cũ.
                    HttpTestClient.Response denied =
                            realHttp().whenReady(() -> realHttp().get(path, session.cookie()));
                    assertEquals(403, denied.status(), denied.body());
                    assertFalse(denied.body().contains(center.toString()));
                    assertFalse(denied.body().contains("Flow test center"));
                }
                awaitIdentity(actor);
                assertFalse(groupPathsOf(subject).contains(ELIGIBLE_GROUP));
                setEmailVerified(subject, true);
                webContext.getBean(IdentityService.class).bootstrap(subject);
                awaitIdentity(actor);
                assertEquals(
                        200, realHttp().get("/api/me/practice-history", session.cookie()).status());
                webContext
                        .getBean(
                                com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient
                                        .class)
                        .setRealmGroupMembership(subject, ELIGIBLE_GROUP, false);
                for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                    assertEquals(403, realHttp().get(path, session.cookie()).status());
                }
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void pendingOrFailedPersonalPartitionBlocksBothProfileReadEndpoints() throws Exception {
        String username = "regression-fence-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            var actor = webContext.getBean(IdentityService.class).bootstrap(subject);
            awaitIdentity(actor.userId());
            try (var session = openSession(username, password, subject)) {
                for (String state : List.of("PENDING", "FAILED")) {
                    jdbc.update(
                            "UPDATE authz_partition SET state = ? WHERE owner_key = ?",
                            state,
                            "workspace:" + actor.personalWorkspaceId());
                    for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                        assertEquals(503, realHttp().get(path, session.cookie()).status());
                    }
                }
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapConvergesAndConcurrentFirstRequestsShareOneIdentity() throws Exception {
        String username = "regression-bootstrap-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, username + "@example.test", true));
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var identities = webContext.getBean(IdentityService.class);
            try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first =
                        threads.submit(() -> {
                            start.await();
                            return identities.bootstrap(subject);
                        });
                var second =
                        threads.submit(() -> {
                            start.await();
                            return identities.bootstrap(subject);
                        });
                start.countDown();
                var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(a.userId(), b.userId());
                assertEquals(a.personalWorkspaceId(), b.personalWorkspaceId());
                assertNotEquals(a.created(), b.created());
                awaitIdentity(a.userId());
                assertTrue(groupPathsOf(subject).contains(ELIGIBLE_GROUP));
                assertEquals(
                        2,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authz_partition WHERE owner_key IN (?,?) AND state = 'READY'",
                                Integer.class,
                                "account:" + a.userId(),
                                "workspace:" + a.personalWorkspaceId()));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                                Integer.class,
                                a.userId()));
                assertFalse(identities.bootstrap(subject).created());
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                                Integer.class,
                                a.userId()));
                var resource =
                        webContext
                                .getBean(com.giaokhoa.authorization.policy.PolicyGraphWriter.class)
                                .findResourceId("account:" + a.userId())
                                .orElseThrow();
                assertNotNull(resource);
                assertTrue(
                        webContext
                                .getBean(
                                        com.giaokhoa.authorization.projection.ProjectionReconciler
                                                .class)
                                .verifyStoredGraph(
                                        Objects.requireNonNull(
                                                jdbc.queryForObject(
                                                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                                                        UUID.class,
                                                        "account:" + a.userId()))));
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void recoveryHttpRetriesReplayAndPreconditionsAreRequired() throws Exception {
        String username = "regression-replay-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            try (var session = openSession(username, password, subject)) {
                String body =
                        JSON.writeValueAsString(
                                Map.of("targetUserId", actor, "reason", "Quên mật khẩu"));
                String key = "retry-" + UUID.randomUUID();
                HttpTestClient.Response missing =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        "/api/me/recovery-requests",
                                        session.cookie(),
                                        true,
                                        body,
                                        Map.of());
                assertEquals(428, missing.status());
                HttpTestClient.Response first =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        "/api/me/recovery-requests",
                                        session.cookie(),
                                        true,
                                        body,
                                        Map.of("Idempotency-Key", key));
                HttpTestClient.Response second =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        "/api/me/recovery-requests",
                                        session.cookie(),
                                        true,
                                        body,
                                        Map.of("Idempotency-Key", key));
                assertEquals(202, first.status(), first.body());
                assertEquals(first.body(), second.body());
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recovery_request WHERE requester_user_id = ?",
                                Integer.class,
                                actor));
                HttpTestClient.Response conflict =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        "/api/me/recovery-requests",
                                        session.cookie(),
                                        true,
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "targetUserId",
                                                        UUID.randomUUID(),
                                                        "reason",
                                                        "Quên mật khẩu")),
                                        Map.of("Idempotency-Key", key));
                assertEquals(409, conflict.status());
                var changedReason =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        "/api/me/recovery-requests",
                                        session.cookie(),
                                        true,
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "targetUserId",
                                                        actor,
                                                        "reason",
                                                        "Lý do khác")),
                                        Map.of("Idempotency-Key", key));
                assertEquals(409, changedReason.status());

                var service = webContext.getBean(RecoveryService.class);
                var start = new java.util.concurrent.CountDownLatch(1);
                String concurrentKey = "concurrent-" + UUID.randomUUID();
                try (var threads =
                        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var one =
                            threads.submit(() -> {
                                start.await();
                                return service.submitOnce(
                                        actor,
                                        actor,
                                        null,
                                        "Khôi phục đăng nhập",
                                        concurrentKey,
                                        "a".repeat(64));
                            });
                    var two =
                            threads.submit(() -> {
                                start.await();
                                return service.submitOnce(
                                        actor,
                                        actor,
                                        null,
                                        "Khôi phục đăng nhập",
                                        concurrentKey,
                                        "a".repeat(64));
                            });
                    start.countDown();
                    assertEquals(
                            one.get(20, java.util.concurrent.TimeUnit.SECONDS).id(),
                            two.get(20, java.util.concurrent.TimeUnit.SECONDS).id());
                }
                assertEquals(
                        2L,
                        service.listRequests().stream()
                                .filter(request -> request.requesterUserId().equals(actor))
                                .count());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void operatorApprovalKeepsRecoveryPendingUntilConfirmedDelivery() throws Exception {
        String username = "regression-operator-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        String targetSubject =
                createUser(
                        keycloakUser(
                                "regression-target-" + UUID.randomUUID(),
                                null,
                                false,
                                "Password1-" + UUID.randomUUID()));
        var directory =
                webContext.getBean(
                        com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient.class);
        try {
            seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            UUID target = seedAccount(targetSubject, "CENTER_ISSUED", null, false);
            var submitted = webContext.getBean(RecoveryService.class).submit(target, target, null);
            directory.setRealmGroupMembership(
                    subject, NativePolicyTemplates.PLATFORM_OPERATORS_GROUP, true);
            try (var session = openSession(username, password, subject)) {
                String path = "/api/platform/recovery-requests/" + submitted.id() + "/resolve";
                String key = "approve-" + UUID.randomUUID();
                var early =
                        realHttp()
                                .whenReady(() ->
                                        realHttp()
                                                .exchange(
                                                        HttpMethod.POST,
                                                        path,
                                                        session.cookie(),
                                                        true,
                                                        "{\"decision\":\"COMPLETE\",\"verificationNote\":\"Đã bàn giao\"}",
                                                        Map.of(
                                                                "Idempotency-Key",
                                                                "early-" + UUID.randomUUID(),
                                                                "If-Match",
                                                                "\"0\"")));
                assertEquals(409, early.status());

                String approval =
                        "{\"decision\":\"APPROVE\",\"verificationNote\":\"Đã xác minh đúng chủ tài khoản\"}";
                var missing =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        path,
                                        session.cookie(),
                                        true,
                                        approval,
                                        Map.of("Idempotency-Key", key));
                assertEquals(428, missing.status());
                var approved =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        path,
                                        session.cookie(),
                                        true,
                                        approval,
                                        Map.of("Idempotency-Key", key, "If-Match", "\"0\""));
                assertEquals(202, approved.status(), approved.body());
                assertFalse(JSON.readTree(approved.body()).get("completed").booleanValue());
                assertEquals("VERIFIED", recoveryRequestState(session, submitted.id()));
                var replay =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        path,
                                        session.cookie(),
                                        true,
                                        approval,
                                        Map.of("Idempotency-Key", key, "If-Match", "\"0\""));
                assertEquals(approved.body(), replay.body());
                var poll = realHttp().get("/api/me/operations/" + submitted.id(), session.cookie());
                assertEquals(200, poll.status());
                assertEquals("PENDING", JSON.readTree(poll.body()).get("state").stringValue());
                String completion =
                        "{\"decision\":\"COMPLETE\",\"verificationNote\":\"Đã khôi phục và bàn giao qua kênh riêng\"}";
                var stale =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        path,
                                        session.cookie(),
                                        true,
                                        completion,
                                        Map.of(
                                                "Idempotency-Key",
                                                "stale-" + UUID.randomUUID(),
                                                "If-Match",
                                                "\"0\""));
                assertEquals(409, stale.status());
                // Mô phỏng công việc operator thực hiện ở console, không gửi credential qua app.
                HTTP.put()
                        .uri(admin("/users/" + targetSubject + "/reset-password"))
                        .headers(h -> h.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "type",
                                        "password",
                                        "value",
                                        "Recovered1-" + UUID.randomUUID(),
                                        "temporary",
                                        true))
                        .retrieve()
                        .toBodilessEntity();
                var completed =
                        realHttp()
                                .exchange(
                                        HttpMethod.POST,
                                        path,
                                        session.cookie(),
                                        true,
                                        completion,
                                        Map.of(
                                                "Idempotency-Key",
                                                "complete-" + UUID.randomUUID(),
                                                "If-Match",
                                                "\"1\""));
                assertEquals(200, completed.status(), completed.body());
                assertTrue(JSON.readTree(completed.body()).get("completed").booleanValue());
                assertEquals("RESOLVED", recoveryRequestState(session, submitted.id()));
            }
        } finally {
            deleteUser(subject);
            deleteUser(targetSubject);
        }
    }

    private void awaitIdentity(UUID actor) {
        for (UUID operation :
                jdbc.queryForList(
                        "SELECT id FROM authorization_operation WHERE initiator_user_id = ?",
                        UUID.class,
                        actor)) {
            awaitSucceeded(operation);
        }
    }

    // ---------- Dữ kiện thư mục ----------

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
                    "flow-user-" + actor,
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
                    "flow-org-" + center,
                    "Flow test center",
                    ownerUserId);
            // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER của chủ sở hữu.
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

    private void revokeMembership(UUID workspaceId, UUID userId) {
        tx(() -> {
            jdbc.update(
                    "UPDATE center_membership SET state = 'REVOKED', revoked_at = now()"
                            + " WHERE workspace_id = ? AND user_id = ? AND state = 'ACTIVE'",
                    workspaceId,
                    userId);
            return null;
        });
    }

    private String recoveryRequestState(RealSession session, UUID requestId) throws Exception {
        HttpTestClient.Response response =
                realHttp()
                        .whenReady(() ->
                                realHttp()
                                        .get("/api/platform/recovery-requests", session.cookie()));
        assertEquals(200, response.status(), response.body());
        for (JsonNode item : JSON.readTree(response.body())) {
            if (requestId.toString().equals(item.get("id").stringValue())) {
                return item.get("state").stringValue();
            }
        }
        throw new AssertionError(
                "Không thấy yêu cầu khôi phục " + requestId + " trong danh sách của operator");
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

    private void awaitSucceeded(UUID operationId) {
        awaitOperationSucceeded(jdbc, operationId);
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> keycloakUser(
            String username, @Nullable String email, boolean emailVerified) {
        return keycloakUser(username, email, emailVerified, "flow-password-" + UUID.randomUUID());
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

    private void setEmailVerified(String userId, boolean verified) {
        Map<String, Object> user = new HashMap<>(getUser(userId));
        user.put("emailVerified", verified);
        putUser(userId, user);
    }

    private void updateProfile(String userId, String email, boolean emailVerified) {
        Map<String, Object> user = new HashMap<>(getUser(userId));
        user.put("email", email);
        user.put("emailVerified", emailVerified);
        putUser(userId, user);
    }

    private void putUser(String userId, Map<String, Object> user) {
        HTTP.put()
                .uri(admin("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    private List<String> groupPathsOf(String userId) {
        List<Map<String, Object>> groups =
                HTTP.get()
                        .uri(admin("/users/" + userId + "/groups"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(LIST);
        if (groups == null) {
            throw new IllegalStateException("Keycloak groups not readable: " + userId);
        }
        return groups.stream().map(group -> String.valueOf(group.get("path"))).sorted().toList();
    }

    private static void seedEligibilityGroup() {
        RealmFoundation.ensureEligibilityTree(
                HTTP, admin(""), IdentityFlowE2EIT::masterTokenStatic);
    }

    private static void ensureApiClient() {
        RealmFoundation.ensureApiClient(
                HTTP,
                admin(""),
                IdentityFlowE2EIT::masterTokenStatic,
                fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
    }
}
