package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.teaching.ClassService.ClassView;
import com.giaokhoa.teaching.ClassService.RosterEntryView;
import com.giaokhoa.teaching.CourseService.CourseView;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng tầng service giảng dạy bằng lời gọi thẳng service (route HTTP thuộc task route riêng):
 * scope theo workspace, gói hết hạn chặn mở rộng nhưng không chặn thu hồi, roster của center đòi
 * membership ACTIVE và lô roster nguyên tử, thu hồi roster kéo theo thu hồi ghi danh phụ thuộc mà
 * giữ lịch sử, khóa phải có ít nhất một môn lúc tạo, và thêm môn không tự ghi danh. Seed chỉ bằng
 * JDBC trong schema cô lập; không cần Keycloak vì tầng service không quyết quyền và không ghi
 * operation. Riêng phần RLS chạy service qua một role runtime NOSUPERUSER NOBYPASSRLS theo mẫu
 * SystemPracticeHistoryIT, vì user của stack TEST là superuser nên tự nó không chịu RLS — chỉ qua
 * role đó mới chứng minh được service đặt đúng context workspace cho mọi đường.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeachingServiceIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "teaching_service_" + UUID.randomUUID().toString().replace("-", "");
    private static final String RUNTIME_ROLE = "teaching_service_runtime";

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ClassService classes;
    @Autowired CourseService courses;

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    @Test
    void createClassReadsBackScopedToWorkspace() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);

        ClassView created = classes.createClass(workspace, "Lớp 1A");
        assertEquals("ACTIVE", created.state());
        assertEquals(created, classes.getClass(workspace, created.id()));
        assertEquals(List.of(created), classes.listClasses(workspace));

        UUID other = seedCenter(seedAccount());
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class, () -> classes.getClass(other, created.id()));
        assertEquals(HttpStatus.NOT_FOUND, problem.getStatus(), "Lớp không lộ sang workspace khác");
    }

    @Test
    void expiredSubscriptionBlocksExpansionButNotRevoke() {
        UUID owner = seedAccount();
        UUID expired = seedCenter(owner);
        seedSubscription(expired, -60, -1, owner);
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class, () -> classes.createClass(expired, "Lớp X"));
        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertEquals("SUBSCRIPTION_INACTIVE", problem.getCode());

        // Workspace chưa từng có kỳ gói nào thì không bị chặn, và thu hồi không qua cổng gói.
        UUID workspace = seedCenter(seedAccount());
        ClassView created = classes.createClass(workspace, "Lớp tự do");
        ClassView revoked = classes.revokeClass(workspace, created.id());
        assertEquals("REVOKED", revoked.state());
        assertEquals(
                List.of(), classes.listClasses(workspace), "Lớp đã thu hồi không còn hiện hành");
        HttpProblemException again =
                assertThrows(
                        HttpProblemException.class,
                        () -> classes.revokeClass(workspace, created.id()));
        assertEquals(HttpStatus.CONFLICT, again.getStatus());
    }

    @Test
    void rosterRequiresCenterMembershipAndBatchIsAtomic() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID member = seedAccount();
        addMembership(workspace, member, "STUDENT");
        UUID outsider = seedAccount();
        ClassView clazz = classes.createClass(workspace, "Lớp roster");

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                classes.addStudents(
                                        workspace, clazz.id(), List.of(member, outsider)));
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals("STUDENT_NOT_CENTER_MEMBER", problem.getCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM class_student WHERE class_id = ?",
                        Integer.class,
                        clazz.id()),
                "Lô có một học sinh không hợp lệ thì không ghi phần nào");

        List<RosterEntryView> added = classes.addStudents(workspace, clazz.id(), List.of(member));
        assertEquals(1, added.size());
        assertEquals("ACTIVE", added.getFirst().state());
        List<RosterEntryView> again = classes.addStudents(workspace, clazz.id(), List.of(member));
        assertEquals(
                added.getFirst().id(),
                again.getFirst().id(),
                "Thêm lại học sinh đã có dòng hiện hành là hợp tập, không nhân bản dòng");
        assertEquals(added, classes.listStudents(workspace, clazz.id()));
    }

    @Test
    void personalRosterNeedsOnlyExistingAccount() {
        UUID owner = seedAccount();
        UUID personal = personalWorkspaceOf(owner);
        UUID learner = seedAccount();
        ClassView clazz = classes.createClass(personal, "Lớp cá nhân");

        List<RosterEntryView> added = classes.addStudents(personal, clazz.id(), List.of(learner));
        assertEquals(1, added.size(), "Personal không đòi center membership");

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                classes.addStudents(
                                        personal, clazz.id(), List.of(UUID.randomUUID())));
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals("STUDENT_ACCOUNT_NOT_FOUND", problem.getCode());
    }

    @Test
    void revokeRosterKeepsHistoryAndRevokesDependentEnrollments() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID student = seedAccount();
        addMembership(workspace, student, "STUDENT");
        ClassView clazz = classes.createClass(workspace, "Lớp thu hồi");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa thu hồi", List.of(subject));
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();
        UUID courseSubjectId = course.subjects().getFirst().id();
        UUID enrollmentId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO enrollment"
                            + " (id, workspace_id, class_student_id, course_subject_id,"
                            + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                    enrollmentId,
                    workspace,
                    entry.id(),
                    courseSubjectId);
            return null;
        });

        RosterEntryView revoked = classes.revokeStudent(workspace, clazz.id(), entry.id());
        assertEquals("REVOKED", revoked.state());
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM class_student WHERE id = ?", String.class, entry.id()));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT revoked_at FROM class_student WHERE id = ?",
                        OffsetDateTime.class,
                        entry.id()),
                "Thu hồi ghi thời điểm, giữ nguyên dòng lịch sử");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment WHERE class_student_id = ?"
                                + " AND state = 'ACTIVE'",
                        Integer.class,
                        entry.id()),
                "Ghi danh phụ thuộc bị thu hồi cùng roster");
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM enrollment WHERE id = ?", String.class, enrollmentId),
                "Hàng ghi danh phụ thuộc chuyển hẳn sang REVOKED, không lùi về trạng thái chờ");
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT revoked_at FROM enrollment WHERE id = ?",
                        OffsetDateTime.class,
                        enrollmentId),
                "Thu hồi ghi danh phụ thuộc ghi thời điểm thu hồi");
        assertEquals(List.of(), classes.listStudents(workspace, clazz.id()));
    }

    @Test
    void courseRequiresAtLeastOneSubjectAtCreate() {
        UUID workspace = seedCenter(seedAccount());
        ClassView clazz = classes.createClass(workspace, "Lớp khóa");

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> courses.createCourse(workspace, clazz.id(), "Khóa rỗng", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM course WHERE name = 'Khóa rỗng'", Integer.class),
                "Khóa không hợp lệ không được ghi");

        List<UUID> subjects = courses.listSubjects().stream().map(s -> s.id()).toList();
        assertTrue(subjects.size() >= 3, "Danh mục seed có ít nhất ba môn");
        CourseView course = courses.createCourse(workspace, clazz.id(), "Khóa ba môn", subjects);
        assertEquals(3, course.subjects().size());
        assertEquals(course, courses.getCourse(workspace, course.id()));
    }

    @Test
    void addingSubjectDoesNotAutoEnroll() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID student = seedAccount();
        addMembership(workspace, student, "STUDENT");
        ClassView clazz = classes.createClass(workspace, "Lớp thêm môn");
        List<UUID> subjects = courses.listSubjects().stream().map(s -> s.id()).toList();
        CourseView course =
                courses.createCourse(
                        workspace, clazz.id(), "Khóa thêm môn", subjects.subList(0, 2));
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO enrollment"
                            + " (id, workspace_id, class_student_id, course_subject_id,"
                            + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                    UUID.randomUUID(),
                    workspace,
                    entry.id(),
                    course.subjects().getFirst().id());
            return null;
        });

        CourseView grown = courses.addSubjects(workspace, course.id(), List.of(subjects.get(2)));
        assertEquals(3, grown.subjects().size());
        UUID addedCourseSubject =
                grown.subjects().stream()
                        .filter(s -> s.subjectId().equals(subjects.get(2)))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment WHERE course_subject_id = ?",
                        Integer.class,
                        addedCourseSubject),
                "Môn thêm sau không tự ghi danh học sinh đã có");
    }

    @Test
    void revokeCourseSubjectKeepsHistoryAndCourseMayBecomeEmpty() {
        UUID workspace = seedCenter(seedAccount());
        ClassView clazz = classes.createClass(workspace, "Lớp gỡ môn");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa gỡ môn", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();

        CourseView emptied = courses.revokeCourseSubject(workspace, course.id(), courseSubjectId);
        assertEquals(List.of(), emptied.subjects(), "Gỡ môn cuối cùng được phép sau lúc tạo");
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM course_subject WHERE id = ?",
                        String.class,
                        courseSubjectId));
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> courses.revokeCourseSubject(workspace, course.id(), courseSubjectId));
        assertEquals(HttpStatus.CONFLICT, problem.getStatus());
    }

    @Test
    void expiredSubscriptionGatesEveryExpansionExceptRenameCourse() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID member = seedAccount();
        addMembership(workspace, member, "STUDENT");
        ClassView clazz = classes.createClass(workspace, "Lớp cổng gói");
        List<UUID> subjects = courses.listSubjects().stream().map(s -> s.id()).toList();
        CourseView course =
                courses.createCourse(
                        workspace, clazz.id(), "Khóa cổng gói", List.of(subjects.getFirst()));
        seedSubscription(workspace, -60, -1, owner);

        assertSubscriptionBlocked(() -> classes.renameClass(workspace, clazz.id(), "Tên lớp mới"));
        assertSubscriptionBlocked(
                () -> classes.addStudents(workspace, clazz.id(), List.of(member)));
        assertSubscriptionBlocked(() ->
                courses.createCourse(
                        workspace, clazz.id(), "Khóa mới", List.of(subjects.getFirst())));
        assertSubscriptionBlocked(
                () -> courses.addSubjects(workspace, course.id(), List.of(subjects.get(1))));

        // Đổi tên khóa cố ý không qua cổng gói (xem B39): gói đã hết hạn vẫn đổi tên được.
        CourseView renamed = courses.renameCourse(workspace, course.id(), "Khóa đã đổi tên");
        assertEquals("Khóa đã đổi tên", renamed.name());
    }

    @Test
    void crossWorkspaceCourseRosterRevokeAndCourseListAreNotFound() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID student = seedAccount();
        addMembership(workspace, student, "STUDENT");
        ClassView clazz = classes.createClass(workspace, "Lớp scope");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa scope", List.of(subject));
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();
        UUID other = seedCenter(seedAccount());

        HttpProblemException getCourseProblem =
                assertThrows(
                        HttpProblemException.class, () -> courses.getCourse(other, course.id()));
        assertEquals(HttpStatus.NOT_FOUND, getCourseProblem.getStatus());
        assertEquals("COURSE_NOT_FOUND", getCourseProblem.getCode());

        HttpProblemException revokeProblem =
                assertThrows(
                        HttpProblemException.class,
                        () -> classes.revokeStudent(other, clazz.id(), entry.id()));
        assertEquals(HttpStatus.NOT_FOUND, revokeProblem.getStatus());
        assertEquals("ROSTER_ENTRY_NOT_FOUND", revokeProblem.getCode());

        HttpProblemException listProblem =
                assertThrows(
                        HttpProblemException.class, () -> courses.listCourses(other, clazz.id()));
        assertEquals(HttpStatus.NOT_FOUND, listProblem.getStatus());
        assertEquals("CLASS_NOT_FOUND", listProblem.getCode());

        assertEquals(
                "ACTIVE",
                classes.listStudents(workspace, clazz.id()).getFirst().state(),
                "Đường chéo workspace không chạm được vào hàng thật của workspace gốc");
    }

    @Test
    void serviceOperationsRunUnderNonBypassRlsRole() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID other = seedCenter(seedAccount());
        UUID student = seedAccount();
        addMembership(workspace, student, "STUDENT");
        UUID subject = courses.listSubjects().getFirst().id();

        String password = UUID.randomUUID().toString();
        jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        jdbc.execute(
                "CREATE ROLE "
                        + RUNTIME_ROLE
                        + " LOGIN PASSWORD '"
                        + password
                        + "' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE ON school_class, class_student, course,"
                        + " course_subject, enrollment TO "
                        + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT ON workspace, subscription_period, user_account, center_membership,"
                        + " subject TO "
                        + RUNTIME_ROLE);
        String url = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
        runtimeDataSource = new SingleConnectionDataSource(url, RUNTIME_ROLE, password, true);
        try {
            JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource);
            runtimeJdbc.execute("SET search_path TO " + SCHEMA + ",public");
            DataSourceTransactionManager runtimeTxManager =
                    new DataSourceTransactionManager(runtimeDataSource);
            TeachingRepository runtimeRepository = new TeachingRepository(runtimeJdbc);
            WorkspaceTransaction runtimeWorkspaceTx =
                    new WorkspaceTransaction(runtimeJdbc, runtimeTxManager);
            TeachingRevocationContributor runtimeContributor =
                    new TeachingRevocationContributor(runtimeRepository, runtimeWorkspaceTx);
            ResourceMutationCoordinator runtimeCoordinator =
                    new ResourceMutationCoordinator(runtimeJdbc, runtimeTxManager);
            ClassService runtimeClasses =
                    new ClassService(
                            runtimeRepository,
                            runtimeWorkspaceTx,
                            runtimeCoordinator,
                            runtimeContributor);
            CourseService runtimeCourses =
                    new CourseService(
                            runtimeRepository,
                            runtimeWorkspaceTx,
                            runtimeCoordinator,
                            runtimeContributor);
            TransactionTemplate runtimeTx = new TransactionTemplate(runtimeTxManager);

            ClassView created =
                    Objects.requireNonNull(
                            runtimeTx.execute(
                                    _ -> runtimeClasses.createClass(workspace, "Lớp runtime")));
            assertEquals(
                    0,
                    runtimeJdbc.queryForObject("SELECT count(*) FROM school_class", Integer.class),
                    "Không có context thì role runtime không thấy hàng nào (fail-closed)");
            assertThrows(
                    DataAccessException.class,
                    () ->
                            runtimeJdbc.update(
                                    "INSERT INTO school_class (id, workspace_id, name, state)"
                                            + " VALUES (?, ?, 'Lớp lậu', 'ACTIVE')",
                                    UUID.randomUUID(),
                                    workspace),
                    "Ghi không qua context bị WITH CHECK của RLS chặn");
            assertEquals(
                    created,
                    runtimeTx.execute(_ -> runtimeClasses.getClass(workspace, created.id())));
            assertEquals(
                    List.of(created),
                    runtimeTx.execute(_ -> runtimeClasses.listClasses(workspace)));
            List<RosterEntryView> added =
                    Objects.requireNonNull(
                            runtimeTx.execute(_ ->
                                    runtimeClasses.addStudents(
                                            workspace, created.id(), List.of(student))));
            CourseView course =
                    Objects.requireNonNull(
                            runtimeTx.execute(_ ->
                                    runtimeCourses.createCourse(
                                            workspace,
                                            created.id(),
                                            "Khóa runtime",
                                            List.of(subject))));
            assertEquals(1, course.subjects().size());
            RosterEntryView revoked =
                    Objects.requireNonNull(
                            runtimeTx.execute(_ ->
                                    runtimeClasses.revokeStudent(
                                            workspace, created.id(), added.getFirst().id())));
            assertEquals("REVOKED", revoked.state());
            HttpProblemException cross =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    runtimeTx.execute(
                                            _ -> runtimeClasses.getClass(other, created.id())));
            assertEquals(HttpStatus.NOT_FOUND, cross.getStatus(), "Workspace khác vẫn vô hình");
        } finally {
            if (runtimeDataSource != null) {
                runtimeDataSource.destroy();
            }
            jdbc.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
            jdbc.execute("REVOKE USAGE ON SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
            jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        }
    }

    private static void assertSubscriptionBlocked(Executable action) {
        HttpProblemException problem = assertThrows(HttpProblemException.class, action);
        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertEquals("SUBSCRIPTION_INACTIVE", problem.getCode());
    }

    // ---------- Seed dữ kiện thư mục bằng JDBC ----------

    private UUID seedAccount() {
        UUID account = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,"
                            + "email_verified,origin,identity_state)"
                            + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                    account,
                    "teaching-service-" + account,
                    "teaching-service-user-" + account,
                    "teaching-service-" + account + "@example.test");
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    personal,
                    account);
            return null;
        });
        return account;
    }

    private UUID personalWorkspaceOf(UUID accountId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        accountId),
                "Account phải có personal workspace");
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

    private void addMembership(UUID workspaceId, UUID userId, String role) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                    membership,
                    role);
            return null;
        });
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
                    "teaching-service-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }
}
