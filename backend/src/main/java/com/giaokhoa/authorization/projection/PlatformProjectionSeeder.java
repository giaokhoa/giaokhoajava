package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ghi desired state của grant platform khi ứng dụng sẵn sàng: một partition PLATFORM toàn cục và
 * một projection bundle duy nhất cho target platform, tính bằng chính projector và hàm băm mà
 * reconciliation dùng để đối chiếu. Seed platform là hành động hệ thống không có initiator nên
 * seeder ghi trực tiếp qua repository của mặt phẳng điều khiển — theo tiền lệ seed partition trong
 * baseline hệ thống — thay vì qua {@code AuthorizationOperationService}, vốn đòi một user thật làm
 * initiator; cơ chế áp graph vẫn là reconciler và worker như mọi target khác. Idempotent: khởi động
 * lại khi desired đã khớp không sinh revision mới; hợp đồng đổi thì fence partition, ghi hash mới
 * và tăng đúng một revision.
 */
@Component
public class PlatformProjectionSeeder {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformProjectionSeeder.class);
    private static final String OWNER_KEY = "platform";

    private final AuthzPartitionRepository partitions;
    private final AuthzProjectionRepository projections;
    private final TransactionTemplate transactions;
    private final TransactionTemplate requiresNew;

    public PlatformProjectionSeeder(
            AuthzPartitionRepository partitions,
            AuthzProjectionRepository projections,
            PlatformTransactionManager transactionManager) {
        this.partitions = partitions;
        this.projections = projections;
        this.transactions = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        Optional<AuthorizationPartition> existing = findPartition();
        UUID partitionId;
        boolean created = false;
        if (existing.isPresent()) {
            partitionId = existing.get().getId();
        } else {
            UUID candidate = UUID.randomUUID();
            try {
                // Tạo trong transaction ngắn riêng: instance khác thắng cuộc đua tạo partition
                // thì ràng buộc unique (kind, owner_key) báo lỗi ở đây thay vì làm hỏng
                // transaction seed, và partition của nó được đọc lại để đi tiếp.
                requiresNew.executeWithoutResult(_ ->
                        partitions.saveAndFlush(
                                new AuthorizationPartition(
                                        candidate,
                                        AuthorizationPartition.Kind.PLATFORM,
                                        OWNER_KEY,
                                        0,
                                        0,
                                        AuthorizationPartition.State.PENDING)));
                partitionId = candidate;
                created = true;
            } catch (DataIntegrityViolationException ex) {
                partitionId =
                        Objects.requireNonNull(
                                findPartition().map(partition -> partition.getId()).orElse(null),
                                "platform partition");
            }
        }
        Optional<AuthorizationProjection> projection = projections.findByTargetKey(targetKey);
        if (projection.isPresent()) {
            AuthorizationProjection row = projection.get();
            if (!partitionId.equals(row.getPartitionId())) {
                throw new IllegalStateException(
                        "Projection target belongs to another partition: " + targetKey);
            }
            if (desiredHash.equals(row.getDesiredHash())) {
                return;
            }
            fenceAndBump(partitionId);
            row.setDesiredHash(desiredHash);
            row.setState(AuthorizationProjection.State.PENDING);
            LOG.info("Updated the platform grant desired state on partition {}", partitionId);
            return;
        }
        boolean fenceFirst = !created;
        UUID settledPartitionId = partitionId;
        String settledDesiredHash = desiredHash;
        try {
            // Fence và insert projection trong cùng transaction riêng để fence luôn xuống cơ
            // sở dữ liệu trước insert (trigger desired kiểm tra tại từng câu lệnh), và một
            // instance khác seed cùng target trong cùng cửa sổ khởi động chỉ làm mất insert
            // này thay vì hỏng cả lượt seed: projection của nó mang cùng desired hash.
            requiresNew.executeWithoutResult(_ -> {
                if (fenceFirst) {
                    fenceAndBump(settledPartitionId);
                }
                projections.saveAndFlush(
                        new AuthorizationProjection(
                                UUID.randomUUID(),
                                settledPartitionId,
                                targetKey,
                                null,
                                List.of(),
                                List.of(),
                                settledDesiredHash,
                                null,
                                AuthorizationProjection.State.PENDING));
            });
        } catch (DataIntegrityViolationException ex) {
            LOG.info(
                    "Platform grant desired state was seeded concurrently on partition {}",
                    partitionId);
            return;
        }
        LOG.info("Seeded the platform grant desired state on partition {}", partitionId);
    }

    private Optional<AuthorizationPartition> findPartition() {
        return partitions.findByKindAndOwnerKey(AuthorizationPartition.Kind.PLATFORM, OWNER_KEY);
    }

    // Mọi sửa đổi desired đều phải đi dưới fence và tiến đúng một revision, cùng kỷ luật với
    // đường ghi operation. Flush tường minh để fence có hiệu lực trong cơ sở dữ liệu trước
    // mọi thay đổi desired tiếp theo.
    private void fenceAndBump(UUID partitionId) {
        AuthorizationPartition partition =
                partitions
                        .findById(partitionId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Platform partition is missing: " + partitionId));
        partition.setState(AuthorizationPartition.State.PENDING);
        partition.setDesiredRevision(partition.getDesiredRevision() + 1);
        partitions.flush();
    }
}
