package com.giaokhoa.tenancy.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.support.IntegrationEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng helper context workspace bằng một bảng tenant mẫu có RLS policy tham chiếu đúng
 * setting mà WorkspaceTransaction đặt. Stack TEST dùng một user superuser cho cả migration lẫn
 * runtime nên RLS không áp được trên chính user đó; test tự tạo một role runtime không superuser,
 * không BYPASSRLS — đúng bất biến của data-model (runtime role không owner/BYPASSRLS) — và chạy các
 * khẳng định về khả năng thấy hàng qua role đó.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceTransactionIT extends IntegrationEnvironment {
    private static final String RUNTIME_ROLE =
            "workspace_context_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PROBE_TABLE = "workspace_context_probe";

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceTransaction workspaceTransaction;

    private SingleConnectionDataSource runtimeDataSource;
    private JdbcTemplate runtimeJdbc;
    private WorkspaceTransaction runtimeTransaction;

    @BeforeAll
    void createRuntimeRoleAndProbeTable() {
        String password = UUID.randomUUID().toString();
        jdbc.execute(
                "CREATE ROLE "
                        + RUNTIME_ROLE
                        + " LOGIN PASSWORD '"
                        + password
                        + "' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute(
                "CREATE TABLE "
                        + PROBE_TABLE
                        + " (workspace_id UUID NOT NULL, label VARCHAR(64) NOT NULL)");
        jdbc.execute("ALTER TABLE " + PROBE_TABLE + " ENABLE ROW LEVEL SECURITY");
        String contextCheck =
                "workspace_id = NULLIF(current_setting('"
                        + WorkspaceTransaction.WORKSPACE_SETTING
                        + "', true), '')::uuid";
        jdbc.execute(
                "CREATE POLICY workspace_context_isolation ON "
                        + PROBE_TABLE
                        + " USING ("
                        + contextCheck
                        + ") WITH CHECK ("
                        + contextCheck
                        + ")");
        jdbc.execute("GRANT SELECT, INSERT ON " + PROBE_TABLE + " TO " + RUNTIME_ROLE);

        String url = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL());
        runtimeDataSource = new SingleConnectionDataSource(url, RUNTIME_ROLE, password, true);
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);
        String schema =
                Objects.requireNonNull(
                        jdbc.queryForObject("SELECT current_schema()", String.class),
                        "TEST schema is required");
        String quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\"";
        jdbc.execute("GRANT USAGE ON SCHEMA " + quotedSchema + " TO " + RUNTIME_ROLE);
        runtimeJdbc.execute("SET search_path TO " + quotedSchema);
        runtimeTransaction =
                new WorkspaceTransaction(
                        runtimeJdbc, new DataSourceTransactionManager(runtimeDataSource));
    }

    @AfterAll
    void dropRuntimeRoleAndProbeTable() {
        runtimeDataSource.destroy();
        jdbc.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        String schema =
                Objects.requireNonNull(
                        jdbc.queryForObject("SELECT current_schema()", String.class),
                        "TEST schema is required");
        jdbc.execute(
                "REVOKE USAGE ON SCHEMA \""
                        + schema.replace("\"", "\"\"")
                        + "\" FROM "
                        + RUNTIME_ROLE);
        jdbc.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
    }

    @Test
    void missingContextSeesNoTenantRows() {
        UUID workspace = seedWorkspace();
        assertEquals(2, countAsOwner(workspace));
        assertEquals(0, countVisible(runtimeJdbc, workspace));
        Integer inPlainTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource))
                        .execute(_ -> countVisible(runtimeJdbc, workspace));
        assertEquals(0, inPlainTransaction);
    }

    @Test
    void contextSeesOnlyItsOwnWorkspaceRows() {
        UUID workspace = seedWorkspace();
        UUID other = seedWorkspace();

        List<String> labels =
                runtimeTransaction.inWorkspace(
                        workspace,
                        () ->
                                runtimeJdbc.queryForList(
                                        "SELECT label FROM "
                                                + PROBE_TABLE
                                                + " WHERE workspace_id = ? ORDER BY label",
                                        String.class,
                                        workspace));
        assertEquals(List.of("row-1", "row-2"), labels);
        assertEquals(
                2,
                runtimeTransaction.inWorkspace(
                        workspace, () -> countVisible(runtimeJdbc, workspace)));
        assertEquals(
                2, runtimeTransaction.inWorkspace(other, () -> countVisible(runtimeJdbc, other)));
        assertEquals(
                0,
                runtimeTransaction.inWorkspace(other, () -> countVisible(runtimeJdbc, workspace)));
    }

    @Test
    void contextDoesNotLeakAfterTheTransactionEnds() {
        UUID workspace = seedWorkspace();
        assertEquals(
                2,
                runtimeTransaction.inWorkspace(
                        workspace, () -> countVisible(runtimeJdbc, workspace)));
        // Cùng một connection vật lý (SingleConnectionDataSource): context theo transaction
        // phải biến mất khi transaction kết thúc, không rò sang lần dùng connection sau.
        assertEquals(0, countVisible(runtimeJdbc, workspace));
        assertEquals(
                "",
                runtimeJdbc.queryForObject(
                        "SELECT current_setting(?, true)",
                        String.class,
                        WorkspaceTransaction.WORKSPACE_SETTING));
    }

    @Test
    void writesAreCheckedAgainstTheContext() {
        UUID workspace = seedWorkspace();
        UUID other = UUID.randomUUID();
        assertThrows(
                DataAccessException.class,
                () ->
                        runtimeTransaction.inWorkspace(
                                workspace,
                                () -> {
                                    runtimeJdbc.update(
                                            "INSERT INTO "
                                                    + PROBE_TABLE
                                                    + " (workspace_id, label) VALUES (?, 'foreign')",
                                            other);
                                    return null;
                                }));
        runtimeTransaction.inWorkspace(
                workspace,
                () -> {
                    runtimeJdbc.update(
                            "INSERT INTO "
                                    + PROBE_TABLE
                                    + " (workspace_id, label) VALUES (?, 'own')",
                            workspace);
                    return null;
                });
        assertEquals(
                3,
                runtimeTransaction.inWorkspace(
                        workspace, () -> countVisible(runtimeJdbc, workspace)));
    }

    @Test
    void exceptionRollsBackWritesMadeInsideTheContext() {
        UUID workspace = seedWorkspace();
        assertThrows(
                IllegalStateException.class,
                () ->
                        runtimeTransaction.inWorkspace(
                                workspace,
                                () -> {
                                    runtimeJdbc.update(
                                            "INSERT INTO "
                                                    + PROBE_TABLE
                                                    + " (workspace_id, label) VALUES (?, 'doomed')",
                                            workspace);
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals(
                2,
                runtimeTransaction.inWorkspace(
                        workspace, () -> countVisible(runtimeJdbc, workspace)));
    }

    @Test
    void applyContextRequiresAnActiveTransaction() {
        assertThrows(
                IllegalStateException.class,
                () -> runtimeTransaction.applyContext(UUID.randomUUID()));
    }

    @Test
    void springBeanSetsTheContextOnlyInsideItsTransaction() {
        UUID workspace = UUID.randomUUID();
        String inside =
                Objects.requireNonNull(
                        workspaceTransaction.inWorkspace(
                                workspace,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT current_setting(?, true)",
                                                String.class,
                                                WorkspaceTransaction.WORKSPACE_SETTING)));
        assertEquals(workspace.toString(), inside);
        assertEquals(
                "",
                jdbc.queryForObject(
                        "SELECT current_setting(?, true)",
                        String.class,
                        WorkspaceTransaction.WORKSPACE_SETTING));
    }

    private UUID seedWorkspace() {
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO " + PROBE_TABLE + " (workspace_id, label) VALUES (?, 'row-1')",
                workspace);
        jdbc.update(
                "INSERT INTO " + PROBE_TABLE + " (workspace_id, label) VALUES (?, 'row-2')",
                workspace);
        return workspace;
    }

    private int countAsOwner(UUID workspace) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM " + PROBE_TABLE + " WHERE workspace_id = ?",
                        Integer.class,
                        workspace));
    }

    private int countVisible(JdbcTemplate template, UUID workspace) {
        return java.util.Objects.requireNonNull(
                template.queryForObject(
                        "SELECT count(*) FROM " + PROBE_TABLE + " WHERE workspace_id = ?",
                        Integer.class,
                        workspace));
    }
}
