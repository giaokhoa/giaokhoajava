package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.service.ClassService;
import com.giaokhoa.teaching.service.CourseService;
import com.giaokhoa.teaching.service.EnrollmentService;
import com.giaokhoa.teaching.service.EnrollmentService.CompletionExpected;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.service.CenterService;
import com.giaokhoa.tenancy.service.InvitationService;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Kiểm tra dữ kiện và desired hash; HTTP/PDP thật được kiểm chứng riêng ở TeachingAccessE2EIT. */
@SpringBootTest
class TeachingLifecycleIT extends TeachingFixture {
    @MockitoBean private ProjectionWorker scheduledWorker;
    @Autowired private ClassService classes;
    @Autowired private CourseService courses;
    @Autowired private EnrollmentService enrollments;
    @Autowired private CenterService centers;
    @Autowired private TeachingPolicyProjector projector;
    @Autowired private SourceRevocationCoordinator revocations;
    @Autowired private InvitationService invitations;
    @Autowired private ResultPolicyProjector resultProjector;

    @Test
    void revokedCourseCannotReceiveNewEnrollments() {
        Setup setup = setup();
        courses.revokeCourse(setup.workspace(), setup.course());
        assertThrows(
                HttpProblemException.class,
                () -> enroll(setup),
                "A revoked course must reject new enrollment even when its course_subject remains ACTIVE");
    }

    @DisplayName("Lớp đã revoke không nhận thêm môn mới thông qua course còn ACTIVE")
    @Test
    void revokedClassGetsNoNewSubjects() {
        Setup setup = setup();
        classes.revokeClass(setup.workspace(), setup.schoolClass());
        UUID otherSubject =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subject WHERE id <> ? LIMIT 1",
                                UUID.class,
                                setup.subject()));
        assertThrows(
                HttpProblemException.class,
                () -> courses.addSubjects(setup.workspace(), setup.course(), List.of(otherSubject)),
                "Revoked parent class must block expansion through an existing child course");
    }

    @Test
    void enrollmentRequiresCurrentStudentRole() {
        Setup setup = setup();
        centers.replaceRoles(
                setup.owner(),
                setup.workspace(),
                setup.membership(),
                Set.of(MembershipRole.Role.TEACHER),
                "\"0\"",
                UUID.randomUUID().toString(),
                "a".repeat(64));
        assertThrows(
                HttpProblemException.class,
                () -> enroll(setup),
                "Removing STUDENT must prevent new enrollment while roster is retained");
    }

    @DisplayName("Chấp nhận lời mời manager đầu tiên làm mới các teaching bundle hiện có")
    @Test
    void firstManagerAcceptRefreshesBundles() {
        Setup setup = setup();
        String target = NativePolicyTemplates.classKey(setup.workspace(), setup.schoolClass());
        tx(() ->
                revocations.recordTeachingRevocation(
                        setup.owner(),
                        setup.workspace(),
                        UUID.randomUUID().toString(),
                        "d".repeat(64),
                        List.of(target)));
        UUID manager = seedAccount("manager-" + UUID.randomUUID(), "CENTER_ISSUED", null, false);
        var invitation =
                invitations.invite(
                        setup.owner(),
                        setup.workspace(),
                        null,
                        manager,
                        Set.of(MembershipRole.Role.MANAGER),
                        UUID.randomUUID().toString(),
                        "e".repeat(64));
        invitations.accept(
                manager,
                Objects.requireNonNull(invitation.token()),
                UUID.randomUUID().toString(),
                "f".repeat(64));
        String expected =
                ProjectionReconciler.desiredHash(
                        target,
                        TeachingPolicyProjector.bundleScope(),
                        projector.bundleDescriptors(target));
        String recorded =
                jdbc.queryForObject(
                        "SELECT desired_hash FROM authz_projection WHERE target_key = ?",
                        String.class,
                        target);
        assertEquals(
                expected,
                recorded,
                "Accepting the first manager changes all teaching bundles; recorded desired hashes must be refreshed");
    }

    @Test
    @DisplayName(
            "Center: 3 môn cùng COMPLETED trong một operation, không hoàn thành phần hoặc tái ghi danh")
    void completeAllSubjectsAtomicallyAtCenter() {
        Setup setup = setup();
        List<UUID> others =
                jdbc.queryForList(
                        "SELECT id FROM subject WHERE id <> ? ORDER BY code LIMIT 2",
                        UUID.class,
                        setup.subject());
        assertEquals(2, others.size());
        courses.addSubjects(setup.workspace(), setup.course(), others);
        List<UUID> subjects = courseSubjectsOf(setup.workspace(), setup.course());
        assertEquals(3, subjects.size());
        enrollments.enrollBatch(
                setup.owner(),
                setup.workspace(),
                List.of(setup.roster()),
                subjects,
                UUID.randomUUID().toString(),
                "a".repeat(64));
        UUID student = studentOfRoster(setup.roster());
        List<CompletionExpected> snapshot =
                completionSnapshot(setup.workspace(), setup.course(), student);
        assertEquals(3, snapshot.size());
        // Published result được seed làm tiền đề; chỉ mutation hoàn thành/thu hồi đi qua service.
        UUID resultId =
                seedPublishedResult(setup.workspace(), setup.owner(), student, subjects.getFirst());
        String resultKey = NativePolicyTemplates.resultKey(setup.workspace(), resultId);
        assertPublishedStudentSource(resultKey, student, true);

        HttpProblemException missing =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.completeStudentCourse(
                                        setup.owner(),
                                        setup.workspace(),
                                        setup.course(),
                                        student,
                                        snapshot.subList(0, 2),
                                        UUID.randomUUID().toString(),
                                        "b".repeat(64)));
        assertEquals(HttpStatus.CONFLICT, missing.getStatus());
        assertEquals(3, activeEnrollments(setup.workspace(), setup.course(), student));
        List<CompletionExpected> stale = new ArrayList<>(snapshot);
        CompletionExpected first = stale.getFirst();
        stale.set(
                0,
                new CompletionExpected(
                        first.id(), Objects.requireNonNull(first.expectedVersion()) + 1));
        HttpProblemException versionMismatch =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.completeStudentCourse(
                                        setup.owner(),
                                        setup.workspace(),
                                        setup.course(),
                                        student,
                                        stale,
                                        UUID.randomUUID().toString(),
                                        "c".repeat(64)));
        assertEquals(HttpStatus.CONFLICT, versionMismatch.getStatus());
        HttpProblemException noPrecondition =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.completeStudentCourse(
                                        setup.owner(),
                                        setup.workspace(),
                                        setup.course(),
                                        student,
                                        List.of(),
                                        UUID.randomUUID().toString(),
                                        "d".repeat(64)));
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, noPrecondition.getStatus());
        assertEquals(3, activeEnrollments(setup.workspace(), setup.course(), student));

        var operation =
                enrollments.completeStudentCourse(
                        setup.owner(),
                        setup.workspace(),
                        setup.course(),
                        student,
                        snapshot,
                        UUID.randomUUID().toString(),
                        "e".repeat(64));
        assertNotNull(operation.getId());
        assertEquals(0, activeEnrollments(setup.workspace(), setup.course(), student));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment e JOIN course_subject cs"
                                + " ON cs.id = e.course_subject_id JOIN class_student roster"
                                + " ON roster.id = e.class_student_id"
                                + " WHERE e.workspace_id = ? AND cs.course_id = ?"
                                + " AND roster.student_user_id = ? AND e.state = 'COMPLETED'",
                        Integer.class,
                        setup.workspace(),
                        setup.course(),
                        student));
        assertPublishedStudentSource(resultKey, student, true);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(DISTINCT completed_at) FROM enrollment e"
                                + " JOIN course_subject cs ON cs.id = e.course_subject_id"
                                + " JOIN class_student roster ON roster.id = e.class_student_id"
                                + " WHERE e.workspace_id = ? AND cs.course_id = ?"
                                + " AND roster.student_user_id = ? AND e.state = 'COMPLETED'",
                        Integer.class,
                        setup.workspace(),
                        setup.course(),
                        student));
        HttpProblemException noReenroll =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.enrollBatch(
                                        setup.owner(),
                                        setup.workspace(),
                                        List.of(setup.roster()),
                                        subjects,
                                        UUID.randomUUID().toString(),
                                        "f".repeat(64)));
        assertEquals(HttpStatus.CONFLICT, noReenroll.getStatus());
        UUID completedEnrollmentId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM enrollment WHERE workspace_id = ?"
                                        + " AND course_subject_id = ? AND state = 'COMPLETED'",
                                UUID.class,
                                setup.workspace(),
                                subjects.getFirst()));
        enrollments.revokeEnrollment(
                setup.owner(),
                setup.workspace(),
                completedEnrollmentId,
                UUID.randomUUID().toString(),
                "c".repeat(64));
        assertPublishedStudentSource(resultKey, student, false);
        assertEquals(
                "PUBLISHED",
                jdbc.queryForObject(
                        "SELECT state FROM sample_result WHERE id = ?", String.class, resultId));
    }

    @Test
    @DisplayName("Personal: owner hoàn tất trọn khóa 3 môn, học sinh hết quyền ghi danh mới")
    void completeThreeSubjectsAtPersonal() {
        UUID owner =
                seedAccount("personal-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID student =
                seedAccount(
                        "personal-student-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID workspace =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                                UUID.class,
                                owner));
        seedSubscription(workspace, -1, 30, owner);
        UUID classId = classes.createClass(workspace, "Lớp cá nhân hoàn thành").id();
        List<UUID> subjectIds =
                jdbc.queryForList("SELECT id FROM subject ORDER BY code LIMIT 3", UUID.class);
        assertEquals(3, subjectIds.size());
        var course = courses.createCourse(workspace, classId, "Khóa cá nhân", subjectIds);
        UUID roster = classes.addStudents(workspace, classId, List.of(student)).getFirst().id();
        List<UUID> subjects =
                course.subjects().stream().map(CourseService.SubjectEntry::id).toList();
        enrollments.enrollBatch(
                owner,
                workspace,
                List.of(roster),
                subjects,
                UUID.randomUUID().toString(),
                "a".repeat(64));
        List<CompletionExpected> snapshot = completionSnapshot(workspace, course.id(), student);
        assertEquals(3, snapshot.size());
        enrollments.completeStudentCourse(
                owner,
                workspace,
                course.id(),
                student,
                snapshot,
                UUID.randomUUID().toString(),
                "b".repeat(64));
        assertEquals(0, activeEnrollments(workspace, course.id(), student));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment e JOIN course_subject cs"
                                + " ON cs.id = e.course_subject_id WHERE e.workspace_id = ?"
                                + " AND cs.course_id = ? AND e.state = 'COMPLETED'",
                        Integer.class,
                        workspace,
                        course.id()));
    }

    private void assertPublishedStudentSource(String resultKey, UUID studentId, boolean expected) {
        String scope = PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName();
        boolean exists =
                resultProjector.sourcesFor(resultKey, scope).stream()
                        .anyMatch(descriptor -> descriptor.contains("|user=" + studentId + "|"));
        assertEquals(
                expected,
                exists,
                "Hoàn thành giữ actor published.history, thu hồi riêng chặn actor mà giữ dữ liệu");
    }

    private UUID seedPublishedResult(
            UUID workspaceId, UUID owner, UUID student, UUID courseSubjectId) {
        UUID subjectId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT subject_id FROM course_subject WHERE id = ?",
                                UUID.class,
                                courseSubjectId));
        UUID periodId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subscription_period WHERE workspace_id = ? LIMIT 1",
                                UUID.class,
                                workspaceId));
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource (id, owner_workspace_id, created_by_user_id, kind,"
                        + " subject_id, state) VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                resourceId,
                workspaceId,
                owner,
                subjectId);
        jdbc.update(
                "INSERT INTO resource_version (id, resource_id, version_no, label, body_stub,"
                        + " answer_stub, review_state) VALUES (?, ?, 1, 'Hoàn thành', 'Bài',"
                        + " 'Đáp án', 'APPROVED')",
                versionId,
                resourceId);
        jdbc.update(
                "INSERT INTO sample_activity (id, workspace_id, course_subject_id, resource_id,"
                        + " version_id, assigned_by, assigned_at, due_at, attempt_limit,"
                        + " subscription_period_id, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'ASSIGNED')",
                activityId,
                workspaceId,
                courseSubjectId,
                resourceId,
                versionId,
                owner,
                java.sql.Timestamp.from(Instant.now().minusSeconds(300)),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                periodId);
        jdbc.update(
                "INSERT INTO sample_submission (id, workspace_id, activity_id, student_user_id,"
                        + " attempt_no, submitted_at, content_stub, state)"
                        + " VALUES (?, ?, ?, ?, 1, now(), 'Bài hoàn tất', 'SUBMITTED')",
                submissionId,
                workspaceId,
                activityId,
                student);
        jdbc.update(
                "INSERT INTO sample_result (id, workspace_id, submission_id, student_user_id,"
                        + " course_subject_id, score_stub, comment_stub, state, published_at, graded_by)"
                        + " VALUES (?, ?, ?, ?, ?, '8', 'Nhận xét', 'PUBLISHED', now(), ?)",
                resultId,
                workspaceId,
                submissionId,
                student,
                courseSubjectId,
                owner);
        return resultId;
    }

    private List<UUID> courseSubjectsOf(UUID workspaceId, UUID courseId) {
        return jdbc.queryForList(
                "SELECT id FROM course_subject WHERE workspace_id = ? AND course_id = ?"
                        + " AND state = 'ACTIVE' ORDER BY id",
                UUID.class,
                workspaceId,
                courseId);
    }

    private UUID studentOfRoster(UUID rosterId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT student_user_id FROM class_student WHERE id = ?",
                        UUID.class,
                        rosterId));
    }

    private List<CompletionExpected> completionSnapshot(
            UUID workspaceId, UUID courseId, UUID student) {
        return jdbc.query(
                "SELECT e.id, e.version FROM enrollment e JOIN course_subject cs"
                        + " ON cs.id = e.course_subject_id JOIN class_student roster"
                        + " ON roster.id = e.class_student_id"
                        + " WHERE e.workspace_id = ? AND cs.course_id = ?"
                        + " AND roster.student_user_id = ? AND e.state = 'ACTIVE' ORDER BY e.id",
                (rs, _row) ->
                        new CompletionExpected((UUID) rs.getObject("id"), rs.getLong("version")),
                workspaceId,
                courseId,
                student);
    }

    private int activeEnrollments(UUID workspaceId, UUID courseId, UUID student) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment e JOIN course_subject cs"
                                + " ON cs.id = e.course_subject_id JOIN class_student roster"
                                + " ON roster.id = e.class_student_id"
                                + " WHERE e.workspace_id = ? AND cs.course_id = ?"
                                + " AND roster.student_user_id = ? AND e.state IN ('ACTIVE', 'PENDING')",
                        Integer.class,
                        workspaceId,
                        courseId,
                        student));
    }

    private Setup setup() {
        UUID owner = seedAccount("owner-" + UUID.randomUUID(), "CENTER_ISSUED", null, false);
        UUID student = seedAccount("student-" + UUID.randomUUID(), "CENTER_ISSUED", null, false);
        UUID workspace = seedCenter(owner);
        UUID membership = addMembership(workspace, student, "STUDENT");
        seedSubscription(workspace, -1, 30, owner);
        UUID schoolClass = classes.createClass(workspace, "Review class").id();
        UUID subject =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subject ORDER BY code LIMIT 1", UUID.class));
        var course =
                courses.createCourse(workspace, schoolClass, "Review course", List.of(subject));
        UUID roster = classes.addStudents(workspace, schoolClass, List.of(student)).getFirst().id();
        return new Setup(
                owner,
                workspace,
                schoolClass,
                course.id(),
                subject,
                course.subjects().getFirst().id(),
                roster,
                membership);
    }

    private void enroll(Setup setup) {
        enrollments.enrollBatch(
                setup.owner(),
                setup.workspace(),
                List.of(setup.roster()),
                List.of(setup.courseSubject()),
                UUID.randomUUID().toString(),
                "c".repeat(64));
    }

    private record Setup(
            UUID owner,
            UUID workspace,
            UUID schoolClass,
            UUID course,
            UUID subject,
            UUID courseSubject,
            UUID roster,
            UUID membership) {}
}
