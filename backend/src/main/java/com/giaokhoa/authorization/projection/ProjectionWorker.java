package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationOperation.State;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Projection worker bền vững: nó claim các authorization operation tới hạn theo thứ tự tạo (một
 * operation không bao giờ vượt operation cũ hơn chạm cùng partition), reconcile mọi partition bị
 * ảnh hưởng tại đúng revision của operation qua {@link ProjectionReconciler}, và chỉ đánh dấu
 * operation SUCCEEDED sau khi cơ sở dữ liệu xác nhận mọi partition READY tại revision đó. Claim
 * khóa các dòng bằng khóa ghi bỏ qua dòng đang bị giữ (SKIP LOCKED) và kiểm lại điều kiện claim
 * ngay trong bước khóa, nên hai instance của ứng dụng không bao giờ xử lý cùng một operation. Thất
 * bại retry với cùng idempotency payload và backoff có giới hạn; cạn ngân sách lần thử sẽ fail
 * operation và các partition của nó mà không mở fence. Các partition bị fence mà không có operation
 * đang sống (dependency fence, sửa drift ngoài hệ thống) được reconcile trên cùng vòng lặp dưới
 * cùng backoff có giới hạn, và các partition READY được kiểm tra định kỳ tìm drift từ console qua
 * một lượt quét xoay vòng chạm tới mọi partition.
 */
@Component
public class ProjectionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionWorker.class);

    static final int MAX_ATTEMPTS = 5;
    static final String ERROR_SUPERSEDED = "SUPERSEDED_BY_NEWER_REVISION";
    static final String ERROR_RECONCILE_FAILED = "PROJECTION_RECONCILE_FAILED";

    private static final int MAX_OPERATIONS_PER_CYCLE = 10;
    private static final int MAX_PARTITIONS_PER_CYCLE = 5;

    private static final List<State> LIVE_OPERATION_STATES =
            List.of(State.PENDING, State.APPLYING, State.RETRYABLE_FAILURE);
    private static final List<State> DUE_STATES = List.of(State.PENDING, State.RETRYABLE_FAILURE);
    private static final Duration STALE_APPLYING_AFTER = Duration.ofMinutes(5);

    // Cửa sổ quét claim theo recorded_seq: lớn hơn nhiều số operation xử lý tối đa một chu
    // kỳ (10), nên operation ngoài cửa sổ hoặc bị một operation cũ hơn cùng partition chặn
    // trong cửa sổ, hoặc được chu kỳ sau nhận; thứ tự claim theo partition không đổi.
    private static final int CLAIM_SCAN_LIMIT = 100;
    private static final Duration DRIFT_SCAN_INTERVAL = Duration.ofMinutes(1);

    private final AuthorizationOperationRepository operations;
    private final AuthzPartitionRepository partitions;
    private final AuthzProjectionRepository projections;
    private final ProjectionReconciler reconciler;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<UUID, OrphanRetry> orphanRetries = new ConcurrentHashMap<>();
    private Instant lastDriftScan = Instant.EPOCH;
    private @Nullable UUID driftScanCursor;
    private @Nullable UUID orphanScanCursor;

    /** Trạng thái giới hạn tốc độ của một partition bị fence mà không có operation đang sống. */
    private record OrphanRetry(int failures, Instant nextAttemptAt) {}

    public ProjectionWorker(
            AuthorizationOperationRepository operations,
            AuthzPartitionRepository partitions,
            AuthzProjectionRepository projections,
            ProjectionReconciler reconciler,
            PlatformTransactionManager transactionManager,
            AuditRecorder audit) {
        this.operations = operations;
        this.partitions = partitions;
        this.projections = projections;
        this.reconciler = reconciler;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private record ClaimedOperation(
            UUID id, List<UUID> partitions, List<Long> revisions, int attemptCount) {}

    private static final class SupersededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static Duration backoffFor(int attemptCount) {
        return Duration.ofSeconds(Math.min(60, 5L * Math.max(1, attemptCount)));
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            drainQueue();
            reconcileOrphanedPartitions();
            repairDriftIfDue();
        } finally {
            running.set(false);
        }
    }

    private void drainQueue() {
        for (int processed = 0; processed < MAX_OPERATIONS_PER_CYCLE; processed++) {
            Optional<ClaimedOperation> claimed = claimNext();
            if (claimed.isEmpty()) {
                return;
            }
            process(claimed.get());
        }
    }

    private Optional<ClaimedOperation> claimNext() {
        return transactions.execute(_ -> {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(STALE_APPLYING_AFTER);
            List<QueueRow> queue =
                    operations
                            .findLiveOrderByRecordedSeq(
                                    LIVE_OPERATION_STATES, PageRequest.of(0, CLAIM_SCAN_LIMIT))
                            .stream()
                            .map(operation -> {
                                // Mốc thời gian đọc một lần vào biến cục bộ: APPLYING
                                // xét độ cũ theo updatedAt, các trạng thái còn lại xét
                                // lịch thử lại; mốc vắng nghĩa là tới hạn ngay.
                                boolean due;
                                if (operation.getState() == State.APPLYING) {
                                    Instant updatedAt = operation.getUpdatedAt();
                                    due = updatedAt == null || !updatedAt.isAfter(staleBefore);
                                } else {
                                    Instant nextAttemptAt = operation.getNextAttemptAt();
                                    due = nextAttemptAt == null || !nextAttemptAt.isAfter(now);
                                }
                                return new QueueRow(
                                        operation.getId(),
                                        operation.getAffectedPartitions(),
                                        operation.getAffectedRevisions(),
                                        operation.getAttemptCount(),
                                        due);
                            })
                            .toList();
            // Mẫu hàng đợi PostgreSQL: các dòng tới hạn được khóa FOR UPDATE SKIP LOCKED, nên
            // một dòng instance khác đang claim không bao giờ xuất hiện ở đây; điều kiện claim
            // được kiểm lại ngay trong câu khóa, và việc giữ khóa ghi tới hết transaction claim
            // là bằng chứng sở hữu.
            Map<UUID, AuthorizationOperation> claimable = new LinkedHashMap<>();
            if (!queue.isEmpty()) {
                for (AuthorizationOperation operation :
                        operations.lockClaimableByIds(
                                queue.stream().map(QueueRow::id).toList(),
                                DUE_STATES,
                                State.APPLYING,
                                now,
                                staleBefore)) {
                    claimable.put(operation.getId(), operation);
                }
            }
            Set<UUID> blocked = new HashSet<>();
            ClaimedOperation claimed = null;
            for (QueueRow row : queue) {
                boolean overlaps = row.partitions().stream().anyMatch(blocked::contains);
                blocked.addAll(row.partitions());
                if (claimed == null && row.due() && !overlaps) {
                    AuthorizationOperation operation = claimable.get(row.id());
                    if (operation != null) {
                        operation.setState(State.APPLYING);
                        operation.setAttemptCount(operation.getAttemptCount() + 1);
                        operation.setNextAttemptAt(null);
                        operation.setLastErrorCode(null);
                        claimed =
                                new ClaimedOperation(
                                        operation.getId(),
                                        operation.getAffectedPartitions(),
                                        operation.getAffectedRevisions(),
                                        operation.getAttemptCount());
                    }
                }
            }
            return Optional.ofNullable(claimed);
        });
    }

    private record QueueRow(
            UUID id, List<UUID> partitions, List<Long> revisions, int attemptCount, boolean due) {}

    private void process(ClaimedOperation operation) {
        try {
            reconcileAffected(operation.partitions(), operation.revisions());
            transactions.executeWithoutResult(_ -> {
                // Đọc lại các desired revision trong cùng transaction ghi nhận
                // thành công: một mutation đã bump một partition sau các kiểm tra ở trên khiến
                // operation này bị superseded (thay thế), không phải lỗi reconcile, và các
                // partition nó đã fence nay thuộc về operation mới hơn.
                for (int i = 0; i < operation.partitions().size(); i++) {
                    Optional<Long> current =
                            partitions.findDesiredRevisionById(operation.partitions().get(i));
                    if (current.isEmpty()
                            || current.get().longValue()
                                    != operation.revisions().get(i).longValue()) {
                        throw new SupersededException();
                    }
                }
                operations.markSucceeded(
                        operation.id(), State.SUCCEEDED, State.APPLYING, operation.attemptCount());
            });
        } catch (SupersededException ex) {
            recordFailure(operation, ERROR_SUPERSEDED, true, false);
        } catch (ProjectionReconciler.ReconciliationException ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(
                        "Operation {} failed reconciliation with code {}: {}",
                        operation.id(),
                        ex.errorCode(),
                        ex.getMessage());
            }
            boolean superseded =
                    ProjectionReconciler.ERROR_REVISION_SUPERSEDED.equals(ex.errorCode());
            recordFailure(
                    operation,
                    superseded ? ERROR_SUPERSEDED : ex.errorCode(),
                    superseded || operation.attemptCount() >= MAX_ATTEMPTS,
                    !superseded);
        } catch (RestClientException
                | DataAccessException
                | IllegalStateException
                | OAuth2AuthorizationException ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(
                        "Operation {} failed reconciliation with code {}",
                        operation.id(),
                        ERROR_RECONCILE_FAILED,
                        ex);
            }
            recordFailure(
                    operation,
                    ERROR_RECONCILE_FAILED,
                    operation.attemptCount() >= MAX_ATTEMPTS,
                    true);
        }
    }

    void reconcileAffected(List<UUID> partitionIds, List<Long> revisions) {
        Map<UUID, Long> remaining = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> dependencies = new LinkedHashMap<>();
        for (int i = 0; i < partitionIds.size(); i++) {
            UUID id = partitionIds.get(i);
            remaining.put(id, revisions.get(i));
            Set<UUID> upstream = new HashSet<>();
            for (var projection : reconciler.projectionsOf(id)) {
                upstream.addAll(projection.dependencyPartitionIds());
            }
            dependencies.put(id, upstream);
        }
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            var entries = remaining.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                UUID id = entry.getKey();
                if (dependencies.getOrDefault(id, Set.of()).stream()
                        .anyMatch(remaining::containsKey)) {
                    continue;
                }
                var partition = reconciler.partition(id);
                long revision = entry.getValue();
                if (partition.desiredRevision() != revision) {
                    throw new SupersededException();
                }
                if (partition.state() != AuthorizationPartition.State.READY
                        || partition.appliedRevision() != revision) {
                    reconciler.reconcilePartition(id, revision);
                }
                entries.remove();
                progressed = true;
            }
            if (!progressed) {
                throw new ProjectionReconciler.ReconciliationException(
                        "DEPENDENCY_CYCLE", "Operation partitions have cyclic dependencies");
            }
        }
    }

    private void recordFailure(
            ClaimedOperation operation,
            String errorCode,
            boolean terminal,
            boolean fencePartitions) {
        transactions.executeWithoutResult(_ -> {
            // Được bảo vệ bởi trạng thái đã claim: một instance thua operation vào tay claim
            // khác sẽ không ghi gì, nên không bao giờ fail được một operation đã thành công.
            int updated =
                    operations.markFailed(
                            operation.id(),
                            terminal ? State.FAILED : State.RETRYABLE_FAILURE,
                            terminal
                                    ? null
                                    : Instant.now().plus(backoffFor(operation.attemptCount())),
                            errorCode,
                            State.APPLYING,
                            operation.attemptCount());
            if (updated == 1 && fencePartitions && terminal) {
                for (UUID partitionId : operation.partitions()) {
                    partitions.markFailedIfPending(
                            partitionId,
                            AuthorizationPartition.State.FAILED,
                            AuthorizationPartition.State.PENDING);
                    projections.markFailedIfPendingByPartitionId(
                            partitionId,
                            AuthorizationProjection.State.FAILED,
                            AuthorizationProjection.State.PENDING);
                }
            }
            // Lỗi đồng bộ chỉ thành sự kiện audit khi operation thật sự kết thúc ở FAILED:
            // lần thử còn retry không ghi (tránh một dòng cho mỗi lần thử), và SUPERSEDED là
            // thay thế revision chứ không phải lỗi đồng bộ.
            if (updated == 1 && terminal && !ERROR_SUPERSEDED.equals(errorCode)) {
                audit.record(
                        null,
                        null,
                        AuditEvent.Action.AUTHORIZATION_SYNC,
                        AuditEvent.TargetType.AUTHORIZATION_OPERATION,
                        operation.id(),
                        operation.id(),
                        AuditEvent.Outcome.FAILED);
            }
        });
    }

    /**
     * Các partition bị fence mà không có operation đang sống vẫn hội tụ: xác minh và mở lại, hoặc
     * giữ nguyên fence. Retry bị giới hạn tốc độ bằng cùng backoff có giới hạn như đường operation,
     * nên một partition không thể hội tụ (ví dụ khi Keycloak đang sập) không bị retry ở mọi poll
     * một giây.
     */
    void reconcileOrphanedPartitions() {
        Set<UUID> referenced = new HashSet<>();
        for (List<UUID> affected :
                operations.findAffectedPartitionsByStateIn(LIVE_OPERATION_STATES)) {
            referenced.addAll(affected);
        }
        List<UUID> orphaned =
                partitions.findIdsByStateOrderByIdAsc(AuthorizationPartition.State.PENDING).stream()
                        .filter(partitionId -> !referenced.contains(partitionId))
                        .toList();
        orphanRetries.keySet().retainAll(new HashSet<>(orphaned));
        List<UUID> batch = scanWindow(orphaned, orphanScanCursor, MAX_PARTITIONS_PER_CYCLE);
        if (!batch.isEmpty()) {
            orphanScanCursor = batch.getLast();
        }
        Instant now = Instant.now();
        for (UUID partitionId : batch) {
            OrphanRetry retry = orphanRetries.get(partitionId);
            if (retry != null && now.isBefore(retry.nextAttemptAt())) {
                continue;
            }
            // Fail-closed: một partition không thể hội tụ giữ nguyên PENDING tới lần thử
            // đã lên lịch tiếp theo của nó.
            if (reconciler.tryReconcilePartition(partitionId)) {
                orphanRetries.remove(partitionId);
            } else {
                int failures = retry == null ? 1 : retry.failures() + 1;
                orphanRetries.put(
                        partitionId, new OrphanRetry(failures, now.plus(backoffFor(failures))));
            }
        }
    }

    /**
     * Các sửa đổi từ console ngoài hệ thống không được đồng bộ tức thì: theo nhịp chậm, các
     * partition READY được đối chiếu với Keycloak, và một partition bị drift sẽ được fence trước,
     * reconcile, xác minh rồi chỉ sau đó mới mở lại bằng đường publish bình thường. Lượt quét xoay
     * vòng qua toàn bộ tập READY thay vì kiểm tra lại mãi cùng các partition cũ nhất.
     */
    private void repairDriftIfDue() {
        Instant now = Instant.now();
        if (now.isBefore(lastDriftScan.plus(DRIFT_SCAN_INTERVAL))) {
            return;
        }
        lastDriftScan = now;
        List<UUID> ready =
                new ArrayList<>(
                        partitions.findIdsByStateOrderByIdAsc(AuthorizationPartition.State.READY));
        ready.sort(Comparator.comparing(id -> id.toString()));
        List<UUID> batch = scanWindow(ready, driftScanCursor, MAX_PARTITIONS_PER_CYCLE);
        if (!batch.isEmpty()) {
            driftScanCursor = batch.getLast();
        }
        for (UUID partitionId : batch) {
            boolean intact;
            try {
                intact = reconciler.verifyStoredGraph(partitionId);
            } catch (DataAccessException ex) {
                continue;
            }
            if (!intact) {
                reconciler.fencePartition(partitionId);
                // Một lần sửa thất bại giữ nguyên fence; đường orphan tiếp tục retry nó.
                reconciler.tryReconcilePartition(partitionId);
            }
        }
    }

    /**
     * Cửa sổ quét drift xoay vòng trên tập READY theo thứ tự chuỗi UUID ổn định: tối đa {@code
     * limit} ID nằm ngay sau {@code after}, vòng về đầu danh sách, nên mọi partition READY đều được
     * chạm tới trong một số lượt quét có giới hạn.
     */
    static List<UUID> scanWindow(List<UUID> orderedIds, @Nullable UUID after, int limit) {
        List<UUID> window = new ArrayList<>();
        for (UUID id : orderedIds) {
            if (window.size() >= limit) {
                break;
            }
            if (after == null || id.toString().compareTo(after.toString()) > 0) {
                window.add(id);
            }
        }
        for (UUID id : orderedIds) {
            if (window.size() >= limit) {
                break;
            }
            if (after != null && id.toString().compareTo(after.toString()) <= 0) {
                window.add(id);
            }
        }
        return window;
    }
}
