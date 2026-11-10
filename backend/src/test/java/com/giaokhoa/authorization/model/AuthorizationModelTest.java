package com.giaokhoa.authorization.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Entity JPA dùng @NullUnmarked; vẫn kiểm tra hợp đồng null của test bằng @NullMarked.
@NullMarked
class AuthorizationModelTest {
    @DisplayName(
            "Các danh sách cột mảng của operation không thể bị thay đổi qua tham số constructor"
                    + " hay getter")
    @Test
    void operationListsAreDefensiveCopies() {
        UUID partition = UUID.randomUUID();
        List<UUID> partitions = new ArrayList<>(List.of(partition));
        List<Long> revisions = new ArrayList<>(List.of(1L));
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "request",
                        "hash",
                        partitions,
                        revisions,
                        1L);
        partitions.set(0, UUID.randomUUID());
        revisions.set(0, 2L);
        assertEquals(List.of(partition), operation.getAffectedPartitions());
        assertEquals(List.of(1L), operation.getAffectedRevisions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> operation.getAffectedPartitions().set(0, UUID.randomUUID()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> operation.getAffectedRevisions().set(0, 3L));
        assertEquals(List.of(partition), operation.getAffectedPartitions());
        assertEquals(List.of(1L), operation.getAffectedRevisions());
    }

    @DisplayName(
            "Các danh sách cột mảng của projection không thể bị thay đổi qua tham số constructor"
                    + " hay getter")
    @Test
    void projectionListsAreDefensiveCopies() {
        UUID dependency = UUID.randomUUID();
        List<String> policies = new ArrayList<>(List.of("scope.permission"));
        List<UUID> dependencies = new ArrayList<>(List.of(dependency));
        var projection =
                new AuthorizationProjection(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "target",
                        "resource",
                        policies,
                        dependencies,
                        "hash",
                        null,
                        AuthorizationProjection.State.PENDING);
        policies.set(0, "changed");
        dependencies.set(0, UUID.randomUUID());
        assertEquals(List.of("scope.permission"), projection.getPolicyNames());
        assertEquals(List.of(dependency), projection.getDependencyPartitionIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> projection.getPolicyNames().set(0, "changed-again"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> projection.getDependencyPartitionIds().set(0, UUID.randomUUID()));
        assertEquals(List.of("scope.permission"), projection.getPolicyNames());
        assertEquals(List.of(dependency), projection.getDependencyPartitionIds());
    }

    @DisplayName("Danh sách cột mảng bắt buộc null bị từ chối trước khi persist")
    @Test
    void requiredListsRejectNullBeforePersisting() {
        UUID id = UUID.randomUUID();
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationOperation(
                                id, id, null, "request", "hash", null, List.of(), 0L));
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationOperation(
                                id, id, null, "request", "hash", List.of(), null, 0L));
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationProjection(
                                id,
                                id,
                                "target",
                                null,
                                null,
                                List.of(),
                                "hash",
                                null,
                                AuthorizationProjection.State.PENDING));
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationProjection(
                                id,
                                id,
                                "target",
                                null,
                                List.of(),
                                null,
                                "hash",
                                null,
                                AuthorizationProjection.State.PENDING));
    }

    @DisplayName("Sao chép giữ nguyên danh sách rỗng phân biệt với null")
    @Test
    void copyingPreservesEmptyLists() {
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "request",
                        "hash",
                        List.of(),
                        List.of(),
                        0L);
        var projection =
                new AuthorizationProjection(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "target",
                        null,
                        List.of(),
                        List.of(),
                        "hash",
                        null,
                        AuthorizationProjection.State.PENDING);
        assertEquals(List.of(), operation.getAffectedPartitions());
        assertEquals(List.of(), operation.getAffectedRevisions());
        assertEquals(List.of(), projection.getPolicyNames());
        assertEquals(List.of(), projection.getDependencyPartitionIds());
    }
}
