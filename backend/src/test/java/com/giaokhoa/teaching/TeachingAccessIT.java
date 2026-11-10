package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.tenancy.CenterService;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Hồi quy eligibility và thứ tự kiểm tra role/khóa workspace với phiên Keycloak thật. */
@SpringBootTest
@Tag("e2e-backend")
class TeachingAccessIT extends TeachingFixture {
    @Autowired private CenterService centers;
    @Autowired private ResourceMutationCoordinator coordinator;

    @DisplayName("Actor chưa xác minh không đọc hay tạo được dữ liệu teaching bằng session cũ")
    @Test
    void unverifiedActorDeniedWithOldSession() throws Exception {
        KeycloakLogin login = createVerifiedUser("review-eligibility");
        UUID actor = seedAccount(login.subject(), "SELF_REGISTERED", login.email(), true);
        UUID workspace = seedCenter(actor);
        seedSubscription(workspace, -1, 30, actor);
        try (var session = openSession(login)) {
            HTTP.put()
                    .uri(admin("/users/" + login.subject()))
                    .headers(headers -> headers.setBearerAuth(masterToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("emailVerified", false))
                    .retrieve()
                    .toBodilessEntity();
            // Qua fence trước: còn 503 AUTHORIZATION_NOT_READY nghĩa là eligibility đang
            // đồng bộ lại sau khi email bị gỡ xác minh; trạng thái cuối phải là PDP từ chối
            // dứt khoát 403 cho cả đọc lẫn ghi, không lẫn hai khả năng như khẳng định cũ.
            var read =
                    requestWhenReady(
                            mockMvc(),
                            get("/api/workspaces/" + workspace + "/classes")
                                    .cookie(session.cookie()));
            var write =
                    requestWhenReady(
                            mockMvc(),
                            post("/api/workspaces/" + workspace + "/classes")
                                    .cookie(session.cookie())
                                    .with(csrf())
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"Forbidden\"}"));
            assertEquals(403, read.getStatus());
            assertEquals(403, write.getStatus());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM school_class WHERE workspace_id = ?",
                            Integer.class,
                            workspace),
                    "Request bị chặn không được ghi lớp trước khi trả lỗi.");
        } finally {
            deleteUser(login.subject());
        }
    }

    @DisplayName("Owner do center cấp phát, không có email, vẫn đọc được dữ liệu teaching")
    @Test
    void emailLessCenterOwnerReadsTeaching() throws Exception {
        KeycloakLogin login = createVerifiedUser("issued-owner");
        UUID actor = seedAccount(login.subject(), "CENTER_ISSUED", login.email(), true);
        UUID workspace = seedCenter(actor);
        try (var session = openSession(login)) {
            Map<String, Object> profile = new HashMap<>();
            profile.put("email", null);
            profile.put("emailVerified", false);
            HTTP.put()
                    .uri(admin("/users/" + login.subject()))
                    .headers(headers -> headers.setBearerAuth(masterToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(profile)
                    .retrieve()
                    .toBodilessEntity();
            var response =
                    requestWhenReady(
                            mockMvc(),
                            get("/api/workspaces/" + workspace + "/classes")
                                    .cookie(session.cookie()));
            assertEquals(
                    200,
                    response.getStatus(),
                    "Danh tính được cấp không email vẫn dùng nghiệp vụ theo role.");
            assertEquals(
                    false,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor),
                    "Phải dùng profile hiện hành thay vì claim verified của phiên cũ.");
        } finally {
            deleteUser(login.subject());
        }
    }

    @DisplayName("Manager bị revoke trong lúc chờ workspace lock không tạo được lớp")
    @Test
    void revokedManagerCannotCreateClass() throws Exception {
        KeycloakLogin login = createVerifiedUser("review-manager-race");
        UUID owner = seedAccount("owner-" + UUID.randomUUID(), "CENTER_ISSUED", null, false);
        UUID manager = seedAccount(login.subject(), "SELF_REGISTERED", login.email(), true);
        UUID workspace = seedCenter(owner);
        UUID membership = addMembership(workspace, manager, "MANAGER");
        seedSubscription(workspace, -1, 30, owner);
        try (var session = openSession(login);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MockHttpServletResponse> pending =
                    tx(() -> {
                        coordinator.lockWorkspace(workspace);
                        int blocker =
                                Objects.requireNonNull(
                                        jdbc.queryForObject(
                                                "SELECT pg_backend_pid()", Integer.class));
                        Future<MockHttpServletResponse> request =
                                pool.submit(() ->
                                        mockMvc()
                                                .perform(
                                                        post("/api/workspaces/"
                                                                        + workspace
                                                                        + "/classes")
                                                                .cookie(session.cookie())
                                                                .with(csrf())
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        UUID.randomUUID()
                                                                                .toString())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        "{\"name\":\"After revoke\"}"))
                                                .andReturn()
                                                .getResponse());
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(15))
                                .pollInterval(Duration.ofMillis(50))
                                .until(() ->
                                        Objects.requireNonNull(
                                                        jdbc.queryForObject(
                                                                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                                                Integer.class,
                                                                blocker))
                                                > 0);
                        centers.replaceRoles(
                                owner,
                                workspace,
                                membership,
                                Set.of(MembershipRole.Role.TEACHER),
                                "\"0\"",
                                UUID.randomUUID().toString(),
                                "b".repeat(64));
                        return request;
                    });
            MockHttpServletResponse response = pending.get(20, TimeUnit.SECONDS);
            assertEquals(
                    403,
                    response.getStatus(),
                    "Role must be checked after the writer acquires the shared workspace lock");
        } finally {
            deleteUser(login.subject());
        }
    }
}
