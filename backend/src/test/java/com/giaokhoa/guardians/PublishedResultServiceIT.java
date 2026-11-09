package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.ResultPolicyProjector;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm chứng tầng service của phía đọc kết quả công bố: {@link ResultPolicyProjector} chiếu bundle
 * của từng kết quả (publication gate chỉ nối {@code read.published} vào kết quả PUBLISHED, nguồn
 * phụ huynh là descriptor canonical {@code GUARDIAN_GROUP}) và {@link PublishedResultService} hỏi
 * PDP cho từng hàng trước khi trả DTO. Liên kết phụ huynh và group của nó được seed ở tầng dữ kiện
 * bền đúng hình dạng mà tầng liên kết và phép đồng bộ group của nó bảo đảm (link APPROVED, cả hai
 * phía còn membership/role hiện hành, parent nằm trong guardian group của đúng học sinh), vì route
 * liên kết thuộc task phía sau.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublishedResultServiceIT extends GuardianFixture {
    private static final String CLASS_NAME = "Lớp kết quả service";
    private static final String COURSE_NAME = "Khóa kết quả service";

    @Autowired private PublishedResultService results;
    @Autowired private ResultPolicyProjector resultProjector;
    @Autowired private KeycloakProvisioningClient directory;
    @Autowired private IdempotencyService idempotency;

    private record Scenario(UUID classId, UUID courseSubjectId, SubjectRef subject) {}

    private Scenario prepareScenario(RealSession owner, GuardianCenter center, UUID workspaceId)
            throws Exception {
        UUID classId = createClass(owner, workspaceId, CLASS_NAME);
        SubjectRef subject = firstSubject(owner);
        UUID courseId =
                createCourse(owner, workspaceId, classId, COURSE_NAME, List.of(subject.id()));
        UUID courseSubjectId = courseSubjectId(courseId, subject.id());
        List<UUID> roster = addRoster(owner, workspaceId, classId, center.studentIds());
        enroll(owner, workspaceId, roster, courseSubjectId);
        return new Scenario(classId, courseSubjectId, subject);
    }

    /** Seed một hoạt động và một bài nộp của học sinh; trả về id của sample_submission. */
    private UUID seedSubmission(
            GuardianCenter center, UUID workspaceId, Scenario scenario, UUID studentUserId) {
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
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
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,"
                            + "resource_id,version_id,assigned_by,assigned_at,due_at,"
                            + "attempt_limit,subscription_period_id,state)"
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
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,"
                            + "student_user_id,attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,?,'SUBMITTED')",
                    submissionId,
                    workspaceId,
                    activityId,
                    studentUserId,
                    now,
                    "Bài làm mẫu");
            return null;
        });
        return submissionId;
    }

    /** Seed một kết quả mẫu trên một bài nộp đã có; trả về id của sample_result. */
    private UUID seedResult(
            GuardianCenter center,
            UUID workspaceId,
            Scenario scenario,
            UUID studentUserId,
            UUID submissionId,
            String state,
            @Nullable String score,
            @Nullable String comment) {
        UUID resultId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tx(() -> {
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

    /** Seed một guardian link ở trạng thái cho trước, đúng cột data-model. */
    private UUID seedLink(
            UUID workspaceId, UUID parentUserId, UUID studentUserId, String state, UUID decidedBy) {
        UUID linkId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO guardian_link(id,workspace_id,parent_user_id,student_user_id,"
                            + "state,requested_by,approved_by,approved_at,revoked_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    linkId,
                    workspaceId,
                    parentUserId,
                    studentUserId,
                    state,
                    parentUserId,
                    "APPROVED".equals(state) || "REVOKED".equals(state) ? decidedBy : null,
                    "APPROVED".equals(state) || "REVOKED".equals(state) ? now : null,
                    "REVOKED".equals(state) ? now : null);
            return null;
        });
        return linkId;
    }

    /**
     * Đưa parent vào guardian group của học sinh trong realm, đúng trạng thái mà phép đồng bộ group
     * của liên kết bảo đảm cho một link APPROVED còn hiệu lực ở cả hai phía.
     */
    private void putParentInGuardianGroup(GuardianCenter center, UUID studentUserId) {
        directory.setRealmGroupMembership(
                center.parent().subject(),
                NativePolicyTemplates.guardiansGroup(center.workspaceId(), studentUserId),
                true);
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

    /** Personal workspace của một tài khoản đã seed (mỗi tài khoản có đúng một workspace này). */
    private UUID personalWorkspaceOf(UUID accountId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        accountId),
                "Tài khoản phải có personal workspace");
    }

    /** Gọi service bằng đúng phiên Spring thật của user, qua session filter như request thật. */
    private PublishedResultService.ResultPage pageAs(
            RealSession session,
            UUID workspaceId,
            UUID studentId,
            @Nullable String cursor,
            int limit)
            throws Exception {
        var raw = new MockHttpServletRequest("GET", "/");
        raw.setCookies(session.cookie());
        var out = new AtomicReference<PublishedResultService.ResultPage>();
        var failure = new AtomicReference<HttpProblemException>();
        sessionFilter.doFilter(
                raw,
                new MockHttpServletResponse(),
                (wrapped, _) -> {
                    var request = (HttpServletRequest) wrapped;
                    var httpSession = Objects.requireNonNull(request.getSession(false));
                    var context =
                            (SecurityContext)
                                    httpSession.getAttribute(
                                            HttpSessionSecurityContextRepository
                                                    .SPRING_SECURITY_CONTEXT_KEY);
                    try {
                        out.set(
                                results.pagePublishedResults(
                                        Objects.requireNonNull(
                                                Objects.requireNonNull(context)
                                                        .getAuthentication()),
                                        request,
                                        workspaceId,
                                        studentId,
                                        cursor,
                                        limit));
                    } catch (HttpProblemException ex) {
                        // Lỗi nghiệp vụ của service (403/503) được chuyển ra ngoài filter chain
                        // để test khẳng định trên đúng trạng thái của nó.
                        failure.set(ex);
                    }
                });
        if (failure.get() != null) {
            throw failure.get();
        }
        return Objects.requireNonNull(out.get(), "service result");
    }

    private List<PublishedResultView> listAs(RealSession session, UUID workspaceId, UUID studentId)
            throws Exception {
        return pageAs(session, workspaceId, studentId, null, 100).items();
    }

    private void assertDenied(RealSession session, UUID workspaceId, UUID studentId)
            throws Exception {
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class, () -> listAs(session, workspaceId, studentId));
        assertEquals(
                HttpStatus.FORBIDDEN,
                problem.getStatus(),
                "Actor không có hàng nào được phép thì bị 403, không phải danh sách rỗng");
    }

    @Test
    void approvedParentReadsOnlyPublishedResultsOfOwnChild() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            seedLink(
                    center.workspaceId(),
                    center.parentId(),
                    center.studentX(),
                    "APPROVED",
                    center.managerId());
            putParentInGuardianGroup(center, center.studentX());
            UUID submissionX =
                    seedSubmission(center, center.workspaceId(), scenario, center.studentX());
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            submissionX,
                            "PUBLISHED",
                            "9.0",
                            "Làm tốt");
            // Hai kết quả PUBLISHED trên cùng một bài nộp: DB không unique theo submission nên
            // phía đọc trả cả hai hàng đã công bố, không tự gộp thành một.
            UUID secondPublishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            submissionX,
                            "PUBLISHED",
                            "8.0",
                            null);
            UUID draftId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentX()),
                            "DRAFT",
                            "3.0",
                            null);
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentY(),
                    seedSubmission(center, center.workspaceId(), scenario, center.studentY()),
                    "PUBLISHED",
                    "7.5",
                    "Của học sinh khác");
            recordResults(center, publishedId, secondPublishedId, draftId);

            String draftKey = NativePolicyTemplates.resultKey(center.workspaceId(), draftId);
            assertTrue(
                    resultProjector.sourcesFor(draftKey, "read.published").isEmpty(),
                    "Kết quả DRAFT không có nguồn nào ở scope read.published");
            String publishedKey =
                    NativePolicyTemplates.resultKey(center.workspaceId(), publishedId);
            assertTrue(
                    resultProjector
                            .bundleDescriptors(publishedKey)
                            .contains(
                                    "GUARDIAN_GROUP|workspace="
                                            + center.workspaceId()
                                            + "|group="
                                            + NativePolicyTemplates.guardiansGroup(
                                                    center.workspaceId(), center.studentX())
                                            + "|target="
                                            + publishedKey
                                            + "|scope=read.published"),
                    "Bundle của kết quả đã công bố mang descriptor GUARDIAN_GROUP canonical");

            List<PublishedResultView> views =
                    listAs(parent, center.workspaceId(), center.studentX());
            assertEquals(2, views.size(), "Phụ huynh chỉ thấy hai kết quả PUBLISHED của đúng con");
            var page1 = pageAs(parent, center.workspaceId(), center.studentX(), null, 1);
            assertEquals(1, page1.items().size());
            String nextCursor = Objects.requireNonNull(page1.nextCursor());
            var page2 = pageAs(parent, center.workspaceId(), center.studentX(), nextCursor, 1);
            assertEquals(1, page2.items().size());
            assertNull(page2.nextCursor());
            assertNotEquals(page1.items().getFirst().id(), page2.items().getFirst().id());
            assertTrue(views.stream().anyMatch(v -> v.id().equals(page1.items().getFirst().id())));
            assertTrue(views.stream().anyMatch(v -> v.id().equals(page2.items().getFirst().id())));
            var crossStudent =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    pageAs(
                                            parent,
                                            center.workspaceId(),
                                            center.studentY(),
                                            nextCursor,
                                            1));
            assertEquals(HttpStatus.BAD_REQUEST, crossStudent.getStatus());
            var changedLimit =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    pageAs(
                                            parent,
                                            center.workspaceId(),
                                            center.studentX(),
                                            nextCursor,
                                            2));
            assertEquals(HttpStatus.BAD_REQUEST, changedLimit.getStatus());
            PublishedResultView first =
                    views.stream()
                            .filter(view -> view.id().equals(publishedId))
                            .findFirst()
                            .orElseThrow();
            assertEquals(center.studentX(), first.student().id());
            assertFalse(first.student().displayName().isBlank());
            assertEquals(CLASS_NAME, first.className());
            assertEquals(COURSE_NAME, first.courseName());
            assertEquals(scenario.subject().name(), first.subjectName());
            assertEquals("9.0", first.score());
            assertEquals("Làm tốt", first.comment());
            assertNotNull(first.publishedAt());
        }
    }

    @Test
    void parentWithoutApprovedLinkCannotReadChildResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var otherParent = openSession(center.otherParent())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentX()),
                            "PUBLISHED",
                            "9.0",
                            "Làm tốt");
            recordResults(center, publishedId);
            assertDenied(parent, center.workspaceId(), center.studentX());
            seedLink(
                    center.workspaceId(),
                    center.parentId(),
                    center.studentX(),
                    "REQUESTED",
                    center.managerId());
            recordResults(center, publishedId);
            assertDenied(parent, center.workspaceId(), center.studentX());
            assertDenied(otherParent, center.workspaceId(), center.studentX());
        }
    }

    @Test
    void studentAndSubjectTeacherReadButManagerAndHomeroomDoNot() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var student = openSession(center.students().get(0));
                var teacher = openSession(center.teacher());
                var manager = openSession(center.manager());
                var homeroom = openSession(center.homeroom())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            assignTeacher(
                    owner, center.workspaceId(), scenario.courseSubjectId(), center.teacherId());
            assignHomeroom(owner, center.workspaceId(), scenario.classId(), center.homeroomId());
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentX()),
                            "PUBLISHED",
                            "8.5",
                            "Tốt");
            seedResult(
                    center,
                    center.workspaceId(),
                    scenario,
                    center.studentX(),
                    seedSubmission(center, center.workspaceId(), scenario, center.studentX()),
                    "DRAFT",
                    "2.0",
                    null);
            UUID otherPublishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentY(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentY()),
                            "PUBLISHED",
                            "7.0",
                            "Của học sinh khác");
            recordResults(center, publishedId, otherPublishedId);

            List<PublishedResultView> own =
                    listAs(student, center.workspaceId(), center.studentX());
            assertEquals(1, own.size(), "Học sinh chỉ thấy kết quả đã công bố của chính mình");
            assertEquals(publishedId, own.getFirst().id());
            assertDenied(student, center.workspaceId(), center.studentY());

            List<PublishedResultView> byTeacher =
                    listAs(teacher, center.workspaceId(), center.studentX());
            assertEquals(
                    1, byTeacher.size(), "Giáo viên đúng môn–khóa đọc được kết quả đã công bố");
            assertDenied(manager, center.workspaceId(), center.studentX());
            assertDenied(homeroom, center.workspaceId(), center.studentX());
        }
    }

    @Test
    void revokedLinkStopsParentFromReadingResults() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            UUID linkId =
                    seedLink(
                            center.workspaceId(),
                            center.parentId(),
                            center.studentX(),
                            "APPROVED",
                            center.managerId());
            putParentInGuardianGroup(center, center.studentX());
            UUID publishedId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentX()),
                            "PUBLISHED",
                            "9.5",
                            "Xuất sắc");
            recordResults(center, publishedId);
            assertEquals(
                    1,
                    listAs(parent, center.workspaceId(), center.studentX()).size(),
                    "Trước khi gỡ, phụ huynh thấy kết quả đã công bố của con");

            tx(() -> {
                jdbc.update(
                        "UPDATE guardian_link SET state = 'REVOKED', revoked_at = now()"
                                + " WHERE id = ?",
                        linkId);
                return null;
            });
            directory.setRealmGroupMembership(
                    center.parent().subject(),
                    NativePolicyTemplates.guardiansGroup(center.workspaceId(), center.studentX()),
                    false);
            recordResults(center, publishedId);
            assertDenied(parent, center.workspaceId(), center.studentX());
        }
    }

    @Test
    void approvedParentTeacherGet403AndStudentGetsEmptyListWhenNothingPublished() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var parent = openSession(center.parent());
                var student = openSession(center.students().get(0));
                var teacher = openSession(center.teacher())) {
            Scenario scenario = prepareScenario(owner, center, center.workspaceId());
            assignTeacher(
                    owner, center.workspaceId(), scenario.courseSubjectId(), center.teacherId());
            seedLink(
                    center.workspaceId(),
                    center.parentId(),
                    center.studentX(),
                    "APPROVED",
                    center.managerId());
            putParentInGuardianGroup(center, center.studentX());
            // Chỉ có kết quả DRAFT: không ứng viên PUBLISHED nào nên nhánh đọc rỗng của B55
            // phải chạy — phụ huynh đã duyệt và giáo viên đúng môn–khóa đều nhận 403.
            UUID draftId =
                    seedResult(
                            center,
                            center.workspaceId(),
                            scenario,
                            center.studentX(),
                            seedSubmission(
                                    center, center.workspaceId(), scenario, center.studentX()),
                            "DRAFT",
                            "3.0",
                            null);
            recordResults(center, draftId);

            assertDenied(parent, center.workspaceId(), center.studentX());
            assertDenied(teacher, center.workspaceId(), center.studentX());
            assertEquals(
                    0,
                    listAs(student, center.workspaceId(), center.studentX()).size(),
                    "Học sinh tự đọc khi chưa có kết quả công bố nào thì được danh sách rỗng");
        }
    }

    @Test
    void personalOwnerReadsPublishedResultButOutsiderCannot() throws Exception {
        GuardianCenter center = seedGuardianCenter();
        try (var owner = openSession(center.owner());
                var student = openSession(center.students().get(0));
                var outsider = openSession(center.otherParent())) {
            UUID personalWid = personalWorkspaceOf(center.ownerId());
            // Personal chưa có kỳ gói: seed một kỳ để seedSubmission có subscription_period_id.
            seedSubscription(personalWid, center.ownerId());
            Scenario scenario = prepareScenario(owner, center, personalWid);
            UUID publishedId =
                    seedResult(
                            center,
                            personalWid,
                            scenario,
                            center.studentX(),
                            seedSubmission(center, personalWid, scenario, center.studentX()),
                            "PUBLISHED",
                            "9.0",
                            "Làm tốt");
            recordResults(personalWid, center.ownerId(), publishedId);

            String publishedKey = NativePolicyTemplates.resultKey(personalWid, publishedId);
            assertTrue(
                    resultProjector
                            .sourcesFor(publishedKey, "read.published")
                            .contains(
                                    "RESULT_ACTOR|workspace="
                                            + personalWid
                                            + "|user="
                                            + center.ownerId()
                                            + "|subject="
                                            + center.owner().subject()
                                            + "|target="
                                            + publishedKey
                                            + "|scope=read.published"),
                    "Bundle của kết quả đã công bố trong personal mang descriptor actor của owner");

            List<PublishedResultView> own = listAs(owner, personalWid, center.studentX());
            assertEquals(1, own.size(), "Owner của personal đọc được kết quả đã công bố");
            assertEquals(publishedId, own.getFirst().id());
            List<PublishedResultView> byStudent = listAs(student, personalWid, center.studentX());
            assertEquals(
                    1,
                    byStudent.size(),
                    "Học sinh còn ghi danh trong personal đọc được kết quả của chính mình");
            assertDenied(outsider, personalWid, center.studentX());
        }
    }
}
