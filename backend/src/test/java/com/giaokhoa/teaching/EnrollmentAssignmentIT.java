package com.giaokhoa.teaching;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.ResourceCopies;
import com.giaokhoa.resources.repository.ResourceScopePreconditions;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.teaching.AssignmentService.HomeroomAssignmentView;
import com.giaokhoa.teaching.AssignmentService.TeachingAssignmentView;
import com.giaokhoa.teaching.ClassService.ClassView;
import com.giaokhoa.teaching.ClassService.RosterEntryView;
import com.giaokhoa.teaching.CourseService.CourseView;
import com.giaokhoa.teaching.EnrollmentService.EnrollmentView;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.CenterService;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.verification.ResultPolicyProjector;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kiểm chứng tầng service của ghi danh và phân công: lô ghi danh nguyên tử và khóa cặp cùng lớp,
 * tiền đề role TEACHER của đúng center cho phân công giảng dạy/chủ nhiệm, cổng gói chặn mở rộng
 * nhưng không chặn thu hồi, cascade đóng nguồn phụ thuộc trong cùng transaction khi gỡ role hay thu
 * hồi membership qua CenterService, và hành vi dưới một role không phải chủ sở hữu bảng. Phần
 * projector chạy end-to-end với worker thật và Keycloak TEST: group của giáo viên/học sinh/chủ
 * nhiệm chỉ phản ánh quan hệ hiện hành kèm membership ACTIVE hiện hành. Seed phần service thuần
 * bằng JDBC trong schema dùng chung của suite; phần projector tạo tài khoản Keycloak thật theo mẫu
 * CenterServiceIT. Route HTTP thuộc task route riêng nên test gọi thẳng service.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EnrollmentAssignmentIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA = "public";
    private static final String RUNTIME_ROLE = "enrollment_assignment_runtime";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";
    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");

    @BeforeAll
    static void seedRealmPrerequisites() {
        seedEligibilityGroup();
        ensureApiClient();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ClassService classes;
    @Autowired CourseService courses;
    @Autowired EnrollmentService enrollments;
    @Autowired AssignmentService assignments;
    @Autowired CenterService centers;
    @Autowired IdempotencyService idempotency;
    @Autowired AuthorizationProvisioningClient directory;
    @Autowired EntityManagerFactory entityManagerFactory;

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    @Test
    void batchEnrollmentValidatesWholeBatchBeforeWriting() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID studentA = seedAccount();
        UUID studentB = seedAccount();
        addMembership(workspace, studentA, "STUDENT");
        addMembership(workspace, studentB, "STUDENT");
        ClassView classA = classes.createClass(workspace, "Lớp A");
        ClassView classB = classes.createClass(workspace, "Lớp B");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, classA.id(), "Khóa A", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();
        RosterEntryView entryA =
                classes.addStudents(workspace, classA.id(), List.of(studentA)).getFirst();
        RosterEntryView entryB =
                classes.addStudents(workspace, classB.id(), List.of(studentB)).getFirst();

        HttpProblemException mismatch =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.enrollBatch(
                                        owner,
                                        workspace,
                                        List.of(entryA.id(), entryB.id()),
                                        List.of(courseSubjectId),
                                        key(),
                                        hash()));
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatus());
        assertEquals("ENROLLMENT_CLASS_MISMATCH", mismatch.getCode());
        assertEquals(
                0,
                countRows("SELECT count(*) FROM enrollment WHERE workspace_id = ?", workspace),
                "Lô trộn lớp bị từ chối thì không ghi bất cứ ghi danh nào");

        HttpProblemException unknown =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.enrollBatch(
                                        owner,
                                        workspace,
                                        List.of(UUID.randomUUID()),
                                        List.of(courseSubjectId),
                                        key(),
                                        hash()));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());
        assertEquals("ENROLLMENT_TARGET_NOT_FOUND", unknown.getCode());

        AuthorizationOperation operation =
                enrollments.enrollBatch(
                        owner,
                        workspace,
                        List.of(entryA.id()),
                        List.of(courseSubjectId),
                        key(),
                        hash());
        assertNotNull(operation.getId());
        List<EnrollmentView> listed = enrollments.listEnrollments(workspace, courseSubjectId);
        assertEquals(1, listed.size());
        assertEquals("ACTIVE", listed.getFirst().state());
        assertEquals(studentA, listed.getFirst().studentUserId());

        // Hợp tập: ghi danh lại cùng cặp không tạo dòng thứ hai.
        enrollments.enrollBatch(
                owner, workspace, List.of(entryA.id()), List.of(courseSubjectId), key(), hash());
        assertEquals(1, enrollments.listEnrollments(workspace, courseSubjectId).size());
    }

    @Test
    void teacherAssignmentRequiresTeacherRoleInSameCenter() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID otherCenter = seedCenter(seedAccount());
        UUID foreignTeacher = seedAccount();
        addMembership(otherCenter, foreignTeacher, "TEACHER");
        UUID studentMember = seedAccount();
        addMembership(workspace, studentMember, "STUDENT");
        UUID teacher = seedAccount();
        addMembership(workspace, teacher, "TEACHER");
        ClassView clazz = classes.createClass(workspace, "Lớp phân công");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa phân công", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();

        HttpProblemException foreign =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                assignments.assignTeachers(
                                        owner,
                                        workspace,
                                        courseSubjectId,
                                        List.of(foreignTeacher),
                                        key(),
                                        hash()));
        assertEquals("TEACHER_NOT_CENTER_TEACHER", foreign.getCode());
        HttpProblemException wrongRole =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                assignments.assignTeachers(
                                        owner,
                                        workspace,
                                        courseSubjectId,
                                        List.of(studentMember),
                                        key(),
                                        hash()));
        assertEquals("TEACHER_NOT_CENTER_TEACHER", wrongRole.getCode());
        assertEquals(List.of(), assignments.listTeacherAssignments(workspace, courseSubjectId));

        assignments.assignTeachers(
                owner, workspace, courseSubjectId, List.of(teacher), key(), hash());
        List<TeachingAssignmentView> listed =
                assignments.listTeacherAssignments(workspace, courseSubjectId);
        assertEquals(1, listed.size());
        assertEquals(teacher, listed.getFirst().teacherUserId());

        HttpProblemException homeroomWrongRole =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                assignments.assignHomeroom(
                                        owner,
                                        workspace,
                                        clazz.id(),
                                        studentMember,
                                        key(),
                                        hash()));
        assertEquals("TEACHER_NOT_CENTER_TEACHER", homeroomWrongRole.getCode());
        assignments.assignHomeroom(owner, workspace, clazz.id(), teacher, key(), hash());
        List<HomeroomAssignmentView> homeroom =
                assignments.listHomeroomAssignments(workspace, clazz.id());
        assertEquals(1, homeroom.size());
        assertEquals(teacher, homeroom.getFirst().teacherUserId());

        // Route phân công không áp dụng cho personal workspace.
        UUID personal = personalWorkspaceOf(owner);
        HttpProblemException personalProblem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                assignments.assignTeachers(
                                        owner,
                                        personal,
                                        courseSubjectId,
                                        List.of(teacher),
                                        key(),
                                        hash()));
        assertEquals(HttpStatus.NOT_FOUND, personalProblem.getStatus());
    }

    @Test
    void expiredSubscriptionGatesExpansionButNotRevocation() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID student = seedAccount();
        UUID teacher = seedAccount();
        addMembership(workspace, student, "STUDENT");
        addMembership(workspace, teacher, "TEACHER");
        ClassView clazz = classes.createClass(workspace, "Lớp cổng gói");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa cổng gói", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();
        enrollments.enrollBatch(
                owner, workspace, List.of(entry.id()), List.of(courseSubjectId), key(), hash());
        assignments.assignTeachers(
                owner, workspace, courseSubjectId, List.of(teacher), key(), hash());
        UUID enrollmentId = enrollments.listEnrollments(workspace, courseSubjectId).getFirst().id();
        UUID assignmentId =
                assignments.listTeacherAssignments(workspace, courseSubjectId).getFirst().id();

        seedSubscription(workspace, -60, -1, owner);
        HttpProblemException enrollBlocked =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                enrollments.enrollBatch(
                                        owner,
                                        workspace,
                                        List.of(entry.id()),
                                        List.of(courseSubjectId),
                                        key(),
                                        hash()));
        assertEquals("SUBSCRIPTION_INACTIVE", enrollBlocked.getCode());
        HttpProblemException assignBlocked =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                assignments.assignTeachers(
                                        owner,
                                        workspace,
                                        courseSubjectId,
                                        List.of(teacher),
                                        key(),
                                        hash()));
        assertEquals("SUBSCRIPTION_INACTIVE", assignBlocked.getCode());

        enrollments.revokeEnrollment(owner, workspace, enrollmentId, key(), hash());
        assignments.revokeTeacherAssignment(
                owner, workspace, courseSubjectId, assignmentId, key(), hash());
        assertEquals(List.of(), enrollments.listEnrollments(workspace, courseSubjectId));
        assertEquals(List.of(), assignments.listTeacherAssignments(workspace, courseSubjectId));
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM enrollment WHERE id = ?", String.class, enrollmentId));
    }

    @Test
    void roleRemovalAndMembershipRevocationCloseDependents() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID teacher = seedAccount();
        UUID teacherMembership = addMembership(workspace, teacher, "TEACHER");
        UUID student = seedAccount();
        UUID studentMembership = addMembership(workspace, student, "STUDENT");
        ClassView clazz = classes.createClass(workspace, "Lớp cascade");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa cascade", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();
        enrollments.enrollBatch(
                owner, workspace, List.of(entry.id()), List.of(courseSubjectId), key(), hash());
        assignments.assignTeachers(
                owner, workspace, courseSubjectId, List.of(teacher), key(), hash());
        assignments.assignHomeroom(owner, workspace, clazz.id(), teacher, key(), hash());

        centers.replaceRoles(
                owner,
                workspace,
                teacherMembership,
                Set.of(MembershipRole.Role.MANAGER),
                etag(teacherMembership),
                key(),
                hash());
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM teaching_assignment WHERE teacher_user_id = ?",
                        String.class,
                        teacher),
                "Gỡ TEACHER đóng phân công giảng dạy trong cùng transaction");
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM homeroom_assignment WHERE teacher_user_id = ?",
                        String.class,
                        teacher),
                "Gỡ TEACHER đóng cả phân công chủ nhiệm");
        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT state FROM enrollment WHERE class_student_id = ?",
                        String.class,
                        entry.id()),
                "Ghi danh của học sinh không bị chạm khi gỡ role giáo viên");

        centers.replaceRoles(
                owner,
                workspace,
                studentMembership,
                Set.of(MembershipRole.Role.TEACHER),
                etag(studentMembership),
                key(),
                hash());
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT state FROM enrollment WHERE class_student_id = ?",
                        String.class,
                        entry.id()),
                "Gỡ STUDENT đóng ghi danh của học sinh đó");

        // Phân công lại sau khi có role trở lại là dòng mới; dòng lịch sử không sống lại.
        centers.replaceRoles(
                owner,
                workspace,
                teacherMembership,
                Set.of(MembershipRole.Role.MANAGER, MembershipRole.Role.TEACHER),
                etag(teacherMembership),
                key(),
                hash());
        assignments.assignTeachers(
                owner, workspace, courseSubjectId, List.of(teacher), key(), hash());
        assertEquals(
                2,
                countRows(
                        "SELECT count(*) FROM teaching_assignment WHERE teacher_user_id = ?",
                        teacher));
        assertEquals(
                1,
                countRows(
                        "SELECT count(*) FROM teaching_assignment WHERE teacher_user_id = ?"
                                + " AND state = 'ACTIVE'",
                        teacher));

        centers.revokeMembership(owner, workspace, teacherMembership, key(), hash());
        assertEquals(
                0,
                countRows(
                        "SELECT count(*) FROM teaching_assignment WHERE teacher_user_id = ?"
                                + " AND state = 'ACTIVE'",
                        teacher),
                "Thu hồi membership đóng mọi phân công còn hiện hành");
    }

    @Test
    void enrollmentAndAssignmentRunUnderNonOwnerRole() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID other = seedCenter(seedAccount());
        UUID student = seedAccount();
        UUID teacher = seedAccount();
        addMembership(workspace, student, "STUDENT");
        addMembership(workspace, teacher, "TEACHER");
        ClassView clazz = classes.createClass(workspace, "Lớp runtime");
        UUID subject = courses.listSubjects().getFirst().id();
        CourseView course =
                courses.createCourse(workspace, clazz.id(), "Khóa runtime", List.of(subject));
        UUID courseSubjectId = course.subjects().getFirst().id();
        RosterEntryView entry =
                classes.addStudents(workspace, clazz.id(), List.of(student)).getFirst();

        String password = UUID.randomUUID().toString();
        migratorJdbc().execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        migratorJdbc()
                .execute(
                        "CREATE ROLE "
                                + RUNTIME_ROLE
                                + " LOGIN PASSWORD '"
                                + password
                                + "' NOSUPERUSER NOBYPASSRLS");
        migratorJdbc().execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        // Cơ chế operation chạm nhiều bảng điều khiển quyền ngoài các bảng giảng dạy nên cấp
        // toàn bộ bảng của schema dùng chung cho role runtime của riêng class này.
        migratorJdbc()
                .execute("GRANT ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        migratorJdbc()
                .execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        String url = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
        runtimeDataSource = new SingleConnectionDataSource(url, RUNTIME_ROLE, password, true);
        try (var runtime = new RuntimeJpaRepositories(runtimeDataSource)) {
            JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource);
            runtimeJdbc.execute("SET search_path TO " + SCHEMA + ",public");
            WorkspaceTransaction runtimeWorkspaceTx =
                    new WorkspaceTransaction(runtimeJdbc, runtime.transactionManager);
            TeachingScopePreconditions runtimePreconditions =
                    new TeachingScopePreconditions(
                            runtime.workspaces,
                            runtime.memberships,
                            runtime.accounts,
                            runtime.periods,
                            new SubscriptionPolicy(runtime.periods));
            IdempotencyService runtimeIdempotency =
                    new IdempotencyService(runtime.operations, runtime.authzPartitions);
            AuthorizationOperationService runtimeOperations =
                    new AuthorizationOperationService(
                            runtime.authzPartitions, runtime.authzProjections, runtimeIdempotency);
            TeachingPolicyProjector runtimeProjector =
                    new TeachingPolicyProjector(
                            runtime.schoolClasses,
                            runtime.courses,
                            runtime.courseSubjects,
                            runtime.homeroomAssignments,
                            runtime.teachingAssignments,
                            runtime.enrollments,
                            runtimePreconditions,
                            directory,
                            runtimeWorkspaceTx,
                            runtime.transactionManager);
            ResourceMutationCoordinator runtimeCoordinator =
                    new ResourceMutationCoordinator(runtimeJdbc, runtime.transactionManager);
            com.giaokhoa.resources.ResourcePolicyProjector runtimeResourceProjector =
                    new com.giaokhoa.resources.ResourcePolicyProjector(
                            runtimeOperations,
                            runtime.transactionManager,
                            runtime.resourceVersions,
                            runtime.resources,
                            runtime.libraryItems,
                            runtime.shareGrants,
                            runtime.resourceDependencies,
                            runtime.resourceAssignments,
                            runtime.resourcesWorkspaces,
                            runtime.resourcesAccounts,
                            runtime.resourcesActivities,
                            runtime.resourcesPartitions,
                            new ResourceCopies(
                                    runtime.resources,
                                    runtime.resourceVersions,
                                    runtime.resourceDependencies),
                            new ResourceScopePreconditions(
                                    runtime.periods,
                                    runtime.memberships,
                                    runtime.enrollments,
                                    runtime.accounts));
            // Projector nhận EntityManager qua @PersistenceContext khi chạy trong context;
            // ở làn dựng tay này phải gán EntityManager dùng chung của bộ repository runtime.
            ReflectionTestUtils.setField(
                    runtimeResourceProjector,
                    "entityManager",
                    SharedEntityManagerCreator.createSharedEntityManager(
                            runtime.entityManagerFactory));
            SourceRevocationCoordinator runtimeRevocations =
                    new SourceRevocationCoordinator(
                            runtime.authzPartitions,
                            runtimeOperations,
                            new CenterPolicyProjector(
                                    runtime.memberships,
                                    runtime.workspaces,
                                    runtime.accounts,
                                    runtime.periods,
                                    directory),
                            runtimeProjector,
                            new PersonalTeachingPolicyProjector(
                                    runtime.schoolClasses,
                                    runtime.courses,
                                    runtime.courseSubjects,
                                    runtime.enrollments,
                                    runtimePreconditions,
                                    runtimeWorkspaceTx,
                                    runtime.transactionManager),
                            new ResultPolicyProjector(
                                    runtime.sampleResults,
                                    runtime.verificationPartitions,
                                    runtime.verificationTeachingAssignments,
                                    runtime.verificationEnrollments,
                                    runtime.verificationWorkspaces,
                                    runtime.verificationAccounts,
                                    runtime.guardianLinks,
                                    runtime.verificationMemberships,
                                    runtimeWorkspaceTx,
                                    runtimeOperations,
                                    runtime.transactionManager),
                            runtimeResourceProjector);
            EnrollmentService runtimeEnrollments =
                    new EnrollmentService(
                            runtime.classStudents,
                            runtime.courseSubjects,
                            runtime.schoolClasses,
                            runtime.courses,
                            runtime.enrollments,
                            runtime.pages,
                            runtimePreconditions,
                            runtimeWorkspaceTx,
                            runtimeIdempotency,
                            runtimeRevocations,
                            runtimeCoordinator);
            AssignmentService runtimeAssignments =
                    new AssignmentService(
                            runtime.schoolClasses,
                            runtime.courses,
                            runtime.courseSubjects,
                            runtime.teachingAssignments,
                            runtime.homeroomAssignments,
                            runtime.pages,
                            runtimePreconditions,
                            runtimeWorkspaceTx,
                            runtimeIdempotency,
                            runtimeRevocations,
                            runtimeCoordinator);
            TransactionTemplate runtimeTx = new TransactionTemplate(runtime.transactionManager);
            TransactionTemplate appTx = new TransactionTemplate(transactionManager);

            assertEquals(
                    0,
                    Objects.requireNonNull(
                            runtimeJdbc.queryForObject(
                                    "SELECT count(*) FROM enrollment WHERE workspace_id = ?",
                                    Integer.class,
                                    workspace)),
                    "Chưa có ghi danh nào của workspace trước khi service dưới role runtime chạy");

            // findExisting của idempotency đọc qua EntityManager của context ứng dụng nên bọc
            // thêm transaction của ứng dụng quanh transaction runtime cho riêng đường đọc đó.
            appTx.executeWithoutResult(_ ->
                    runtimeTx.executeWithoutResult(_ ->
                            runtimeEnrollments.enrollBatch(
                                    owner,
                                    workspace,
                                    List.of(entry.id()),
                                    List.of(courseSubjectId),
                                    key(),
                                    hash())));
            appTx.executeWithoutResult(_ ->
                    runtimeTx.executeWithoutResult(_ ->
                            runtimeAssignments.assignTeachers(
                                    owner,
                                    workspace,
                                    courseSubjectId,
                                    List.of(teacher),
                                    key(),
                                    hash())));
            assertEquals(
                    1,
                    countRows("SELECT count(*) FROM enrollment WHERE workspace_id = ?", workspace),
                    "Ghi danh qua service chạy dưới role runtime đã ghi được");
            assertEquals(
                    1,
                    countRows(
                            "SELECT count(*) FROM teaching_assignment WHERE workspace_id = ?",
                            workspace),
                    "Phân công qua service chạy dưới role runtime đã ghi được");
            List<EnrollmentView> listed =
                    runtimeTx.execute(
                            _ -> runtimeEnrollments.listEnrollments(workspace, courseSubjectId));
            assertEquals(1, Objects.requireNonNull(listed).size());
            HttpProblemException cross =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    runtimeTx.execute(_ ->
                                            runtimeEnrollments.listEnrollments(
                                                    other, courseSubjectId)));
            assertEquals(HttpStatus.NOT_FOUND, cross.getStatus(), "Workspace khác vẫn vô hình");
        } finally {
            if (runtimeDataSource != null) {
                runtimeDataSource.destroy();
            }
            migratorJdbc()
                    .execute(
                            "REVOKE ALL ON ALL TABLES IN SCHEMA "
                                    + SCHEMA
                                    + " FROM "
                                    + RUNTIME_ROLE);
            migratorJdbc()
                    .execute(
                            "REVOKE ALL ON ALL SEQUENCES IN SCHEMA "
                                    + SCHEMA
                                    + " FROM "
                                    + RUNTIME_ROLE);
            migratorJdbc().execute("REVOKE USAGE ON SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
            migratorJdbc().execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        }
    }

    @Test
    void projectorAppliesGroupsFromCurrentMemberships() throws Exception {
        Fixture operator = newAccount("enrollassign-operator");
        Fixture owner = newAccount("enrollassign-owner");
        Fixture teacher = newAccount("enrollassign-teacher");
        Fixture student = newAccount("enrollassign-student");
        UUID workspace = null;
        String organizationId = null;
        try {
            workspace = createCenterAndAwait(operator, owner);
            organizationId =
                    jdbc.queryForObject(
                            "SELECT keycloak_organization_id FROM center WHERE workspace_id = ?",
                            String.class,
                            workspace);
            UUID teacherMembership = addMembership(workspace, teacher.userId(), "TEACHER");
            addMembership(workspace, student.userId(), "STUDENT");
            // Membership seed thẳng bằng JDBC làm bundle workspace đã ghi ở lúc tạo center lệch
            // nguồn hiện hành; ghi lại bundle qua một lần thay role cùng tập (không đổi role nào)
            // để partition trở lại nhất quán trước khi ghi các operation giảng dạy.
            AuthorizationOperation refresh =
                    centers.replaceRoles(
                            owner.userId(),
                            workspace,
                            teacherMembership,
                            Set.of(MembershipRole.Role.TEACHER),
                            etag(teacherMembership),
                            key(),
                            hash());
            awaitOperationSucceeded(refresh.getId());
            ClassView clazz = classes.createClass(workspace, "Lớp projector");
            UUID subject = courses.listSubjects().getFirst().id();
            CourseView course =
                    courses.createCourse(workspace, clazz.id(), "Khóa projector", List.of(subject));
            UUID courseSubjectId = course.subjects().getFirst().id();
            RosterEntryView entry =
                    classes.addStudents(workspace, clazz.id(), List.of(student.userId()))
                            .getFirst();

            AuthorizationOperation enrollOperation =
                    enrollments.enrollBatch(
                            owner.userId(),
                            workspace,
                            List.of(entry.id()),
                            List.of(courseSubjectId),
                            key(),
                            hash());
            awaitOperationSucceeded(enrollOperation.getId());
            String courseSubjectKey =
                    NativePolicyTemplates.courseSubjectKey(workspace, courseSubjectId);
            assertEquals(
                    1,
                    countRows(
                            "SELECT count(*) FROM authz_projection WHERE target_key = ?"
                                    + " AND state = 'READY' AND applied_hash = desired_hash",
                            courseSubjectKey),
                    "Projection của môn–khóa đã áp đúng desired hash");
            String studentsGroup =
                    "/gk/workspaces/"
                            + workspace
                            + "/course-subjects/"
                            + courseSubjectId
                            + "/students";
            assertTrue(
                    groupPathsOf(student.subject()).contains(studentsGroup),
                    "Học sinh vào group học sinh của môn–khóa sau khi ghi danh đồng bộ");

            AuthorizationOperation assignOperation =
                    assignments.assignTeachers(
                            owner.userId(),
                            workspace,
                            courseSubjectId,
                            List.of(teacher.userId()),
                            key(),
                            hash());
            awaitOperationSucceeded(assignOperation.getId());
            String teachersGroup =
                    "/gk/workspaces/"
                            + workspace
                            + "/course-subjects/"
                            + courseSubjectId
                            + "/teachers";
            assertTrue(
                    groupPathsOf(teacher.subject()).contains(teachersGroup),
                    "Giáo viên vào group giáo viên của môn–khóa sau khi phân công đồng bộ");

            AuthorizationOperation homeroomOperation =
                    assignments.assignHomeroom(
                            owner.userId(), workspace, clazz.id(), teacher.userId(), key(), hash());
            awaitOperationSucceeded(homeroomOperation.getId());
            String homeroomGroup =
                    "/gk/workspaces/" + workspace + "/classes/" + clazz.id() + "/homeroom";
            assertTrue(
                    groupPathsOf(teacher.subject()).contains(homeroomGroup),
                    "Chủ nhiệm vào group chủ nhiệm của lớp sau khi phân công đồng bộ");

            AuthorizationOperation roleOperation =
                    centers.replaceRoles(
                            owner.userId(),
                            workspace,
                            teacherMembership,
                            Set.of(MembershipRole.Role.MANAGER),
                            etag(teacherMembership),
                            key(),
                            hash());
            awaitOperationSucceeded(roleOperation.getId());
            assertFalse(
                    groupPathsOf(teacher.subject()).contains(teachersGroup),
                    "Gỡ TEACHER lấy giáo viên ra khỏi group giáo viên ngay trong revision đó");
            assertFalse(
                    groupPathsOf(teacher.subject()).contains(homeroomGroup),
                    "Gỡ TEACHER lấy giáo viên ra khỏi group chủ nhiệm");
            assertEquals(
                    "REVOKED",
                    jdbc.queryForObject(
                            "SELECT state FROM teaching_assignment WHERE teacher_user_id = ?",
                            String.class,
                            teacher.userId()));
        } finally {
            if (workspace != null && organizationId != null) {
                cleanupWorkspaceDirectoryService(workspace, organizationId);
            }
            operator.delete();
            owner.delete();
            teacher.delete();
            student.delete();
        }
    }

    // ---------- Hỗ trợ ----------

    /** ETag strong của version hiện hành của một membership, theo hợp đồng If-Match của service. */
    private String etag(UUID membershipId) {
        long version =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT version FROM center_membership WHERE id = ?",
                                Long.class,
                                membershipId));
        return "\"" + version + "\"";
    }

    private int countRows(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }

    private static String key() {
        return "enroll-assign-" + UUID.randomUUID();
    }

    /** Hash payload hợp lệ theo CHECK của operation: SHA-256 hex của một giá trị ngẫu nhiên. */
    private static String hash() {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private void awaitOperationSucceeded(UUID operationId) {
        AtomicBoolean succeeded = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            await().atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        List<String> states =
                                jdbc.queryForList(
                                        "SELECT state FROM authorization_operation WHERE id = ?",
                                        String.class,
                                        operationId);
                        if (states.isEmpty()) {
                            return false;
                        }
                        String state = states.getFirst();
                        boolean done = "SUCCEEDED".equals(state);
                        if (done) {
                            succeeded.set(true);
                            return true;
                        }
                        boolean failed = "FAILED".equals(state);
                        if (failed) {
                            String errorCode =
                                    jdbc.queryForObject(
                                            "SELECT last_error_code FROM authorization_operation"
                                                    + " WHERE id = ?",
                                            String.class,
                                            operationId);
                            failure.set("Operation FAILED: " + operationId + " error=" + errorCode);
                            return true;
                        }
                        return false;
                    });
        } catch (ConditionTimeoutException ignored) {
            // Hết 90 giây: fail ở dưới như vòng poll cũ.
        }
        if (failure.get() != null) {
            fail(failure.get());
        }
        if (!succeeded.get()) {
            fail("Operation did not succeed in time: " + operationId);
        }
    }

    // ---------- Seed dữ kiện thư mục bằng JDBC (mẫu TeachingServiceIT) ----------

    private UUID seedAccount() {
        UUID account = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,"
                            + "email_verified,origin,identity_state)"
                            + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                    account,
                    "enroll-assign-" + account,
                    "enroll-assign-user-" + account,
                    "enroll-assign-" + account + "@example.test");
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

    private UUID addMembership(UUID workspaceId, UUID userId, String role) {
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
        return membership;
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
                    "enroll-assign-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    // ---------- Keycloak TEST realm qua Admin API (mẫu CenterServiceIT) ----------

    private record Fixture(UUID userId, String subject) {
        void delete() {
            deleteWithConflictRetry(() -> deleteResource(admin("/users/" + subject)));
        }
    }

    private Fixture newAccount(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("email", username + "@example.test");
        body.put("emailVerified", true);
        body.put("requiredActions", List.of());
        body.put(
                "credentials",
                List.of(
                        Map.of(
                                "type",
                                "password",
                                "value",
                                "enroll-assign-password-" + UUID.randomUUID(),
                                "temporary",
                                false)));
        var response =
                HTTP.post()
                        .uri(admin("/users"))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        String subject = path.substring(path.lastIndexOf('/') + 1);
        UUID userId = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    userId,
                    subject,
                    username,
                    username + "@example.test",
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    userId);
            return null;
        });
        return new Fixture(userId, subject);
    }

    private UUID createCenterAndAwait(Fixture operator, Fixture owner) throws Exception {
        AuthorizationOperation operation =
                centers.createCenter(
                        operator.userId(),
                        "Trung tâm " + UUID.randomUUID(),
                        owner.userId(),
                        "create-" + UUID.randomUUID(),
                        idempotency.payloadHash(
                                "POST", "/api/test", JSON.readTree("{\"name\":\"fixture\"}")));
        awaitOperationSucceeded(operation.getId());
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT workspace_id FROM center WHERE owner_user_id = ?",
                        UUID.class,
                        owner.userId()),
                "Center phải tồn tại sau khi tạo");
    }

    private Set<String> groupPathsOf(String subject) {
        List<Map<String, Object>> groups =
                HTTP.get()
                        .uri(admin("/users/" + subject + "/groups"))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (groups == null) {
            return Set.of();
        }
        return Set.copyOf(groups.stream().map(group -> String.valueOf(group.get("path"))).toList());
    }

    private void cleanupWorkspaceDirectoryService(UUID workspaceId, String organizationId) {
        deleteResource(admin("/organizations/" + organizationId));
        Map<String, Object> group =
                HTTP.get()
                        .uri(admin("/group-by-path/gk/workspaces/" + workspaceId))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(MAP);
        if (group != null && group.get("id") != null) {
            deleteResource(admin("/groups/" + group.get("id")));
        }
    }

    private static void seedEligibilityGroup() {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : List.of("gk", "identity", "eligible")) {
            path.append('/').append(segment);
            String currentPath = path.toString();
            String found = null;
            try {
                Map<String, Object> group =
                        HTTP.get()
                                .uri(admin("/group-by-path" + currentPath))
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
                found = createResource(admin(endpoint), Map.of("name", segment));
            }
            parentId = found;
        }
    }

    private static void ensureApiClient() {
        String audience = fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + audience))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        String clientId =
                createResource(
                        admin("/clients"),
                        Map.of(
                                "clientId",
                                audience,
                                "protocol",
                                "openid-connect",
                                "enabled",
                                true,
                                "publicClient",
                                false,
                                "standardFlowEnabled",
                                false,
                                "directAccessGrantsEnabled",
                                false,
                                "authorizationServicesEnabled",
                                true,
                                "serviceAccountsEnabled",
                                true,
                                "fullScopeAllowed",
                                false,
                                "authorizationSettings",
                                Map.of(
                                        "policyEnforcementMode",
                                        "ENFORCING",
                                        "allowRemoteResourceManagement",
                                        true,
                                        "decisionStrategy",
                                        "UNANIMOUS")));
        removeClientDefaults(clientId);
    }

    private static void removeClientDefaults(String clientId) {
        String root = "/clients/" + clientId + "/authz/resource-server";
        for (Map<String, Object> permission : getList(admin(root + "/permission/resource"))) {
            if (DEFAULT_PERMISSION.equals(permission.get("name"))) {
                deleteResource(admin(root + "/permission/resource/" + permission.get("id")));
            }
        }
        for (Map<String, Object> resource : getList(admin(root + "/resource"))) {
            if (DEFAULT_RESOURCE.equals(resource.get("name"))) {
                deleteResource(admin(root + "/resource/" + resource.get("_id")));
            }
        }
        for (Map<String, Object> policy : getList(admin(root + "/policy"))) {
            if (DEFAULT_POLICY.equals(policy.get("name"))) {
                deleteResource(admin(root + "/policy/" + policy.get("id")));
            }
        }
    }

    private static List<Map<String, Object>> getList(String uri) {
        List<Map<String, Object>> result =
                HTTP.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST_MAP);
        if (result == null) {
            throw new IllegalStateException("Keycloak list not readable: " + uri);
        }
        return result;
    }

    private static String createResource(String uri, Map<String, ?> body) {
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

    private static void deleteResource(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                .retrieve()
                .toBodilessEntity();
    }

    private static String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private static String masterTokenStatic() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("Master token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    static String fixture(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }
}
