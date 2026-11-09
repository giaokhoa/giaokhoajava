package com.giaokhoa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.giaokhoa.support.IntegrationEnvironment;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng RLS, FK và trigger của ba bảng mẫu (hoạt động, bài nộp, kết quả) bằng SQL thô qua một
 * role NOSUPERUSER NOBYPASSRLS, không qua service: user của stack TEST là superuser nên tự nó không
 * chịu RLS, chỉ role runtime mới chứng minh được policy. Ba lớp chốt chặn phải đứng vững ngay cả
 * khi tầng ứng dụng bị bỏ qua: RLS theo context workspace, FK composite (môn–khóa, kỳ gói, cặp
 * resource/version và chuỗi hoạt động → bài nộp → kết quả cùng workspace), và các trigger nghiệp vụ
 * ở tầng DB (bài nộp cần ghi danh hiện hành và không vượt số lượt snapshot; học sinh/môn của kết
 * quả suy ra từ bài nộp; snapshot của hoạt động và kết quả đã công bố bất biến; cấm hard-delete).
 *
 * <p>Seed đồ thị mẫu bằng SQL thô qua kết nối ứng dụng (superuser) trong schema cô lập; phần khẳng
 * định chạy hoàn toàn qua role runtime.
 */
// Harness tối thiểu: chỉ kiểm chứng schema/bảng bằng JDBC thuần trên schema riêng nên tự chạy
// Flyway và tự cấp JdbcTemplate, không dựng ApplicationContext của ứng dụng.
class SampleRawConstraintsIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "sample_raw_" + UUID.randomUUID().toString().replace("-", "");
    private static final String RUNTIME_ROLE = "sample_raw_runtime";
    private static final List<String> SAMPLE_TABLES =
            List.of("sample_activity", "sample_submission", "sample_result");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;

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
        var source =
                new DriverManagerDataSource(
                        testDatabaseUrl() + "?currentSchema=" + SCHEMA + ",public",
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")),
                        Objects.requireNonNull(System.getenv("TEST_DATABASE_PASSWORD")));
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
    }

    @AfterAll
    static void removeSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    private record SampleGraph(
            UUID workspaceA,
            UUID workspaceB,
            UUID courseSubjectA1M1,
            UUID courseSubjectA1M2,
            UUID courseSubjectB1M1,
            UUID periodA,
            UUID periodB,
            UUID resourceA,
            UUID versionA1,
            UUID resourceA2,
            UUID activityA,
            UUID submissionA,
            UUID resultA,
            UUID studentA,
            UUID revokedStudent,
            UUID outsider,
            UUID teacherA) {}

    @Test
    void rawSelectSeesOnlyRowsOfContextWorkspace() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            for (String table : SAMPLE_TABLES) {
                assertEquals(
                        0,
                        countRows(runtime, table, graph.workspaceA()),
                        "Không có context workspace thì RLS che toàn bộ bảng " + table);
            }
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "sample_activity", graph.workspaceA())));
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "sample_submission", graph.workspaceA())));
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "sample_result", graph.workspaceA())));
            assertEquals(
                    0,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "sample_activity", graph.workspaceB())),
                    "Context A không thấy hoạt động của workspace B");
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceB(),
                            () -> countRows(runtime, "sample_activity", graph.workspaceB())),
                    "Context B thấy đúng hoạt động của B");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawInsertUnderWrongWorkspaceContextViolatesRlsCheck() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "42501",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertActivityRow(
                                                runtime,
                                                graph.workspaceB(),
                                                graph.courseSubjectB1M1(),
                                                graph.resourceA(),
                                                graph.versionA1(),
                                                graph.teacherA(),
                                                graph.periodB());
                                        return null;
                                    }),
                    "WITH CHECK của RLS chặn ghi hoạt động mang workspace khác context");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawCompositeForeignKeysRejectCrossReferences() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertActivityRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.courseSubjectB1M1(),
                                                graph.resourceA(),
                                                graph.versionA1(),
                                                graph.teacherA(),
                                                graph.periodA());
                                        return null;
                                    }),
                    "FK composite chặn hoạt động nối sang môn–khóa của workspace khác");
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertActivityRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.courseSubjectA1M1(),
                                                graph.resourceA(),
                                                graph.versionA1(),
                                                graph.teacherA(),
                                                graph.periodB());
                                        return null;
                                    }),
                    "FK composite chặn hoạt động gắn kỳ gói của workspace khác");
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertActivityRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.courseSubjectA1M1(),
                                                graph.resourceA2(),
                                                graph.versionA1(),
                                                graph.teacherA(),
                                                graph.periodA());
                                        return null;
                                    }),
                    "FK composite chặn hoạt động dùng version của resource khác");
            // Bài nộp chéo workspace bị trigger kiểm tra hoạt động chặn ở 23514 trước FK
            // composite: trigger BEFORE INSERT chạy trước kiểm tra FK và không thấy hoạt
            // động của workspace khác trong context hiện tại.
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceB(),
                                    () -> {
                                        insertSubmissionRow(
                                                runtime,
                                                graph.workspaceB(),
                                                graph.activityA(),
                                                graph.studentA(),
                                                1);
                                        return null;
                                    }),
                    "Trigger chặn bài nộp gắn hoạt động của workspace khác");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawSubmissionRequiresCurrentEnrollmentAndAttemptLimit() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertSubmissionRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.activityA(),
                                                graph.outsider(),
                                                1);
                                        return null;
                                    }),
                    "Trigger chặn bài nộp của học sinh chưa ghi danh vào môn–khóa");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertSubmissionRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.activityA(),
                                                graph.revokedStudent(),
                                                1);
                                        return null;
                                    }),
                    "Trigger chặn bài nộp khi ghi danh đã bị thu hồi");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertSubmissionRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.activityA(),
                                                graph.studentA(),
                                                3);
                                        return null;
                                    }),
                    "Trigger chặn số lượt vượt snapshot attempt_limit của hoạt động");
            assertSqlState(
                    "23505",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertSubmissionRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.activityA(),
                                                graph.studentA(),
                                                1);
                                        return null;
                                    }),
                    "Unique chặn trùng bộ ba hoạt động/học sinh/lượt nộp");

            inContext(
                    runtime,
                    graph.workspaceA(),
                    () -> {
                        insertSubmissionRow(
                                runtime,
                                graph.workspaceA(),
                                graph.activityA(),
                                graph.studentA(),
                                2);
                        return null;
                    });
            assertEquals(
                    2,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "sample_submission", graph.workspaceA())),
                    "Lượt nộp thứ hai trong giới hạn snapshot được ghi");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawResultMustDeriveStudentAndSubjectFromSubmission() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertResultRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.submissionA(),
                                                graph.outsider(),
                                                graph.courseSubjectA1M1(),
                                                "DRAFT",
                                                null);
                                        return null;
                                    }),
                    "Trigger chặn kết quả khai học sinh khác với học sinh của bài nộp");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertResultRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.submissionA(),
                                                graph.studentA(),
                                                graph.courseSubjectA1M2(),
                                                "DRAFT",
                                                null);
                                        return null;
                                    }),
                    "Trigger chặn kết quả khai môn–khóa khác với môn–khóa của hoạt động");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertResultRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.submissionA(),
                                                graph.studentA(),
                                                graph.courseSubjectA1M1(),
                                                "PUBLISHED",
                                                null);
                                        return null;
                                    }),
                    "CHECK chặn kết quả PUBLISHED thiếu published_at");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        insertResultRow(
                                                runtime,
                                                graph.workspaceA(),
                                                graph.submissionA(),
                                                graph.studentA(),
                                                graph.courseSubjectA1M1(),
                                                "DRAFT",
                                                Instant.now());
                                        return null;
                                    }),
                    "CHECK chặn kết quả DRAFT đã có published_at");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawSnapshotAndHistoryGuardsHold() {
        SampleGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE sample_activity SET attempt_limit = 9"
                                                        + " WHERE id = ?",
                                                graph.activityA());
                                        return null;
                                    }),
                    "Trigger giữ snapshot chặn đổi attempt_limit của hoạt động");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE sample_activity SET subscription_period_id = ?"
                                                        + " WHERE id = ?",
                                                graph.periodB(),
                                                graph.activityA());
                                        return null;
                                    }),
                    "Trigger giữ snapshot chặn đổi kỳ gói của hoạt động");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE sample_submission SET content_stub = 'Đổi bài'"
                                                        + " WHERE id = ?",
                                                graph.submissionA());
                                        return null;
                                    }),
                    "Trigger giữ lịch sử chặn sửa nội dung bài đã nộp");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "DELETE FROM sample_result WHERE id = ?",
                                                graph.resultA());
                                        return null;
                                    }),
                    "Trigger giữ lịch sử chặn hard-delete kết quả");

            inContext(
                    runtime,
                    graph.workspaceA(),
                    () -> {
                        runtime.update(
                                "UPDATE sample_activity SET due_at = ? WHERE id = ?",
                                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                                graph.activityA());
                        runtime.update(
                                "UPDATE sample_result SET score_stub = '9' WHERE id = ?",
                                graph.resultA());
                        runtime.update(
                                "UPDATE sample_result SET state = 'PUBLISHED', published_at = now()"
                                        + " WHERE id = ?",
                                graph.resultA());
                        return null;
                    });
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE sample_result SET comment_stub = 'Đổi nhận xét'"
                                                        + " WHERE id = ?",
                                                graph.resultA());
                                        return null;
                                    }),
                    "Kết quả đã công bố bị đóng băng, không sửa được nhận xét");

            inContext(
                    runtime,
                    graph.workspaceA(),
                    () -> {
                        runtime.update(
                                "UPDATE sample_activity SET state = 'CLOSED' WHERE id = ?",
                                graph.activityA());
                        runtime.update(
                                "UPDATE sample_activity SET state = 'ARCHIVED' WHERE id = ?",
                                graph.activityA());
                        return null;
                    });
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE sample_activity SET due_at = now() WHERE id = ?",
                                                graph.activityA());
                                        return null;
                                    }),
                    "Hoạt động đã lưu trữ bị đóng băng toàn bộ");
        } finally {
            closeRuntime();
        }
    }

    // ---------- Seed đồ thị mẫu bằng SQL thô ----------

    private SampleGraph seedRawGraph() {
        UUID ownerA = seedRawAccount();
        UUID ownerB = seedRawAccount();
        UUID workspaceA = seedCenter(ownerA);
        UUID workspaceB = seedCenter(ownerB);
        UUID teacherA = seedRawAccount();
        UUID studentA = seedRawAccount();
        UUID revokedStudent = seedRawAccount();
        UUID outsider = seedRawAccount();
        addMembership(workspaceA, teacherA, "TEACHER");
        addMembership(workspaceA, studentA, "STUDENT");
        addMembership(workspaceA, revokedStudent, "STUDENT");
        List<UUID> subjects = jdbc.queryForList("SELECT id FROM subject ORDER BY code", UUID.class);
        UUID subjectM1 = subjects.get(0);
        UUID subjectM2 = subjects.get(1);

        UUID classA1 = insertClass(workspaceA, "Lớp A1");
        UUID courseA1 = insertCourse(workspaceA, classA1, "Khóa A1");
        UUID csA1M1 = insertCourseSubject(workspaceA, classA1, courseA1, subjectM1);
        UUID csA1M2 = insertCourseSubject(workspaceA, classA1, courseA1, subjectM2);
        UUID classB1 = insertClass(workspaceB, "Lớp B1");
        UUID courseB1 = insertCourse(workspaceB, classB1, "Khóa B1");
        UUID csB1M1 = insertCourseSubject(workspaceB, classB1, courseB1, subjectM1);

        UUID classStudentA = insertClassStudent(workspaceA, classA1, studentA);
        insertRow(
                "INSERT INTO enrollment (id, workspace_id, class_student_id, course_subject_id,"
                        + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceA,
                classStudentA,
                csA1M1);
        UUID classStudentRevoked = insertClassStudent(workspaceA, classA1, revokedStudent);
        UUID enrollmentRevoked =
                insertRow(
                        "INSERT INTO enrollment (id, workspace_id, class_student_id,"
                                + " course_subject_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                        workspaceA,
                        classStudentRevoked,
                        csA1M1);
        jdbc.update(
                "UPDATE enrollment SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                enrollmentRevoked);

        UUID periodA = seedSubscription(workspaceA, ownerA);
        UUID periodB = seedSubscription(workspaceB, ownerB);
        UUID resourceA = insertResource(workspaceA, teacherA, subjectM1);
        UUID versionA1 = insertResourceVersion(resourceA, 1);
        UUID resourceA2 = insertResource(workspaceA, teacherA, subjectM1);
        insertResourceVersion(resourceA2, 1);
        UUID resourceB = insertResource(workspaceB, ownerB, subjectM1);
        UUID versionB1 = insertResourceVersion(resourceB, 1);

        UUID activityA =
                insertActivityRow(
                        jdbc, workspaceA, csA1M1, resourceA, versionA1, teacherA, periodA);
        insertActivityRow(jdbc, workspaceB, csB1M1, resourceB, versionB1, ownerB, periodB);
        UUID submissionA = insertSubmissionRow(jdbc, workspaceA, activityA, studentA, 1);
        UUID resultA =
                insertResultRow(jdbc, workspaceA, submissionA, studentA, csA1M1, "DRAFT", null);
        return new SampleGraph(
                workspaceA,
                workspaceB,
                csA1M1,
                csA1M2,
                csB1M1,
                periodA,
                periodB,
                resourceA,
                versionA1,
                resourceA2,
                activityA,
                submissionA,
                resultA,
                studentA,
                revokedStudent,
                outsider,
                teacherA);
    }

    private UUID seedRawAccount() {
        String keycloakSub = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        // Bất biến của DB: tài khoản chỉ commit được khi personal workspace tồn tại trong
        // cùng giao dịch, nên hai insert phải đi cùng một transaction (mẫu seedAccount của
        // TeachingFixture).
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    // Tài khoản đã xác minh email phải mang email thật theo ràng buộc
                    // ck_user_verified_email.
                    jdbc.update(
                            "INSERT INTO user_account (id, keycloak_sub, username, email,"
                                    + " email_verified, origin, identity_state) VALUES (?, ?,"
                                    + " ?, ?, true, 'SELF_REGISTERED', 'ACTIVE')",
                            id,
                            keycloakSub,
                            "sample-user-" + id,
                            "raw-" + keycloakSub + "@example.test");
                    jdbc.update(
                            "INSERT INTO workspace (id, kind, owner_user_id, state)"
                                    + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                            UUID.randomUUID(),
                            id);
                });
        return id;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER của chủ sở
        // hữu, nên toàn bộ khối seed center đi cùng một transaction (mẫu seedCenter của
        // TeachingFixture).
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    jdbc.update(
                            "INSERT INTO workspace (id, kind, center_id, state) VALUES (?,"
                                    + " 'CENTER', ?, 'ACTIVE')",
                            workspace,
                            center);
                    jdbc.update(
                            "INSERT INTO center (id, workspace_id, keycloak_organization_id,"
                                    + " name, owner_user_id, state) VALUES (?, ?, ?,"
                                    + " 'Center test', ?, 'ACTIVE')",
                            center,
                            workspace,
                            "center-org-" + center,
                            ownerUserId);
                    jdbc.update(
                            "INSERT INTO center_membership (id, workspace_id, user_id, state,"
                                    + " joined_at) VALUES (?, ?, ?, 'ACTIVE', now())",
                            ownerMembership,
                            workspace,
                            ownerUserId);
                    jdbc.update(
                            "INSERT INTO membership_role (membership_id, role) VALUES (?,"
                                    + " 'OWNER')",
                            ownerMembership);
                });
        return workspace;
    }

    private void addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO center_membership (id, workspace_id, user_id, state, joined_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', now())",
                membership,
                workspaceId,
                userId);
        for (String role : roles) {
            jdbc.update(
                    "INSERT INTO membership_role (membership_id, role) VALUES (?, ?)",
                    membership,
                    role);
        }
    }

    private UUID seedSubscription(UUID workspaceId, UUID confirmedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO subscription_period (id, workspace_id, valid_from, expires_at,"
                        + " confirmation_ref, confirmed_by, confirmed_at)"
                        + " VALUES (?, ?, now() - interval '1 day', now() + interval '30 days',"
                        + " ?, ?, now())",
                id,
                workspaceId,
                "ref-" + id,
                confirmedBy);
        return id;
    }

    private UUID insertClass(UUID workspaceId, String name) {
        return insertRow(
                "INSERT INTO school_class (id, workspace_id, name, state) VALUES (?, ?, ?, 'ACTIVE')",
                workspaceId,
                name);
    }

    private UUID insertCourse(UUID workspaceId, UUID classId, String name) {
        return insertRow(
                "INSERT INTO course (id, workspace_id, class_id, name, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceId,
                classId,
                name);
    }

    private UUID insertCourseSubject(
            UUID workspaceId, UUID classId, UUID courseId, UUID subjectId) {
        return insertRow(
                "INSERT INTO course_subject (id, workspace_id, class_id, course_id, subject_id,"
                        + " state) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                workspaceId,
                classId,
                courseId,
                subjectId);
    }

    private UUID insertClassStudent(UUID workspaceId, UUID classId, UUID studentUserId) {
        return insertRow(
                "INSERT INTO class_student (id, workspace_id, class_id, student_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceId,
                classId,
                studentUserId);
    }

    private UUID insertResource(UUID ownerWorkspaceId, UUID createdBy, UUID subjectId) {
        return insertRow(
                "INSERT INTO resource (id, owner_workspace_id, created_by_user_id, kind,"
                        + " subject_id, state) VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                ownerWorkspaceId,
                createdBy,
                subjectId);
    }

    private UUID insertResourceVersion(UUID resourceId, int versionNo) {
        return insertRow(
                "INSERT INTO resource_version (id, resource_id, version_no, label, body_stub,"
                        + " answer_stub, review_state) VALUES (?, ?, ?, ?, 'Nội dung', 'Đáp án',"
                        + " 'APPROVED')",
                resourceId,
                versionNo,
                "Bản " + versionNo);
    }

    private UUID insertActivityRow(
            JdbcTemplate db,
            UUID workspaceId,
            UUID courseSubjectId,
            UUID resourceId,
            UUID versionId,
            UUID assignedBy,
            UUID periodId) {
        return insertRow(
                db,
                "INSERT INTO sample_activity (id, workspace_id, course_subject_id, resource_id,"
                        + " version_id, assigned_by, assigned_at, due_at, attempt_limit,"
                        + " subscription_period_id, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 2, ?,"
                        + " 'ASSIGNED')",
                workspaceId,
                courseSubjectId,
                resourceId,
                versionId,
                assignedBy,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)),
                periodId);
    }

    private UUID insertSubmissionRow(
            JdbcTemplate db, UUID workspaceId, UUID activityId, UUID studentUserId, int attemptNo) {
        return insertRow(
                db,
                "INSERT INTO sample_submission (id, workspace_id, activity_id, student_user_id,"
                        + " attempt_no, submitted_at, content_stub, state)"
                        + " VALUES (?, ?, ?, ?, ?, now(), 'Bài làm mẫu', 'SUBMITTED')",
                workspaceId,
                activityId,
                studentUserId,
                attemptNo);
    }

    private UUID insertResultRow(
            JdbcTemplate db,
            UUID workspaceId,
            UUID submissionId,
            UUID studentUserId,
            UUID courseSubjectId,
            String state,
            @Nullable Instant publishedAt) {
        UUID id = UUID.randomUUID();
        // Tách hai nhánh để không phải truyền giá trị null qua varargs (NullAway của repo
        // mặc định coi từng phần tử varargs là @NonNull).
        if (publishedAt == null) {
            db.update(
                    "INSERT INTO sample_result (id, workspace_id, submission_id, student_user_id,"
                            + " course_subject_id, score_stub, comment_stub, state, published_at,"
                            + " graded_by) VALUES (?, ?, ?, ?, ?, '8', 'Nhận xét mẫu', ?, NULL,"
                            + " NULL)",
                    id,
                    workspaceId,
                    submissionId,
                    studentUserId,
                    courseSubjectId,
                    state);
        } else {
            db.update(
                    "INSERT INTO sample_result (id, workspace_id, submission_id, student_user_id,"
                            + " course_subject_id, score_stub, comment_stub, state, published_at,"
                            + " graded_by) VALUES (?, ?, ?, ?, ?, '8', 'Nhận xét mẫu', ?, ?, NULL)",
                    id,
                    workspaceId,
                    submissionId,
                    studentUserId,
                    courseSubjectId,
                    state,
                    Timestamp.from(publishedAt));
        }
        return id;
    }

    private UUID insertRow(String sql, Object... args) {
        return insertRow(jdbc, sql, args);
    }

    private UUID insertRow(JdbcTemplate db, String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] full = new Object[args.length + 1];
        full[0] = id;
        System.arraycopy(args, 0, full, 1, args.length);
        db.update(sql, full);
        return id;
    }

    // ---------- Role runtime và context workspace ----------

    private JdbcTemplate openRuntime() {
        String password = UUID.randomUUID().toString();
        jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        jdbc.execute(
                "CREATE ROLE "
                        + RUNTIME_ROLE
                        + " LOGIN PASSWORD '"
                        + password
                        + "' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        // Cấp đủ quyền bảng để RLS/trigger/FK là chốt chặn duy nhất phải bắt được hành vi sai,
        // đúng mẫu TeachingRawConstraintsIT.
        jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        String url = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
        runtimeDataSource = new SingleConnectionDataSource(url, RUNTIME_ROLE, password, true);
        JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource);
        runtime.execute("SET search_path TO " + SCHEMA + ",public");
        return runtime;
    }

    private void closeRuntime() {
        if (runtimeDataSource != null) {
            runtimeDataSource.destroy();
        }
        jdbc.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        jdbc.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        jdbc.execute("REVOKE USAGE ON SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
    }

    private <T> T inContext(JdbcTemplate runtime, UUID workspaceId, Supplier<T> action) {
        DataSourceTransactionManager txManager =
                new DataSourceTransactionManager(
                        Objects.requireNonNull(runtimeDataSource, "Role runtime phải mở trước"));
        return new TransactionTemplate(txManager)
                .execute(_ -> {
                    runtime.queryForObject(
                            "SELECT set_config('app.workspace_id', ?, true)",
                            String.class,
                            workspaceId.toString());
                    return action.get();
                });
    }

    private int countRows(JdbcTemplate runtime, String table, UUID workspaceId) {
        return Objects.requireNonNull(
                runtime.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                        Integer.class,
                        workspaceId));
    }

    private static void assertSqlState(String expectedState, Executable action, String message) {
        DataAccessException thrown = assertThrows(DataAccessException.class, action, message);
        Throwable cursor = thrown;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && expectedState.equals(sql.getSQLState())) {
                return;
            }
            cursor = cursor.getCause();
        }
        fail(message + " — kỳ vọng SQLState " + expectedState + " nhưng nhận: " + thrown);
    }
}
