package com.giaokhoa.authorization.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.DesiredProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.PartitionChange;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepositoryCustom.DependentProjection;
import com.giaokhoa.support.RandomIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuthorizationOperationServiceTest {
    private static final UUID PARTITION = RandomIds.randomId();
    private static final String TARGET = "workspace:" + RandomIds.randomId();

    private final AuthorizationOperationService service =
            new AuthorizationOperationService(
                    mock(AuthzPartitionRepository.class),
                    mock(AuthzProjectionRepository.class),
                    mock(IdempotencyService.class));

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
    void primariesAndClosurePartitionsAreLockedInOneGlobalOrder() {
        AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
        AuthzProjectionRepository projections = mock(AuthzProjectionRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AuthorizationOperationService lockingService =
                new AuthorizationOperationService(partitions, projections, idempotency);
        UUID closurePartition = RandomIds.randomId();
        UUID primary = RandomIds.randomId();
        // Thứ tự khóa toàn cục ở main là thứ tự chuỗi của UUID: xếp lại để partition của
        // closure đứng trước primary, đúng tiền đề test khẳng định.
        if (closurePartition.toString().compareTo(primary.toString()) > 0) {
            UUID swap = closurePartition;
            closurePartition = primary;
            primary = swap;
        }
        // Bản chốt sau khi xếp thứ tự: lambda stub bên dưới chỉ bắt được biến effectively
        // final.
        UUID orderedPartition = closurePartition;
        List<UUID> lockOrder = new ArrayList<>();
        when(partitions.lockById(any()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0, UUID.class);
                    lockOrder.add(id);
                    return Optional.of(
                            new AuthorizationPartition(
                                    id,
                                    AuthorizationPartition.Kind.WORKSPACE,
                                    "owner:" + id,
                                    0,
                                    0,
                                    AuthorizationPartition.State.PENDING));
                });
        AtomicInteger closureQueries = new AtomicInteger();
        UUID dependentProjection = UUID.randomUUID();
        when(projections.findReadyDependentsOfAny(any()))
                .thenAnswer(invocation -> {
                    if (closureQueries.getAndIncrement() > 0) {
                        return List.of();
                    }
                    return List.of(
                            new DependentProjection(
                                    dependentProjection, orderedPartition, List.of()));
                });
        when(projections.findAllById(any()))
                .thenReturn(
                        List.of(
                                new AuthorizationProjection(
                                        dependentProjection,
                                        orderedPartition,
                                        "workspace:" + orderedPartition,
                                        null,
                                        List.of(),
                                        List.of(),
                                        "a".repeat(64),
                                        "a".repeat(64),
                                        AuthorizationProjection.State.READY)));
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
