package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng phía đọc kết quả công bố ở tầng HTTP: phụ huynh chỉ đọc được kết quả PUBLISHED của đúng
 * con, đúng center, khi liên kết đã APPROVED; liên kết đang REQUESTED, đã REVOKED, con khác và
 * center khác đều bị chặn. DTO chỉ chứa các trường của PublishedResultView — không bài làm, không
 * đáp án, không trường bản nháp — và danh sách không lộ tổng số hàng.
 *
 * <p>Đây là task test-first: route kết quả, route guardian và các bảng mẫu chưa được triển khai nên
 * các test hiện đỏ ở lời gọi guardian/kết quả đầu tiên (404) — đỏ đó là có ý nghĩa, không phải lỗi
 * hạ tầng.
 *
 * <p>Điểm cần đối chiếu khi triển khai phía seed: kết quả mẫu được seed thẳng bằng JDBC theo đúng
 * cột của data-model ({@code sample_activity/sample_submission/sample_result}) vì chưa có API tạo
 * kết quả; seed chỉ chạy sau khi luồng liên kết đã chạy qua API thật, nên thứ tự task (schema mẫu
 * có trước read side) giữ cho seed hợp lệ khi tới lượt nó chạy. Nếu migration của bảng mẫu gắn thêm
 * FK bắt buộc vào resource stub, seed này cần tạo resource stub trước — phần khẳng định hợp đồng
 * không đổi.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublishedResultContractIT extends GuardianFixture {
    private static final String CLASS_NAME = "Lớp kết quả";
    private static final String COURSE_NAME = "Khóa kết quả";

    @Autowired private ResultPolicyProjector resultProjector;
    @Autowired private IdempotencyService idempotency;

    private record Scenario(UUID courseSubjectId, SubjectRef subject) {}

    /** Lớp/khóa/môn với cả hai học sinh đã ghi danh, sẵn sàng để seed kết quả mẫu. */
    private Scenario prepareScenario(RealSession owner, GuardianCenter center, UUID workspaceId)
            throws Exception {
        UUID classId = createClass(owner, workspaceId, CLASS_NAME);
        SubjectRef subject = firstSubject(owner);
        UUID courseId =
                createCourse(owner, workspaceId, classId, COURSE_NAME, List.of(subject.id()));
        UUID courseSubjectId = courseSubjectId(courseId, subject.id());
        List<UUID> roster = addRoster(owner, workspaceId, classId, center.studentIds());
        enroll(owner, workspaceId, roster, courseSubjectId);
        return new Scenario(courseSubjectId, subject);
    }

    /** Seed một kết quả mẫu theo đúng cột data-model; trả về id của sample_result. */
    private UUID seedResult(
            GuardianCenter center,
            UUID workspaceId,
            Scenario scenario,
            UUID studentUserId,
            String state,
            @Nullable String score,
            @Nullable String comment) {
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID subscriptionPeriodId =
                jdbc.queryForObject(
                        "SELECT id FROM subscription_period WHERE workspace_id = ?",
                        UUID.class,
                        workspaceId);
        tx(() -> {
            // FK của sample_activity trỏ vào resource/resource_version: tạo stub trước, đúng
            // mẫu seed của các test primitive mẫu.
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    workspaceId,
                    center.ownerId(),
                    scenario.subject().id());
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản mẫu','Nội dung','Đáp án','APPROVED')",
                    versionId,
                    resourceId);
            jdbc.update(
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,resource_id,"
                            + "version_id,assigned_by,assigned_at,due_at,attempt_limit,"
                            + "subscription_period_id,state)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ASSIGNED')",
                    activityId,
                    workspaceId,
                    scenario.courseSubjectId(),
                    resourceId,
                    versionId,
                    center.ownerId(),
                    now,
                    now.plusDays(7),
                    1,
                    subscriptionPeriodId);
            jdbc.update(
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,student_user_id,"
                            + "attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,?,'SUBMITTED')",
                    submissionId,
                    workspaceId,
                    activityId,
                    studentUserId,
                    now,
                    "Bài làm mẫu");
            jdbc.update(
                    "INSERT INTO sample_result(id,workspace_id,submission_id,student_user_id,"
                            + "course_subject_id,score_stub,comment_stub,state,published_at,"
                            + "graded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    resultId,
                    workspaceId,
                    submissionId,
                    studentUserId,
                    scenario.courseSubjectId(),
                    score,
                    comment,
                    state,
                    "PUBLISHED".equals(state) ? now : null,
                    center.teacherId());
            return null;
        });
        return resultId;
    }

    /** Ghi projection cho các kết quả và chờ operation SUCCEEDED tại revision mới. */
    private void recordResults(GuardianCenter center, UUID... resultIds) {
        recordResults(center.workspaceId(), center.ownerId(), resultIds);
    }

    /** Ghi projection cho các kết quả của một workspace bất kỳ (center hoặc personal). */
    private void recordResults(UUID workspaceId, UUID initiatorId, UUID... resultIds) {
        List<String> targetKeys =
                Arrays.stream(resultIds)
                        .map(resultId -> NativePolicyTemplates.resultKey(workspaceId, resultId))
                        .toList();
        String payloadHash =
                idempotency.payloadHash(
                        "POST",
                        "/workspaces/" + workspaceId + "/results",
                        JSON.readTree("{\"targets\":\"" + String.join(",", targetKeys) + "\"}"));
        AuthorizationOperation operation =
                resultProjector.recordResultChange(
                        initiatorId,
                        workspaceId,
                        "published-result-it-" + UUID.randomUUID(),
                        payloadHash,
                        targetKeys);
        awaitOperationSucceeded(jdbc, operation.getId());
    }

    private void approveLinkForX(GuardianCenter center) throws Exception {
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
        }
    }

    @Test
    void parentWithoutLinkCannotReadChildResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent())) {
            MockHttpServletResponse response =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    403,
                    response.getStatus(),
                    "Vai trò PARENT trong membership tự nó không mở quyền xem kết quả của con");
        }
    }

    @Test
    void parentWithPendingLinkCannotReadChildResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    403,
                    response.getStatus(),
                    "Liên kết đang REQUESTED chưa được duyệt thì chưa đọc được kết quả");
        }
    }

    @Test
    void approvedParentReadsOnlyPublishedResultsOfOwnChild() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            "PUBLISHED",
                            "9.0",
                            "Làm tốt");
            recordResults(center, publishedId);
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentX(),
                    "DRAFT",
                    "3.0",
                    null);
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentY(),
                    "PUBLISHED",
                    "7.5",
                    "Của học sinh khác");

            MockHttpServletResponse response =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(200, response.getStatus(), "GET kết quả của con sau khi được duyệt");
            String raw = response.getContentAsString();
            JsonNode body = JSON.readTree(raw);
            assertNull(body.get("totalCount"), "Danh sách kết quả không lộ tổng số hàng");
            JsonNode items = body.get("items");
            assertNotNull(items, "Kết quả trả dạng {items}");
            assertEquals(
                    1,
                    items.size(),
                    "Chỉ kết quả PUBLISHED của đúng con được trả; bản DRAFT bị giữ lại");
            JsonNode item = items.get(0);
            assertEquals(publishedId.toString(), item.get("id").stringValue());
            assertEquals(center.studentX().toString(), item.get("student").get("id").stringValue());
            assertFalse(
                    item.get("student").get("displayName").stringValue().isBlank(),
                    "DTO mang displayName tối thiểu của học sinh");
            assertEquals(CLASS_NAME, item.get("className").stringValue());
            assertEquals(COURSE_NAME, item.get("courseName").stringValue());
            assertEquals(scenario.subject().name(), item.get("subjectName").stringValue());
            assertTrue(
                    item.get("score").toString().contains("9.0"),
                    "Điểm của kết quả đã công bố được trả đúng");
            assertEquals("Làm tốt", item.get("comment").stringValue());
            assertFalse(
                    item.get("publishedAt").stringValue().isBlank(),
                    "Kết quả đã công bố có publishedAt");
            for (String hidden :
                    List.of(
                            "contentStub",
                            "submission",
                            "answer",
                            "DRAFT",
                            "scoreStub",
                            "commentStub",
                            "gradedBy",
                            "workspaceId")) {
                assertFalse(
                        raw.contains(hidden),
                        "Phản hồi cho phụ huynh không được chứa trường ẩn: " + hidden);
            }
        }
    }

    @Test
    void approvedParentCannotReadOtherChildResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            approveLinkForX(center);
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentY(),
                    "PUBLISHED",
                    "8.0",
                    "Của học sinh khác");
            MockHttpServletResponse response =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentY()));
            assertEquals(
                    403,
                    response.getStatus(),
                    "Liên kết với X không mở quyền đọc kết quả của học sinh khác dù kết quả đã"
                            + " công bố");
        }
    }

    @Test
    void approvedLinkInOneCenterDoesNotGrantAnotherCenter() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        UUID workspaceB = seedSecondCenter(center);
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent())) {
            approveLinkForX(center);
            Scenario scenarioB = prepareScenario(owner, center, workspaceB);
            seedResult(
                    center, workspaceB, scenarioB, center.studentX(), "PUBLISHED", "6.5", "Tại B");
            MockHttpServletResponse response =
                    getRaw(parent, resultsPath(workspaceB, center.studentX()));
            assertEquals(
                    403,
                    response.getStatus(),
                    "Liên kết đã duyệt tại center A không tự cấp quyền đọc kết quả tại center B");
        }
    }

    @Test
    void studentReadsOnlyOwnPublishedResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var student = openSession(center.students().get(0))) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            // Đọc thăm endpoint trước khi seed: test phải đỏ vì route chưa có, không đỏ vì seed
            // chạm bảng mẫu chưa tồn tại.
            MockHttpServletResponse probe =
                    getRaw(student, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(200, probe.getStatus(), "Học sinh đọc kết quả công bố của chính mình");
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            "PUBLISHED",
                            "8.5",
                            "Tốt");
            recordResults(center, publishedId);
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentX(),
                    "DRAFT",
                    "2.0",
                    null);
            MockHttpServletResponse own =
                    getRaw(student, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(200, own.getStatus(), "Học sinh đọc kết quả công bố của chính mình");
            JsonNode items = JSON.readTree(own.getContentAsString()).get("items");
            assertNotNull(items, "Kết quả trả dạng {items}");
            assertEquals(1, items.size(), "Học sinh cũng chỉ thấy kết quả đã công bố");
            assertEquals(publishedId.toString(), items.get(0).get("id").stringValue());
            MockHttpServletResponse other =
                    getRaw(student, resultsPath(center.workspaceId(), center.studentY()));
            assertEquals(
                    403, other.getStatus(), "Học sinh không đọc được kết quả của học sinh khác");
        }
    }

    @Test
    void revokedLinkStopsParentFromReadingResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            "PUBLISHED",
                            "9.5",
                            "Xuất sắc");
            recordResults(center, publishedId);
            MockHttpServletResponse before =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(200, before.getStatus());
            assertEquals(
                    1,
                    JSON.readTree(before.getContentAsString()).get("items").size(),
                    "Trước khi gỡ, phụ huynh thấy đúng kết quả đã công bố");
            revokeSucceeded(manager, center.workspaceId(), linkIdOf(link));
            MockHttpServletResponse after =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    403,
                    after.getStatus(),
                    "Sau khi gỡ liên kết, lần đọc tiếp theo bị chặn dù kết quả vẫn còn cho người"
                            + " có thẩm quyền khác");
        }
    }
}
