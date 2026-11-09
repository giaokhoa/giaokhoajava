package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng liên kết phụ huynh ở tầng HTTP: phụ huynh tự gửi yêu cầu cho chính mình, chỉ chủ sở
 * hữu/quản lý được duyệt hoặc gỡ, giáo viên chủ nhiệm và giáo viên bộ môn đứng riêng không duyệt
 * được, danh sách chỉ lộ yêu cầu của chính actor (quản lý thấy danh sách cần duyệt), yêu cầu bị từ
 * chối phải gửi mới, và cùng một phiên sau khi liên kết bị gỡ thì lần đọc kết quả tiếp theo bị
 * chặn.
 *
 * <p>Đây là task test-first: route, bảng và service guardian chưa được triển khai nên các test hiện
 * đỏ với 404 ở lời gọi guardian đầu tiên — đỏ đó là có ý nghĩa, không phải lỗi hạ tầng. Các test kỳ
 * vọng 404 cho đối tượng ngoài phạm vi được viết kèm một luồng hợp lệ chạy trước để không xanh giả
 * khi route chưa tồn tại.
 *
 * <p>Ba điểm hợp đồng test này chốt theo tài liệu hiện có và cần đối chiếu khi triển khai: item của
 * danh sách liên kết lộ {@code version} vì {@code GuardianDecision} bắt buộc mang {@code
 * expectedVersion}; học sinh ngoài phạm vi center trả 404 theo quy tắc không tiết lộ sự tồn tại
 * ngoài phạm vi của hợp đồng HTTP; body mang {@code parentUserId} của người khác chưa được hợp đồng
 * chốt là từ chối (400) hay bỏ qua trường lạ (202) nên test chấp nhận cả hai, chỉ chốt chặt không
 * bao giờ tạo liên kết cho phụ huynh khác.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianAccessIT extends GuardianFixture {
    private static final String SCHEMA =
            "guardian_access_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private ResultPolicyProjector resultProjector;
    @Autowired private IdempotencyService idempotency;

    private record Scenario(UUID courseSubjectId, SubjectRef subject) {}

    /** Lớp/khóa/môn với học sinh X đã ghi danh, sẵn sàng để seed kết quả mẫu. */
    private Scenario prepareScenario(RealSession owner, GuardianCenter center, UUID workspaceId)
            throws Exception {
        UUID classId = createClass(owner, workspaceId, "Lớp kết quả");
        SubjectRef subject = firstSubject(owner);
        UUID courseId =
                createCourse(owner, workspaceId, classId, "Khóa kết quả", List.of(subject.id()));
        UUID courseSubjectId = courseSubjectId(courseId, subject.id());
        UUID rosterX = addRoster(owner, workspaceId, classId, center.studentX());
        enroll(owner, workspaceId, rosterX, courseSubjectId);
        return new Scenario(courseSubjectId, subject);
    }

    /** Seed một kết quả PUBLISHED của học sinh; trả về id của sample_result. */
    private UUID seedPublishedResult(
            GuardianCenter center, UUID workspaceId, Scenario scenario, UUID studentUserId) {
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
                    "9.5",
                    "Xuất sắc",
                    "PUBLISHED",
                    now,
                    center.teacherId());
            return null;
        });
        return resultId;
    }

    /** Ghi projection cho các kết quả và chờ operation SUCCEEDED tại revision mới. */
    private void recordResults(GuardianCenter center, UUID... resultIds) {
        List<String> targetKeys =
                Arrays.stream(resultIds)
                        .map(resultId ->
                                NativePolicyTemplates.resultKey(center.workspaceId(), resultId))
                        .toList();
        String payloadHash =
                idempotency.payloadHash(
                        "POST",
                        "/workspaces/" + center.workspaceId() + "/results",
                        JSON.readTree("{\"targets\":\"" + String.join(",", targetKeys) + "\"}"));
        AuthorizationOperation operation =
                resultProjector.recordResultChange(
                        center.ownerId(),
                        center.workspaceId(),
                        "published-result-it-" + UUID.randomUUID(),
                        payloadHash,
                        targetKeys);
        awaitOperationSucceeded(jdbc, operation.getId());
    }

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        // Class này chờ operation do worker thật xử lý nên teardown dừng scheduler trước để
        // không còn actor đồng thời nào chạm schema khi DROP.
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Test
    void parentRequestsLinkAndSeesItPendingInOwnList() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            assertEquals(
                    center.parentId().toString(),
                    link.get("parentUserId").stringValue(),
                    "Requester là actor hiện hành, server ghi đúng phụ huynh gửi yêu cầu");
            assertEquals(
                    "REQUESTED",
                    link.get("state").stringValue(),
                    "Liên kết mới ở trạng thái REQUESTED, chưa tự mở quyền");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void managerApprovesLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            JsonNode pending =
                    findLink(listLinks(manager, center.workspaceId()), center.studentX());
            decideSucceeded(manager, center.workspaceId(), pending, "APPROVE");
            assertEquals(
                    "APPROVED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Sau khi quản lý duyệt, liên kết ở trạng thái APPROVED");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void ownerApprovesLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var owner = openSession(center.owner())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            JsonNode pending = findLink(listLinks(owner, center.workspaceId()), center.studentX());
            decideSucceeded(owner, center.workspaceId(), pending, "APPROVE");
            assertEquals("APPROVED", linkStateOf(parent, center.workspaceId(), center.studentX()));
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void rejectedLinkNeedsNewRequest() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode first = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), first, "REJECT");
            JsonNode rejected =
                    findLink(listLinks(parent, center.workspaceId()), center.studentX());
            assertEquals("REJECTED", rejected.get("state").stringValue());
            MockHttpServletResponse secondDecision =
                    decide(
                            manager,
                            center.workspaceId(),
                            linkIdOf(rejected),
                            "APPROVE",
                            rejected.get("version").longValue());
            assertEquals(
                    409,
                    secondDecision.getStatus(),
                    "Request đã bị từ chối là kết thúc; muốn liên kết lại phải gửi yêu cầu mới");
            JsonNode renewed =
                    requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            assertNotEquals(
                    linkIdOf(first),
                    linkIdOf(renewed),
                    "Yêu cầu mới là một liên kết mới, không hồi sinh request đã từ chối");
            assertEquals("REQUESTED", renewed.get("state").stringValue());
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void homeroomTeacherCannotDecideLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var homeroom = openSession(center.homeroom())) {
            UUID classId = createClass(owner, center.workspaceId(), "Lớp chủ nhiệm");
            addRoster(owner, center.workspaceId(), classId, center.studentX());
            assignHomeroom(owner, center.workspaceId(), classId, center.homeroomId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    decide(
                            homeroom,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue());
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên chủ nhiệm không có vai trò quản lý/chủ sở hữu thì không được duyệt"
                            + " liên kết");
            assertEquals(
                    "REQUESTED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Liên kết vẫn chờ duyệt sau lần duyệt bị từ chối");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void subjectTeacherCannotDecideLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var teacher = openSession(center.teacher())) {
            UUID classId = createClass(owner, center.workspaceId(), "Lớp bộ môn");
            UUID rosterId = addRoster(owner, center.workspaceId(), classId, center.studentX());
            SubjectRef subject = firstSubject(owner);
            UUID courseId =
                    createCourse(
                            owner,
                            center.workspaceId(),
                            classId,
                            "Khóa bộ môn",
                            List.of(subject.id()));
            UUID courseSubjectId = courseSubjectId(courseId, subject.id());
            enroll(owner, center.workspaceId(), rosterId, courseSubjectId);
            assignTeacher(owner, center.workspaceId(), courseSubjectId, center.teacherId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    decide(
                            teacher,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue());
            assertEquals(
                    403,
                    response.getStatus(),
                    "Giáo viên bộ môn đứng riêng không được duyệt liên kết");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void parentCannotRequestLinkForAnotherParent() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var otherParent = openSession(center.otherParent())) {
            MockHttpServletResponse response =
                    requestLink(
                            parent,
                            center.workspaceId(),
                            "{\"studentUserId\":\""
                                    + center.studentX()
                                    + "\",\"parentUserId\":\""
                                    + center.otherParentId()
                                    + "\"}");
            assertTrue(
                    response.getStatus() == 202 || response.getStatus() == 400,
                    "Body mang parentId của người khác phải bị từ chối hoặc bị bỏ qua, không được"
                            + " tạo liên kết cho phụ huynh khác; status thực tế "
                            + response.getStatus());
            for (JsonNode item : listLinks(otherParent, center.workspaceId())) {
                assertNotEquals(
                        center.studentX().toString(),
                        item.get("studentUserId").stringValue(),
                        "Phụ huynh khác không có liên kết nào từ yêu cầu giả danh này");
            }
            boolean accepted = response.getStatus() == 202;
            if (accepted) {
                JsonNode own = findLink(listLinks(parent, center.workspaceId()), center.studentX());
                assertEquals(
                        center.parentId().toString(),
                        own.get("parentUserId").stringValue(),
                        "Nếu yêu cầu được nhận, liên kết thuộc về chính actor gửi");
            }
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void parentCannotRequestLinkForStudentOutsideCenter() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        KeycloakLogin outsider = createVerifiedUser("guardian-outsider");
        UUID outsiderId = seedAccount(outsider);
        try (var parent = openSession(center.parent())) {
            // Luồng hợp lệ chạy trước để test không xanh giả khi route chưa tồn tại.
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse response =
                    requestLink(
                            parent,
                            center.workspaceId(),
                            "{\"studentUserId\":\"" + outsiderId + "\"}");
            assertEquals(
                    404,
                    response.getStatus(),
                    "Học sinh không thuộc phạm vi center thì không được tạo liên kết và không lộ"
                            + " sự tồn tại ngoài phạm vi");
        } finally {
            deleteUser(outsider.subject());
            deleteGuardianCenter(center);
        }
    }

    @Test
    void linkListsAreScopedToOwnRequests() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var otherParent = openSession(center.otherParent());
                var manager = openSession(center.manager());
                var teacher = openSession(center.teacher())) {
            requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            requestLinkSucceeded(otherParent, center.workspaceId(), center.studentY());
            for (JsonNode item : listLinks(parent, center.workspaceId())) {
                assertEquals(
                        center.parentId().toString(),
                        item.get("parentUserId").stringValue(),
                        "Phụ huynh chỉ thấy yêu cầu của chính mình");
            }
            for (JsonNode item : listLinks(otherParent, center.workspaceId())) {
                assertEquals(
                        center.otherParentId().toString(),
                        item.get("parentUserId").stringValue(),
                        "Phụ huynh chỉ thấy yêu cầu của chính mình");
            }
            JsonNode managerItems = listLinks(manager, center.workspaceId());
            findLink(managerItems, center.studentX());
            findLink(managerItems, center.studentY());
            MockHttpServletResponse teacherList = getRaw(teacher, linksPath(center.workspaceId()));
            assertEquals(
                    403,
                    teacherList.getStatus(),
                    "Giáo viên không có quyền xem danh sách liên kết phụ huynh");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void studentCannotRequestGuardianLink() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var student = openSession(center.students().get(0))) {
            MockHttpServletResponse response =
                    requestLink(
                            student,
                            center.workspaceId(),
                            "{\"studentUserId\":\"" + center.studentY() + "\"}");
            assertEquals(
                    403, response.getStatus(), "Vai trò học sinh không có scope guardian.request");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void decisionForUnknownLinkIsNotFound() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            // Luồng hợp lệ chạy trước để test không xanh giả khi route chưa tồn tại.
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            MockHttpServletResponse response =
                    decide(manager, center.workspaceId(), UUID.randomUUID(), "APPROVE", 1L);
            assertEquals(
                    404, response.getStatus(), "Quyết định trên liên kết không tồn tại trả 404");
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void staleDecisionVersionConflicts() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            MockHttpServletResponse stale =
                    decide(
                            manager,
                            center.workspaceId(),
                            linkIdOf(link),
                            "APPROVE",
                            link.get("version").longValue() + 100);
            assertEquals(409, stale.getStatus(), "expectedVersion cũ so với row hiện hành trả 409");
            assertEquals(
                    "REQUESTED",
                    linkStateOf(parent, center.workspaceId(), center.studentX()),
                    "Quyết định xung đột không đổi trạng thái liên kết");
            JsonNode current =
                    findLink(listLinks(manager, center.workspaceId()), center.studentX());
            decideSucceeded(manager, center.workspaceId(), current, "APPROVE");
            assertEquals("APPROVED", linkStateOf(parent, center.workspaceId(), center.studentX()));
        } finally {
            deleteGuardianCenter(center);
        }
    }

    @Test
    void revokedLinkBlocksSameSessionFromReadingResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var manager = openSession(center.manager())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            JsonNode link = requestLinkSucceeded(parent, center.workspaceId(), center.studentX());
            decideSucceeded(manager, center.workspaceId(), link, "APPROVE");
            // Phụ huynh đã duyệt mà chưa có kết quả nào cũng nhận 403 (B56), nên lần đọc
            // trước khi gỡ cần một kết quả PUBLISHED thật đã được chiếu.
            UUID publishedId =
                    seedPublishedResult(center, center.workspaceId(), scenario, center.studentX());
            recordResults(center, publishedId);
            MockHttpServletResponse beforeRevoke =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    200,
                    beforeRevoke.getStatus(),
                    "Liên kết đã duyệt thì phụ huynh đọc được kết quả của con");
            revokeSucceeded(manager, center.workspaceId(), linkIdOf(link));
            MockHttpServletResponse afterRevoke =
                    getRaw(parent, resultsPath(center.workspaceId(), center.studentX()));
            assertEquals(
                    403,
                    afterRevoke.getStatus(),
                    "Cùng phiên cũ sau khi liên kết bị gỡ phải bị chặn ngay, không chờ đăng xuất");
        } finally {
            deleteGuardianCenter(center);
        }
    }
}
