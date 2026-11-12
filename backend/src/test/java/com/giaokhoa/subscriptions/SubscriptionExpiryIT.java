package com.giaokhoa.subscriptions;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.KeycloakSessionFixture;
import com.giaokhoa.support.RealmFoundation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * US8 ở tầng kỳ gói: hai loại gói hết hạn độc lập, đọc còn quyền vẫn mở sau hết hạn, mọi thao tác
 * tăng quyền bị cổng gói chặn, thu hồi vẫn đi được sau hết hạn và gia hạn không phục hồi nguồn đã
 * thu hồi (spec US8.1, US8.3, US8.5, US8.6, US8.7).
 *
 * <p>Mọi kỳ gói đều được ghi qua đúng đường production (operator xác nhận kỳ qua API nền tảng, chờ
 * operation SUCCEEDED) — không seed thẳng dòng kỳ gói, không mock đồng hồ: mốc hết hạn được quan
 * sát bằng đồng hồ thật của chính Keycloak và ứng dụng, với cùng một phiên đăng nhập trước và sau
 * mốc. Không có cron nào tham gia: operation xác nhận ghi gate hiệu lực một lần lúc xác nhận, và
 * quyết định sau mốc tự lật nhờ time policy native, kể cả khi không có thao tác ghi nào khác xen
 * vào.
 */
@SpringBootTest
class SubscriptionExpiryIT extends KeycloakSessionFixture {
    private static final String OPERATOR_GROUP = "/gk/platform/operators";
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_READY = 503;
    private static @Nullable String operatorGroupId;

    @BeforeAll
    static void seedRealmFoundation() {
        operatorGroupId = ensureGroup(OPERATOR_GROUP);
        RealmFoundation.ensureApiClient(
                HTTP,
                BASE + "/admin/realms/" + REALM,
                KeycloakSessionFixture::masterTokenStatic,
                fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
    }

    @Nested
    @DisplayName("Gói cá nhân hết hạn")
    class PersonalExpiry {

        @DisplayName(
                "Gói cá nhân đã hết hạn: vẫn đọc được lớp của mình, tạo lớp và tạo học liệu bị từ"
                        + " chối đích danh vì gói")
        @Test
        void expiredKeepsReadsLosesCreates() throws Exception {
            KeycloakLogin user = createVerifiedUser("expiry-personal-expired");
            KeycloakLogin operator = createOperator();
            try {
                UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID personal = personalWorkspaceOf(userId);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(
                            operatorSession,
                            personal,
                            Instant.now().minus(2, ChronoUnit.DAYS),
                            Instant.now().minus(1, ChronoUnit.DAYS));
                }

                try (var session = openSession(user)) {
                    MockHttpServletResponse classes =
                            getReady(session, "/api/workspaces/" + personal + "/classes");
                    assertEquals(
                            200,
                            classes.getStatus(),
                            "Gói hết hạn vẫn đọc được danh sách lớp cá nhân, body: "
                                    + classes.getContentAsString());

                    MockHttpServletResponse createClass =
                            postRaw(
                                    session,
                                    "/api/workspaces/" + personal + "/classes",
                                    "{\"name\":\"Lớp sau hết hạn\"}");
                    assertEquals(
                            403,
                            createClass.getStatus(),
                            "Gói cá nhân hết hạn thì tạo lớp phải bị từ chối, body: "
                                    + createClass.getContentAsString());

                    MockHttpServletResponse createResource =
                            sampleAction(session, personal, createResourceJson());
                    assertEquals(
                            403,
                            createResource.getStatus(),
                            "Gói cá nhân hết hạn thì tạo học liệu phải bị từ chối, body: "
                                    + createResource.getContentAsString());
                    assertEquals(
                            "SUBSCRIPTION_INACTIVE",
                            JSON.readTree(createResource.getContentAsString())
                                    .get("code")
                                    .stringValue(),
                            "Lý do từ chối phải đích danh là gói không còn hiệu lực");
                }
            } finally {
                deleteUser(user.subject());
                deleteUser(operator.subject());
            }
        }

        @DisplayName(
                "Cổng gói cá nhân tự lật đúng mốc hết hạn theo đồng hồ thật: trước mốc tạo lớp"
                        + " được, từ mốc trở đi bị từ chối, cùng phiên và không cần thao tác ghi"
                        + " nào khác")
        @Test
        void gateFlipsAtExpiryBoundary() throws Exception {
            KeycloakLogin user = createVerifiedUser("expiry-personal-boundary");
            KeycloakLogin operator = createOperator();
            try {
                UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID personal = personalWorkspaceOf(userId);
                // SubscriptionService cắt mốc về tròn giây; biên hiệu lực thật là giá trị đã cắt.
                Instant until = Instant.now().plusSeconds(45).truncatedTo(ChronoUnit.SECONDS);
                Instant from = until.minusSeconds(120);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, personal, from, until);
                }

                String classesUri = "/api/workspaces/" + personal + "/classes";
                boolean sawAllow = false;
                Instant firstDeny = null;
                Instant hardDeadline = until.plusSeconds(30);
                int probe = 0;
                try (var session = openSession(user)) {
                    while (Instant.now().isBefore(hardDeadline)) {
                        Instant start = Instant.now();
                        String probeName = "Lớp biên " + probe;
                        probe++;
                        MockHttpServletResponse response =
                                postRaw(session, classesUri, "{\"name\":\"" + probeName + "\"}");
                        String body = response.getContentAsString();
                        int status = response.getStatus();
                        if (status == HTTP_NOT_READY && body.contains("AUTHORIZATION_NOT_READY")) {
                            // Fence của operation tạo lớp trước đang áp: chưa phải quyết định.
                            Thread.sleep(400);
                            continue;
                        }
                        if (status == HTTP_ACCEPTED) {
                            assertTrue(
                                    start.isBefore(until),
                                    "Lần tạo lớp được phép phải bắt đầu trước mốc hết hạn, lúc: "
                                            + start);
                            sawAllow = true;
                        } else if (status == HTTP_FORBIDDEN) {
                            firstDeny = start;
                            break;
                        } else {
                            fail("Trạng thái bất ngờ khi thăm dò biên gói: " + status + " " + body);
                        }
                        Thread.sleep(1000);
                    }
                    assertTrue(
                            sawAllow,
                            "Phải có ít nhất một lần tạo lớp được phép trước mốc hết hạn");
                    assertNotNull(
                            firstDeny,
                            "Cổng gói phải tự đóng sau mốc hết hạn dù không có thao tác ghi nào"
                                    + " khác");
                    assertFalse(
                            firstDeny.isBefore(until.minusSeconds(1)),
                            "Lần từ chối đầu tiên không được sớm hơn mốc hết hạn (dung sai 1 giây"
                                    + " do cắt tròn giây), firstDeny="
                                    + firstDeny
                                    + ", until="
                                    + until);
                    assertEquals(
                            200,
                            getReady(session, classesUri).getStatus(),
                            "Sau mốc hết hạn, đọc danh sách lớp vẫn được phép với cùng phiên");
                }
            } finally {
                deleteUser(user.subject());
                deleteUser(operator.subject());
            }
        }

        @DisplayName(
                "Gia hạn gói cá nhân mở lại thao tác tạo học liệu cho cùng phiên, không cần đăng"
                        + " nhập lại")
        @Test
        void renewalReopensCreates() throws Exception {
            KeycloakLogin user = createVerifiedUser("expiry-personal-renewal");
            KeycloakLogin operator = createOperator();
            try {
                UUID userId = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID personal = personalWorkspaceOf(userId);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(
                            operatorSession,
                            personal,
                            Instant.now().minus(2, ChronoUnit.DAYS),
                            Instant.now().minus(1, ChronoUnit.DAYS));
                }

                try (var session = openSession(user)) {
                    assertEquals(
                            403,
                            sampleAction(session, personal, createResourceJson()).getStatus(),
                            "Trước gia hạn, tạo học liệu bị từ chối vì gói đã hết hạn");
                    try (var operatorSession = openSession(operator)) {
                        confirmPeriod(
                                operatorSession,
                                personal,
                                Instant.now().minusSeconds(5),
                                Instant.now().plus(365, ChronoUnit.DAYS));
                    }
                    MockHttpServletResponse created =
                            sampleAction(session, personal, createResourceJson());
                    assertEquals(
                            201,
                            created.getStatus(),
                            "Sau gia hạn, cùng phiên tạo được học liệu ngay, body: "
                                    + created.getContentAsString());
                    assertEquals(
                            "SUCCEEDED",
                            JSON.readTree(created.getContentAsString()).get("state").stringValue());
                }
            } finally {
                deleteUser(user.subject());
                deleteUser(operator.subject());
            }
        }
    }

    @Nested
    @DisplayName("Gói trung tâm hết hạn")
    class CenterExpiry {

        @DisplayName(
                "Gói trung tâm đã hết hạn: chủ sở hữu vẫn xem được lớp và thành viên, tạo lớp và"
                        + " mời thành viên bị từ chối")
        @Test
        void expiredKeepsReadsLosesIncreases() throws Exception {
            KeycloakLogin owner = createVerifiedUser("expiry-center-owner");
            KeycloakLogin invitee = createVerifiedUser("expiry-center-invitee");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                seedAccount(invitee.subject(), "SELF_REGISTERED", invitee.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID center = seedCenter(ownerId);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(
                            operatorSession,
                            center,
                            Instant.now().minus(2, ChronoUnit.DAYS),
                            Instant.now().minus(1, ChronoUnit.DAYS));
                }

                try (var session = openSession(owner)) {
                    assertEquals(
                            200,
                            getReady(session, "/api/workspaces/" + center + "/classes").getStatus(),
                            "Gói trung tâm hết hạn vẫn xem được danh sách lớp theo quyền");
                    assertEquals(
                            200,
                            getReady(session, "/api/workspaces/" + center + "/memberships")
                                    .getStatus(),
                            "Gói trung tâm hết hạn vẫn xem được danh sách thành viên theo quyền");

                    MockHttpServletResponse createClass =
                            postRaw(
                                    session,
                                    "/api/workspaces/" + center + "/classes",
                                    "{\"name\":\"Lớp sau hết hạn\"}");
                    assertEquals(
                            403,
                            createClass.getStatus(),
                            "Gói trung tâm hết hạn thì tạo lớp phải bị từ chối, body: "
                                    + createClass.getContentAsString());

                    MockHttpServletResponse invite =
                            postRaw(
                                    session,
                                    "/api/workspaces/" + center + "/invitations",
                                    "{\"email\":\""
                                            + invitee.email()
                                            + "\",\"roles\":[\"TEACHER\"]}");
                    assertEquals(
                            403,
                            invite.getStatus(),
                            "Gói trung tâm hết hạn thì mời thêm thành viên phải bị từ chối, body: "
                                    + invite.getContentAsString());
                }
            } finally {
                deleteUser(owner.subject());
                deleteUser(invitee.subject());
                deleteUser(operator.subject());
            }
        }

        @DisplayName(
                "Gói trung tâm đã hết hạn: chủ sở hữu vẫn gỡ được thành viên và thu hồi có hiệu"
                        + " lực từ yêu cầu tiếp theo của người bị gỡ")
        @Test
        void revokeAfterExpiryStillWorks() throws Exception {
            KeycloakLogin owner = createVerifiedUser("expiry-center-revoke-owner");
            KeycloakLogin teacher = createVerifiedUser("expiry-revoke-teacher");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                UUID teacherId =
                        seedAccount(teacher.subject(), "SELF_REGISTERED", teacher.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID center = seedCenter(ownerId);
                UUID membership = addMembership(center, teacherId, "TEACHER");
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(
                            operatorSession,
                            center,
                            Instant.now().minus(2, ChronoUnit.DAYS),
                            Instant.now().minus(1, ChronoUnit.DAYS));
                }

                try (var teacherSession = openSession(teacher);
                        var ownerSession = openSession(owner)) {
                    UUID operationId =
                            mutationOperation(
                                    ownerSession,
                                    "DELETE",
                                    "/api/workspaces/" + center + "/memberships/" + membership,
                                    "{}");
                    awaitOperationSucceeded(jdbc, operationId);

                    MockHttpServletResponse after =
                            getReady(teacherSession, "/api/workspaces/" + center + "/classes");
                    assertEquals(
                            403,
                            after.getStatus(),
                            "Sau khi thu hồi hoàn tất, yêu cầu tiếp theo của thành viên bị gỡ tại"
                                    + " trung tâm hết hạn phải bị từ chối, body: "
                                    + after.getContentAsString());
                }
                assertEquals(
                        "REVOKED",
                        jdbc.queryForObject(
                                "SELECT state FROM center_membership WHERE id = ?",
                                String.class,
                                membership),
                        "Membership phải ở trạng thái REVOKED sau thu hồi");
            } finally {
                deleteUser(owner.subject());
                deleteUser(teacher.subject());
                deleteUser(operator.subject());
            }
        }

        @DisplayName(
                "Gia hạn gói trung tâm mở lại quyền tăng cho thành viên còn hiệu lực nhưng không"
                        + " phục hồi membership đã thu hồi trước đó")
        @Test
        void renewalDoesNotResurrectRevoked() throws Exception {
            KeycloakLogin owner = createVerifiedUser("expiry-renewal-owner");
            KeycloakLogin manager = createVerifiedUser("expiry-renewal-manager");
            KeycloakLogin member = createVerifiedUser("expiry-renewal-member");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                UUID managerId =
                        seedAccount(manager.subject(), "SELF_REGISTERED", manager.email(), true);
                UUID memberId =
                        seedAccount(member.subject(), "SELF_REGISTERED", member.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID center = seedCenter(ownerId);
                addMembership(center, managerId, "MANAGER");
                UUID memberMembership = addMembership(center, memberId, "TEACHER");
                Instant until = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, center, Instant.now().minusSeconds(120), until);
                }

                try (var ownerSession = openSession(owner);
                        var managerSession = openSession(manager)) {
                    // Quản lý còn hiệu lực tạo lớp được trước mốc hết hạn: kiểm ngay sau khi
                    // kỳ được xác nhận để cửa sổ hiệu lực không bị các thao tác khác ăn mất.
                    UUID createOperation =
                            mutationOperation(
                                    managerSession,
                                    "POST",
                                    "/api/workspaces/" + center + "/classes",
                                    "{\"name\":\"Lớp trước hết hạn\"}");
                    awaitOperationSucceeded(jdbc, createOperation);
                    // Thu hồi membership của member trong lúc gói còn hiệu lực.
                    UUID revokeOperation =
                            mutationOperation(
                                    ownerSession,
                                    "DELETE",
                                    "/api/workspaces/"
                                            + center
                                            + "/memberships/"
                                            + memberMembership,
                                    "{}");
                    awaitOperationSucceeded(jdbc, revokeOperation);

                    // Chờ đồng hồ thật qua mốc hết hạn; không có thao tác ghi nào lên gói.
                    await().atMost(Duration.ofSeconds(330))
                            .pollInterval(Duration.ofMillis(500))
                            .until(() -> Instant.now().isAfter(until.plusSeconds(1)));
                    MockHttpServletResponse expiredCreate =
                            postRaw(
                                    managerSession,
                                    "/api/workspaces/" + center + "/classes",
                                    "{\"name\":\"Lớp sau hết hạn\"}");
                    assertEquals(
                            403,
                            expiredCreate.getStatus(),
                            "Sau mốc hết hạn, quản lý không tạo thêm lớp được, body: "
                                    + expiredCreate.getContentAsString());

                    try (var operatorSession = openSession(operator)) {
                        confirmPeriod(
                                operatorSession,
                                center,
                                Instant.now().minusSeconds(5),
                                Instant.now().plus(365, ChronoUnit.DAYS));
                    }
                    UUID reopenOperation =
                            mutationOperation(
                                    managerSession,
                                    "POST",
                                    "/api/workspaces/" + center + "/classes",
                                    "{\"name\":\"Lớp sau gia hạn\"}");
                    awaitOperationSucceeded(jdbc, reopenOperation);
                }

                try (var memberSession = openSession(member)) {
                    MockHttpServletResponse after =
                            getReady(memberSession, "/api/workspaces/" + center + "/classes");
                    assertEquals(
                            403,
                            after.getStatus(),
                            "Gia hạn không phục hồi quyền của membership đã thu hồi, body: "
                                    + after.getContentAsString());
                }
                assertEquals(
                        "REVOKED",
                        jdbc.queryForObject(
                                "SELECT state FROM center_membership WHERE id = ?",
                                String.class,
                                memberMembership),
                        "Membership đã thu hồi giữ nguyên REVOKED sau gia hạn");
            } finally {
                deleteUser(owner.subject());
                deleteUser(manager.subject());
                deleteUser(member.subject());
                deleteUser(operator.subject());
            }
        }
    }

    @Nested
    @DisplayName("Hai loại gói độc lập")
    class Independence {

        @DisplayName(
                "Trạng thái gói của personal và của center không ảnh hưởng lẫn nhau: nơi hết hạn"
                        + " bị chặn tạo lớp, nơi còn hạn vẫn tạo được, ở cả hai chiều")
        @Test
        void personalAndCenterIndependent() throws Exception {
            KeycloakLogin userA = createVerifiedUser("expiry-independence-a");
            KeycloakLogin userB = createVerifiedUser("expiry-independence-b");
            KeycloakLogin operator = createOperator();
            try {
                UUID userAId = seedAccount(userA.subject(), "SELF_REGISTERED", userA.email(), true);
                UUID userBId = seedAccount(userB.subject(), "SELF_REGISTERED", userB.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID personalA = personalWorkspaceOf(userAId);
                UUID personalB = personalWorkspaceOf(userBId);
                UUID centerA = seedCenter(userAId);
                UUID centerB = seedCenter(userBId);
                Instant pastFrom = Instant.now().minus(2, ChronoUnit.DAYS);
                Instant pastUntil = Instant.now().minus(1, ChronoUnit.DAYS);
                Instant liveFrom = Instant.now().minus(1, ChronoUnit.DAYS);
                Instant liveUntil = Instant.now().plus(365, ChronoUnit.DAYS);
                // Mỗi kỳ một phiên operator mới mở ngay trước khi xác nhận: phiên giữ lâu
                // qua nhiều lần mở phiên của actor khác từng bị chuyển hướng 302 giữa chừng.
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, personalA, pastFrom, pastUntil);
                }
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, centerA, liveFrom, liveUntil);
                }
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, personalB, liveFrom, liveUntil);
                }
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, centerB, pastFrom, pastUntil);
                }

                try (var sessionA = openSession(userA)) {
                    assertEquals(
                            403,
                            postRaw(
                                            sessionA,
                                            "/api/workspaces/" + personalA + "/classes",
                                            "{\"name\":\"Lớp personal A\"}")
                                    .getStatus(),
                            "Personal A hết hạn: tạo lớp cá nhân bị từ chối");
                    assertEquals(
                            202,
                            postRaw(
                                            sessionA,
                                            "/api/workspaces/" + centerA + "/classes",
                                            "{\"name\":\"Lớp center A\"}")
                                    .getStatus(),
                            "Center A còn hạn: cùng actor tạo lớp tại center được phép");
                }
                try (var sessionB = openSession(userB)) {
                    assertEquals(
                            403,
                            postRaw(
                                            sessionB,
                                            "/api/workspaces/" + centerB + "/classes",
                                            "{\"name\":\"Lớp center B\"}")
                                    .getStatus(),
                            "Center B hết hạn: tạo lớp tại center bị từ chối");
                    assertEquals(
                            202,
                            postRaw(
                                            sessionB,
                                            "/api/workspaces/" + personalB + "/classes",
                                            "{\"name\":\"Lớp personal B\"}")
                                    .getStatus(),
                            "Personal B còn hạn: cùng actor tạo lớp cá nhân được phép");
                }
            } finally {
                deleteUser(userA.subject());
                deleteUser(userB.subject());
                deleteUser(operator.subject());
            }
        }
    }

    // ---------- Xác nhận kỳ gói qua đường production ----------

    private void confirmPeriod(RealSession session, UUID workspaceId, Instant from, Instant until)
            throws Exception {
        String json =
                "{\"workspaceId\":\""
                        + workspaceId
                        + "\",\"validFrom\":\""
                        + OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
                        + "\",\"expiresAt\":\""
                        + OffsetDateTime.ofInstant(until, ZoneOffset.UTC)
                        + "\",\"confirmationRef\":\"expiry-confirm-"
                        + UUID.randomUUID()
                        + "\"}";
        MockHttpServletResponse response = postRaw(session, "/api/platform/subscriptions", json);
        assertEquals(
                202,
                response.getStatus(),
                "Operator xác nhận kỳ gói phải trả operation, body: "
                        + response.getContentAsString());
        UUID operationId =
                UUID.fromString(
                        JSON.readTree(response.getContentAsString())
                                .get("operationId")
                                .stringValue());
        awaitOperationSucceeded(jdbc, operationId);
    }

    private KeycloakLogin createOperator() {
        KeycloakLogin login = createVerifiedUser("expiry-operator");
        joinGroup(login.subject(), Objects.requireNonNull(operatorGroupId, "operatorGroupId"));
        return login;
    }

    private void joinGroup(String userId, String groupId) {
        HTTP.put()
                .uri(admin("/users/" + userId + "/groups/" + groupId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    // ---------- HTTP ----------

    private MockHttpServletResponse getReady(RealSession session, String uri) throws Exception {
        return requestWhenReady(mockMvc(), get(uri).cookie(session.cookie()));
    }

    private MockHttpServletResponse postRaw(RealSession session, String uri, String json)
            throws Exception {
        return mockMvc()
                .perform(
                        post(uri)
                                .cookie(session.cookie())
                                .with(csrf())
                                .header("Idempotency-Key", "expiry-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn()
                .getResponse();
    }

    private UUID mutationOperation(RealSession session, String method, String uri, String json)
            throws Exception {
        var builder = "DELETE".equals(method) ? delete(uri) : post(uri);
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                builder.cookie(session.cookie())
                                        .with(csrf())
                                        .header("Idempotency-Key", "expiry-" + UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andReturn()
                        .getResponse();
        assertEquals(
                202,
                response.getStatus(),
                method
                        + " "
                        + uri
                        + " phải trả 202 OperationView, body: "
                        + response.getContentAsString());
        JsonNode body = JSON.readTree(response.getContentAsString());
        return UUID.fromString(body.get("operationId").stringValue());
    }

    private MockHttpServletResponse sampleAction(RealSession session, UUID workspaceId, String json)
            throws Exception {
        return postRaw(session, "/api/workspaces/" + workspaceId + "/samples/actions", json);
    }

    private static String createResourceJson() {
        return "{\"action\":\"CREATE_RESOURCE\",\"subjectId\":\""
                + UUID.randomUUID()
                + "\",\"kind\":\"EXAM\",\"label\":\"Học liệu cá nhân\"}";
    }

    // ---------- Dữ kiện ----------

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
                    "expiry-user-" + actor,
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

    // ---------- Nền realm: group operator ----------

    private static String ensureGroup(String groupPath) {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : groupPath.substring(1).split("/")) {
            path.append('/').append(segment);
            String currentPath = path.toString();
            String found = null;
            try {
                Map<String, Object> group =
                        HTTP.get()
                                .uri(adminUrl("/group-by-path" + currentPath))
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
                found = createGroup(adminUrl(endpoint), Map.of("name", segment));
            }
            parentId = found;
        }
        return Objects.requireNonNull(parentId, "operator group");
    }

    private static String createGroup(String uri, Map<String, ?> body) {
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

    private static String adminUrl(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }
}
