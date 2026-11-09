package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.CenterService;
import com.giaokhoa.tenancy.InvitationService;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Kiểm tra dữ kiện và desired hash; HTTP/PDP thật được kiểm chứng riêng ở TeachingAccessIT. */
@SpringBootTest
class TeachingLifecycleIT extends TeachingFixture {
    private static final String SCHEMA =
            "teaching_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    @MockitoBean private ProjectionWorker worker;
    @Autowired private ClassService classes;
    @Autowired private CourseService courses;
    @Autowired private EnrollmentService enrollments;
    @Autowired private CenterService centers;
    @Autowired private TeachingPolicyProjector projector;
    @Autowired private SourceRevocationCoordinator revocations;
    @Autowired private InvitationService invitations;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup(@Autowired ApplicationContext context) {
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Test
    void revokedCourseCannotReceiveNewEnrollments() {
        Setup setup = setup();
        courses.revokeCourse(setup.workspace(), setup.course());
        assertThrows(
                HttpProblemException.class,
                () -> enroll(setup),
                "A revoked course must reject new enrollment even when its course_subject remains ACTIVE");
    }

    @Test
    void revokedClassCannotReceiveNewSubjectsThroughActiveCourse() {
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

    @Test
    void acceptingFirstManagerInvitationRefreshesExistingTeachingBundles() {
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
