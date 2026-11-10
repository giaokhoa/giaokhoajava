package com.giaokhoa.authorization.operation;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepositoryCustom.DependentProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi một mutation authorization theo cách bền vững: trong một transaction của app-db, nó bump
 * desired revision của mọi partition thay đổi, fence các partition đó cùng projection của chúng,
 * fence mọi READY projection phụ thuộc vào chúng (dependency closure), lưu desired hash mới và chèn
 * operation idempotent. Transaction commit trước mọi lời gọi mạng; {@link
 * com.giaokhoa.authorization.projection.ProjectionWorker} áp trạng thái đã ghi sau đó. Một replay
 * với cùng idempotency key trả về operation hiện có, không đụng vào nó.
 */
@Service
public class AuthorizationOperationService {
    private final AuthzPartitionRepository partitions;
    private final AuthzProjectionRepository projections;
    private final IdempotencyService idempotency;

    public AuthorizationOperationService(
            AuthzPartitionRepository partitions,
            AuthzProjectionRepository projections,
            IdempotencyService idempotency) {
        this.partitions = partitions;
        this.projections = projections;
        this.idempotency = idempotency;
    }

    /**
     * Một desired graph của một partition: các source descriptor canonical của nó, hoặc rỗng để
     * retract.
     */
    public record DesiredProjection(
            String targetKey,
            String scope,
            List<String> sourceDescriptors,
            List<UUID> dependencyPartitionIds) {
        public DesiredProjection {
            sourceDescriptors =
                    sourceDescriptors == null ? List.of() : List.copyOf(sourceDescriptors);
            dependencyPartitionIds =
                    dependencyPartitionIds == null
                            ? List.of()
                            : List.copyOf(dependencyPartitionIds);
        }
    }

    /** Mọi desired graph mà một partition chuyển tới tại revision tiếp theo của nó. */
    public record PartitionChange(UUID partitionId, List<DesiredProjection> projections) {
        public PartitionChange {
            projections = projections == null ? List.of() : List.copyOf(projections);
        }
    }

    /**
     * Fence các partition đã thay đổi cùng dependent closure của chúng và ghi operation. Caller
     * liệt kê partition theo thứ tự phụ thuộc; các dependent bị fence gia nhập tập bị ảnh hưởng
     * phía sau chúng tại revision hiện tại của chúng, nên operation chỉ hoàn tất khi toàn bộ
     * closure nhất quán trở lại.
     */
    @Transactional
    public AuthorizationOperation applyDesiredState(
            UUID initiatorUserId,
            @Nullable UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            List<PartitionChange> changes) {
        idempotency.requireKey(idempotencyKey);
        validate(changes);

        List<UUID> primaries = changes.stream().map(change -> change.partitionId()).toList();
        Closure closure = dependentClosure(primaries);
        // Khóa các primary và dependent closure trong MỘT thứ tự toàn cục — cùng thứ tự
        // IdempotencyService khóa — để hai mutation có closure giao nhau không thể
        // deadlock lẫn nhau; dependent chưa fence mà được tạo đồng thời vẫn bị
        // deferred partition trigger bắt tại thời điểm commit.
        Set<UUID> lockOrder = new TreeSet<>(Comparator.comparing(id -> id.toString()));
        lockOrder.addAll(primaries);
        lockOrder.addAll(closure.partitionIds());
        Map<UUID, AuthorizationPartition> lockedPartitions = new HashMap<>();
        Map<UUID, Long> currentRevisions = new HashMap<>();
        for (UUID partitionId : lockOrder) {
            AuthorizationPartition partition = lockPartition(partitionId);
            lockedPartitions.put(partitionId, partition);
            currentRevisions.put(partitionId, partition.getDesiredRevision());
        }
        Map<UUID, Long> newRevisions = new LinkedHashMap<>();
        for (UUID partitionId : primaries) {
            newRevisions.put(
                    partitionId,
                    Objects.requireNonNull(currentRevisions.get(partitionId), "locked revision")
                            + 1);
        }
        Map<UUID, Long> dependentRevisions = new LinkedHashMap<>();
        for (UUID partitionId : sortedByUuidString(closure.partitionIds())) {
            dependentRevisions.put(partitionId, currentRevisions.get(partitionId));
        }

        List<UUID> affected = new ArrayList<>(primaries);
        affected.addAll(sortedByUuidString(closure.partitionIds()));
        List<Long> revisions = new ArrayList<>();
        for (UUID partitionId : primaries) {
            revisions.add(newRevisions.get(partitionId));
        }
        for (UUID partitionId : sortedByUuidString(closure.partitionIds())) {
            revisions.add(dependentRevisions.get(partitionId));
        }
        AuthorizationOperation operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        initiatorUserId,
                        workspaceId,
                        idempotencyKey,
                        payloadHash,
                        List.copyOf(affected),
                        List.copyOf(revisions),
                        revisions.getFirst());
        return idempotency.executeOnce(
                operation,
                () -> applyFencesAndDesiredState(changes, newRevisions, closure, lockedPartitions));
    }

    private void applyFencesAndDesiredState(
            List<PartitionChange> changes,
            Map<UUID, Long> newRevisions,
            Closure closure,
            Map<UUID, AuthorizationPartition> lockedPartitions) {
        Set<UUID> fenced = new HashSet<>();
        for (PartitionChange change : changes) {
            fenced.add(change.partitionId());
        }
        fenced.addAll(closure.partitionIds());
        // Fence mọi partition liên quan trước khi bất kỳ trường desired nào thay đổi: cơ sở dữ
        // liệu chỉ chấp nhận sửa desired dưới một fence. Flush tường minh để fence xuống cơ sở
        // dữ liệu trước mọi thay đổi desired phía dưới, vì trigger desired kiểm tra trạng thái
        // partition ngay tại từng câu lệnh.
        for (UUID partitionId : fenced) {
            // Mọi partition bị fence đều đã khóa ở applyDesiredState (lockOrder là hợp của
            // primaries và closure) nên entity quản lý luôn có mặt.
            AuthorizationPartition partition =
                    Objects.requireNonNull(lockedPartitions.get(partitionId), "locked partition");
            partition.setState(AuthorizationPartition.State.PENDING);
        }
        partitions.flush();
        for (PartitionChange change : changes) {
            projections.fenceReadyByPartitionId(
                    change.partitionId(),
                    AuthorizationProjection.State.PENDING,
                    AuthorizationProjection.State.READY);
            AuthorizationPartition partition =
                    Objects.requireNonNull(
                            lockedPartitions.get(change.partitionId()), "locked partition");
            partition.setDesiredRevision(
                    Objects.requireNonNull(newRevisions.get(change.partitionId()), "new revision"));
        }
        for (AuthorizationProjection projection :
                projections.findAllById(closure.projectionIds())) {
            projection.setState(AuthorizationProjection.State.PENDING);
        }
        // Nạp mọi projection hiện có của cả operation bằng một truy vấn thay vì tra từng
        // target: bản đồ được cập nhật tại chỗ khi chèn mới nên một target xuất hiện hai lần
        // trong cùng operation vẫn giữ ngữ nghĩa last-wins của đường tra cứu cũ.
        Map<String, AuthorizationProjection> existingByTarget =
                new HashMap<>(
                        projections
                                .findByTargetKeyIn(
                                        changes.stream()
                                                .flatMap(
                                                        change ->
                                                                change.projections().stream())
                                                .map(DesiredProjection::targetKey)
                                                .toList())
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AuthorizationProjection::getTargetKey,
                                                Function.identity())));
        for (PartitionChange change : changes) {
            for (DesiredProjection desired : change.projections()) {
                upsertDesired(change.partitionId(), desired, existingByTarget);
            }
        }
    }

    private void upsertDesired(
            UUID partitionId,
            DesiredProjection desired,
            Map<String, AuthorizationProjection> existingByTarget) {
        String desiredHash =
                ProjectionReconciler.desiredHash(
                        desired.targetKey(), desired.scope(), desired.sourceDescriptors());
        AuthorizationProjection projection = existingByTarget.get(desired.targetKey());
        if (projection != null) {
            if (!partitionId.equals(projection.getPartitionId())) {
                throw new IllegalStateException(
                        "Projection target belongs to another partition: " + desired.targetKey());
            }
            projection.setDesiredHash(desiredHash);
            projection.setDependencyPartitionIds(desired.dependencyPartitionIds());
            projection.setState(AuthorizationProjection.State.PENDING);
            return;
        }
        AuthorizationProjection created =
                new AuthorizationProjection(
                        UUID.randomUUID(),
                        partitionId,
                        desired.targetKey(),
                        null,
                        List.of(),
                        desired.dependencyPartitionIds(),
                        desiredHash,
                        null,
                        AuthorizationProjection.State.PENDING);
        projections.save(created);
        existingByTarget.put(desired.targetKey(), created);
    }

    private record Closure(Set<UUID> partitionIds, List<UUID> projectionIds) {}

    /**
     * Các READY projection phụ thuộc vào các partition đã thay đổi, và đệ quy lên owner của chúng.
     */
    private Closure dependentClosure(List<UUID> primaries) {
        Set<UUID> owners = new HashSet<>();
        List<UUID> projectionIds = new ArrayList<>();
        Set<UUID> seenProjections = new HashSet<>();
        List<UUID> frontier = primaries;
        while (!frontier.isEmpty()) {
            List<UUID> level = frontier;
            List<DependentProjection> dependents = projections.findReadyDependentsOfAny(level);
            List<UUID> next = new ArrayList<>();
            for (DependentProjection dependent : dependents) {
                if (seenProjections.add(dependent.projectionId())) {
                    projectionIds.add(dependent.projectionId());
                }
                if (!primaries.contains(dependent.partitionId())
                        && owners.add(dependent.partitionId())) {
                    next.add(dependent.partitionId());
                }
            }
            frontier = next;
        }
        return new Closure(owners, projectionIds);
    }

    private AuthorizationPartition lockPartition(UUID partitionId) {
        return partitions
                .lockById(partitionId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown authorization partition: " + partitionId));
    }

    private void validate(List<PartitionChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("At least one partition change is required");
        }
        Set<UUID> partitions = new HashSet<>();
        Map<String, String> targets = new HashMap<>();
        for (PartitionChange change : changes) {
            if (change.partitionId() == null || !partitions.add(change.partitionId())) {
                throw new IllegalArgumentException("Partition changes must be distinct");
            }
            for (DesiredProjection desired : change.projections()) {
                NativePolicyTemplates.TargetType type =
                        NativePolicyTemplates.targetTypeOf(desired.targetKey())
                                .orElseThrow(() ->
                                        new IllegalArgumentException(
                                                "Target key matches no fixed template: "
                                                        + desired.targetKey()));
                if (desired.scope() == null || !type.scopes().contains(desired.scope())) {
                    throw new IllegalArgumentException(
                            "Scope is not fixed for target "
                                    + desired.targetKey()
                                    + ": "
                                    + desired.scope());
                }
                if (desired.dependencyPartitionIds().contains(change.partitionId())) {
                    throw new IllegalArgumentException(
                            "Projection cannot depend on its own partition: "
                                    + desired.targetKey());
                }
                String previous = targets.put(desired.targetKey(), desired.scope());
                if (previous != null && !previous.equals(desired.scope())) {
                    throw new IllegalArgumentException(
                            "Target key recorded with two scopes: " + desired.targetKey());
                }
            }
        }
    }

    private static List<UUID> sortedByUuidString(Set<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(id -> id.toString())).toList();
    }
}
