package com.giaokhoa.tenancy.data;

import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đặt context workspace cho RLS trong phạm vi một transaction PostgreSQL. Context được áp bằng
 * set_config với is_local = true (tương đương SET LOCAL) nên chỉ sống tới hết transaction hiện tại:
 * không rò sang transaction khác hay lần dùng connection sau, và khi context chưa được đặt,
 * current_setting trả về rỗng khiến policy không khớp bất kỳ hàng tenant nào (fail-closed).
 */
@Component
public class WorkspaceTransaction {
    public static final String WORKSPACE_SETTING = "app.workspace_id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WorkspaceTransaction(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public <T extends @Nullable Object> @Nullable T inWorkspace(
            UUID workspaceId, Supplier<@Nullable T> work) {
        return transactions.execute(_ -> {
            applyContext(workspaceId);
            return work.get();
        });
    }

    public void applyContext(UUID workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Workspace context requires an active transaction;"
                            + " SET LOCAL would not survive the statement");
        }
        jdbc.queryForObject(
                "SELECT set_config(?, ?, true)",
                String.class,
                WORKSPACE_SETTING,
                workspaceId.toString());
    }
}
