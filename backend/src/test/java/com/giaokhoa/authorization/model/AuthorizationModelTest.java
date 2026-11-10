package com.giaokhoa.authorization.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Entity JPA dùng @NullUnmarked; vẫn kiểm tra hợp đồng null của test bằng @NullMarked.
@NullMarked
class AuthorizationModelTest {
    @DisplayName("Các mảng của operation không thể bị thay đổi qua tham số constructor hay getter")
    @Test
    void operationArraysAreDefensiveCopies() {
        UUID partition = UUID.randomUUID();
        UUID[] partitions = {partition};
        Long[] revisions = {1L};
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
        partitions[0] = UUID.randomUUID();
        revisions[0] = 2L;
        assertArrayEquals(new UUID[] {partition}, operation.getAffectedPartitions());
        assertArrayEquals(new Long[] {1L}, operation.getAffectedRevisions());
        operation.getAffectedPartitions()[0] = UUID.randomUUID();
        operation.getAffectedRevisions()[0] = 3L;
        assertArrayEquals(new UUID[] {partition}, operation.getAffectedPartitions());
        assertArrayEquals(new Long[] {1L}, operation.getAffectedRevisions());
    }

    @DisplayName("Các mảng của projection không thể bị thay đổi qua tham số constructor hay getter")
    @Test
    void projectionArraysAreDefensiveCopies() {
        UUID dependency = UUID.randomUUID();
        String[] policies = {"scope.permission"};
        UUID[] dependencies = {dependency};
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
        policies[0] = "changed";
        dependencies[0] = UUID.randomUUID();
        assertArrayEquals(new String[] {"scope.permission"}, projection.getPolicyNames());
        assertArrayEquals(new UUID[] {dependency}, projection.getDependencyPartitionIds());
        projection.getPolicyNames()[0] = "changed-again";
        projection.getDependencyPartitionIds()[0] = UUID.randomUUID();
        assertArrayEquals(new String[] {"scope.permission"}, projection.getPolicyNames());
        assertArrayEquals(new UUID[] {dependency}, projection.getDependencyPartitionIds());
    }

    @Test
    void requiredArraysRejectNullBeforePersisting() {
        UUID id = UUID.randomUUID();
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationOperation(
                                id, id, null, "request", "hash", null, new Long[0], 0L));
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationOperation(
                                id, id, null, "request", "hash", new UUID[0], null, 0L));
        assertThrows(
                NullPointerException.class,
                () ->
                        new AuthorizationProjection(
                                id,
                                id,
                                "target",
                                null,
                                null,
                                new UUID[0],
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
                                new String[0],
                                null,
                                "hash",
                                null,
                                AuthorizationProjection.State.PENDING));
    }

    @Test
    void copyingPreservesEmptyArrays() {
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "request",
                        "hash",
                        new UUID[0],
                        new Long[0],
                        0L);
        var projection =
                new AuthorizationProjection(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "target",
                        null,
                        new String[0],
                        new UUID[0],
                        "hash",
                        null,
                        AuthorizationProjection.State.PENDING);
        assertArrayEquals(new UUID[0], operation.getAffectedPartitions());
        assertArrayEquals(new Long[0], operation.getAffectedRevisions());
        assertArrayEquals(new String[0], projection.getPolicyNames());
        assertArrayEquals(new UUID[0], projection.getDependencyPartitionIds());
    }
}
