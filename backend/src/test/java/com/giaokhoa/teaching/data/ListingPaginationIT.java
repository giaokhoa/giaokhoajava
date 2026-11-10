package com.giaokhoa.teaching.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.InitialCredentialHandoff;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.teaching.data.TeachingRepository.Page;
import com.giaokhoa.tenancy.MembershipPolicyProjector;
import com.giaokhoa.tenancy.MembershipService;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Kết quả phân trang trên PostgreSQL thật; không ràng buộc cú pháp hay số câu SQL. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class ListingPaginationIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "listing_pages_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void dropSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void membershipPageKeepsEveryRoleAndAdvancesPastRevokedCursor() {
        UUID workspace = center();
        UUID previous = member(workspace, 10, "STUDENT");
        UUID first = member(workspace, 11, "MANAGER", "TEACHER");
        UUID second = member(workspace, 12, "STUDENT");
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                previous);
        UUID foreignWorkspace = center();
        UUID foreignCursor = member(foreignWorkspace, 13, "STUDENT");
        MembershipService service =
                new MembershipService(
                        jdbc,
                        mock(IdempotencyService.class),
                        mock(InitialCredentialHandoff.class),
                        mock(MembershipPolicyProjector.class),
                        mock(ResourceMutationCoordinator.class),
                        mock(AuditRecorder.class));

        var page = service.pageMemberships(workspace, previous.toString(), 1);
        assertEquals(
                List.of(first),
                page.items().stream().map(MembershipService.MembershipView::id).toList());
        assertEquals(
                Set.of(MembershipRole.Role.MANAGER, MembershipRole.Role.TEACHER),
                Set.copyOf(page.items().getFirst().roles()));
        assertEquals(first.toString(), page.nextCursor());
        var last = service.pageMemberships(workspace, page.nextCursor(), 1);
        assertEquals(
                List.of(second),
                last.items().stream().map(MembershipService.MembershipView::id).toList());
        assertNull(last.nextCursor());
        for (String cursor :
                List.of("bad", foreignCursor.toString(), UUID.randomUUID().toString())) {
            var empty = service.pageMemberships(workspace, cursor, 1);
            assertTrue(empty.items().isEmpty());
            assertNull(empty.nextCursor());
        }
    }

    @Test
    void classPagesFilterTeacherAndWorkspaceBeforeChoosingThePage() {
        UUID workspace = center();
        UUID teacher = user();
        membership(workspace, UUID.randomUUID(), teacher, "TEACHER");
        schoolClass(workspace, 1);
        UUID first = schoolClass(workspace, 2);
        UUID second = schoolClass(workspace, 3);
        UUID foreignWorkspace = center();
        membership(foreignWorkspace, UUID.randomUUID(), teacher, "TEACHER");
        UUID foreign = schoolClass(foreignWorkspace, 4);
        jdbc.update(
                "INSERT INTO homeroom_assignment(id,workspace_id,class_id,teacher_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                UUID.randomUUID(),
                foreignWorkspace,
                foreign,
                teacher);
        for (UUID classId : List.of(first, second)) {
            jdbc.update(
                    "INSERT INTO homeroom_assignment(id,workspace_id,class_id,teacher_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(),
                    workspace,
                    classId,
                    teacher);
        }
        TeachingRepository repository = new TeachingRepository(jdbc);
        assertPages(
                cursor -> repository.pageClasses(workspace, cursor, 1, teacher),
                TeachingRepository.ClassRow::id,
                List.of(first, second));
        jdbc.update("UPDATE school_class SET state = 'REVOKED' WHERE id = ?", first);
        var next = repository.pageClasses(workspace, first.toString(), 1, teacher);
        assertEquals(
                List.of(second),
                next.items().stream().map(TeachingRepository.ClassRow::id).toList());
        assertNull(next.nextCursor());
        assertTrue(
                repository
                        .pageClasses(workspace, foreign.toString(), 1, teacher)
                        .items()
                        .isEmpty());
        assertTrue(repository.pageClasses(workspace, "bad", 1, teacher).items().isEmpty());
    }

    @Test
    void teachingListsHaveNoMissingOrRepeatedRowsAndRejectForeignParentCursors() {
        UUID workspace = center();
        UUID classId = schoolClass(workspace, 1);
        UUID otherClass = schoolClass(workspace, 2);
        UUID subject = jdbc.queryForObject("SELECT id FROM subject WHERE code = 'M1'", UUID.class);
        TeachingRepository repository = new TeachingRepository(jdbc);
        List<UUID> courses = new ArrayList<>();
        List<UUID> roster = new ArrayList<>();
        List<UUID> enrollments = new ArrayList<>();
        List<UUID> teachers = new ArrayList<>();
        List<UUID> homerooms = new ArrayList<>();
        UUID courseSubject = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            UUID course = id(i);
            jdbc.update(
                    "INSERT INTO course(id,workspace_id,class_id,name,state) VALUES (?,?,?,'Khóa','ACTIVE')",
                    course,
                    workspace,
                    classId);
            courses.add(course);
            if (course.equals(courses.getFirst())) {
                jdbc.update(
                        "INSERT INTO course_subject(id,workspace_id,class_id,course_id,subject_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                        courseSubject,
                        workspace,
                        classId,
                        course,
                        subject);
            }
            UUID student = user();
            membership(workspace, UUID.randomUUID(), student, "STUDENT");
            jdbc.update(
                    "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    id(i),
                    workspace,
                    classId,
                    student);
            roster.add(id(i));
            jdbc.update(
                    "INSERT INTO enrollment(id,workspace_id,class_student_id,course_subject_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    id(i),
                    workspace,
                    id(i),
                    courseSubject);
            enrollments.add(id(i));
            UUID teacher = user();
            membership(workspace, UUID.randomUUID(), teacher, "TEACHER");
            jdbc.update(
                    "INSERT INTO teaching_assignment(id,workspace_id,course_subject_id,teacher_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    id(i),
                    workspace,
                    courseSubject,
                    teacher);
            teachers.add(id(i));
            jdbc.update(
                    "INSERT INTO homeroom_assignment(id,workspace_id,class_id,teacher_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                    id(i),
                    workspace,
                    classId,
                    teacher);
            homerooms.add(id(i));
        }
        UUID otherStudent = user();
        membership(workspace, UUID.randomUUID(), otherStudent, "STUDENT");
        jdbc.update(
                "INSERT INTO course(id,workspace_id,class_id,name,state) VALUES (?,?,?,'Khóa khác','ACTIVE')",
                id(4),
                workspace,
                otherClass);
        jdbc.update(
                "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state) VALUES (?,?,?,?,'ACTIVE')",
                id(4),
                workspace,
                otherClass,
                otherStudent);
        assertPages(
                cursor -> repository.pageCourses(workspace, classId, cursor, 1),
                TeachingRepository.CourseRow::id,
                courses);
        assertPages(
                cursor -> repository.pageRoster(workspace, classId, cursor, 1),
                TeachingRepository.RosterRow::id,
                roster);
        assertPages(
                cursor -> repository.pageEnrollments(workspace, courseSubject, cursor, 1),
                TeachingRepository.EnrollmentListRow::id,
                enrollments);
        assertPages(
                cursor -> repository.pageTeachingAssignments(workspace, courseSubject, cursor, 1),
                TeachingRepository.TeachingAssignmentRow::id,
                teachers);
        assertPages(
                cursor -> repository.pageHomeroomAssignments(workspace, classId, cursor, 1),
                TeachingRepository.HomeroomAssignmentRow::id,
                homerooms);
        assertTrue(
                repository
                        .pageCourses(workspace, otherClass, courses.getFirst().toString(), 1)
                        .items()
                        .isEmpty());
        assertTrue(
                repository
                        .pageRoster(workspace, otherClass, roster.getFirst().toString(), 1)
                        .items()
                        .isEmpty());
    }

    private static <T> void assertPages(
            Function<@Nullable String, Page<T>> read, Function<T, UUID> idOf, List<UUID> expected) {
        List<UUID> actual = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < expected.size(); i++) {
            Page<T> page = read.apply(cursor);
            assertEquals(1, page.items().size());
            UUID row = idOf.apply(page.items().getFirst());
            actual.add(row);
            if (i + 1 < expected.size()) {
                assertEquals(row.toString(), page.nextCursor());
            } else {
                assertNull(page.nextCursor());
            }
            cursor = page.nextCursor();
        }
        assertEquals(expected, actual);
    }

    private UUID center() {
        UUID owner = user();
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace(id,kind,center_id,state) VALUES (?,'CENTER',?,'ACTIVE')",
                workspace,
                center);
        jdbc.update(
                "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,owner_user_id,state) VALUES (?,?,?,'Trung tâm',?,'ACTIVE')",
                center,
                workspace,
                center.toString(),
                owner);
        UUID ownerMembership = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at) VALUES (?,?,?,'ACTIVE','2025-01-01T00:00:00Z')",
                ownerMembership,
                workspace,
                owner);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')",
                ownerMembership);
        return workspace;
    }

    private UUID user() {
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,origin,identity_state) VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                user,
                user.toString(),
                user.toString(),
                user + "@example.test");
        return user;
    }

    private UUID member(UUID workspace, int number, String... roles) {
        UUID membership = id(number);
        membership(workspace, membership, user(), roles);
        return membership;
    }

    private void membership(UUID workspace, UUID membership, UUID user, String... roles) {
        jdbc.update(
                "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at) VALUES (?,?,?,'ACTIVE','2026-01-01T00:00:00Z')",
                membership,
                workspace,
                user);
        for (String role : roles) {
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                    membership,
                    role);
        }
    }

    private UUID schoolClass(UUID workspace, int number) {
        UUID classId = id(number);
        jdbc.update(
                "INSERT INTO school_class(id,workspace_id,name,state) VALUES (?,?,?,'ACTIVE')",
                classId,
                workspace,
                "Lớp " + number);
        return classId;
    }

    private static UUID id(int number) {
        return new UUID(0, number);
    }
}
