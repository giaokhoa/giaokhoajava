package com.giaokhoa.authorization.fence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.fence.DependencyManifestResolver.Manifest;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AuthorizationFenceTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final UUID ACCOUNT = RandomIds.randomId();
    private static final UUID WORKSPACE_PARTITION = RandomIds.randomId();
    private static final UUID GRANT = RandomIds.randomId();
    private static final List<String> KEYS = List.of("workspace:" + WORKSPACE);

    @Test
    void acquireSnapshotsRevisionsWhenEveryPartitionIsReady() {
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), readyRows());
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        Manifest manifest = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION));
        when(resolver.resolve(ACTOR, WORKSPACE, KEYS)).thenReturn(manifest);

        var snapshot =
                new AuthorizationFence(partitions, resolver, stubTransactions()).acquire(manifest);

        assertEquals(Map.of(ACCOUNT, 2L, WORKSPACE_PARTITION, 2L), snapshot.revisions());
    }

    @Test
    void aPendingPartitionFailsClosedWith503() {
        Map<UUID, Row> rows = readyRows();
        rows.put(WORKSPACE_PARTITION, new Row("PENDING", 2, 1));
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new AuthorizationFence(partitions, resolver, stubTransactions())
                                        .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
    }

    @DisplayName("Partition của manifest không có trong kết quả khóa thì fence fail closed 503")
    @Test
    void aPartitionMissingFromTheLockResultFailsClosedWith503() {
        Map<UUID, Row> rows = readyRows();
        rows.remove(ACCOUNT);
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new AuthorizationFence(partitions, resolver, stubTransactions())
                                        .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
    }

    @Test
    void anUnappliedRevisionFailsClosedWith503() {
        Map<UUID, Row> rows = readyRows();
        rows.put(ACCOUNT, new Row("READY", 3, 2));
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        assertThrows(
                HttpProblemException.class,
                () ->
                        new AuthorizationFence(partitions, resolver, stubTransactions())
                                .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));
    }

    @Test
    void acquireReResolvesWhenTheClosureGrowsUnderTheLocks() {
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), readyRows());
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        Manifest small = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION));
        Manifest grown = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION, GRANT));
        when(resolver.resolve(ACTOR, WORKSPACE, KEYS)).thenReturn(grown, grown);

        var snapshot =
                new AuthorizationFence(partitions, resolver, stubTransactions()).acquire(small);

        assertEquals(grown.partitionIds(), snapshot.manifest().partitionIds());
        assertEquals(3, snapshot.revisions().size());
    }

    @Test
    void atSnapshotRejectsARevisionBumpedAfterAcquisition() {
        Map<UUID, Row> rows = readyRows();
        rows.put(WORKSPACE_PARTITION, new Row("READY", 3, 3));
        var partitions = stubLocks(mock(AuthzPartitionRepository.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        var snapshot =
                new AuthorizationFence.Snapshot(
                        manifest(List.of(ACCOUNT, WORKSPACE_PARTITION)),
                        Map.of(ACCOUNT, 2L, WORKSPACE_PARTITION, 2L));

        assertThrows(
                AuthorizationFence.ClosureChangedException.class,
                () ->
                        new AuthorizationFence(partitions, resolver, stubTransactions())
                                .atSnapshot(snapshot, () -> "must not run"));
    }

    private static Manifest manifest(List<UUID> partitions) {
        return new Manifest(ACTOR, WORKSPACE, KEYS, Map.of(), partitions);
    }

    private record Row(String state, long desired, long applied) {}

    private static Map<UUID, Row> readyRows() {
        Map<UUID, Row> rows = new LinkedHashMap<>();
        rows.put(ACCOUNT, new Row("READY", 2, 2));
        rows.put(WORKSPACE_PARTITION, new Row("READY", 2, 2));
        rows.put(GRANT, new Row("READY", 2, 2));
        return rows;
    }

    private static AuthzPartitionRepository stubLocks(
            AuthzPartitionRepository partitions, Map<UUID, Row> rows) {
        // Query khóa đã gộp theo lô: stub trả mọi partition đã khai, production tự đối
        // chiếu từng partition của manifest theo id trong bộ nhớ.
        when(partitions.lockAllShared(any()))
                .thenAnswer(invocation -> {
                    List<AuthorizationPartition> locked = new ArrayList<>();
                    rows.forEach((id, row) ->
                            locked.add(
                                    new AuthorizationPartition(
                                            id,
                                            AuthorizationPartition.Kind.WORKSPACE,
                                            "owner:" + id,
                                            row.desired(),
                                            row.applied(),
                                            AuthorizationPartition.State.valueOf(row.state()))));
                    return locked;
                });
        return partitions;
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
