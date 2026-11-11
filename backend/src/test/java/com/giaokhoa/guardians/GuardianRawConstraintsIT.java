package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.giaokhoa.support.IntegrationEnvironment;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * Kiểm chứng schema guardian_link bằng SQL thô qua một role không phải chủ sở hữu bảng, không qua
 * service. Các lớp chốt chặn ở tầng DB phải đứng vững ngay cả khi tầng ứng dụng bị bỏ qua: FK tới
 * tài khoản/workspace, CHECK trạng thái và hình dạng trường theo trạng thái, unique từng phần cho
 * cặp parent–học sinh hiện hành, trigger membership ACTIVE cho cả hai phía lúc tạo và cho parent
 * tại thời điểm duyệt, cùng trigger giữ lịch sử (cấm hard-delete, cấm đổi định danh, đóng băng
 * trạng thái kết thúc, chỉ hai chuyển trạng thái của vòng đời). Cách ly workspace ở tầng DB nằm ở
 * ranh giới schema, không còn policy theo từng hàng.
 *
 * <p>Seed center/membership bằng SQL thô qua kết nối ứng dụng trong schema cô lập; phần khẳng định
 * chạy hoàn toàn qua role runtime tự tạo của class.
 */
// Harness tối thiểu: chỉ kiểm chứng schema/bảng bằng JDBC thuần trên schema riêng nên tự chạy
// Flyway và tự cấp JdbcTemplate, không dựng ApplicationContext của ứng dụng.
class GuardianRawConstraintsIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "guardian_raw_" + UUID.randomUUID().toString().replace("-", "");
    private static final String RUNTIME_ROLE = "guardian_raw_runtime";

    private static JdbcTemplate jdbc;
    private static JdbcTemplate surgery;
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
        surgery = new JdbcTemplate(migrator);
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
        surgery.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private @Nullable SingleConnectionDataSource runtimeDataSource;

    private record RawGraph(
            UUID workspaceA,
            UUID workspaceB,
            UUID ownerA,
            UUID parentA,
            UUID studentA,
            UUID parentB,
            UUID studentB,
            UUID outsider) {}

    @Test
    void rawForeignKeyAndStateChecksRejectBadRows() {
        RawGraph graph = seedRawGraph();
        // Cặp parent–học sinh riêng cho test này: cặp đã seed ở đồ thị chung đang có
        // sẵn một liên kết REQUESTED nên unique từng phần sẽ nổ trước các kiểm tra cần đo.
        UUID parent = seedRawAccount();
        UUID student = seedRawAccount();
        addMembership(graph.workspaceA(), parent, "PARENT");
        addMembership(graph.workspaceA(), student, "STUDENT");
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23503",
                    () ->
                            inTransaction(() -> {
                                insertLink(
                                        runtime,
                                        graph.workspaceA(),
                                        parent,
                                        student,
                                        UUID.randomUUID());
                                return null;
                            }),
                    "FK chặn requested_by không phải tài khoản tồn tại");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "INSERT INTO guardian_link (id, workspace_id,"
                                                + " parent_user_id, student_user_id, state,"
                                                + " requested_by) VALUES (?, ?, ?, ?,"
                                                + " 'PENDING', ?)",
                                        UUID.randomUUID(),
                                        graph.workspaceA(),
                                        parent,
                                        student,
                                        parent);
                                return null;
                            }),
                    "CHECK chặn trạng thái ngoài vòng đời REQUESTED/APPROVED/REJECTED/REVOKED");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "INSERT INTO guardian_link (id, workspace_id,"
                                                + " parent_user_id, student_user_id, state,"
                                                + " requested_by) VALUES (?, ?, ?, ?,"
                                                + " 'APPROVED', ?)",
                                        UUID.randomUUID(),
                                        graph.workspaceA(),
                                        parent,
                                        student,
                                        parent);
                                return null;
                            }),
                    "CHECK hình dạng chặn APPROVED thiếu người duyệt và thời điểm duyệt");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "INSERT INTO guardian_link (id, workspace_id,"
                                                + " parent_user_id, student_user_id, state,"
                                                + " requested_by, revoked_at) VALUES (?, ?, ?,"
                                                + " ?, 'REQUESTED', ?, now())",
                                        UUID.randomUUID(),
                                        graph.workspaceA(),
                                        parent,
                                        student,
                                        parent);
                                return null;
                            }),
                    "CHECK hình dạng chặn REQUESTED đã mang thời điểm thu hồi");
        } finally {
            closeRuntime();
        }
    }

    @Test
    void rawMembershipGateAtInsertAndApproval() {
        RawGraph graph = seedRawGraph();
        JdbcTemplate runtime = openRuntime();
        try {
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                insertLink(
                                        runtime,
                                        graph.workspaceA(),
                                        graph.outsider(),
                                        graph.studentA(),
                                        graph.outsider());
                                return null;
                            }),
                    "Trigger membership chặn parent chưa là thành viên ACTIVE của center");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                insertLink(
                                        runtime,
                                        graph.workspaceA(),
                                        graph.parentA(),
                                        graph.outsider(),
                                        graph.parentA());
                                return null;
                            }),
                    "Trigger membership chặn học sinh ngoài phạm vi center");

            // Parent là thành viên lúc gửi request nhưng bị thu hồi membership trước
            // khi duyệt: điều kiện "parent hiện hành" xét tại thời điểm duyệt.
            UUID parentLate = seedRawAccount();
            UUID studentLate = seedRawAccount();
            addMembership(graph.workspaceA(), parentLate, "PARENT");
            addMembership(graph.workspaceA(), studentLate, "STUDENT");
            UUID link = insertLink(jdbc, graph.workspaceA(), parentLate, studentLate, parentLate);
            jdbc.update(
                    "UPDATE center_membership SET state = 'REVOKED', revoked_at = now()"
                            + " WHERE workspace_id = ? AND user_id = ?",
                    graph.workspaceA(),
                    parentLate);
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "UPDATE guardian_link SET state = 'APPROVED',"
                                                + " approved_by = ?, approved_at = now()"
                                                + " WHERE id = ?",
                                        graph.ownerA(),
                                        link);
                                return null;
                            }),
                    "Trigger duyệt chặn khi parent không còn là thành viên ACTIVE");
        } finally {
            closeRuntime();
        }
    }

    @DisplayName("Các guard về uniqueness hiện hành, vòng đời và lịch sử ở tầng raw đều đứng vững")
    @Test
    void rawUniquenessAndHistoryGuardsHold() {
        RawGraph graph = seedRawGraph();
        // Cặp parent–học sinh riêng cho test này để không đụng cặp đã seed ở đồ thị chung.
        UUID parent = seedRawAccount();
        UUID student = seedRawAccount();
        addMembership(graph.workspaceA(), parent, "PARENT");
        addMembership(graph.workspaceA(), student, "STUDENT");
        JdbcTemplate runtime = openRuntime();
        try {
            UUID link =
                    inTransaction(
                            () -> insertLink(runtime, graph.workspaceA(), parent, student, parent));
            assertSqlState(
                    "23505",
                    () ->
                            inTransaction(() -> {
                                insertLink(runtime, graph.workspaceA(), parent, student, parent);
                                return null;
                            }),
                    "Unique từng phần chặn cặp parent–học sinh hiện hành trùng");

            // Từ chối là kết thúc: tạo lại bằng hàng mới, lịch sử cũ vẫn còn.
            inTransaction(() -> {
                runtime.update("UPDATE guardian_link SET state = 'REJECTED' WHERE id = ?", link);
                return null;
            });
            UUID relink =
                    inTransaction(
                            () -> insertLink(runtime, graph.workspaceA(), parent, student, parent));
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM guardian_link WHERE workspace_id = ?"
                                    + " AND parent_user_id = ? AND student_user_id = ?",
                            Integer.class,
                            graph.workspaceA(),
                            parent,
                            student),
                    "Sau từ chối, request mới là hàng mới và lịch sử cũ vẫn còn");

            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "UPDATE guardian_link SET student_user_id = ?"
                                                + " WHERE id = ?",
                                        graph.outsider(),
                                        link);
                                return null;
                            }),
                    "Trigger giữ lịch sử chặn đổi học sinh của liên kết");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update("DELETE FROM guardian_link WHERE id = ?", link);
                                return null;
                            }),
                    "Trigger giữ lịch sử chặn hard-delete liên kết");
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "UPDATE guardian_link SET state = 'REVOKED',"
                                                + " approved_by = ?, approved_at = now(),"
                                                + " revoked_at = now() WHERE id = ?",
                                        graph.ownerA(),
                                        relink);
                                return null;
                            }),
                    "Trigger vòng đời chặn REQUESTED nhảy thẳng sang REVOKED lách qua duyệt");

            // Vòng đời hợp lệ: duyệt rồi gỡ; hàng đã REVOKED đóng băng toàn bộ.
            inTransaction(() -> {
                runtime.update(
                        "UPDATE guardian_link SET state = 'APPROVED', approved_by = ?,"
                                + " approved_at = now() WHERE id = ?",
                        graph.ownerA(),
                        relink);
                runtime.update(
                        "UPDATE guardian_link SET state = 'REVOKED', revoked_at = now()"
                                + " WHERE id = ?",
                        relink);
                return null;
            });
            assertSqlState(
                    "23514",
                    () ->
                            inTransaction(() -> {
                                runtime.update(
                                        "UPDATE guardian_link SET revoked_at = now()"
                                                + " WHERE id = ?",
                                        relink);
                                return null;
                            }),
                    "Trigger giữ lịch sử đóng băng hàng đã REVOKED");
        } finally {
            closeRuntime();
        }
    }

    // ---------- Seed center và liên kết bằng SQL thô ----------

    private RawGraph seedRawGraph() {
        UUID ownerA = seedRawAccount();
        UUID ownerB = seedRawAccount();
        UUID workspaceA = seedCenter(ownerA);
        UUID workspaceB = seedCenter(ownerB);
        UUID parentA = seedRawAccount();
        UUID studentA = seedRawAccount();
        UUID parentB = seedRawAccount();
        UUID studentB = seedRawAccount();
        UUID outsider = seedRawAccount();
        addMembership(workspaceA, parentA, "PARENT");
        addMembership(workspaceA, studentA, "STUDENT");
        addMembership(workspaceB, parentB, "PARENT");
        addMembership(workspaceB, studentB, "STUDENT");
        insertLink(jdbc, workspaceA, parentA, studentA, parentA);
        insertLink(jdbc, workspaceB, parentB, studentB, parentB);
        return new RawGraph(
                workspaceA, workspaceB, ownerA, parentA, studentA, parentB, studentB, outsider);
    }

    private UUID seedRawAccount() {
        String keycloakSub = UUID.randomUUID().toString();
        // Tài khoản đã xác minh email phải mang email thật theo ràng buộc ck_user_verified_email.
        return seedAccount(keycloakSub, "guardian-" + keycloakSub + "@example.test");
    }

    private UUID seedAccount(String keycloakSub, String email) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    actor,
                    keycloakSub,
                    "guardian-user-" + actor,
                    email,
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
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
            // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER
            // của chủ sở hữu.
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

    private UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            for (String role : roles) {
                jdbc.update(
                        "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                        membership,
                        role);
            }
            return null;
        });
        return membership;
    }

    private UUID insertLink(
            JdbcTemplate db,
            UUID workspaceId,
            UUID parentUserId,
            UUID studentUserId,
            UUID requestedBy) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO guardian_link (id, workspace_id, parent_user_id, student_user_id,"
                        + " state, requested_by) VALUES (?, ?, ?, ?, 'REQUESTED', ?)",
                id,
                workspaceId,
                parentUserId,
                studentUserId,
                requestedBy);
        return id;
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    // ---------- Role runtime của class ----------

    private JdbcTemplate openRuntime() {
        String password = UUID.randomUUID().toString();
        surgery.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
        surgery.execute(
                "CREATE ROLE "
                        + RUNTIME_ROLE
                        + " LOGIN PASSWORD '"
                        + password
                        + "' NOSUPERUSER NOBYPASSRLS");
        surgery.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        // Cấp đủ quyền bảng để trigger/FK là chốt chặn duy nhất phải bắt được hành vi sai,
        // đúng mẫu TeachingRawConstraintsIT.
        surgery.execute("GRANT ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        surgery.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
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
        surgery.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        surgery.execute(
                "REVOKE ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        surgery.execute("REVOKE USAGE ON SCHEMA " + SCHEMA + " FROM " + RUNTIME_ROLE);
        surgery.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
    }

    private <T> T inTransaction(Supplier<T> action) {
        DataSourceTransactionManager txManager =
                new DataSourceTransactionManager(
                        Objects.requireNonNull(runtimeDataSource, "Role runtime phải mở trước"));
        return new TransactionTemplate(txManager).execute(_ -> action.get());
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
