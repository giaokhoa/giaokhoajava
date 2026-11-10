package com.giaokhoa.authorization.policy;

import com.giaokhoa.shared.AdvisoryLocks;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tuần tự hóa mutation lên resource Keycloak hoặc dữ kiện nguồn workspace giữa các instance bằng cơ
 * sở dữ liệu PostgreSQL dùng chung. Một advisory lock phạm vi transaction bao trọn toàn bộ chuỗi
 * đọc/sửa/ghi/đọc lại, gồm mọi scope của một target resource; một khối synchronized trong tiến
 * trình là không đủ khi nhiều worker cùng provision một resource Keycloak.
 */
@Component
public class ResourceMutationCoordinator {
    private final JdbcOperations jdbc;
    private final PlatformTransactionManager transactions;

    public ResourceMutationCoordinator(
            JdbcOperations jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public <T> T withResourceLock(String targetKey, Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(_ -> {
            lockResource(targetKey);
            return action.get();
        });
    }

    /** Giữ cùng khóa từ lúc đọc/ghi nguồn đến khi ghi desired hash và commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockWorkspace(UUID workspaceId) {
        lockResource("workspace-source:" + workspaceId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForTransaction(String targetKey) {
        lockResource(targetKey);
    }

    private void lockResource(String targetKey) {
        AdvisoryLocks.lock(jdbc, "giaokhoa:policy-resource:", targetKey);
    }
}
