package com.giaokhoa.authorization.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.DesiredProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.PartitionChange;
import com.giaokhoa.support.TestIds;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class AuthorizationOperationServiceTest {
    private static final UUID PARTITION = TestIds.randomId();
    private static final String TARGET = "workspace:" + TestIds.randomId();

    private final AuthorizationOperationService service =
            new AuthorizationOperationService(
                    mock(JdbcTemplate.class), mock(IdempotencyService.class));

    private void apply(List<PartitionChange> changes) {
        service.applyDesiredState(UUID.randomUUID(), null, "key-1", "a".repeat(64), changes);
    }

    @Test
    void emptyChangesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> apply(List.of()));
    }

    @Test
    void duplicatePartitionsAreRejected() {
        PartitionChange change = new PartitionChange(PARTITION, List.of());
        assertThrows(IllegalArgumentException.class, () -> apply(List.of(change, change)));
    }

    @Test
    void unknownTargetKeysAreRejected() {
        PartitionChange change =
                new PartitionChange(
                        PARTITION,
                        List.of(new DesiredProjection("nowhere:1", "enter", List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> apply(List.of(change)));
    }

    @Test
    void scopesOutsideTheFixedSetAreRejected() {
        PartitionChange change =
                new PartitionChange(
                        PARTITION,
                        List.of(new DesiredProjection(TARGET, "fly", List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> apply(List.of(change)));
    }

    @Test
    void primariesAndClosurePartitionsAreLockedInOneGlobalOrder() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AuthorizationOperationService lockingService =
                new AuthorizationOperationService(jdbc, idempotency);
        UUID closurePartition = TestIds.randomId();
        UUID primary = TestIds.randomId();
        // Thứ tự khóa toàn cục ở main là thứ tự chuỗi của UUID: xếp lại để partition của
        // closure đứng trước primary, đúng tiền đề test khẳng định.
        if (closurePartition.toString().compareTo(primary.toString()) > 0) {
            UUID swap = closurePartition;
            closurePartition = primary;
            primary = swap;
        }
        // Bản chốt sau khi xếp thứ tự: lambda stub ResultSet bên dưới chỉ bắt được biến
        // effectively final.
        UUID orderedPartition = closurePartition;
        List<UUID> lockOrder = new ArrayList<>();
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(Long.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    lockOrder.add(invocation.getArgument(2, UUID.class));
                    return List.of(0L);
                });
        AtomicInteger closureQueries = new AtomicInteger();
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<PreparedStatementSetter>any(),
                        ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    if (closureQueries.getAndIncrement() > 0) {
                        return List.of();
                    }
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getObject("partition_id", UUID.class)).thenReturn(orderedPartition);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.queryForList(startsWith("SELECT id, partition_id"), anyString()))
                .thenReturn(List.of());
        when(idempotency.executeOnce(any(AuthorizationOperation.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return invocation.getArgument(0);
                });

        lockingService.applyDesiredState(
                UUID.randomUUID(),
                null,
                "key-1",
                "a".repeat(64),
                List.of(
                        new PartitionChange(
                                primary,
                                List.of(
                                        new DesiredProjection(
                                                "workspace:" + primary,
                                                "enter",
                                                List.of("source"),
                                                List.of())))));

        // Partition của closure sắp xếp trước primary, nên phải khóa nó trước: khóa
        // theo từng nhóm (các primary, rồi closure) có thể deadlock hai mutation giao nhau.
        assertEquals(List.of(closurePartition, primary), lockOrder);
    }

    @Test
    void aProjectionCannotDependOnItsOwnPartition() {
        PartitionChange change =
                new PartitionChange(
                        PARTITION,
                        List.of(
                                new DesiredProjection(
                                        TARGET, "enter", List.of("source"), List.of(PARTITION))));
        assertThrows(IllegalArgumentException.class, () -> apply(List.of(change)));
    }
}
