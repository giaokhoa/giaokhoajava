package com.giaokhoa.infrastructure.tenancy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Điểm đặt context tenant duy nhất của hệ thống trong phạm vi một transaction PostgreSQL. Ba việc
 * được đặt cùng nhau để các tầng truy cập dữ liệu luôn thấy cùng một tenant: (1) {@link
 * TenantContext} cho resolver của Hibernate, (2) {@code set_config('app.workspace_id', …)} với
 * is_local = true (tương đương SET LOCAL) phục vụ RLS trong giai đoạn chuyển tiếp — context chỉ
 * sống tới hết transaction hiện tại, không rò sang transaction khác hay lần dùng connection sau, và
 * khi chưa đặt thì policy không khớp bất kỳ hàng tenant nào (fail-closed), (3) {@code search_path}
 * ở mức transaction trỏ vào schema riêng của workspace khi workspace đó đã được cấp schema (cột
 * {@code workspace.schema_name}); workspace chưa có schema riêng giữ nguyên hành vi cũ trên schema
 * hệ thống.
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
        TenantContext.Context previous = TenantContext.current();
        try {
            return transactions.execute(_ -> {
                applyContext(workspaceId);
                return work.get();
            });
        } finally {
            TenantContext.restore(previous);
        }
    }

    public void applyContext(UUID workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Workspace context requires an active transaction;"
                            + " SET LOCAL would not survive the statement");
        }
        List<@Nullable String> schemaNames =
                jdbc.queryForList(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId);
        @Nullable String schemaName = schemaNames.isEmpty() ? null : schemaNames.getFirst();
        TenantContext.set(workspaceId, schemaName);
        jdbc.queryForObject(
                "SELECT set_config(?, ?, true)",
                String.class,
                WORKSPACE_SETTING,
                workspaceId.toString());
        if (schemaName != null) {
            jdbc.queryForObject(
                    "SELECT set_config('search_path', ?, true)",
                    String.class,
                    schemaName + ", " + TenantContext.SYSTEM_SCHEMA);
        }
    }
}
