package com.giaokhoa.infrastructure.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.support.IntegrationEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kiểm chứng hợp đồng của {@link WorkspaceTransaction}: context tenant chỉ sống trong phạm vi
 * transaction của helper và được khôi phục khi transaction kết thúc (kể cả khi exception làm
 * rollback), còn lời gọi đặt context ngoài transaction bị từ chối vì search_path mức transaction
 * không sống qua khỏi câu lệnh. Định tuyến schema của workspace đã kích hoạt được kiểm chứng ở
 * {@link TenancyInfrastructureIT}.
 */
@SpringBootTest
class WorkspaceTransactionIT extends IntegrationEnvironment {

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceTransaction workspaceTransaction;

    @Test
    void applyContextRequiresAnActiveTransaction() {
        assertThrows(
                IllegalStateException.class,
                () -> workspaceTransaction.applyContext(UUID.randomUUID()));
    }

    @DisplayName("Context tenant chỉ sống trong transaction và được khôi phục khi kết thúc")
    @Test
    void contextIsScopedToTheTransaction() {
        UUID workspace = UUID.randomUUID();
        assertNull(TenantContext.current());
        UUID seen =
                workspaceTransaction.inWorkspace(
                        workspace,
                        () -> {
                            TenantContext.Context current = TenantContext.current();
                            assertNotNull(current);
                            return current.workspaceId();
                        });
        assertEquals(workspace, seen);
        assertNull(TenantContext.current());
    }

    @DisplayName("Exception rollback các ghi đã thực hiện bên trong context")
    @Test
    void exceptionRollsBackContextWrites() {
        UUID workspace = UUID.randomUUID();
        assertThrows(
                IllegalStateException.class,
                () ->
                        workspaceTransaction.inWorkspace(
                                workspace,
                                () -> {
                                    jdbc.update(
                                            "INSERT INTO workspace (id, kind, state)"
                                                    + " VALUES (?, 'SYSTEM', 'ACTIVE')",
                                            workspace);
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM workspace WHERE id = ?", Integer.class, workspace));
    }
}
