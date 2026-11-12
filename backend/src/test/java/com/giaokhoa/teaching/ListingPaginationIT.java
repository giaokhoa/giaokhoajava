package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.service.InitialCredentialService;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository.Page;
import com.giaokhoa.tenancy.MembershipPolicyProjector;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.service.MembershipService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Kết quả phân trang trên PostgreSQL thật; không ràng buộc cú pháp hay số câu SQL. */
// Harness tối thiểu: class tự dựng repository JPA và service bằng mock thường, không cần bean
// nào của ứng dụng nên tự chạy Flyway và tự cấp DataSource trên schema riêng, không dựng
// ApplicationContext. Mọi test chạy trong một transaction JPA bao ngoài (JdbcTemplate dùng
// chung connection của transaction đó) và rollback ở cuối như khi dùng @Transactional của
// Spring Test.
class ListingPaginationIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "listing_pages_" + UUID.randomUUID().toString().replace("-", "");

    private static JdbcTemplate jdbc;
    private static JdbcTemplate surgery;
    private static RuntimeJpaRepositories repositories;
    private static PlatformTransactionManager txManager;
    private TransactionStatus txStatus;

    @BeforeAll
    static void migrateSchema() {
        var migrator =
                new DriverManagerDataSource(
                        testDatabaseUrl(),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_MIGRATOR_PASSWORD")));
        Flyway.configure()
                .dataSource(migrator)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .load()
                .migrate();
        surgery = new JdbcTemplate(migrator);
        var source =
                new DriverManagerDataSource(
                        testDatabaseUrl() + "?currentSchema=" + SCHEMA + ",public",
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_PASSWORD")));
        jdbc = new JdbcTemplate(source);
        repositories = new RuntimeJpaRepositories(source);
        txManager = repositories.transactionManager;
    }

    @BeforeEach
    void beginTestTransaction() {
        txStatus = txManager.getTransaction(new DefaultTransactionDefinition());
    }

    @AfterEach
    void rollbackTestTransaction() {
        txManager.rollback(txStatus);
    }

    @AfterAll
    static void removeSchema() {
        repositories.close();
        surgery.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @DisplayName("Trang membership giữ mọi role và tiến qua cursor đã bị revoke")
    @Test
    void membershipPageKeepsEveryRole() {
        UUID workspace = center();
        UUID previous = member(workspace, 10, "STUDENT");
        UUID first = member(workspace, 11, "MANAGER", "TEACHER");
        UUID second = member(workspace, 12, "STUDENT");
        UUID foreignWorkspace = center();
        UUID foreignCursor = member(foreignWorkspace, 13, "STUDENT");
        MembershipService service =
                new MembershipService(
                        repositories.memberships,
                        repositories.accounts,
                        repositories.workspaces,
                        new SubscriptionPolicy(repositories.periods),
                        mock(IdempotencyService.class),
                        mock(InitialCredentialService.class),
                        mock(MembershipPolicyProjector.class),
                        mock(ResourceMutationCoordinator.class),
                        mock(AuditRecorder.class));

        UUID actor = UUID.randomUUID();
        var initial = service.pageMemberships(actor, workspace, null, 1);
        assertEquals(List.of(previous), initial.items().stream().map(row -> row.id()).toList());
        // Anchor bị thu hồi sau khi lấy cursor nhưng vẫn là mốc để trang kế tiếp đọc đúng.
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                previous);
        var page = service.pageMemberships(actor, workspace, initial.nextCursor(), 1);
        assertEquals(List.of(first), page.items().stream().map(row -> row.id()).toList());
        assertEquals(
                Set.of(MembershipRole.Role.MANAGER, MembershipRole.Role.TEACHER),
                Set.copyOf(page.items().getFirst().roles()));
        assertNotNull(page.nextCursor());
        var last = service.pageMemberships(actor, workspace, page.nextCursor(), 1);
        assertEquals(List.of(second), last.items().stream().map(row -> row.id()).toList());
        assertNull(last.nextCursor());
        for (String cursor :
                List.of("bad", foreignCursor.toString(), UUID.randomUUID().toString())) {
            var invalid = cursor;
            assertEquals(
                    "INVALID_CURSOR",
                    assertThrows(
                                    HttpProblemException.class,
                                    () -> service.pageMemberships(actor, workspace, invalid, 1))
                            .getCode());
        }
        assertEquals(
                "INVALID_CURSOR",
                assertThrows(
                                HttpProblemException.class,
                                () ->
                                        service.pageMemberships(
                                                UUID.randomUUID(), workspace, page.nextCursor(), 1))
                        .getCode());
        assertEquals(
                "INVALID_CURSOR",
                assertThrows(
                                HttpProblemException.class,
                                () ->
                                        service.pageMemberships(
                                                actor, foreignWorkspace, page.nextCursor(), 1))
                        .getCode());
    }

    @DisplayName("Trang lớp lọc theo giáo viên và workspace trước khi chọn trang")
    @Test
    void classPagesFilterBeforePaging() {
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
        TeachingPageRepository pages = repositories.pages;
        assertPages(
                cursor -> pages.pageClasses(workspace, cursor, 1, teacher),
                row -> row.getId(),
                List.of(first, second));
        jdbc.update("UPDATE school_class SET state = 'REVOKED' WHERE id = ?", first);
        var next = pages.pageClasses(workspace, first.toString(), 1, teacher);
        assertEquals(List.of(second), next.items().stream().map(row -> row.getId()).toList());
        assertNull(next.nextCursor());
        assertInvalidCursor(() -> pages.pageClasses(workspace, foreign.toString(), 1, teacher));
        assertInvalidCursor(() -> pages.pageClasses(workspace, "bad", 1, teacher));
    }

    @DisplayName("Danh sách teaching không thiếu hay trùng dòng và từ chối cursor của parent lạ")
    @Test
    void teachingListsAreGapless() {
        UUID workspace = center();
        UUID classId = schoolClass(workspace, 1);
        UUID otherClass = schoolClass(workspace, 2);
        UUID subject = jdbc.queryForObject("SELECT id FROM subject WHERE code = 'M1'", UUID.class);
        TeachingPageRepository pages = repositories.pages;
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
                cursor -> pages.pageCourses(workspace, classId, cursor, 1),
                row -> row.getId(),
                courses);
        assertPages(
                cursor -> pages.pageRoster(workspace, classId, cursor, 1),
                row -> row.getId(),
                roster);
        assertPages(
                cursor -> pages.pageEnrollments(workspace, courseSubject, cursor, 1),
                row -> row.getId(),
                enrollments);
        assertPages(
                cursor -> pages.pageTeachingAssignments(workspace, courseSubject, cursor, 1),
                row -> row.getId(),
                teachers);
        assertPages(
                cursor -> pages.pageHomeroomAssignments(workspace, classId, cursor, 1),
                row -> row.getId(),
                homerooms);
        assertInvalidCursor(
                () -> pages.pageCourses(workspace, otherClass, courses.getFirst().toString(), 1));
        assertInvalidCursor(
                () -> pages.pageRoster(workspace, otherClass, roster.getFirst().toString(), 1));
    }

    private static void assertInvalidCursor(Supplier<?> read) {
        HttpProblemException problem = assertThrows(HttpProblemException.class, read::get);
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals("INVALID_CURSOR", problem.getCode());
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
