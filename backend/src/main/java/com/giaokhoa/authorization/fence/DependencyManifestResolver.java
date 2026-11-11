package com.giaokhoa.authorization.fence;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
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
    static final String NOT_READY_DETAIL = "Quyền đang được đồng bộ, thử lại sau.";

    private final AuthzPartitionRepository partitions;
    private final AuthzProjectionRepository projections;

    public DependencyManifestResolver(
            AuthzPartitionRepository partitions, AuthzProjectionRepository projections) {
        this.partitions = partitions;
        this.projections = projections;
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
        return partitions
                .findByKindAndOwnerKey(kind, ownerKey)
                .map(AuthorizationPartition::getId)
                .orElseThrow(() ->
                        new HttpProblemException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                ErrorCodes.AUTHORIZATION_NOT_READY,
                                NOT_READY_DETAIL));
    }

    private @Nullable ProjectionRow projectionByTarget(String targetKey) {
        return projections
                .findByTargetKey(targetKey)
                .map(projection ->
                        new ProjectionRow(
                                projection.getPartitionId(),
                                projection.getKeycloakResourceId(),
                                projection.getDependencyPartitionIds()))
                .orElse(null);
    }

    /** Chỉ cột dependency của mọi projection thuộc cả một tầng partition, trong một query. */
    private List<List<UUID>> dependencyIdsOf(List<UUID> partitionIds) {
        return projections.findByPartitionIdIn(partitionIds).stream()
                .map(AuthorizationProjection::getDependencyPartitionIds)
                .toList();
    }
}
