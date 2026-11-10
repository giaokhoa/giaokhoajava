package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import java.sql.Array;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * operation SUCCEEDED sau khi cơ sở dữ liệu xác nhận mọi partition READY tại revision đó. Claim là
 * nguyên tử (các dòng khóa {@code FOR UPDATE SKIP LOCKED} cộng một update có điều kiện), nên hai
 * instance của ứng dụng không bao giờ xử lý cùng một operation. Thất bại retry với cùng idempotency
 * payload và backoff có giới hạn; cạn ngân sách lần thử sẽ fail operation và các partition của nó
 * mà không mở fence. Các partition bị fence mà không có operation đang sống (dependency fence, sửa
 * drift ngoài hệ thống) được reconcile trên cùng vòng lặp dưới cùng backoff có giới hạn, và các
 * partition READY được kiểm tra định kỳ tìm drift từ console qua một lượt quét xoay vòng chạm tới
 * mọi partition.
 */
@Component
public class ProjectionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionWorker.class);

    static final int MAX_ATTEMPTS = 5;
    static final String ERROR_SUPERSEDED = "SUPERSEDED_BY_NEWER_REVISION";
    static final String ERROR_RECONCILE_FAILED = "PROJECTION_RECONCILE_FAILED";

    private static final int MAX_OPERATIONS_PER_CYCLE = 10;
    private static final int MAX_PARTITIONS_PER_CYCLE = 5;
    private static final Duration DRIFT_SCAN_INTERVAL = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
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
            JdbcTemplate jdbc,
            ProjectionReconciler reconciler,
            PlatformTransactionManager transactionManager,
            AuditRecorder audit) {
        this.jdbc = jdbc;
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
            List<QueueRow> queue =
                    jdbc.query(
                            "SELECT id, affected_partitions, affected_revisions,"
                                    + " attempt_count, state,"
                                    + " (next_attempt_at IS NULL"
                                    + " OR next_attempt_at <= now()) AS due,"
                                    + " (updated_at <= now() - INTERVAL '5 minutes')"
                                    + " AS stale"
                                    + " FROM authorization_operation"
                                    + " WHERE state IN ('PENDING', 'APPLYING',"
                                    + " 'RETRYABLE_FAILURE')"
                                    + " ORDER BY recorded_seq",
                            (rs, _) -> {
                                boolean due =
                                        "APPLYING".equals(rs.getString("state"))
                                                ? rs.getBoolean("stale")
                                                : rs.getBoolean("due");
                                return new QueueRow(
                                        rs.getObject("id", UUID.class),
                                        uuidList(rs, "affected_partitions"),
                                        longList(rs, "affected_revisions"),
                                        rs.getInt("attempt_count"),
                                        due);
                            });
            // Mẫu hàng đợi PostgreSQL: các dòng tới hạn được khóa FOR UPDATE SKIP LOCKED, nên
            // một dòng instance khác đang claim không bao giờ xuất hiện ở đây; update có điều
            // kiện bên dưới, với số dòng bị ảnh hưởng là bằng chứng sở hữu duy nhất, là chốt chặn
            // cuối.
            Set<UUID> claimable =
                    new HashSet<>(
                            jdbc.queryForList(
                                    "SELECT id FROM authorization_operation"
                                            + " WHERE (state IN ('PENDING', 'RETRYABLE_FAILURE')"
                                            + " AND (next_attempt_at IS NULL"
                                            + " OR next_attempt_at <= now()))"
                                            + " OR (state = 'APPLYING'"
                                            + " AND updated_at <= now()"
                                            + " - INTERVAL '5 minutes')"
                                            + " ORDER BY recorded_seq"
                                            + " FOR UPDATE SKIP LOCKED",
                                    UUID.class));
            Set<UUID> blocked = new HashSet<>();
            ClaimedOperation claimed = null;
            for (QueueRow row : queue) {
                boolean overlaps = row.partitions().stream().anyMatch(blocked::contains);
                blocked.addAll(row.partitions());
                if (claimed == null && row.due() && !overlaps && claimable.contains(row.id())) {
                    int updated =
                            jdbc.update(
                                    "UPDATE authorization_operation SET state = 'APPLYING',"
                                            + " attempt_count = attempt_count + 1,"
                                            + " next_attempt_at = NULL, last_error_code = NULL,"
                                            + " updated_at = now() WHERE id = ?"
                                            + " AND (state IN ('PENDING', 'RETRYABLE_FAILURE')"
                                            + " OR (state = 'APPLYING'"
                                            + " AND updated_at <= now()"
                                            + " - INTERVAL '5 minutes'))",
                                    row.id());
                    boolean claimedByThisInstance = updated == 1;
                    if (claimedByThisInstance) {
                        claimed =
                                new ClaimedOperation(
                                        row.id(),
                                        row.partitions(),
                                        row.revisions(),
                                        row.attemptCount() + 1);
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
                    List<Long> current =
                            jdbc.queryForList(
                                    "SELECT desired_revision FROM authz_partition WHERE id = ?",
                                    Long.class,
                                    operation.partitions().get(i));
                    if (current.isEmpty()
                            || current.getFirst().longValue()
                                    != operation.revisions().get(i).longValue()) {
                        throw new SupersededException();
                    }
                }
                jdbc.update(
                        "UPDATE authorization_operation SET state = 'SUCCEEDED',"
                                + " next_attempt_at = NULL, last_error_code = NULL,"
                                + " updated_at = now()"
                                + " WHERE id = ? AND state = 'APPLYING' AND attempt_count = ?",
                        operation.id(),
                        operation.attemptCount());
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
                    jdbc.update(
                            "UPDATE authorization_operation SET state = ?, next_attempt_at = ?,"
                                    + " last_error_code = ?, updated_at = now()"
                                    + " WHERE id = ? AND state = 'APPLYING' AND attempt_count = ?",
                            terminal ? "FAILED" : "RETRYABLE_FAILURE",
                            terminal
                                    ? null
                                    : Timestamp.from(
                                            Instant.now()
                                                    .plus(backoffFor(operation.attemptCount()))),
                            errorCode,
                            operation.id(),
                            operation.attemptCount());
            if (updated == 1 && fencePartitions && terminal) {
                for (UUID partitionId : operation.partitions()) {
                    jdbc.update(
                            "UPDATE authz_partition SET state = 'FAILED'"
                                    + " WHERE id = ? AND state = 'PENDING'",
                            partitionId);
                    jdbc.update(
                            "UPDATE authz_projection SET state = 'FAILED'"
                                    + " WHERE partition_id = ? AND state = 'PENDING'",
                            partitionId);
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
        List<UUID> orphaned =
                jdbc.queryForList(
                        "SELECT p.id FROM authz_partition p WHERE p.state = 'PENDING'"
                                + " AND NOT EXISTS (SELECT 1 FROM authorization_operation o"
                                + " WHERE o.state IN ('PENDING', 'APPLYING', 'RETRYABLE_FAILURE')"
                                + " AND p.id = ANY(o.affected_partitions))"
                                + " ORDER BY p.id",
                        UUID.class);
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
                        jdbc.queryForList(
                                "SELECT id FROM authz_partition WHERE state = 'READY'",
                                UUID.class));
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

    private static List<UUID> uuidList(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] raw = (Object[]) array.getArray();
        List<UUID> out = new ArrayList<>(raw.length);
        for (Object item : raw) {
            out.add(item instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(item)));
        }
        return List.copyOf(out);
    }

    private static List<Long> longList(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] raw = (Object[]) array.getArray();
        List<Long> out = new ArrayList<>(raw.length);
        for (Object item : raw) {
            out.add(
                    item instanceof Number number
                            ? number.longValue()
                            : Long.parseLong(String.valueOf(item)));
        }
        return List.copyOf(out);
    }
}
