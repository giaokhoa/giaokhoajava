package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.support.RandomIds;
import java.sql.Array;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ProjectionWorkerTest {
    private static final UUID OPERATION = RandomIds.randomId();
    private static final UUID PARTITION = RandomIds.randomId();

    @Test
    void backoffGrowsWithAttemptsAndIsCapped() {
        assertEquals(Duration.ofSeconds(5), ProjectionWorker.backoffFor(1));
        assertEquals(Duration.ofSeconds(10), ProjectionWorker.backoffFor(2));
        assertTrue(ProjectionWorker.backoffFor(3).compareTo(ProjectionWorker.backoffFor(2)) > 0);
        assertEquals(Duration.ofSeconds(60), ProjectionWorker.backoffFor(100));
        assertEquals(Duration.ofSeconds(5), ProjectionWorker.backoffFor(0));
    }

    @Test
    void anOperationWhoseClaimUpdateLosesTheRaceIsNotProcessed() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        stubQueue(jdbc);
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        // Một instance khác thắng claim: update có điều kiện không khớp dòng nào.
        when(jdbc.update(contains("SET state = 'APPLYING'"), any(UUID.class))).thenReturn(0);

        new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc)).poll();

        verify(reconciler, never()).partition(any());
    }

    @Test
    void aRevisionBumpedDuringReconcileEndsSupersededNotSucceeded() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        stubQueue(jdbc);
        when(jdbc.update(contains("SET state = 'APPLYING'"), any(UUID.class))).thenReturn(1);
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        // Một mutation mới hơn đã bump desired revision sau kiểm tra trong vòng lặp.
        when(jdbc.queryForList(contains("desired_revision"), eq(Long.class), any(UUID.class)))
                .thenReturn(List.of(2L));

        new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc)).poll();

        List<Invocation> updates =
                mockingDetails(jdbc).getInvocations().stream()
                        .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                        .toList();
        assertFalse(
                updates.stream()
                        .anyMatch(invocation ->
                                String.valueOf(invocation.getArgument(0, Object.class))
                                        .contains("'SUCCEEDED'")),
                "The operation must not be marked SUCCEEDED at a stale revision");
        assertTrue(
                updates.stream()
                        .anyMatch(invocation -> {
                            List<Object> arguments = flattened(invocation);
                            return arguments.contains("FAILED")
                                    && arguments.contains(ProjectionWorker.ERROR_SUPERSEDED);
                        }),
                "The operation must end FAILED with SUPERSEDED_BY_NEWER_REVISION");
        assertFalse(
                updates.stream()
                        .anyMatch(invocation ->
                                String.valueOf(invocation.getArgument(0, Object.class))
                                        .contains("authz_partition SET state = 'FAILED'")),
                "A superseded operation must not fence partitions of the newer operation");
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
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        when(jdbc.queryForList(anyString(), eq(UUID.class), anyInt()))
                .thenReturn(List.of(PARTITION));
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(PARTITION));
        when(reconciler.partition(PARTITION))
                .thenReturn(
                        new ProjectionReconciler.PartitionRow(
                                PARTITION, 1L, 0L, AuthorizationPartition.State.PENDING));
        when(reconciler.tryReconcilePartition(eq(PARTITION), anyInt())).thenReturn(false);
        ProjectionWorker worker =
                new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc));

        worker.reconcileOrphanedPartitions();
        worker.reconcileOrphanedPartitions();

        // Poll thứ hai nằm trong cửa sổ backoff có giới hạn: không có lần thử mạng mới.
        verify(reconciler, times(1)).tryReconcilePartition(eq(PARTITION), anyInt());
    }

    @Test
    void orphanScanOnlySelectsPartitionsWithRecordedStateIntent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of());
        ProjectionWorker worker =
                new ProjectionWorker(
                        jdbc,
                        mock(ProjectionReconciler.class),
                        stubTransactions(),
                        new AuditRecorder(jdbc));

        worker.reconcileOrphanedPartitions();

        // Câu truy vấn phải tự nó loại partition chưa từng được chiếu ngay từ DB (PENDING,
        // revision 0 hai phía, không dòng projection nào): các partition đó không còn vào
        // được vòng thử. Hành vi trên DB thật do bộ IT projection trên CI khóa tiếp.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(UUID.class));
        assertTrue(sql.getValue().contains("p.desired_revision > 0"));
        assertTrue(sql.getValue().contains("p.applied_revision > 0"));
        assertTrue(sql.getValue().contains("EXISTS (SELECT 1 FROM authz_projection"));
    }

    @Test
    void anOrphanThatExhaustedItsBudgetIsSkippedUntilItsRevisionChanges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(PARTITION));
        when(reconciler.tryReconcilePartition(eq(PARTITION), anyInt())).thenReturn(false);
        ProjectionWorker worker =
                new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc));
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
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
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
        var worker =
                new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc));
        worker.reconcileAffected(List.of(a, c, b), List.of(1L, 1L, 1L));
        var order = inOrder(reconciler);
        order.verify(reconciler).reconcilePartition(a, 1);
        order.verify(reconciler).reconcilePartition(b, 1);
        order.verify(reconciler).reconcilePartition(c, 1);
    }

    @Test
    void cyclicDependenciesFailClosedBeforeWritingAnyPartition() {
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        UUID a = uuid(1);
        UUID b = uuid(2);
        when(reconciler.projectionsOf(a)).thenReturn(List.of(projection(a, b)));
        when(reconciler.projectionsOf(b)).thenReturn(List.of(projection(b, a)));
        var worker =
                new ProjectionWorker(
                        mock(JdbcTemplate.class),
                        reconciler,
                        stubTransactions(),
                        new AuditRecorder(mock(JdbcTemplate.class)));
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
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        List<UUID> ids = List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6));
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(ids);
        for (UUID id : ids) {
            when(reconciler.partition(id))
                    .thenReturn(
                            new ProjectionReconciler.PartitionRow(
                                    id, 1L, 0L, AuthorizationPartition.State.PENDING));
        }
        when(reconciler.tryReconcilePartition(eq(uuid(6)), anyInt())).thenReturn(true);
        var worker =
                new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc));
        worker.reconcileOrphanedPartitions();
        worker.reconcileOrphanedPartitions();
        verify(reconciler).tryReconcilePartition(eq(uuid(6)), anyInt());
        for (UUID id : ids.subList(0, 5)) {
            verify(reconciler, times(1)).tryReconcilePartition(eq(id), anyInt());
        }
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

    @Test
    void aWorkerThatLostItsAttemptCannotFailPartitionsOwnedByTheNewAttempt() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectionReconciler reconciler = mock(ProjectionReconciler.class);
        stubQueue(jdbc, ProjectionWorker.MAX_ATTEMPTS - 1);
        when(jdbc.update(contains("SET state = 'APPLYING'"), any(UUID.class))).thenReturn(1);
        when(reconciler.partition(PARTITION))
                .thenThrow(
                        new ProjectionReconciler.ReconciliationException(
                                "KEYCLOAK_FAILURE", "Lỗi sau khi worker khác đã reclaim"));
        // Update ghi failure trả 0: attempt_count đã đổi trong DB, worker này hết quyền sở hữu.
        new ProjectionWorker(jdbc, reconciler, stubTransactions(), new AuditRecorder(jdbc)).poll();
        verify(jdbc, never())
                .update(contains("authz_partition SET state = 'FAILED'"), any(UUID.class));
        verify(jdbc, never())
                .update(contains("authz_projection SET state = 'FAILED'"), any(UUID.class));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }

    /** Một operation PENDING tới hạn trên {@link #PARTITION} tại revision 1, attempt 0. */
    private static void stubQueue(JdbcTemplate jdbc) throws Exception {
        stubQueue(jdbc, 0);
    }

    private static void stubQueue(JdbcTemplate jdbc, int attemptCount) throws Exception {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    Array partitions = mock(Array.class);
                    when(partitions.getArray()).thenReturn(new Object[] {PARTITION});
                    Array revisions = mock(Array.class);
                    when(revisions.getArray()).thenReturn(new Object[] {1L});
                    when(rs.getObject("id", UUID.class)).thenReturn(OPERATION);
                    when(rs.getArray("affected_partitions")).thenReturn(partitions);
                    when(rs.getArray("affected_revisions")).thenReturn(revisions);
                    when(rs.getInt("attempt_count")).thenReturn(attemptCount);
                    when(rs.getString("state")).thenReturn("PENDING");
                    when(rs.getBoolean("due")).thenReturn(true);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.queryForList(anyString(), eq(UUID.class), anyInt())).thenReturn(List.of());
        // Tập claimable khóa FOR UPDATE SKIP LOCKED chứa operation đang xếp hàng.
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(OPERATION));
        // Các test operation không dựng partition mồ côi nào: lượt quét orphan trả tập rỗng.
        // Stub cụ thể này phải đứng sau stub tổng quát ở trên để thắng khi khớp cả hai.
        when(jdbc.queryForList(contains("FROM authz_partition p"), eq(UUID.class)))
                .thenReturn(List.of());
    }

    private static List<Object> flattened(Invocation invocation) {
        List<Object> flat = new ArrayList<>();
        for (Object argument : invocation.getArguments()) {
            if (argument instanceof Object[] array) {
                flat.addAll(Arrays.asList(array));
            } else {
                flat.add(argument);
            }
        }
        return flat;
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
