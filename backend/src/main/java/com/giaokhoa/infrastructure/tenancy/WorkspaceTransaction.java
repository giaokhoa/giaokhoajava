package com.giaokhoa.infrastructure.tenancy;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chốt tenant trước khi mở transaction/EntityManager. Một transaction chỉ dùng một schema. Đọc
 * nguồn ở schema khác chạy trong transaction đọc riêng và trả dữ kiện đã materialize.
 *
 * <p>Cách ly tenant dựa vào ranh giới schema và {@link TenantContext}. Không còn context ở tầng cơ
 * sở dữ liệu: RLS và GUC app.workspace_id đã được tháo bỏ.
 */
@Component
public class WorkspaceTransaction {
    private static final Object TENANT_CONTEXT_BACKUP = new Object();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final @Nullable EntityManagerFactory entityManagerFactory;

    public WorkspaceTransaction(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads.setReadOnly(true);
        this.entityManagerFactory =
                transactionManager instanceof JpaTransactionManager jpa
                        ? jpa.getEntityManagerFactory()
                        : null;
    }

    public <T extends @Nullable Object> @Nullable T inWorkspace(
            UUID workspaceId, Supplier<@Nullable T> work) {
        TenantContext.Context context = resolveContext(workspaceId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            requireMatchingTenant(context);
        }
        return execute(transactions, context, work);
    }

    /** Cùng schema thì giữ transaction hiện hành; khác schema thì mở một unit đọc độc lập. */
    public <T extends @Nullable Object> @Nullable T readInWorkspace(
            UUID workspaceId, Supplier<@Nullable T> work) {
        TenantContext.Context context = resolveContext(workspaceId);
        return execute(
                TransactionSynchronizationManager.isActualTransactionActive()
                                && context.schemaName().equals(TenantContext.currentSchema())
                        ? transactions
                        : reads,
                context,
                work);
    }

    private <T extends @Nullable Object> @Nullable T execute(
            TransactionTemplate template,
            TenantContext.Context context,
            Supplier<@Nullable T> work) {
        TenantContext.Context previous = TenantContext.current();
        boolean joined =
                template.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED
                        && TransactionSynchronizationManager.isActualTransactionActive();
        try {
            if (!joined) {
                TenantContext.restore(context);
            }
            return template.execute(_ -> {
                applyContext(context);
                return work.get();
            });
        } finally {
            TenantContext.restore(previous);
        }
    }

    public void applyContext(UUID workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workspace context requires an active transaction");
        }
        applyContext(resolveContext(workspaceId));
    }

    private TenantContext.Context resolveContext(UUID workspaceId) {
        List<@Nullable String> names =
                jdbc.queryForList(
                        "SELECT schema_name FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId);
        @Nullable String schema = names.isEmpty() ? null : names.getFirst();
        return new TenantContext.Context(
                workspaceId, schema == null ? TenantContext.SYSTEM_SCHEMA : schema);
    }

    private void applyContext(TenantContext.Context context) {
        requireMatchingTenant(context);
        TransactionContext state =
                (TransactionContext)
                        TransactionSynchronizationManager.getResource(TENANT_CONTEXT_BACKUP);
        if (state == null) {
            state =
                    new TransactionContext(
                            TenantContext.current(),
                            context.schemaName(),
                            Objects.requireNonNull(
                                    jdbc.queryForObject(
                                            "SELECT current_setting('search_path')",
                                            String.class)));
            registerContext(state);
        }
        TenantContext.restore(context);
        // JPA đã mượn đúng schema qua TenantConnectionProvider; không đổi schema của Session.
        if (entityManagerFactory == null
                && !TenantContext.SYSTEM_SCHEMA.equals(context.schemaName())) {
            jdbc.queryForObject(
                    "SELECT set_config('search_path', ?, true)",
                    String.class,
                    context.schemaName() + ", " + state.searchPath());
        }
    }

    private void requireMatchingTenant(TenantContext.Context context) {
        Object resource = TransactionSynchronizationManager.getResource(TENANT_CONTEXT_BACKUP);
        if (resource instanceof TransactionContext state
                && !state.schemaName().equals(context.schemaName())) {
            throw new IllegalStateException("Cannot change tenant schema in an active transaction");
        }
        if (entityManagerFactory != null
                && TransactionSynchronizationManager.getResource(entityManagerFactory)
                        instanceof EntityManagerHolder holder
                && !context.schemaName()
                        .equals(
                                holder.getEntityManager()
                                        .unwrap(Session.class)
                                        .getTenantIdentifierValue())) {
            throw new IllegalStateException(
                    "Tenant must be selected before opening the EntityManager; use inWorkspace");
        }
    }

    private record TransactionContext(
            TenantContext.@Nullable Context previous, String schemaName, String searchPath) {}

    private void registerContext(TransactionContext state) {
        TransactionSynchronizationManager.bindResource(TENANT_CONTEXT_BACKUP, state);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void suspend() {
                        TransactionSynchronizationManager.unbindResource(TENANT_CONTEXT_BACKUP);
                    }

                    @Override
                    public void resume() {
                        TransactionSynchronizationManager.bindResource(
                                TENANT_CONTEXT_BACKUP, state);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResource(TENANT_CONTEXT_BACKUP);
                        TenantContext.restore(state.previous());
                    }
                });
    }
}
