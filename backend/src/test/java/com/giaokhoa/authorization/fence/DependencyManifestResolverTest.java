package com.giaokhoa.authorization.fence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

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
        stubExpansion(jdbc, List.of());
        var manifest =
                new DependencyManifestResolver(jdbc).resolve(ACTOR, null, List.of("platform"));
        assertEquals(sorted(List.of(ACCOUNT, GRANT, SOURCE)), manifest.partitionIds());
        assertEquals("platform-resource", manifest.recordedResourceIds().get("platform"));
    }

    @Test
    void manifestAlwaysContainsActorAccountAndTargetWorkspace() {
        JdbcTemplate jdbc = stubPartitions(mock(JdbcTemplate.class));
        when(jdbc.queryForList(contains("WHERE target_key"), anyString())).thenReturn(List.of());
        stubExpansion(jdbc, List.of());

        var manifest =
                new DependencyManifestResolver(jdbc)
                        .resolve(ACTOR, WORKSPACE, List.of("workspace:" + WORKSPACE));

        assertEquals(sorted(List.of(ACCOUNT, WORKSPACE_PARTITION)), manifest.partitionIds());
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
        stubExpansion(jdbc, List.of());

        var manifest =
                new DependencyManifestResolver(jdbc).resolve(ACTOR, WORKSPACE, List.of(resultKey));

        assertEquals(sorted(List.of(ACCOUNT, WORKSPACE_PARTITION)), manifest.partitionIds());
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
        when(jdbc.queryForList(contains("WHERE target_key"), anyString()))
                .thenReturn(List.of(grantProjection));
        // Tầng mở rộng chứa cả partition của workspace: dependency của projection nó sở
        // hữu (SOURCE) phải vào closure, còn partition độc lập cùng phụ thuộc workspace
        // thì không.
        stubExpansion(jdbc, List.of(List.of(SOURCE)));

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
        when(jdbc.query(
                        contains("partition_id = ANY"),
                        ArgumentMatchers.<PreparedStatementSetter>any(),
                        ArgumentMatchers.<RowMapper<List<UUID>>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<List<UUID>> mapper = invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getArray("dependency_partition_ids")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });

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

    /** Stub query mở rộng closure theo tầng: trả cùng một tầng dependency cho mọi lần gọi. */
    private static void stubExpansion(JdbcTemplate jdbc, List<List<UUID>> dependencies) {
        when(jdbc.query(
                        contains("partition_id = ANY"),
                        ArgumentMatchers.<PreparedStatementSetter>any(),
                        ArgumentMatchers.<RowMapper<List<UUID>>>any()))
                .thenReturn(dependencies);
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
