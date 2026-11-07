package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ghi desired state của grant platform khi ứng dụng sẵn sàng: một partition PLATFORM toàn cục và
 * một projection bundle duy nhất cho target platform, tính bằng chính projector và hàm băm mà
 * reconciliation dùng để đối chiếu. Seed platform là hành động hệ thống không có initiator nên
 * seeder ghi trực tiếp bằng JDBC — tiền lệ seed partition bằng SQL có ở migration V002 — thay vì
 * qua {@code AuthorizationOperationService}, vốn đòi một user thật làm initiator; cơ chế áp graph
 * vẫn là reconciler và worker như mọi target khác. Idempotent: khởi động lại khi desired đã khớp
 * không sinh revision mới; hợp đồng đổi thì fence partition, ghi hash mới và tăng đúng một
 * revision.
 */
@Component
public class PlatformProjectionSeeder {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformProjectionSeeder.class);
    private static final String OWNER_KEY = "platform";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PlatformProjectionSeeder(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnReady() {
        transactions.executeWithoutResult(_ -> seed());
    }

    /** Ghi desired state của bundle platform nếu chưa có hoặc đã lệch hợp đồng hiện hành. */
    public void seed() {
        String targetKey = NativePolicyTemplates.platformKey();
        String desiredHash =
                ProjectionReconciler.desiredHash(
                        targetKey,
                        PlatformPolicyProjector.bundleScope(),
                        PlatformPolicyProjector.bundleDescriptors());
        UUID partitionId = findPartition();
        boolean created = false;
        if (partitionId == null) {
            UUID candidate = UUID.randomUUID();
            int inserted =
                    jdbc.update(
                            "INSERT INTO authz_partition(id, kind, owner_key)"
                                    + " VALUES (?, 'PLATFORM', ?) ON CONFLICT (kind, owner_key)"
                                    + " DO NOTHING",
                            candidate,
                            OWNER_KEY);
            if (inserted == 1) {
                partitionId = candidate;
                created = true;
            } else {
                // Instance khác vừa tạo partition trong cùng cửa sổ khởi động: đi tiếp theo
                // partition đã tồn tại của nó.
                partitionId = Objects.requireNonNull(findPartition(), "platform partition");
            }
        }
        List<Map<String, Object>> projections =
                jdbc.queryForList(
                        "SELECT id, partition_id, desired_hash FROM authz_projection"
                                + " WHERE target_key = ?",
                        targetKey);
        if (!projections.isEmpty()) {
            Map<String, Object> projection = projections.getFirst();
            if (!partitionId.equals(projection.get("partition_id"))) {
                throw new IllegalStateException(
                        "Projection target belongs to another partition: " + targetKey);
            }
            if (desiredHash.equals(projection.get("desired_hash"))) {
                return;
            }
            fenceAndBump(partitionId);
            jdbc.update(
                    "UPDATE authz_projection SET desired_hash = ?, state = 'PENDING' WHERE id = ?",
                    desiredHash,
                    projection.get("id"));
            LOG.info("Updated the platform grant desired state on partition {}", partitionId);
            return;
        }
        if (!created) {
            fenceAndBump(partitionId);
        }
        jdbc.update(
                "INSERT INTO authz_projection(id, partition_id, target_key, desired_hash, state)"
                        + " VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT (target_key) DO NOTHING",
                UUID.randomUUID(),
                partitionId,
                targetKey,
                desiredHash);
        LOG.info("Seeded the platform grant desired state on partition {}", partitionId);
    }

    private @Nullable UUID findPartition() {
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'PLATFORM' AND owner_key = ?",
                        UUID.class,
                        OWNER_KEY);
        return partitions.isEmpty() ? null : partitions.getFirst();
    }

    // Mọi sửa đổi desired đều phải đi dưới fence và tiến đúng một revision, cùng kỷ luật với
    // đường ghi operation.
    private void fenceAndBump(UUID partitionId) {
        jdbc.update(
                "UPDATE authz_partition SET state = 'PENDING',"
                        + " desired_revision = desired_revision + 1, updated_at = now()"
                        + " WHERE id = ?",
                partitionId);
    }
}
