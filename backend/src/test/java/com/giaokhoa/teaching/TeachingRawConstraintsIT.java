package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Kiểm chứng RLS và FK của các bảng giảng dạy bằng SQL thô qua một role NOSUPERUSER NOBYPASSRLS,
 * không qua service: user của stack TEST là superuser nên tự nó không chịu RLS, chỉ role runtime
 * mới chứng minh được policy. Ba lớp chốt chặn phải đứng vững ngay cả khi tầng ứng dụng bị bỏ qua:
 * RLS theo context workspace (không context thì không thấy hàng nào, context A không thấy hàng B,
 * WITH CHECK chặn ghi hàng của workspace khác), FK composite (mọi tham chiếu chéo workspace/lớp đều
 * vi phạm), và các trigger nghiệp vụ ở tầng DB (cùng lớp cho ghi danh, membership ACTIVE cho
 * roster/chủ nhiệm ở center, unique từng phần cho cặp hiện hành, cấm hard-delete và đổi định danh).
 *
 * <p>Seed cấu trúc giảng dạy bằng SQL thô qua kết nối ứng dụng (superuser) trong schema cô lập;
 * phần khẳng định chạy hoàn toàn qua role runtime.
 */
// Harness tối thiểu: chỉ kiểm chứng schema/bảng bằng JDBC thuần trên schema riêng nên tự chạy
// Flyway và tự cấp JdbcTemplate cho fixture, không dựng ApplicationContext của ứng dụng.
class TeachingRawConstraintsIT extends TeachingFixture {
    private static final String SCHEMA =
            "teaching_raw_" + UUID.randomUUID().toString().replace("-", "");
    private static final String RUNTIME_ROLE = "teaching_raw_runtime";
    private static final List<String> TEACHING_TABLES =
            List.of(
                    "school_class",
                    "class_student",
                    "course",
                    "course_subject",
                    "enrollment",
                    "teaching_assignment",
                    "homeroom_assignment");

    private static JdbcTemplate harnessJdbc;
    private static PlatformTransactionManager harnessTx;

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
        harnessJdbc = new JdbcTemplate(source);
        harnessTx = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void bindFixture() {
        jdbc = harnessJdbc;
        transactionManager = harnessTx;
    }

    @AfterAll
    static void removeSchema() {
        harnessJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    private record RawGraph(
            UUID workspaceA,
            UUID workspaceB,
            UUID classA1,
            UUID classA2,
            UUID classB1,
            UUID courseSubjectA1M1,
            UUID courseSubjectA1M2,
            UUID courseSubjectA2M1,
            UUID courseSubjectB1M1,
            UUID classStudentA,
            UUID studentA,
            UUID teacherA,
            UUID outsider) {}

    @Test
    void rawSelectSeesOnlyRowsOfContextWorkspace() {
        RawGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            for (String table : TEACHING_TABLES) {
                assertEquals(
                        0,
                        countRows(runtime, table, graph.workspaceA()),
                        "Không có context workspace thì RLS che toàn bộ bảng " + table);
            }
            assertEquals(
                    2,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "school_class", graph.workspaceA())));
            assertEquals(
                    2,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "class_student", graph.workspaceA())));
            assertEquals(
                    2,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "course", graph.workspaceA())));
            assertEquals(
                    3,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "course_subject", graph.workspaceA())));
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "enrollment", graph.workspaceA())));
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "teaching_assignment", graph.workspaceA())));
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "homeroom_assignment", graph.workspaceA())));
            assertEquals(
                    0,
                    inContext(
                            runtime,
                            graph.workspaceA(),
                            () -> countRows(runtime, "school_class", graph.workspaceB())),
                    "Context A không thấy hàng nào của workspace B");
            assertEquals(
                    1,
                    inContext(
                            runtime,
                            graph.workspaceB(),
                            () -> countRows(runtime, "school_class", graph.workspaceB())),
                    "Context B thấy đúng hàng của B");
            assertEquals(
                    0,
                    inContext(
                            runtime,
                            graph.workspaceB(),
                            () -> countRows(runtime, "school_class", graph.workspaceA())),
                    "Context B không thấy hàng nào của workspace A");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawInsertUnderWrongWorkspaceContextViolatesRlsCheck() {
        RawGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "42501",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO school_class (id, workspace_id, name, state)"
                                                        + " VALUES (?, ?, 'Lớp lậu', 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceB());
                                        return null;
                                    }),
                    "WITH CHECK của RLS chặn ghi lớp mang workspace khác context");
            assertSqlState(
                    "42501",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO enrollment (id, workspace_id, class_student_id,"
                                                        + " course_subject_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceB(),
                                                graph.classStudentA(),
                                                graph.courseSubjectA1M1());
                                        return null;
                                    }),
                    "WITH CHECK của RLS chặn ghi danh mang workspace khác context");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawCompositeForeignKeysRejectCrossWorkspaceReferences() {
        RawGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO course (id, workspace_id, class_id, name, state)"
                                                        + " VALUES (?, ?, ?, 'Khóa lậu', 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classB1());
                                        return null;
                                    }),
                    "FK composite chặn khóa thuộc lớp của workspace khác");
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO course_subject (id, workspace_id, class_id, course_id,"
                                                        + " subject_id, state) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classA2(),
                                                courseOf(graph.courseSubjectA1M1()),
                                                subjectOf(graph.courseSubjectA1M2()));
                                        return null;
                                    }),
                    "FK composite ba cột chặn môn–khóa khai lớp khác với lớp thật của khóa");
            // Ghi danh chéo workspace bị trigger cùng lớp chặn ở 23514 trước FK composite:
            // trigger BEFORE INSERT chạy trước kiểm tra FK và hai phía khác lớp.
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO enrollment (id, workspace_id, class_student_id,"
                                                        + " course_subject_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classStudentA(),
                                                graph.courseSubjectB1M1());
                                        return null;
                                    }),
                    "Trigger cùng lớp chặn ghi danh nối sang môn–khóa của workspace khác");
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO teaching_assignment (id, workspace_id,"
                                                        + " course_subject_id, teacher_user_id, state)"
                                                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.courseSubjectB1M1(),
                                                graph.teacherA());
                                        return null;
                                    }),
                    "FK composite chặn phân công nối sang môn–khóa của workspace khác");
            assertSqlState(
                    "23503",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO homeroom_assignment (id, workspace_id, class_id,"
                                                        + " teacher_user_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classB1(),
                                                graph.teacherA());
                                        return null;
                                    }),
                    "FK composite chặn chủ nhiệm nối sang lớp của workspace khác");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawEnrollmentAcrossClassesInSameWorkspaceViolatesSameClassTrigger() {
        RawGraph graph = seedRawGraph();
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
                                                "INSERT INTO enrollment (id, workspace_id, class_student_id,"
                                                        + " course_subject_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classStudentA(),
                                                graph.courseSubjectA2M1());
                                        return null;
                                    }),
                    "Trigger cùng lớp chặn ghi danh khi học sinh lớp này nối môn–khóa của lớp khác");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawRosterAndHomeroomInsertsRequireActiveCenterMembership() {
        RawGraph graph = seedRawGraph();
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
                                                "INSERT INTO class_student (id, workspace_id, class_id,"
                                                        + " student_user_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classA1(),
                                                graph.outsider());
                                        return null;
                                    }),
                    "Trigger roster chặn học sinh center chưa có membership ACTIVE");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO homeroom_assignment (id, workspace_id, class_id,"
                                                        + " teacher_user_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classA1(),
                                                graph.outsider());
                                        return null;
                                    }),
                    "Trigger chủ nhiệm chặn giáo viên chưa có membership ACTIVE của center");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawCurrentUniquenessAndHistoryGuardsHold() {
        RawGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23505",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "INSERT INTO class_student (id, workspace_id, class_id,"
                                                        + " student_user_id, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                                UUID.randomUUID(),
                                                graph.workspaceA(),
                                                graph.classA1(),
                                                graph.studentA());
                                        return null;
                                    }),
                    "Unique từng phần chặn cặp roster hiện hành trùng");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "DELETE FROM school_class WHERE id = ?",
                                                graph.classA1());
                                        return null;
                                    }),
                    "Trigger giữ lịch sử chặn hard-delete lớp");
            assertSqlState(
                    "23514",
                    () ->
                            inContext(
                                    runtime,
                                    graph.workspaceA(),
                                    () -> {
                                        runtime.update(
                                                "UPDATE school_class SET workspace_id = ? WHERE id = ?",
                                                graph.workspaceB(),
                                                graph.classA2());
                                        return null;
                                    }),
                    "Trigger giữ lịch sử chặn đổi workspace của lớp");

            inContext(
                    runtime,
                    graph.workspaceA(),
                    () -> {
                        runtime.update(
                                "UPDATE class_student SET state = 'REVOKED', revoked_at = now() WHERE id = ?",
                                graph.classStudentA());
                        runtime.update(
                                "INSERT INTO class_student (id, workspace_id, class_id, student_user_id, state)"
                                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                                UUID.randomUUID(),
                                graph.workspaceA(),
                                graph.classA1(),
                                graph.studentA());
                        return null;
                    });
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM class_student WHERE class_id = ? AND student_user_id = ?",
                            Integer.class,
                            graph.classA1(),
                            graph.studentA()),
                    "Sau thu hồi, ghi danh lại bằng hàng mới và lịch sử cũ vẫn còn");
        } finally {
            closeRuntime();
        }
    }

    // ---------- Seed cấu trúc giảng dạy bằng SQL thô ----------

    private UUID seedRawAccount() {
        String keycloakSub = UUID.randomUUID().toString();
        // Tài khoản đã xác minh email phải mang email thật theo ràng buộc ck_user_verified_email.
        return seedAccount(
                keycloakSub, "SELF_REGISTERED", "raw-" + keycloakSub + "@example.test", true);
    }

    private RawGraph seedRawGraph() {
        UUID ownerA = seedRawAccount();
        UUID ownerB = seedRawAccount();
        UUID workspaceA = seedCenter(ownerA);
        UUID workspaceB = seedCenter(ownerB);
        UUID studentA = seedRawAccount();
        UUID teacherA = seedRawAccount();
        UUID outsider = seedRawAccount();
        addMembership(workspaceA, studentA, "STUDENT");
        addMembership(workspaceA, teacherA, "TEACHER");
        List<UUID> subjects = jdbc.queryForList("SELECT id FROM subject ORDER BY code", UUID.class);
        UUID subjectM1 = subjects.get(0);
        UUID subjectM2 = subjects.get(1);

        UUID classA1 = insertClass(workspaceA, "Lớp A1");
        UUID classA2 = insertClass(workspaceA, "Lớp A2");
        UUID classB1 = insertClass(workspaceB, "Lớp B1");
        UUID courseA1 = insertCourse(workspaceA, classA1, "Khóa A1");
        UUID courseA2 = insertCourse(workspaceA, classA2, "Khóa A2");
        UUID courseB1 = insertCourse(workspaceB, classB1, "Khóa B1");
        UUID csA1M1 = insertCourseSubject(workspaceA, classA1, courseA1, subjectM1);
        UUID csA1M2 = insertCourseSubject(workspaceA, classA1, courseA1, subjectM2);
        UUID csA2M1 = insertCourseSubject(workspaceA, classA2, courseA2, subjectM1);
        UUID csB1M1 = insertCourseSubject(workspaceB, classB1, courseB1, subjectM1);
        UUID classStudentA = insertClassStudent(workspaceA, classA1, studentA);
        insertClassStudent(workspaceA, classA2, studentA);
        insertRow(
                "INSERT INTO enrollment (id, workspace_id, class_student_id, course_subject_id,"
                        + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceA,
                classStudentA,
                csA1M1);
        insertRow(
                "INSERT INTO teaching_assignment (id, workspace_id, course_subject_id, teacher_user_id,"
                        + " state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceA,
                csA1M1,
                teacherA);
        insertRow(
                "INSERT INTO homeroom_assignment (id, workspace_id, class_id, teacher_user_id, state)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceA,
                classA1,
                teacherA);
        return new RawGraph(
                workspaceA,
                workspaceB,
                classA1,
                classA2,
                classB1,
                csA1M1,
                csA1M2,
                csA2M1,
                csB1M1,
                classStudentA,
                studentA,
                teacherA,
                outsider);
    }

    private UUID insertClass(UUID workspaceId, String name) {
        return insertRow(
                "INSERT INTO school_class (id, workspace_id, name, state) VALUES (?, ?, ?, 'ACTIVE')",
                workspaceId,
                name);
    }

    private UUID insertCourse(UUID workspaceId, UUID classId, String name) {
        return insertRow(
                "INSERT INTO course (id, workspace_id, class_id, name, state) VALUES (?, ?, ?, ?, 'ACTIVE')",
                workspaceId,
                classId,
                name);
    }

    private UUID insertCourseSubject(
            UUID workspaceId, UUID classId, UUID courseId, UUID subjectId) {
        return insertRow(
                "INSERT INTO course_subject (id, workspace_id, class_id, course_id, subject_id, state)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
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

    private UUID insertRow(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] full = new Object[args.length + 1];
        full[0] = id;
        System.arraycopy(args, 0, full, 1, args.length);
        jdbc.update(sql, full);
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
        // đúng mẫu EnrollmentAssignmentIT.
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

    private UUID courseOf(UUID courseSubjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT course_id FROM course_subject WHERE id = ?",
                        UUID.class,
                        courseSubjectId));
    }

    private UUID subjectOf(UUID courseSubjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT subject_id FROM course_subject WHERE id = ?",
                        UUID.class,
                        courseSubjectId));
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
