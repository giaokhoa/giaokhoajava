package com.giaokhoa.verification;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * US8 ở tầng hoàn tất bài: hoạt động đã giao trong lúc gói còn hiệu lực (hoạt động ghi đúng kỳ gói
 * lúc giao) vẫn hoàn tất trọn vòng nộp/chấm/công bố sau khi gói hết hạn, bằng đúng các phiên đã mở
 * trước mốc (spec US8.2, US8.4); các thao tác mở rộng trên cùng hoạt động — gia hạn hạn nộp, mở
 * thêm lượt, giao lại, giao bài mới — bị chặn sau mốc. Ngoại lệ hoàn tất không vượt thu hồi nguồn:
 * gỡ ghi danh sau hết hạn vẫn chặn lượt nộp (spec, edge cases của US8 và gate native mục 6).
 *
 * <p>Kỳ gói được ghi qua đường production (operator xác nhận qua API nền tảng) với cửa sổ ngắn theo
 * đồng hồ thật; test chờ đồng hồ qua mốc rồi mới hoàn tất — không mock thời gian, không cron. Chuỗi
 * lớp/khóa/roster/ghi danh và bản nháp kết quả được seed thẳng bằng JDBC theo mẫu các IT mẫu hiện
 * hành (tiền đề fixture), còn mọi thao tác được kiểm thử — giao bài, nộp, chấm, công bố, mở rộng,
 * thu hồi ghi danh — đều đi qua HTTP thật.
 */
@SpringBootTest
class CompletionAfterExpiryIT extends KeycloakSessionFixture {
    private static final String OPERATOR_GROUP = "/gk/platform/operators";
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
    @DisplayName("Hoàn tất bài tại trung tâm sau khi gói trung tâm hết hạn")
    class CenterCompletion {

        @DisplayName(
                "Bài giao trước hết hạn: học sinh nộp, giáo viên chấm/công bố và phụ huynh đọc"
                        + " kết quả công bố được sau mốc; gia hạn/mở lượt/giao lại/giao mới bị"
                        + " chặn")
        @Test
        void completionSurvivesExpiry() throws Exception {
            KeycloakLogin owner = createVerifiedUser("completion-center-owner");
            KeycloakLogin teacher = createVerifiedUser("completion-center-teacher");
            KeycloakLogin student = createVerifiedUser("completion-center-student");
            KeycloakLogin parent = createVerifiedUser("completion-center-parent");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                UUID teacherId =
                        seedAccount(teacher.subject(), "SELF_REGISTERED", teacher.email(), true);
                UUID studentId =
                        seedAccount(student.subject(), "SELF_REGISTERED", student.email(), true);
                UUID parentId =
                        seedAccount(parent.subject(), "SELF_REGISTERED", parent.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID center = seedCenter(ownerId);
                addMembership(center, teacherId, "TEACHER");
                addMembership(center, studentId, "STUDENT");
                addMembership(center, parentId, "PARENT");
                TeachingChain chain = seedCenterChain(center, studentId, teacherId);
                ResourceStub stub = seedResourceStub(center, teacherId, chain.subjectId());
                seedGuardianLink(center, parentId, studentId, ownerId);
                Instant until = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, center, Instant.now().minusSeconds(180), until);
                }
                UUID periodId =
                        Objects.requireNonNull(
                                jdbc.queryForObject(
                                        "SELECT id FROM subscription_period WHERE workspace_id"
                                                + " = ?",
                                        UUID.class,
                                        center),
                                "period");

                UUID activityId;
                UUID pastDueActivityId;
                try (var teacherSession = openSession(teacher)) {
                    JsonNode assigned =
                            sampleActionExpectCreated(
                                    teacherSession, center, assignJson(chain, stub));
                    activityId = UUID.fromString(assigned.get("targetId").stringValue());
                    // Deadline đã qua sau cutoff gói; học sinh giữ phiên cũ vẫn không được nộp.
                    pastDueActivityId =
                            UUID.fromString(
                                    sampleActionExpectCreated(
                                                    teacherSession,
                                                    center,
                                                    assignJson(
                                                            chain,
                                                            stub,
                                                            OffsetDateTime.ofInstant(
                                                                    until.minusSeconds(30),
                                                                    ZoneOffset.UTC)))
                                            .get("targetId")
                                            .stringValue());
                }
                assertEquals(
                        periodId,
                        jdbc.queryForObject(
                                "SELECT subscription_period_id FROM sample_activity WHERE id = ?",
                                UUID.class,
                                activityId),
                        "Hoạt động phải ghi đúng kỳ gói đang hiệu lực tại thời điểm giao");

                awaitExpiry(until);

                UUID resultId;
                try (var studentSession = openSession(student);
                        var teacherSession = openSession(teacher)) {
                    MockHttpServletResponse late =
                            sampleAction(
                                    studentSession,
                                    center,
                                    "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                            + pastDueActivityId
                                            + "\",\"contentStub\":\"Quá hạn bài nộp\"}");
                    assertEquals(409, late.getStatus(), late.getContentAsString());
                    assertTrue(late.getContentAsString().contains("SUBMISSION_DEADLINE_PASSED"));
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM sample_submission WHERE activity_id = ?",
                                    Integer.class,
                                    pastDueActivityId));
                    JsonNode submitted =
                            sampleActionExpectCreated(
                                    studentSession,
                                    center,
                                    "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                            + activityId
                                            + "\",\"contentStub\":\"Bài làm sau hết hạn\"}");
                    UUID submissionId = UUID.fromString(submitted.get("targetId").stringValue());
                    resultId =
                            seedDraftResult(
                                    center, submissionId, studentId, chain.courseSubjectId());

                    JsonNode graded =
                            sampleActionExpectCreated(
                                    teacherSession,
                                    center,
                                    "{\"action\":\"GRADE_RESULT\",\"resultId\":\""
                                            + resultId
                                            + "\",\"scoreStub\":\"9.0\",\"commentStub\":\"Đạt\"}");
                    assertEquals("SUCCEEDED", graded.get("state").stringValue());
                    JsonNode published =
                            sampleActionExpectCreated(
                                    teacherSession,
                                    center,
                                    "{\"action\":\"PUBLISH_RESULT\",\"resultId\":\""
                                            + resultId
                                            + "\"}");
                    assertEquals("SUCCEEDED", published.get("state").stringValue());

                    String laterDue = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14).toString();
                    sampleActionExpectDenied(
                            teacherSession,
                            center,
                            "{\"action\":\"EXTEND_DEADLINE\",\"activityId\":\""
                                    + activityId
                                    + "\",\"dueAt\":\""
                                    + laterDue
                                    + "\"}",
                            "Sau hết hạn, gia hạn hạn nộp của hoạt động cũ bị chặn");
                    sampleActionExpectDenied(
                            teacherSession,
                            center,
                            "{\"action\":\"OPEN_ATTEMPT\",\"activityId\":\""
                                    + activityId
                                    + "\",\"studentUserId\":\""
                                    + studentId
                                    + "\"}",
                            "Sau hết hạn, mở thêm lượt nộp bị chặn");
                    sampleActionExpectDenied(
                            teacherSession,
                            center,
                            "{\"action\":\"REASSIGN_ACTIVITY\",\"activityId\":\""
                                    + activityId
                                    + "\",\"dueAt\":\""
                                    + laterDue
                                    + "\"}",
                            "Sau hết hạn, giao lại hoạt động bị chặn");
                    sampleActionExpectDenied(
                            teacherSession,
                            center,
                            assignJson(chain, stub),
                            "Sau hết hạn, giao bài mới bị chặn");
                }

                try (var parentSession = openSession(parent)) {
                    JsonNode results = resultsOf(parentSession, center, studentId);
                    assertTrue(
                            containsResult(results, resultId),
                            "Phụ huynh đọc được kết quả đã công bố của con sau khi gói trung tâm"
                                    + " hết hạn");
                }
                try (var studentSession = openSession(student)) {
                    JsonNode results = resultsOf(studentSession, center, studentId);
                    assertTrue(
                            containsResult(results, resultId),
                            "Học sinh đọc được kết quả đã công bố của mình sau hết hạn");
                }
            } finally {
                deleteUser(owner.subject());
                deleteUser(teacher.subject());
                deleteUser(student.subject());
                deleteUser(parent.subject());
                deleteUser(operator.subject());
            }
        }

        @DisplayName(
                "Gỡ ghi danh sau khi gói hết hạn vẫn thực hiện được và chặn lượt nộp của bài đã"
                        + " giao: ngoại lệ hoàn tất không vượt thu hồi nguồn")
        @Test
        void enrollmentRevokeBlocksCompletion() throws Exception {
            KeycloakLogin owner = createVerifiedUser("completion-revoke-owner");
            KeycloakLogin teacher = createVerifiedUser("completion-revoke-teacher");
            KeycloakLogin student = createVerifiedUser("completion-revoke-student");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                UUID teacherId =
                        seedAccount(teacher.subject(), "SELF_REGISTERED", teacher.email(), true);
                UUID studentId =
                        seedAccount(student.subject(), "SELF_REGISTERED", student.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID center = seedCenter(ownerId);
                addMembership(center, teacherId, "TEACHER");
                addMembership(center, studentId, "STUDENT");
                TeachingChain chain = seedCenterChain(center, studentId, teacherId);
                ResourceStub stub = seedResourceStub(center, teacherId, chain.subjectId());
                Instant until = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(operatorSession, center, Instant.now().minusSeconds(180), until);
                }

                UUID activityId;
                try (var teacherSession = openSession(teacher)) {
                    JsonNode assigned =
                            sampleActionExpectCreated(
                                    teacherSession, center, assignJson(chain, stub));
                    activityId = UUID.fromString(assigned.get("targetId").stringValue());
                }

                awaitExpiry(until);

                try (var ownerSession = openSession(owner)) {
                    UUID operationId =
                            mutationOperation(
                                    ownerSession,
                                    "DELETE",
                                    "/api/workspaces/"
                                            + center
                                            + "/enrollments/"
                                            + chain.enrollmentId(),
                                    "{}");
                    awaitOperationSucceeded(jdbc, operationId);
                }
                assertEquals(
                        "REVOKED",
                        jdbc.queryForObject(
                                "SELECT state FROM enrollment WHERE id = ?",
                                String.class,
                                chain.enrollmentId()),
                        "Ghi danh phải ở trạng thái REVOKED sau thu hồi");

                try (var studentSession = openSession(student)) {
                    MockHttpServletResponse submit =
                            sampleAction(
                                    studentSession,
                                    center,
                                    "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                            + activityId
                                            + "\",\"contentStub\":\"Bài làm sau thu hồi\"}");
                    assertEquals(
                            403,
                            submit.getStatus(),
                            "Đã gỡ ghi danh thì nộp bài bị chặn dù bài giao trước hết hạn, body: "
                                    + submit.getContentAsString());
                }
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM sample_submission WHERE activity_id = ?",
                                Integer.class,
                                activityId),
                        "Lượt nộp bị chặn không được ghi");
            } finally {
                deleteUser(owner.subject());
                deleteUser(teacher.subject());
                deleteUser(student.subject());
                deleteUser(operator.subject());
            }
        }
    }

    @Nested
    @DisplayName("Hoàn tất bài tại lớp cá nhân sau khi gói cá nhân hết hạn")
    class PersonalCompletion {

        @DisplayName(
                "Bài giao trước hết hạn: học sinh nộp, chủ lớp chấm/công bố và học sinh đọc kết"
                        + " quả được sau mốc; gia hạn hạn nộp bị chặn")
        @Test
        void completionSurvivesExpiry() throws Exception {
            KeycloakLogin owner = createVerifiedUser("completion-personal-owner");
            KeycloakLogin student = createVerifiedUser("completion-personal-student");
            KeycloakLogin operator = createOperator();
            try {
                UUID ownerId = seedAccount(owner.subject(), "SELF_REGISTERED", owner.email(), true);
                UUID studentId =
                        seedAccount(student.subject(), "SELF_REGISTERED", student.email(), true);
                seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
                UUID personal = personalWorkspaceOf(ownerId);
                TeachingChain chain = seedPersonalChain(personal, studentId);
                ResourceStub stub = seedResourceStub(personal, ownerId, chain.subjectId());
                Instant until = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
                try (var operatorSession = openSession(operator)) {
                    confirmPeriod(
                            operatorSession, personal, Instant.now().minusSeconds(180), until);
                }

                UUID activityId;
                try (var ownerSession = openSession(owner)) {
                    JsonNode assigned =
                            sampleActionExpectCreated(
                                    ownerSession, personal, assignJson(chain, stub));
                    activityId = UUID.fromString(assigned.get("targetId").stringValue());
                }

                awaitExpiry(until);

                UUID resultId;
                try (var studentSession = openSession(student);
                        var ownerSession = openSession(owner)) {
                    JsonNode submitted =
                            sampleActionExpectCreated(
                                    studentSession,
                                    personal,
                                    "{\"action\":\"SUBMIT_ACTIVITY\",\"activityId\":\""
                                            + activityId
                                            + "\",\"contentStub\":\"Bài làm cá nhân sau hết"
                                            + " hạn\"}");
                    UUID submissionId = UUID.fromString(submitted.get("targetId").stringValue());
                    resultId =
                            seedDraftResult(
                                    personal, submissionId, studentId, chain.courseSubjectId());

                    assertEquals(
                            "SUCCEEDED",
                            sampleActionExpectCreated(
                                            ownerSession,
                                            personal,
                                            "{\"action\":\"GRADE_RESULT\",\"resultId\":\""
                                                    + resultId
                                                    + "\",\"scoreStub\":\"8.5\",\"commentStub\":\"Tốt\"}")
                                    .get("state")
                                    .stringValue());
                    assertEquals(
                            "SUCCEEDED",
                            sampleActionExpectCreated(
                                            ownerSession,
                                            personal,
                                            "{\"action\":\"PUBLISH_RESULT\",\"resultId\":\""
                                                    + resultId
                                                    + "\"}")
                                    .get("state")
                                    .stringValue());
                    sampleActionExpectDenied(
                            ownerSession,
                            personal,
                            "{\"action\":\"EXTEND_DEADLINE\",\"activityId\":\""
                                    + activityId
                                    + "\",\"dueAt\":\""
                                    + OffsetDateTime.now(ZoneOffset.UTC).plusDays(14)
                                    + "\"}",
                            "Sau hết hạn gói cá nhân, gia hạn hạn nộp bị chặn");
                }

                try (var studentSession = openSession(student)) {
                    JsonNode results = resultsOf(studentSession, personal, studentId);
                    assertTrue(
                            containsResult(results, resultId),
                            "Học sinh đọc được kết quả đã công bố của mình sau khi gói cá nhân"
                                    + " hết hạn");
                }
            } finally {
                deleteUser(owner.subject());
                deleteUser(student.subject());
                deleteUser(operator.subject());
            }
        }
    }

    // ---------- Chờ mốc hết hạn theo đồng hồ thật ----------

    private static void awaitExpiry(Instant until) {
        await().atMost(Duration.ofSeconds(330))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> Instant.now().isAfter(until.plusSeconds(1)));
    }

    private static boolean containsResult(JsonNode results, UUID resultId) {
        for (JsonNode item : results.get("items")) {
            if (resultId.toString().equals(item.get("id").stringValue())) {
                return true;
            }
        }
        return false;
    }

    private static String assignJson(TeachingChain chain, ResourceStub stub) {
        return assignJson(chain, stub, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7));
    }

    private static String assignJson(TeachingChain chain, ResourceStub stub, OffsetDateTime dueAt) {
        return "{\"action\":\"ASSIGN_ACTIVITY\",\"courseSubjectId\":\""
                + chain.courseSubjectId()
                + "\",\"resourceId\":\""
                + stub.resourceId()
                + "\",\"versionId\":\""
                + stub.versionId()
                + "\",\"dueAt\":\""
                + dueAt
                + "\",\"attemptLimit\":1}";
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
                        + "\",\"confirmationRef\":\"completion-confirm-"
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
        KeycloakLogin login = createVerifiedUser("completion-operator");
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

    private MockHttpServletResponse postRaw(RealSession session, String uri, String json)
            throws Exception {
        return postRawWithKey(session, uri, json, "completion-" + UUID.randomUUID());
    }

    private MockHttpServletResponse postRawWithKey(
            RealSession session, String uri, String json, String idempotencyKey) throws Exception {
        return mockMvc()
                .perform(
                        post(uri)
                                .cookie(session.cookie())
                                .with(csrf())
                                .header("Idempotency-Key", idempotencyKey)
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
                                        .header(
                                                "Idempotency-Key",
                                                "completion-" + UUID.randomUUID())
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
        return UUID.fromString(
                JSON.readTree(response.getContentAsString()).get("operationId").stringValue());
    }

    /**
     * Gửi một sample action; chỉ thử lại khi phản hồi là 503 AUTHORIZATION_NOT_READY (fence của
     * partition chưa sẵn sàng — hợp đồng "chưa sẵn sàng, thử lại" của nền tảng, cùng mẫu
     * requestWhenReady) và giữ nguyên Idempotency-Key qua mọi lần thử, tối đa 90 giây. Mọi trạng
     * thái khác — gồm 403 bị chặn — trả về ngay cho caller khẳng định.
     */
    private MockHttpServletResponse sampleAction(RealSession session, UUID workspaceId, String json)
            throws Exception {
        String uri = "/api/workspaces/" + workspaceId + "/samples/actions";
        String idempotencyKey = "completion-" + UUID.randomUUID();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        for (; ; ) {
            MockHttpServletResponse response = postRawWithKey(session, uri, json, idempotencyKey);
            if (!authorizationNotReady(response) || System.nanoTime() >= deadline) {
                return response;
            }
            Thread.sleep(1000);
        }
    }

    private static boolean authorizationNotReady(MockHttpServletResponse response)
            throws Exception {
        return response.getStatus() == 503
                && response.getContentAsString().contains("AUTHORIZATION_NOT_READY");
    }

    /** Khẳng định một sample action bị từ chối 403, kèm body trong thông điệp khi lệch. */
    private void sampleActionExpectDenied(
            RealSession session, UUID workspaceId, String json, String label) throws Exception {
        MockHttpServletResponse response = sampleAction(session, workspaceId, json);
        assertEquals(403, response.getStatus(), label + ", body: " + response.getContentAsString());
    }

    private JsonNode sampleActionExpectCreated(RealSession session, UUID workspaceId, String json)
            throws Exception {
        MockHttpServletResponse response = sampleAction(session, workspaceId, json);
        assertEquals(
                201,
                response.getStatus(),
                "Sample action phải hoàn tất đồng bộ, body: " + response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    private JsonNode resultsOf(RealSession session, UUID workspaceId, UUID studentUserId)
            throws Exception {
        MockHttpServletResponse response =
                requestWhenReady(
                        mockMvc(),
                        get("/api/workspaces/"
                                        + workspaceId
                                        + "/students/"
                                        + studentUserId
                                        + "/results")
                                .cookie(session.cookie()));
        assertEquals(
                200,
                response.getStatus(),
                "Đọc kết quả phải được phép, body: " + response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    // ---------- Dữ kiện ----------

    private record TeachingChain(
            UUID classId,
            UUID courseSubjectId,
            UUID classStudentId,
            UUID subjectId,
            @Nullable UUID enrollmentId) {}

    private record ResourceStub(UUID resourceId, UUID versionId) {}

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
                    "completion-user-" + actor,
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

    /**
     * Seed chuỗi lớp/khóa/môn/roster/ghi danh/phân công trong center bằng JDBC: tiền đề fixture ở
     * trạng thái như sau các operation SUCCEEDED, để test tập trung vào hành vi hoàn tất quanh mốc
     * hết hạn.
     */
    private TeachingChain seedCenterChain(UUID workspaceId, UUID studentId, UUID teacherId) {
        UUID subjectId =
                Objects.requireNonNull(
                        jdbc.queryForObject("SELECT id FROM subject LIMIT 1", UUID.class),
                        "Danh mục môn phải có sẵn");
        UUID classId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID courseSubjectId = UUID.randomUUID();
        UUID classStudentId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO school_class(id,workspace_id,name,state)"
                            + " VALUES (?,?,?,'ACTIVE')",
                    classId,
                    workspaceId,
                    "Lớp center seed");
            jdbc.update(
                    "INSERT INTO course(id,workspace_id,class_id,name,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    courseId,
                    workspaceId,
                    classId,
                    "Khóa center seed");
            jdbc.update(
                    "INSERT INTO course_subject(id,workspace_id,class_id,course_id,subject_id,"
                            + "state) VALUES (?,?,?,?,?,'ACTIVE')",
                    courseSubjectId,
                    workspaceId,
                    classId,
                    courseId,
                    subjectId);
            jdbc.update(
                    "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    classStudentId,
                    workspaceId,
                    classId,
                    studentId);
            jdbc.update(
                    "INSERT INTO enrollment(id,workspace_id,class_student_id,course_subject_id,"
                            + "state) VALUES (?,?,?,?,'ACTIVE')",
                    enrollmentId,
                    workspaceId,
                    classStudentId,
                    courseSubjectId);
            jdbc.update(
                    "INSERT INTO teaching_assignment(id,workspace_id,course_subject_id,"
                            + "teacher_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(),
                    workspaceId,
                    courseSubjectId,
                    teacherId);
            return null;
        });
        return new TeachingChain(classId, courseSubjectId, classStudentId, subjectId, enrollmentId);
    }

    /** Seed chuỗi lớp personal tương tự, không membership và không phân công giáo viên. */
    private TeachingChain seedPersonalChain(UUID workspaceId, UUID studentId) {
        UUID subjectId =
                Objects.requireNonNull(
                        jdbc.queryForObject("SELECT id FROM subject LIMIT 1", UUID.class),
                        "Danh mục môn phải có sẵn");
        UUID classId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID courseSubjectId = UUID.randomUUID();
        UUID classStudentId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO school_class(id,workspace_id,name,state)"
                            + " VALUES (?,?,?,'ACTIVE')",
                    classId,
                    workspaceId,
                    "Lớp personal seed");
            jdbc.update(
                    "INSERT INTO course(id,workspace_id,class_id,name,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    courseId,
                    workspaceId,
                    classId,
                    "Khóa personal seed");
            jdbc.update(
                    "INSERT INTO course_subject(id,workspace_id,class_id,course_id,subject_id,"
                            + "state) VALUES (?,?,?,?,?,'ACTIVE')",
                    courseSubjectId,
                    workspaceId,
                    classId,
                    courseId,
                    subjectId);
            jdbc.update(
                    "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    classStudentId,
                    workspaceId,
                    classId,
                    studentId);
            jdbc.update(
                    "INSERT INTO enrollment(id,workspace_id,class_student_id,course_subject_id,"
                            + "state) VALUES (?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(),
                    workspaceId,
                    classStudentId,
                    courseSubjectId);
            return null;
        });
        return new TeachingChain(classId, courseSubjectId, classStudentId, subjectId, null);
    }

    private ResourceStub seedResourceStub(UUID workspaceId, UUID createdBy, UUID subjectId) {
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    workspaceId,
                    createdBy,
                    subjectId);
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản mẫu','Nội dung','Đáp án','APPROVED')",
                    versionId,
                    resourceId);
            return null;
        });
        return new ResourceStub(resourceId, versionId);
    }

    /** Seed một kết quả DRAFT cho đúng bài nộp thật vừa ghi, theo mẫu các IT kết quả hiện hành. */
    private UUID seedDraftResult(
            UUID workspaceId, UUID submissionId, UUID studentId, UUID courseSubjectId) {
        UUID resultId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO sample_result(id,workspace_id,submission_id,student_user_id,"
                            + "course_subject_id,score_stub,comment_stub,state,published_at,"
                            + "graded_by) VALUES (?,?,?,?,?,NULL,NULL,'DRAFT',NULL,NULL)",
                    resultId,
                    workspaceId,
                    submissionId,
                    studentId,
                    courseSubjectId);
            return null;
        });
        return resultId;
    }

    private void seedGuardianLink(
            UUID workspaceId, UUID parentUserId, UUID studentUserId, UUID decidedBy) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO guardian_link(id,workspace_id,parent_user_id,student_user_id,"
                            + "state,requested_by,approved_by,approved_at,revoked_at)"
                            + " VALUES (?,?,?,?,'APPROVED',?,?,?,NULL)",
                    UUID.randomUUID(),
                    workspaceId,
                    parentUserId,
                    studentUserId,
                    parentUserId,
                    decidedBy,
                    now);
            return null;
        });
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
