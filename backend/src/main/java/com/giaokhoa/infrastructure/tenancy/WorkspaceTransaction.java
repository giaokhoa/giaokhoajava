package com.giaokhoa.infrastructure.tenancy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Điểm đặt context tenant duy nhất của hệ thống trong phạm vi một transaction PostgreSQL. Hai việc
 * được đặt cùng nhau để các tầng truy cập dữ liệu luôn thấy cùng một tenant: (1) {@link
 * TenantContext} cho resolver của Hibernate, (2) {@code search_path} ở mức transaction (qua
 * set_config với is_local = true, tương đương SET LOCAL) trỏ vào schema riêng của workspace khi
 * workspace đó đã được cấp schema (cột {@code workspace.schema_name}); workspace chưa có schema
 * riêng giữ nguyên hành vi trên schema hệ thống. Cách ly giữa các workspace chưa kích hoạt dựa vào
 * mệnh đề workspace_id trong truy vấn ứng dụng; schema riêng là ranh giới cách ly của workspace đã
 * kích hoạt.
 */
@Component
public class WorkspaceTransaction {
    private static final Object TENANT_CONTEXT_BACKUP = new Object();

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
        restoreTenantContextAfterCompletion();
        List<@Nullable String> schemaNames =
                jdbc.queryForList(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId);
        @Nullable String schemaName = schemaNames.isEmpty() ? null : schemaNames.getFirst();
        TenantContext.set(workspaceId, schemaName);
        if (schemaName != null) {
            // Đuôi search_path lấy nguyên từ connection hiện tại (schema hệ thống do cấu hình
            // pool quyết định — public ở deployment chuẩn, schema riêng ở môi trường kiểm thử
            // cô lập) thay vì ép tên schema hệ thống cố định.
            String currentPath =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT current_setting('search_path')", String.class),
                            "search_path hiện tại không được null");
            jdbc.queryForObject(
                    "SELECT set_config('search_path', ?, true)",
                    String.class,
                    schemaName + ", " + currentPath);
        }
    }

    /**
     * Chụp {@link TenantContext} ở lời gọi applyContext đầu tiên trong transaction và khôi phục khi
     * transaction hoàn tất. Nhiều service gọi applyContext trực tiếp trong transaction của mình,
     * không có {@link #inWorkspace} bao ngoài để restore ở finally: nếu không khôi phục tại đây,
     * ThreadLocal giữ schema của workspace vừa phục vụ và resolver trả schema đó cho mọi phiên JPA
     * sau trên cùng thread — rò tenant qua thread dùng lại của pool xử lý request.
     */
    private void restoreTenantContextAfterCompletion() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(TENANT_CONTEXT_BACKUP)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(
                TENANT_CONTEXT_BACKUP, Optional.ofNullable(TenantContext.current()));
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        Object backup =
                                TransactionSynchronizationManager.unbindResourceIfPossible(
                                        TENANT_CONTEXT_BACKUP);
                        if (backup instanceof Optional<?> previous) {
                            TenantContext.restore(
                                    previous.isPresent()
                                            ? (TenantContext.Context) previous.get()
                                            : null);
                        }
                    }
                });
    }
}
