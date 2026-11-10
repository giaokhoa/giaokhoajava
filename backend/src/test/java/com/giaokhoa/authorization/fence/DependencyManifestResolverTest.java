package com.giaokhoa.authorization.fence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DependencyManifestResolverTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final UUID ACCOUNT = RandomIds.randomId();
    private static final UUID WORKSPACE_PARTITION = RandomIds.randomId();
    private static final UUID GRANT = RandomIds.randomId();
    private static final UUID SOURCE = RandomIds.randomId();
    private static final UUID INDEPENDENT = RandomIds.randomId();
    private static final String VERSION_KEY =
            "workspace:"
                    + WORKSPACE
                    + ":resource:"
                    + UUID.randomUUID()
                    + ":version:"
                    + UUID.randomUUID();

    @Test
    void actorOnlyActionIncludesAccountAndTargetClosureWithoutAnUnrelatedWorkspace() {
        var partitions = stubPartitions(mock(AuthzPartitionRepository.class));
        var projections = mock(AuthzProjectionRepository.class);
        when(projections.findByTargetKey(anyString()))
                .thenReturn(Optional.of(projection(GRANT, "platform-resource", List.of(SOURCE))));
        stubExpansion(projections, List.of());
        var manifest =
                new DependencyManifestResolver(partitions, projections)
                        .resolve(ACTOR, null, List.of("platform"));
        assertEquals(sorted(List.of(ACCOUNT, GRANT, SOURCE)), manifest.partitionIds());
        assertEquals("platform-resource", manifest.recordedResourceIds().get("platform"));
    }

    @Test
    void manifestAlwaysContainsActorAccountAndTargetWorkspace() {
        var partitions = stubPartitions(mock(AuthzPartitionRepository.class));
        var projections = mock(AuthzProjectionRepository.class);
        when(projections.findByTargetKey(anyString())).thenReturn(Optional.empty());
        stubExpansion(projections, List.of());

        var manifest =
                new DependencyManifestResolver(partitions, projections)
                        .resolve(ACTOR, WORKSPACE, List.of("workspace:" + WORKSPACE));

        assertEquals(sorted(List.of(ACCOUNT, WORKSPACE_PARTITION)), manifest.partitionIds());
        assertTrue(manifest.recordedResourceIds().isEmpty());
    }

    @Test
    void resultTargetManifestIsFencedByOwningWorkspacePartitionWithNoUpstreamDependencies() {
        var partitions = stubPartitions(mock(AuthzPartitionRepository.class));
        var projections = mock(AuthzProjectionRepository.class);
        String resultKey = "workspace:" + WORKSPACE + ":result:" + UUID.randomUUID();
        when(projections.findByTargetKey(anyString()))
                .thenReturn(
                        Optional.of(projection(WORKSPACE_PARTITION, "result-resource", List.of())));
        stubExpansion(projections, List.of());

        var manifest =
                new DependencyManifestResolver(partitions, projections)
                        .resolve(ACTOR, WORKSPACE, List.of(resultKey));

        assertEquals(sorted(List.of(ACCOUNT, WORKSPACE_PARTITION)), manifest.partitionIds());
        assertEquals("result-resource", manifest.recordedResourceIds().get(resultKey));
    }

    @Test
    void manifestClosesUpstreamThroughOwnersButNeverSidewaysToIndependentDependents() {
        var partitions = stubPartitions(mock(AuthzPartitionRepository.class));
        var projections = mock(AuthzProjectionRepository.class);
        when(projections.findByTargetKey(anyString()))
                .thenReturn(
                        Optional.of(
                                projection(
                                        GRANT, "native-resource", List.of(WORKSPACE_PARTITION))));
        // Tầng mở rộng chứa cả partition của workspace: dependency của projection nó sở
        // hữu (SOURCE) phải vào closure, còn partition độc lập cùng phụ thuộc workspace
        // thì không.
        stubExpansion(projections, List.of(List.of(SOURCE)));

        var manifest =
                new DependencyManifestResolver(partitions, projections)
                        .resolve(ACTOR, WORKSPACE, List.of(VERSION_KEY));

        assertEquals(
                sorted(List.of(ACCOUNT, WORKSPACE_PARTITION, GRANT, SOURCE)),
                sorted(manifest.partitionIds()));
        assertFalse(
                manifest.partitionIds().contains(INDEPENDENT),
                "A dependent of the same workspace is not an upstream source of this target");
        assertEquals("native-resource", manifest.recordedResourceIds().get(VERSION_KEY));
    }

    @DisplayName("Thiếu partition của actor hoặc workspace thì resolve fail closed với 503")
    @Test
    void aMissingPartitionFailsClosedWith503() {
        var partitions = mock(AuthzPartitionRepository.class);
        var projections = mock(AuthzProjectionRepository.class);
        when(partitions.findByKindAndOwnerKey(any(), anyString())).thenReturn(Optional.empty());

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new DependencyManifestResolver(partitions, projections)
                                        .resolve(ACTOR, WORKSPACE, List.of("platform")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals("AUTHORIZATION_NOT_READY", problem.getCode());
    }

    /** Stub query mở rộng closure theo tầng: trả cùng một tầng dependency cho mọi lần gọi. */
    private static void stubExpansion(
            AuthzProjectionRepository projections, List<List<UUID>> dependencies) {
        when(projections.findByPartitionIdIn(any()))
                .thenReturn(
                        dependencies.stream()
                                .map(deps -> projection(UUID.randomUUID(), null, deps))
                                .toList());
    }

    private static AuthzPartitionRepository stubPartitions(AuthzPartitionRepository partitions) {
        when(partitions.findByKindAndOwnerKey(any(), anyString()))
                .thenAnswer(invocation -> {
                    String ownerKey = invocation.getArgument(1, String.class);
                    if (ownerKey.equals("account:" + ACTOR)) {
                        return Optional.of(partition(ACCOUNT));
                    }
                    if (ownerKey.equals("workspace:" + WORKSPACE)) {
                        return Optional.of(partition(WORKSPACE_PARTITION));
                    }
                    return Optional.empty();
                });
        return partitions;
    }

    private static AuthorizationPartition partition(UUID id) {
        return new AuthorizationPartition(
                id,
                AuthorizationPartition.Kind.WORKSPACE,
                "owner:" + id,
                0,
                0,
                AuthorizationPartition.State.READY);
    }

    private static AuthorizationProjection projection(
            UUID partitionId, @Nullable String keycloakResourceId, List<UUID> dependencies) {
        return new AuthorizationProjection(
                UUID.randomUUID(),
                partitionId,
                "target",
                keycloakResourceId,
                List.of(),
                dependencies,
                "hash",
                null,
                AuthorizationProjection.State.PENDING);
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted(java.util.Comparator.comparing(id -> id.toString())).toList();
    }
}
