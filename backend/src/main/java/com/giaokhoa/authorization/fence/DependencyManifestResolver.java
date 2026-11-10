package com.giaokhoa.authorization.fence;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.shared.http.HttpProblemException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolve dependency manifest của một protected request: ACCOUNT partition của actor, WORKSPACE
 * partition đích (nếu action có workspace) và closure nguồn upstream của các target được yêu cầu —
 * các partition sở hữu một target projection cộng, theo quan hệ truyền dẫn, mọi partition mà các
 * projection đó phụ thuộc. Partition chỉ phụ thuộc vào cùng các nguồn (một grant độc lập dùng chung
 * workspace) không thuộc manifest, nên fence một nguồn không bao giờ fence các nguồn còn lại. Thiếu
 * một partition nghĩa là projection chưa từng được ghi: fail closed với 503 thay vì quyết định trên
 * một tập thiếu.
 */
@Component
public class DependencyManifestResolver {
    static final String NOT_READY_CODE = "AUTHORIZATION_NOT_READY";
    static final String NOT_READY_DETAIL = "Quyền đang được đồng bộ, thử lại sau.";

    private final JdbcTemplate jdbc;

    public DependencyManifestResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một manifest đã resolve; ID các partition theo thứ tự khóa ổn định mà fence sử dụng. */
    public record Manifest(
            UUID actorUserId,
            @Nullable UUID workspaceId,
            List<String> targetKeys,
            Map<String, String> recordedResourceIds,
            List<UUID> partitionIds) {
        public Manifest {
            targetKeys = List.copyOf(targetKeys);
            recordedResourceIds =
                    Map.copyOf(Objects.requireNonNull(recordedResourceIds, "recordedResourceIds"));
            partitionIds = List.copyOf(partitionIds);
        }
    }

    private record ProjectionRow(
            UUID partitionId,
            @Nullable String keycloakResourceId,
            List<UUID> dependencyPartitionIds) {}

    public Manifest resolve(
            UUID actorUserId, @Nullable UUID workspaceId, Collection<String> targetKeys) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        if (targetKeys == null || targetKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one target key is required");
        }
        Set<UUID> partitions = new TreeSet<>(Comparator.comparing(id -> id.toString()));
        List<UUID> frontier = new ArrayList<>();
        include(
                partitions,
                frontier,
                partitionId(AuthorizationPartition.Kind.ACCOUNT, "account:" + actorUserId));
        if (workspaceId != null) {
            include(
                    partitions,
                    frontier,
                    partitionId(AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId));
        }

        Map<String, String> resourceIds = new LinkedHashMap<>();
        for (String targetKey : new LinkedHashSet<>(targetKeys)) {
            ProjectionRow target = projectionByTarget(targetKey);
            if (target == null) {
                continue;
            }
            if (target.keycloakResourceId() != null) {
                resourceIds.put(targetKey, target.keycloakResourceId());
            }
            include(partitions, frontier, target.partitionId());
            for (UUID dependency : target.dependencyPartitionIds()) {
                include(partitions, frontier, dependency);
            }
        }
        // Mở rộng closure theo từng tầng, một query cho cả tầng theo mẫu
        // AuthorizationOperationService.dependentClosure: mỗi partition chỉ mở rộng một
        // lần, nên một lần resolve tốn số query theo số tầng thay vì theo số partition.
        Set<UUID> expanded = new HashSet<>();
        while (!frontier.isEmpty()) {
            List<UUID> level = new ArrayList<>();
            for (UUID owner : frontier) {
                if (expanded.add(owner)) {
                    level.add(owner);
                }
            }
            frontier.clear();
            if (level.isEmpty()) {
                break;
            }
            for (List<UUID> dependencies : dependencyIdsOf(level)) {
                for (UUID dependency : dependencies) {
                    include(partitions, frontier, dependency);
                }
            }
        }
        return new Manifest(
                actorUserId,
                workspaceId,
                List.copyOf(targetKeys),
                resourceIds,
                List.copyOf(partitions));
    }

    private static void include(Set<UUID> partitions, List<UUID> frontier, UUID partitionId) {
        if (partitions.add(partitionId)) {
            frontier.add(partitionId);
        }
    }

    private UUID partitionId(AuthorizationPartition.Kind kind, String ownerKey) {
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = ? AND owner_key = ?",
                        UUID.class,
                        kind.name(),
                        ownerKey);
        if (found.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE, NOT_READY_CODE, NOT_READY_DETAIL);
        }
        return found.getFirst();
    }

    private @Nullable ProjectionRow projectionByTarget(String targetKey) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT partition_id, keycloak_resource_id, dependency_partition_ids"
                                + " FROM authz_projection WHERE target_key = ?",
                        targetKey);
        return rows.isEmpty() ? null : toRow(rows.getFirst());
    }

    /** Chỉ cột dependency của mọi projection thuộc cả một tầng partition, trong một query. */
    private List<List<UUID>> dependencyIdsOf(List<UUID> partitionIds) {
        return jdbc.query(
                "SELECT dependency_partition_ids FROM authz_projection"
                        + " WHERE partition_id = ANY(?)",
                ps ->
                        ps.setArray(
                                1,
                                ps.getConnection()
                                        .createArrayOf(
                                                "uuid", partitionIds.toArray(new UUID[0]))),
                (rs, _) -> uuids(rs.getArray("dependency_partition_ids")));
    }

    private static ProjectionRow toRow(Map<String, Object> row) {
        return new ProjectionRow(
                (UUID) Objects.requireNonNull(row.get("partition_id"), "partition_id"),
                (String) row.get("keycloak_resource_id"),
                uuids(row.get("dependency_partition_ids")));
    }

    private static List<UUID> uuids(@Nullable Object value) {
        // Schema yêu cầu dependency_partition_ids NOT NULL; dữ liệu thiếu không được
        // biến thành closure rỗng vì sẽ bỏ sót nguồn cần fence.
        if (value == null) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE, NOT_READY_CODE, NOT_READY_DETAIL);
        }
        Object[] raw;
        if (value instanceof Array array) {
            try {
                raw = (Object[]) array.getArray();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot read partition references", ex);
            }
        } else if (value instanceof Object[] objects) {
            raw = objects;
        } else {
            throw new IllegalStateException(
                    "Unexpected partition reference value: " + value.getClass());
        }
        return java.util.Arrays.stream(raw)
                .map(item ->
                        item instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(item)))
                .toList();
    }
}
