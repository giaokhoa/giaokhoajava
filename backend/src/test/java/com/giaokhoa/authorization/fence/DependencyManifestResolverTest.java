package com.giaokhoa.authorization.fence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class DependencyManifestResolverTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID WORKSPACE_PARTITION =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID GRANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID SOURCE = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID INDEPENDENT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String VERSION_KEY =
            "workspace:"
                    + WORKSPACE
                    + ":resource:"
                    + UUID.randomUUID()
                    + ":version:"
                    + UUID.randomUUID();

    @Test
    void actorOnlyActionIncludesAccountAndTargetClosureWithoutAnUnrelatedWorkspace() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        when(jdbc.queryForList(contains("WHERE target_key"), anyString()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "partition_id",
                                        GRANT,
                                        "keycloak_resource_id",
                                        "platform-resource",
                                        "dependency_partition_ids",
                                        new UUID[] {SOURCE})));
        when(jdbc.queryForList(contains("WHERE partition_id"), any(UUID.class)))
                .thenReturn(List.of());
        var manifest =
                new DependencyManifestResolver(jdbc).resolve(ACTOR, null, List.of("platform"));
        assertEquals(sorted(List.of(ACCOUNT, GRANT, SOURCE)), manifest.partitionIds());
        assertEquals("platform-resource", manifest.recordedResourceIds().get("platform"));
    }

    @Test
    void manifestAlwaysContainsActorAccountAndTargetWorkspace() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        when(jdbc.queryForList(contains("WHERE target_key"), anyString())).thenReturn(List.of());
        when(jdbc.queryForList(contains("WHERE partition_id"), any(UUID.class)))
                .thenReturn(List.of());

        var manifest =
                new DependencyManifestResolver(jdbc)
                        .resolve(ACTOR, WORKSPACE, List.of("workspace:" + WORKSPACE));

        assertEquals(List.of(ACCOUNT, WORKSPACE_PARTITION), sorted(manifest.partitionIds()));
        assertTrue(manifest.recordedResourceIds().isEmpty());
    }

    @Test
    void resultTargetManifestIsFencedByOwningWorkspacePartitionWithNoUpstreamDependencies() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        String resultKey = "workspace:" + WORKSPACE + ":result:" + UUID.randomUUID();
        Map<String, Object> resultProjection =
                Map.of(
                        "partition_id",
                        WORKSPACE_PARTITION,
                        "keycloak_resource_id",
                        "result-resource",
                        "dependency_partition_ids",
                        new UUID[] {});
        when(jdbc.queryForList(contains("WHERE target_key"), anyString()))
                .thenReturn(List.of(resultProjection));
        when(jdbc.queryForList(contains("WHERE partition_id"), any(UUID.class)))
                .thenReturn(List.of());

        var manifest =
                new DependencyManifestResolver(jdbc).resolve(ACTOR, WORKSPACE, List.of(resultKey));

        assertEquals(List.of(ACCOUNT, WORKSPACE_PARTITION), sorted(manifest.partitionIds()));
        assertEquals("result-resource", manifest.recordedResourceIds().get(resultKey));
    }

    @Test
    void manifestClosesUpstreamThroughOwnersButNeverSidewaysToIndependentDependents() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        Map<String, Object> grantProjection =
                Map.of(
                        "partition_id",
                        GRANT,
                        "keycloak_resource_id",
                        "native-resource",
                        "dependency_partition_ids",
                        new UUID[] {WORKSPACE_PARTITION});
        Map<String, Object> workspaceProjection =
                Map.of(
                        "partition_id",
                        WORKSPACE_PARTITION,
                        "keycloak_resource_id",
                        "workspace-resource",
                        "dependency_partition_ids",
                        new UUID[] {SOURCE});
        when(jdbc.queryForList(contains("WHERE target_key"), anyString()))
                .thenReturn(List.of(grantProjection));
        when(jdbc.queryForList(contains("WHERE partition_id"), any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID owner = invocation.getArgument(1, UUID.class);
                    if (WORKSPACE_PARTITION.equals(owner)) {
                        return List.of(workspaceProjection);
                    }
                    return List.of();
                });

        var manifest =
                new DependencyManifestResolver(jdbc)
                        .resolve(ACTOR, WORKSPACE, List.of(VERSION_KEY));

        assertEquals(
                sorted(List.of(ACCOUNT, WORKSPACE_PARTITION, GRANT, SOURCE)),
                sorted(manifest.partitionIds()));
        assertFalse(
                manifest.partitionIds().contains(INDEPENDENT),
                "A dependent of the same workspace is not an upstream source of this target");
        assertEquals("native-resource", manifest.recordedResourceIds().get(VERSION_KEY));
    }

    @Test
    void aTargetProjectionWithoutDependencyArrayFailsClosedWith503() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        Map<String, Object> projection =
                Map.of("partition_id", GRANT, "keycloak_resource_id", "native-resource");
        when(jdbc.queryForList(contains("WHERE target_key"), anyString()))
                .thenReturn(List.of(projection));

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new DependencyManifestResolver(jdbc)
                                        .resolve(ACTOR, WORKSPACE, List.of(VERSION_KEY)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals("AUTHORIZATION_NOT_READY", problem.getCode());
    }

    @Test
    void anOwnedProjectionWithoutDependencyArrayFailsClosedWith503() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        when(jdbc.queryForList(contains("WHERE target_key"), anyString())).thenReturn(List.of());
        when(jdbc.queryForList(contains("WHERE partition_id"), any(UUID.class)))
                .thenReturn(List.of(Map.of("partition_id", ACCOUNT)));

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new DependencyManifestResolver(jdbc)
                                        .resolve(ACTOR, WORKSPACE, List.of("platform")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals("AUTHORIZATION_NOT_READY", problem.getCode());
    }

    @Test
    void aMissingPartitionFailsClosedWith503() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(
                        contains("authz_partition"), eq(UUID.class), anyString(), anyString()))
                .thenReturn(List.of());

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                new DependencyManifestResolver(jdbc)
                                        .resolve(ACTOR, WORKSPACE, List.of("platform")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals("AUTHORIZATION_NOT_READY", problem.getCode());
    }

    private static JdbcTemplate stubPartitions(JdbcTemplate jdbc) {
        when(jdbc.queryForList(
                        contains("authz_partition"), eq(UUID.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String ownerKey = invocation.getArgument(3, String.class);
                    if (ownerKey.equals("account:" + ACTOR)) {
                        return List.of(ACCOUNT);
                    }
                    if (ownerKey.equals("workspace:" + WORKSPACE)) {
                        return List.of(WORKSPACE_PARTITION);
                    }
                    return List.of();
                });
        return jdbc;
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted(java.util.Comparator.comparing(id -> id.toString())).toList();
    }
}
