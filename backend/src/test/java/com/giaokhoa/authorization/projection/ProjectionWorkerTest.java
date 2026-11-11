package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.support.RandomIds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ProjectionWorkerTest {
    private static final UUID OPERATION = RandomIds.randomId();
    private static final UUID PARTITION = RandomIds.randomId();

    private final AuthorizationOperationRepository operations =
            mock(AuthorizationOperationRepository.class);
    private final AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
    private final AuthzProjectionRepository projections = mock(AuthzProjectionRepository.class);
    private final ProjectionReconciler reconciler = mock(ProjectionReconciler.class);

    private ProjectionWorker worker() {
        return new ProjectionWorker(
                operations,
                partitions,
                projections,
                reconciler,
                stubTransactions(),
                new AuditRecorder(mock(JdbcTemplate.class)));
    }

    @Test
    void backoffGrowsWithAttemptsAndIsCapped() {
        assertEquals(Duration.ofSeconds(5), ProjectionWorker.backoffFor(1));
        assertEquals(Duration.ofSeconds(10), ProjectionWorker.backoffFor(2));
        assertTrue(ProjectionWorker.backoffFor(3).compareTo(ProjectionWorker.backoffFor(2)) > 0);
        assertEquals(Duration.ofSeconds(60), ProjectionWorker.backoffFor(100));
        assertEquals(Duration.ofSeconds(5), ProjectionWorker.backoffFor(0));
    }

    @Test
    void anOperationWhoseClaimUpdateLosesTheRaceIsNotProcessed() {
        stubQueue(0);
        // Một instance khác thắng claim: tập khóa SKIP LOCKED không chứa operation.
        when(operations.lockClaimableByIds(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));

        worker().poll();

        verify(reconciler, never()).partition(any());
    }

    @Test
    void aRevisionBumpedDuringReconcileEndsSupersededNotSucceeded() {
        stubQueue(0);
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        // Một mutation mới hơn đã bump desired revision sau kiểm tra trong vòng lặp.
        when(partitions.findDesiredRevisionById(PARTITION)).thenReturn(Optional.of(2L));

        worker().poll();

        verify(operations, never()).markSucceeded(any(), any(), any(), anyInt());
        verify(operations)
                .markFailed(
                        eq(OPERATION),
                        eq(AuthorizationOperation.State.FAILED),
                        isNull(),
                        eq(ProjectionWorker.ERROR_SUPERSEDED),
                        eq(AuthorizationOperation.State.APPLYING),
                        eq(1));
        verify(partitions, never()).markFailedIfPending(any(), any(), any());
    }

    @Test
    void driftScanWindowRotatesThroughTheWholeReadySet() {
        List<UUID> ready = List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6), uuid(7));
        assertEquals(ready.subList(0, 5), ProjectionWorker.scanWindow(ready, null, 5));
        // Cửa sổ tiếp tục sau ID quét cuối cùng và vòng lại, nên các partition ngoài
        // trang đầu tiên được chạm tới thay vì mãi kiểm tra lại cùng năm partition cũ nhất.
        assertEquals(
                List.of(uuid(6), uuid(7), uuid(1), uuid(2), uuid(3)),
                ProjectionWorker.scanWindow(ready, uuid(5), 5));
        assertEquals(ready, ProjectionWorker.scanWindow(ready, uuid(7), 7));
        assertEquals(List.of(), ProjectionWorker.scanWindow(List.of(), null, 5));
    }

    @Test
    void aFailingOrphanedPartitionIsNotRetriedOnEveryPoll() {
        when(operations.findAffectedPartitionsByStateIn(any())).thenReturn(List.of());
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(List.of(PARTITION));
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        when(reconciler.tryReconcilePartition(eq(PARTITION), anyInt())).thenReturn(false);
        ProjectionWorker worker = worker();

        worker.reconcileOrphanedPartitions();
        worker.reconcileOrphanedPartitions();

        // Poll thứ hai nằm trong cửa sổ backoff có giới hạn: không có lần thử mạng mới.
        verify(reconciler, times(1)).tryReconcilePartition(eq(PARTITION), anyInt());
    }

    @Test
    void anOrphanedPartitionDeletedAfterTheScanIsSkippedSilently() {
        when(operations.findAffectedPartitionsByStateIn(any())).thenReturn(List.of());
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(List.of(PARTITION));
        // Partition bị xoá giữa lượt quét và lần đọc: vòng mồ côi bỏ qua, không
        // để EmptyResultDataAccessException thoát ra poll().
        when(reconciler.partition(PARTITION)).thenThrow(new EmptyResultDataAccessException(1));
        ProjectionWorker worker = worker();

        worker.reconcileOrphanedPartitions();

        verify(reconciler, never()).tryReconcilePartition(any(), anyInt());
    }

    @Test
    void orphanScanOnlySelectsPartitionsWithRecordedStateIntent() {
        when(operations.findAffectedPartitionsByStateIn(any())).thenReturn(List.of());
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(List.of());
        ProjectionWorker worker = worker();

        worker.reconcileOrphanedPartitions();

        // Lượt quét phải đi qua truy vấn đã lọc ý định trạng thái ngay từ DB (PENDING, revision
        // đã rời 0 hoặc đã có projection): các partition chưa từng được chiếu không còn vào
        // được vòng thử. Hành vi trên DB thật do bộ IT projection trên CI khóa tiếp.
        verify(partitions)
                .findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING);
        verify(partitions, never()).findIdsByStateOrderByIdAsc(any());
        verify(reconciler, never()).partition(any());
    }

    @Test
    void anOrphanThatExhaustedItsBudgetIsSkippedUntilItsRevisionChanges() {
        when(operations.findAffectedPartitionsByStateIn(any())).thenReturn(List.of());
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(List.of(PARTITION));
        ProjectionWorker worker = worker();
        worker.recordOrphanFailure(PARTITION, ProjectionWorker.MAX_ATTEMPTS, Instant.EPOCH, 1L);

        // Ngân sách đã cạn và revision không đổi: không thử thêm lần nào nữa.
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        worker.reconcileOrphanedPartitions();
        verify(reconciler, never()).tryReconcilePartition(any(), anyInt());

        // Revision đổi nghĩa là partition đã nhận ý định trạng thái mới: ngân sách tính lại
        // từ đầu và partition được thử ngay trong lượt quét này.
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 2L, 0L, AuthorizationPartition.State.PENDING));
        worker.reconcileOrphanedPartitions();
        verify(reconciler, times(1)).tryReconcilePartition(PARTITION, 1);
    }

    @Test
    void dependentPartitionsReconcileAfterTheirSourcesRegardlessOfUuidOrder() {
        UUID a = uuid(3);
        UUID b = uuid(2);
        UUID c = uuid(1);
        when(reconciler.projectionsOf(b)).thenReturn(List.of(projection(b, a)));
        when(reconciler.projectionsOf(c)).thenReturn(List.of(projection(c, b)));
        for (UUID id : List.of(a, b, c)) {
            when(reconciler.partition(id))
                    .thenReturn(
                            new ProjectionReconciler.PartitionRow(
                                    id, 1, 0, AuthorizationPartition.State.PENDING));
        }
        var worker = worker();
        worker.reconcileAffected(List.of(a, c, b), List.of(1L, 1L, 1L));
        var order = inOrder(reconciler);
        order.verify(reconciler).reconcilePartition(a, 1);
        order.verify(reconciler).reconcilePartition(b, 1);
        order.verify(reconciler).reconcilePartition(c, 1);
    }

    @Test
    void cyclicDependenciesFailClosedBeforeWritingAnyPartition() {
        UUID a = uuid(1);
        UUID b = uuid(2);
        when(reconciler.projectionsOf(a)).thenReturn(List.of(projection(a, b)));
        when(reconciler.projectionsOf(b)).thenReturn(List.of(projection(b, a)));
        var worker = worker();
        var failure =
                assertThrows(
                        ProjectionReconciler.ReconciliationException.class,
                        () -> worker.reconcileAffected(List.of(a, b), List.of(1L, 1L)));
        assertEquals("DEPENDENCY_CYCLE", failure.errorCode());
        verify(reconciler, never())
                .reconcilePartition(any(UUID.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void orphanScanReachesRecoverableRowsBehindFiveFailingRowsDuringBackoff() {
        List<UUID> ids = List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6));
        when(operations.findAffectedPartitionsByStateIn(any())).thenReturn(List.of());
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(ids);
        for (UUID id : ids) {
            when(reconciler.partition(id))
                    .thenReturn(
                            new ProjectionReconciler.PartitionRow(
                                    id, 1L, 0L, AuthorizationPartition.State.PENDING));
        }
        when(reconciler.tryReconcilePartition(eq(uuid(6)), anyInt())).thenReturn(true);
        var worker = worker();
        worker.reconcileOrphanedPartitions();
        worker.reconcileOrphanedPartitions();
        verify(reconciler).tryReconcilePartition(eq(uuid(6)), anyInt());
        for (UUID id : ids.subList(0, 5)) {
            verify(reconciler, times(1)).tryReconcilePartition(eq(id), anyInt());
        }
    }

    @Test
    void aWorkerThatLostItsAttemptCannotFailPartitionsOwnedByTheNewAttempt() {
        stubQueue(ProjectionWorker.MAX_ATTEMPTS - 1);
        when(reconciler.partition(PARTITION))
                .thenThrow(
                        new ProjectionReconciler.ReconciliationException(
                                "KEYCLOAK_FAILURE", "Lỗi sau khi worker khác đã reclaim"));
        // Update ghi failure trả 0: attempt_count đã đổi trong DB, worker này hết quyền
        // sở hữu (mock mặc định trả 0 cho markFailed).
        worker().poll();
        verify(operations)
                .markFailed(
                        eq(OPERATION),
                        eq(AuthorizationOperation.State.FAILED),
                        isNull(),
                        eq("KEYCLOAK_FAILURE"),
                        eq(AuthorizationOperation.State.APPLYING),
                        eq(ProjectionWorker.MAX_ATTEMPTS));
        verify(partitions, never()).markFailedIfPending(any(), any(), any());
        verify(projections, never()).markFailedIfPendingByPartitionId(any(), any(), any());
    }

    private static ProjectionReconciler.ProjectionRow projection(UUID owner, UUID dependency) {
        return new ProjectionReconciler.ProjectionRow(
                UUID.randomUUID(),
                owner,
                "workspace:" + owner,
                "resource",
                List.of(),
                List.of(dependency),
                "a".repeat(64),
                "a".repeat(64),
                AuthorizationProjection.State.READY);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }

    /** Một operation PENDING tới hạn trên {@link #PARTITION} tại revision 1. */
    private void stubQueue(int attemptCount) {
        var operation =
                new AuthorizationOperation(
                        OPERATION,
                        UUID.randomUUID(),
                        null,
                        "key",
                        "a".repeat(64),
                        List.of(PARTITION),
                        List.of(1L),
                        1L);
        operation.setAttemptCount(attemptCount);
        when(operations.findLiveOrderByRecordedSeq(any(), any())).thenReturn(List.of(operation));
        when(operations.lockClaimableByIds(any(), any(), any(), any(), any()))
                .thenReturn(List.of(operation));
        // Operation đang sống nên partition của nó không phải partition mồ côi.
        when(operations.findAffectedPartitionsByStateIn(any()))
                .thenReturn(List.of(List.of(PARTITION)));
        when(partitions.findIdsByStateWithIntentOrderByIdAsc(AuthorizationPartition.State.PENDING))
                .thenReturn(List.of(PARTITION));
    }

    private static PlatformTransactionManager stubTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }
}
