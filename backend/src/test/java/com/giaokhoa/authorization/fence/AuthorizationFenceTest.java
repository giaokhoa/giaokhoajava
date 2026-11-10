package com.giaokhoa.authorization.fence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.fence.DependencyManifestResolver.Manifest;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AuthorizationFenceTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final UUID ACCOUNT = RandomIds.randomId();
    private static final UUID WORKSPACE_PARTITION = RandomIds.randomId();
    private static final UUID GRANT = RandomIds.randomId();
    private static final List<String> KEYS = List.of("workspace:" + WORKSPACE);

    @Test
    void acquireSnapshotsRevisionsWhenEveryPartitionIsReady() {
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), readyRows());
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        Manifest manifest = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION));
        when(resolver.resolve(ACTOR, WORKSPACE, KEYS)).thenReturn(manifest);

        var snapshot = new AuthorizationFence(jdbc, resolver, stubTransactions()).acquire(manifest);

        assertEquals(Map.of(ACCOUNT, 2L, WORKSPACE_PARTITION, 2L), snapshot.revisions());
    }

    @Test
    void aPendingPartitionFailsClosedWith503() {
        Map<UUID, Map<String, Object>> rows = readyRows();
        rows.put(WORKSPACE_PARTITION, row("PENDING", 2, 1));
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new AuthorizationFence(jdbc, resolver, stubTransactions())
                                        .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
    }

    @Test
    void missingRevisionFailsClosedWith503() {
        Map<UUID, Map<String, Object>> rows = readyRows();
        rows.put(ACCOUNT, Map.of("state", "READY", "desired_revision", 2L));
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new AuthorizationFence(jdbc, resolver, stubTransactions())
                                        .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
    }

    @Test
    void anUnappliedRevisionFailsClosedWith503() {
        Map<UUID, Map<String, Object>> rows = readyRows();
        rows.put(ACCOUNT, row("READY", 3, 2));
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);

        assertThrows(
                HttpProblemException.class,
                () ->
                        new AuthorizationFence(jdbc, resolver, stubTransactions())
                                .acquire(manifest(List.of(ACCOUNT, WORKSPACE_PARTITION))));
    }

    @Test
    void acquireReResolvesWhenTheClosureGrowsUnderTheLocks() {
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), readyRows());
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        Manifest small = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION));
        Manifest grown = manifest(List.of(ACCOUNT, WORKSPACE_PARTITION, GRANT));
        when(resolver.resolve(ACTOR, WORKSPACE, KEYS)).thenReturn(grown, grown);

        var snapshot = new AuthorizationFence(jdbc, resolver, stubTransactions()).acquire(small);

        assertEquals(grown.partitionIds(), snapshot.manifest().partitionIds());
        assertEquals(3, snapshot.revisions().size());
    }

    @Test
    void atSnapshotRejectsARevisionBumpedAfterAcquisition() {
        Map<UUID, Map<String, Object>> rows = readyRows();
        rows.put(WORKSPACE_PARTITION, row("READY", 3, 3));
        JdbcTemplate jdbc = stubLocks(mock(JdbcTemplate.class), rows);
        DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        var snapshot =
                new AuthorizationFence.Snapshot(
                        manifest(List.of(ACCOUNT, WORKSPACE_PARTITION)),
                        Map.of(ACCOUNT, 2L, WORKSPACE_PARTITION, 2L));

        assertThrows(
                AuthorizationFence.ClosureChangedException.class,
                () ->
                        new AuthorizationFence(jdbc, resolver, stubTransactions())
                                .atSnapshot(snapshot, () -> "must not run"));
    }

    private static Manifest manifest(List<UUID> partitions) {
        return new Manifest(ACTOR, WORKSPACE, KEYS, Map.of(), partitions);
    }

    private static Map<String, Object> row(String state, long desired, long applied) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("state", state);
        row.put("desired_revision", desired);
        row.put("applied_revision", applied);
        return row;
    }

    private static Map<UUID, Map<String, Object>> readyRows() {
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put(ACCOUNT, row("READY", 2, 2));
        rows.put(WORKSPACE_PARTITION, row("READY", 2, 2));
        rows.put(GRANT, row("READY", 2, 2));
        return rows;
    }

    private static JdbcTemplate stubLocks(JdbcTemplate jdbc, Map<UUID, Map<String, Object>> rows) {
        // Query khóa đã gộp theo lô: stub trả mọi dòng đã khai, production tự đối chiếu
        // từng partition của manifest theo id trong bộ nhớ.
        when(jdbc.query(
                        contains("FOR SHARE"),
                        ArgumentMatchers.<PreparedStatementSetter>any(),
                        ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    List<Map<String, Object>> locked = new ArrayList<>();
                    rows.forEach((id, row) -> {
                        Map<String, Object> copy = new LinkedHashMap<>(row);
                        copy.put("id", id);
                        locked.add(copy);
                    });
                    return locked;
                });
        return jdbc;
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
