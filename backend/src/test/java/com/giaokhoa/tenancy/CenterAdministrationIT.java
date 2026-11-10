package com.giaokhoa.tenancy;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.giaokhoa.support.KeycloakSessionFixture;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Contract US2 ở tầng quản trị trung tâm: operator tạo tenant và xác nhận gói thủ công, owner bổ
 * nhiệm manager, manager mời thành viên và đổi role theo scope riêng, gói hết hạn chặn thêm quyền
 * nhưng không chặn thu hồi, giáo viên không tạo được lớp center, và người quản trị không tự đọc
 * được nội dung riêng của người khác.
 *
 * <p>Đây là task test-first: các endpoint US2 chưa được triển khai nên test khẳng định đúng hợp
 * đồng HTTP (202 operation chưa hoàn tất, 403 khi thiếu quyền, 400 khi kỳ gói sai) và hiện đỏ với
 * 404 vì route chưa tồn tại — đỏ đó là có ý nghĩa, không phải lỗi hạ tầng. Các test dùng endpoint
 * đã có (workspaces, operations, capabilities) để khóa hành vi nền thì xanh ngay.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class CenterAdministrationIT extends KeycloakSessionFixture {
    private static final String OPERATOR_GROUP = "/gk/platform/operators";
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static @Nullable String operatorGroupId;

    @BeforeAll
    static void seedOperatorGroup() {
        operatorGroupId = ensureGroup(OPERATOR_GROUP);
        ensureApiClient();
    }

    @Test
    void operatorDeepLinkSynchronizesEligibilityWithoutWorkspaceRequest() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            seedAccount(operator.subject(), "SELF_REGISTERED", operator.email(), true);
            try (var session = openSession(operator)) {
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(60))
                        .until(() ->
                                Objects.requireNonNull(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM authz_projection WHERE target_key = 'platform' AND state = 'READY'",
                                                        Integer.class))
                                        == 1);
                assertEquals(
                        200,
                        mockMvc()
                                .perform(get("/api/session").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus());
                int before =
                        mockMvc()
                                .perform(get("/api/platform/centers").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                assertTrue(
                        before == 200 || before == 503,
                        "Operator hợp lệ chỉ có thể chờ đồng bộ, không nhận 403");
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/platform/centers").cookie(session.cookie()))
                                .getStatus());
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void operatorCreateCenterReturnsPendingOperation() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);

            try (var session = openSession(operator)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
                // của operator, điều kiện để PDP cho qua scope platform.
                getWorkspacesWhenReady(session);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/centers")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-create-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"name\":\"Trung tâm mới\",\"ownerUserId\":\""
                                                                + owner
                                                                + "\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
                assertFalse(
                        body.get("statusUrl").stringValue().isBlank(),
                        "Operation chưa xong phải có đường poll trạng thái");
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);

                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM center WHERE owner_user_id = ?",
                                Integer.class,
                                owner),
                        "Center chỉ tồn tại sau khi operation SUCCEEDED");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ordinaryUserCannotCreateTenant() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-plain");
        try {
            seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);

            try (var session = openSession(user)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/centers")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-plain-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"name\":\"Trung tâm tự tạo\",\"ownerUserId\":\""
                                                                + UUID.randomUUID()
                                                                + "\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Người thường không được tự tạo tenant");
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM center WHERE name = 'Trung tâm tự tạo'",
                                Integer.class),
                        "Lời gọi bị từ chối không được để lại tenant");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void ordinaryUserCannotListPlatformCenters() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-list-plain");
        try {
            seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);

            try (var session = openSession(user)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(get("/api/platform/centers").cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Danh sách tenant chỉ dành cho operator");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void operatorListsPlatformCenters() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            seedAccount(operator.subject(), "SELF_REGISTERED", null, false);

            try (var session = openSession(operator)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
                // của operator, điều kiện để PDP cho qua scope platform.
                getWorkspacesWhenReady(session);
                MockHttpServletResponse response =
                        requestWhenReady(
                                mockMvc(), get("/api/platform/centers").cookie(session.cookie()));

                assertEquals(200, response.getStatus(), response.getContentAsString());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertTrue(body.get("items").isArray(), "Danh sách tenant có trang items");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ordinaryUserCannotConfirmSubscription() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-sub-plain");
        try {
            UUID actor = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
            UUID center = seedCenter(actor);

            try (var session = openSession(user)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 0, 365)))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Người thường không tự kích hoạt gói");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void operatorConfirmsSubscriptionWithValidPeriod() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
            UUID center = seedCenter(owner);

            try (var session = openSession(operator)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
                // của operator, điều kiện để PDP cho qua scope platform.
                getWorkspacesWhenReady(session);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-op-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 0, 365)))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);

                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                                Integer.class,
                                center),
                        "Kỳ gói chỉ được ghi sau khi operation SUCCEEDED");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void subscriptionWithEndBeforeStartIsRejected() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
            UUID center = seedCenter(owner);

            try (var session = openSession(operator)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
                // của operator, điều kiện để PDP cho qua scope platform.
                getWorkspacesWhenReady(session);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-bad-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 30, 0)))
                                .andReturn()
                                .getResponse();

                assertEquals(400, response.getStatus(), "Kỳ gói phải có mốc kết thúc sau bắt đầu");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ownerAppointsManagerInOwnCenterOnly() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner");
        KeycloakLogin memberLogin = createVerifiedUser("center-member");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            UUID membershipA = addMembership(centerA, member, "STUDENT");
            addMembership(centerB, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                long version = membershipVersion(session, centerA, membershipA);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + centerA
                                                        + "/memberships/"
                                                        + membershipA
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of("MANAGER"),
                    activeRoles(centerA, member),
                    "Bổ nhiệm manager chỉ đổi quyền trong đúng center");
            assertEquals(
                    Set.of("STUDENT"),
                    activeRoles(centerB, member),
                    "Quyền ở center còn lại không bị kéo theo");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void managerCannotAppointAnotherManager() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-two");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(managerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-mgr-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Manager không được bổ nhiệm manager khác");
                assertEquals(
                        Set.of("STUDENT"),
                        activeRoles(center, member),
                        "Lời gọi bị từ chối không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void managerCannotPromoteSelfToOwner() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-three");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, manager, "MANAGER");

            try (var session = openSession(managerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-self-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"OWNER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Manager không tự trở thành owner");
                assertEquals(
                        Set.of("MANAGER"),
                        activeRoles(center, manager),
                        "Lời gọi bị từ chối không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
        }
    }

    @Test
    void teacherCannotCreateCenterClassEvenWithPersonalPackage() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-four");
        KeycloakLogin teacherLogin = createVerifiedUser("center-teacher");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacher =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, teacher, "TEACHER");
            seedSubscription(personalWorkspaceOf(teacher), -1, 365, owner);

            try (var session = openSession(teacherLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/classes")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-class-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"name\":\"Lớp của giáo viên\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        403,
                        response.getStatus(),
                        "Giáo viên không tạo được lớp center dù có gói cá nhân");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    @Test
    void roleChangeBatchReturnsPendingOperation() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-five");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-three");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-batch-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(
                        body.get("completed").booleanValue(),
                        "Đổi role theo batch chưa hoàn tất thì không được báo xong sớm");
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of("TEACHER"),
                    activeRoles(center, member),
                    "Batch vừa gỡ role cũ vừa thêm role mới phải áp đủ cả hai phần");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void cannotRemoveLastOwner() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-last");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID ownerMembership =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id FROM center_membership"
                                            + " WHERE workspace_id = ? AND user_id = ?",
                                    UUID.class,
                                    center,
                                    owner),
                            "membership của owner");
            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        delete(
                                                        "/api/workspaces/"
                                                                + center
                                                                + "/memberships/"
                                                                + ownerMembership)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-last-owner-" + UUID.randomUUID()))
                                .andReturn()
                                .getResponse();

                // Từ chối vì vi phạm quy tắc nghiệp vụ (giống invite OWNER → 400),
                // không phải xung đột version hay danh tính trùng (những trường hợp đó mới 409).
                assertEquals(400, response.getStatus(), "Không được gỡ owner cuối cùng của center");
                assertEquals(
                        Set.of("OWNER"),
                        activeRoles(center, owner),
                        "Owner cuối cùng vẫn còn quyền sau lời gọi bị từ chối");
            }
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void roleChangeWithoutIfMatchIsPreconditionRequired() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-no-ifmatch");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-no-ifmatch");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-no-ifmatch-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        428, response.getStatus(), "Đổi role thiếu If-Match phải bị từ chối 428");
                assertEquals(
                        Set.of("STUDENT"),
                        activeRoles(center, member),
                        "Lời gọi thiếu precondition không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void roleChangeWithStaleVersionIsConflict() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-stale");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-stale");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse first =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-stale-first-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, first.getStatus());
                UUID operationId =
                        UUID.fromString(
                                JSON.readTree(first.getContentAsString())
                                        .get("operationId")
                                        .stringValue());
                awaitOperationSucceeded(session, operationId);

                // Đổi role lần đầu đã cập nhật row membership nên version tăng; version cũ không
                // còn khớp.
                MockHttpServletResponse stale =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-stale-second-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(409, stale.getStatus(), "Đổi role với version cũ phải bị từ chối 409");
                assertEquals(
                        Set.of("TEACHER"),
                        activeRoles(center, member),
                        "Lời gọi lỗi version không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void expiredSubscriptionBlocksInviteButNotRevoke() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-six");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager-three");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-four");
        KeycloakLogin inviteeLogin = createVerifiedUser("center-invitee-four");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            UUID membership = addMembership(center, member, "STUDENT");
            seedSubscription(center, -60, -1, owner);

            try (var session = openSession(managerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse invite =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-exp-invite-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, invite.getStatus(), "Gói hết hạn chặn mời thành viên mới");

                MockHttpServletResponse revoke =
                        mockMvc()
                                .perform(
                                        delete(
                                                        "/api/workspaces/"
                                                                + center
                                                                + "/memberships/"
                                                                + membership)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-exp-revoke-" + UUID.randomUUID()))
                                .andReturn()
                                .getResponse();

                assertEquals(202, revoke.getStatus(), "Thu hồi vẫn được phép khi gói hết hạn");
                JsonNode body = JSON.readTree(revoke.getContentAsString());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of(),
                    activeRoles(center, member),
                    "Thành viên đã bị thu hồi dù gói hết hạn");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(memberLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void operationStatusHiddenFromOtherUser() throws Exception {
        KeycloakLogin requesterLogin = createVerifiedUser("center-requester");
        KeycloakLogin otherLogin = createVerifiedUser("center-other");
        try {
            UUID requester =
                    seedAccount(
                            requesterLogin.subject(),
                            "SELF_REGISTERED",
                            requesterLogin.email(),
                            true);
            seedAccount(otherLogin.subject(), "SELF_REGISTERED", otherLogin.email(), true);

            UUID requestId;
            try (var session = openSession(requesterLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse created =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-op-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"targetUserId\":\""
                                                                + requester
                                                                + "\",\"reason\":\"Quên mật khẩu\"}"))
                                .andReturn()
                                .getResponse();
                assertEquals(202, created.getStatus());
                requestId =
                        UUID.fromString(
                                JSON.readTree(created.getContentAsString())
                                        .get("operationId")
                                        .stringValue());
            }

            try (var session = openSession(otherLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        get("/api/me/operations/" + requestId)
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        404,
                        response.getStatus(),
                        "Trạng thái operation của người khác bị giấu thành không tồn tại");
            }
        } finally {
            deleteUser(requesterLogin.subject());
            deleteUser(otherLogin.subject());
        }
    }

    @Test
    void workspacesEndpointDoesNotLeakOtherCenters() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-seven");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-five");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, member, "STUDENT");

            try (var session = openSession(memberLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response = getWorkspacesWhenReady(session);

                assertEquals(200, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertTrue(body.isArray());
                List<String> ids = new ArrayList<>();
                body.forEach(item -> ids.add(item.get("id").stringValue()));
                assertTrue(
                        ids.contains(centerA.toString()), "Thành viên thấy center mình tham gia");
                assertFalse(
                        ids.contains(centerB.toString()), "Không lộ center mình không tham gia");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void capabilitiesForAnotherProfileIsRejected() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-eight");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-six");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/capabilities")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"targets\":[{\"type\":\"PROFILE\",\"id\":\""
                                                                + member
                                                                + "\",\"scopes\":[\"profile.read\"]}]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        400,
                        response.getStatus(),
                        "Capabilities chỉ xét target của chính actor, không xét profile người khác");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void mutationWithoutCsrfIsRejected() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-ten");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                assertEquals(200, getWorkspacesWhenReady(session).getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-nocsrf-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + owner
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Mutation trình duyệt thiếu CSRF bị chặn");
            }
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void anonymousMembershipListIsRedirectedToLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(get("/api/workspaces/" + UUID.randomUUID() + "/memberships"))
                        .andReturn()
                        .getResponse();

        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"),
                "Chưa đăng nhập thì được hướng dẫn đăng nhập, không nhận dữ liệu nghiệp vụ");
    }

    // ---------- Dữ kiện thư mục ----------

    private KeycloakLogin createOperator() {
        KeycloakLogin login = createVerifiedUser("center-operator");
        joinGroup(login.subject(), Objects.requireNonNull(operatorGroupId, "operatorGroupId"));
        return login;
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
                    "center-user-" + actor,
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

    // Row version của membership để gửi If-Match khi đổi role; hợp đồng bắt buộc
    // precondition này, thiếu thì server trả 428.
    private long membershipVersion(RealSession session, UUID workspaceId, UUID membershipId)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                get("/api/workspaces/" + workspaceId + "/memberships")
                                        .cookie(session.cookie()))
                        .andReturn()
                        .getResponse();
        assertEquals(200, response.getStatus(), "Đọc danh sách thành viên để lấy row version");
        JsonNode found = null;
        for (JsonNode item : JSON.readTree(response.getContentAsString()).get("items")) {
            if (membershipId.toString().equals(item.get("id").stringValue())) {
                found = item;
                break;
            }
        }
        return Objects.requireNonNull(
                        found, "Không thấy membership trong danh sách: " + membershipId)
                .get("version")
                .longValue();
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
                    "center-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    private String subscriptionJson(UUID workspaceId, int validFromDaysAgo, int expiresInDays) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(validFromDaysAgo);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays);
        return "{\"workspaceId\":\""
                + workspaceId
                + "\",\"validFrom\":\""
                + validFrom
                + "\",\"expiresAt\":\""
                + expiresAt
                + "\",\"confirmationRef\":\"center-confirm-"
                + UUID.randomUUID()
                + "\"}";
    }

    private void awaitOperationSucceeded(RealSession session, UUID operationId) throws Exception {
        AtomicReference<MockHttpServletResponse> last = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        try {
            await().atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        MockHttpServletResponse response =
                                mockMvc()
                                        .perform(
                                                get("/api/me/operations/" + operationId)
                                                        .cookie(session.cookie()))
                                        .andReturn()
                                        .getResponse();
                        last.set(response);
                        boolean readable = response.getStatus() == 200;
                        if (!readable) {
                            return true;
                        }
                        JsonNode body = JSON.readTree(response.getContentAsString());
                        boolean done = body.get("completed").booleanValue();
                        if (done) {
                            completed.set(true);
                        }
                        return done;
                    });
        } catch (ConditionTimeoutException ignored) {
            // Hết 90 giây: các khẳng định phía dưới báo đúng trạng thái như vòng poll cũ.
        }
        MockHttpServletResponse response = Objects.requireNonNull(last.get());
        assertEquals(200, response.getStatus(), "Đường poll operation phải đọc được");
        if (!completed.get()) {
            fail("Operation did not succeed in time: " + operationId);
        }
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals(
                "SUCCEEDED",
                body.get("state").stringValue(),
                "Operation hoàn tất nhưng không thành công");
    }

    @Override
    protected RealSession openSession(KeycloakLogin login) throws Exception {
        // Không nạp workspace trước như mẫu chung: ca
        // operatorDeepLinkSynchronizesEligibilityWithoutWorkspaceRequest khóa hành vi đồng bộ
        // eligibility không qua lời gọi workspace, nên phiên ở đây chỉ mở, test tự gọi khi cần.
        return openSession(login.username(), login.password(), login.subject());
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

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
                // Cây /gk là object nền của realm (test-realm.json đã khai): chỉ tạo khi thiếu,
                // không bao giờ xóa ở teardown — nhiều class dùng chung đường dẫn cố định này.
                found = createResource(admin(endpoint), Map.of("name", segment));
            }
            parentId = found;
        }
        return Objects.requireNonNull(parentId, "operator group");
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

    // Token master được giữ trong bộ nhớ tới gần hết hạn thay vì xin mới cho mỗi lần gọi
    // Admin API: mỗi lần xin token là một lượt Keycloak xác thực mật khẩu của tài khoản master.
    // Hiệu lực chỉ căn theo masterTokenValidUntil: hạ hạn về EPOCH là token cũ hết được dùng.
    private static String cachedMasterToken = "";
    private static Instant masterTokenValidUntil = Instant.EPOCH;

    private static String cachedMasterToken() {
        if (Instant.now().isBefore(masterTokenValidUntil)) {
            return cachedMasterToken;
        }
        return fetchMasterTokenStatic();
    }

    private static synchronized String fetchMasterTokenStatic() {
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
        // Chừa biên 10 giây để không cầm token sắp hết hạn vào một lời gọi Admin API.
        masterTokenValidUntil = Instant.now().plusSeconds(Math.max(5, expiresIn - 10));
        return token;
    }

    private static synchronized void invalidateMasterToken() {
        masterTokenValidUntil = Instant.EPOCH;
    }

    /**
     * Chạy một lời gọi Admin API bằng token đã cache; gặp 401 (token bị thu hồi phía Keycloak dù
     * hạn theo đồng hồ local chưa tới) thì bỏ cache, xin token mới và thử lại đúng một lần.
     */
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
}
