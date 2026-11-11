package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.authorization.policy.PolicyGraphWriter.AppliedGraph;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.guardians.GuardianPolicyProjector;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.SharePolicyProjector;
import com.giaokhoa.shared.Sha256;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.teaching.PersonalTeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.ActivityPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Dựng lại desired native graph của một partition đang bị fence từ các dòng bền vững, áp nó qua
 * {@link PolicyGraphWriter} idempotent và chỉ sau đó mới publish partition READY tại revision nó đã
 * xác minh. Trạng thái mong muốn của một projection là {@code desired_hash} đã ghi của nó: SHA-256
 * của các canonical source descriptor dựng lại từ các dòng nguồn, nên một projection mà nguồn của
 * nó đã biến mất hoặc thay đổi không bao giờ được publish từ một graph đã cũ. Thất bại để partition
 * vẫn bị fence; không gì ở đây mở fence dựa trên bằng chứng dở dang.
 */
@Component
public class ProjectionReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionReconciler.class);

    private static final String NO_FIXED_TEMPLATE_MESSAGE =
            "Target key matches no fixed template: ";

    public static final String ERROR_NO_RECORDED_PROJECTION = "NO_RECORDED_PROJECTION";
    public static final String ERROR_DESIRED_SOURCE_MISMATCH = "DESIRED_SOURCE_MISMATCH";
    public static final String ERROR_DEPENDENCY_NOT_READY = "DEPENDENCY_NOT_READY";
    public static final String ERROR_REVISION_SUPERSEDED = "REVISION_SUPERSEDED";

    private final AuthzPartitionRepository partitions;
    private final AuthzProjectionRepository projections;
    private final PolicyGraphWriter writer;
    private final AuthorizationProvisioningClient directory;
    private final CenterPolicyProjector centerProjector;
    private final TeachingPolicyProjector teachingProjector;
    private final GuardianPolicyProjector guardianProjector;
    private final ResultPolicyProjector resultProjector;
    private final ActivityPolicyProjector activityProjector;
    private final ResourcePolicyProjector resourceProjector;
    private final SharePolicyProjector shareProjector;
    private final PersonalEntitlementProjector personalEntitlementProjector;
    private final PersonalTeachingPolicyProjector personalTeachingProjector;
    private final TransactionTemplate transactions;
    private final ResourceMutationCoordinator coordinator;

    public ProjectionReconciler(
            AuthzPartitionRepository partitions,
            AuthzProjectionRepository projections,
            PolicyGraphWriter writer,
            AuthorizationProvisioningClient directory,
            CenterPolicyProjector centerProjector,
            TeachingPolicyProjector teachingProjector,
            GuardianPolicyProjector guardianProjector,
            ResultPolicyProjector resultProjector,
            ActivityPolicyProjector activityProjector,
            ResourcePolicyProjector resourceProjector,
            SharePolicyProjector shareProjector,
            PersonalEntitlementProjector personalEntitlementProjector,
            PersonalTeachingPolicyProjector personalTeachingProjector,
            PlatformTransactionManager transactionManager,
            ResourceMutationCoordinator coordinator) {
        this.partitions = partitions;
        this.projections = projections;
        this.writer = writer;
        this.directory = directory;
        this.centerProjector = centerProjector;
        this.teachingProjector = teachingProjector;
        this.guardianProjector = guardianProjector;
        this.resultProjector = resultProjector;
        this.activityProjector = activityProjector;
        this.resourceProjector = resourceProjector;
        this.shareProjector = shareProjector;
        this.personalEntitlementProjector = personalEntitlementProjector;
        this.personalTeachingProjector = personalTeachingProjector;
        this.transactions = new TransactionTemplate(transactionManager);
        this.coordinator = coordinator;
    }

    /** Một thất bại reconciliation mang error code bền vững được ghi trên operation. */
    public static final class ReconciliationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String code;

        public ReconciliationException(String errorCode, String message) {
            super(message);
            this.code = errorCode;
        }

        public String errorCode() {
            return code;
        }
    }

    public record PartitionRow(
            UUID id,
            long desiredRevision,
            long appliedRevision,
            AuthorizationPartition.State state) {}

    public record ProjectionRow(
            UUID id,
            UUID partitionId,
            String targetKey,
            String keycloakResourceId,
            List<String> policyNames,
            List<UUID> dependencyPartitionIds,
            String desiredHash,
            String appliedHash,
            AuthorizationProjection.State state) {
        public ProjectionRow {
            policyNames = List.copyOf(policyNames);
            dependencyPartitionIds = List.copyOf(dependencyPartitionIds);
        }
    }

    private sealed interface Resolved {
        record Apply(String scope, PolicyGraph graph) implements Resolved {}

        record Retract(String scope) implements Resolved {}

        /**
         * Toàn bộ graph của một target dạng bundle: một graph cho mỗi scope còn nguồn hiệu lực,
         * trên tập scope mà projector của target quản lý.
         */
        record ApplyBundle(Map<String, PolicyGraph> graphs, List<String> managedScopes)
                implements Resolved {
            public ApplyBundle {
                graphs = Map.copyOf(graphs);
                managedScopes = List.copyOf(managedScopes);
            }
        }

        record RetractBundle(List<String> managedScopes) implements Resolved {
            public RetractBundle {
                managedScopes = List.copyOf(managedScopes);
            }
        }
    }

    private record Outcome(
            ProjectionRow projection, Resolved resolved, @Nullable AppliedGraph applied) {}

    private static PartitionRow rowOf(AuthorizationPartition partition) {
        return new PartitionRow(
                partition.getId(),
                partition.getDesiredRevision(),
                partition.getAppliedRevision(),
                partition.getState());
    }

    private static ProjectionRow rowOf(AuthorizationProjection projection) {
        return new ProjectionRow(
                projection.getId(),
                projection.getPartitionId(),
                projection.getTargetKey(),
                projection.getKeycloakResourceId(),
                projection.getPolicyNames(),
                projection.getDependencyPartitionIds(),
                projection.getDesiredHash(),
                projection.getAppliedHash(),
                projection.getState());
    }

    public PartitionRow partition(UUID partitionId) {
        return partitions
                .findById(partitionId)
                .map(ProjectionReconciler::rowOf)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    /** Nạp nhiều partition trong một query, lập chỉ mục theo id cho các kiểm tra theo lô. */
    private Map<UUID, PartitionRow> partitionsById(Set<UUID> partitionIds) {
        Map<UUID, PartitionRow> byId = new HashMap<>();
        if (partitionIds.isEmpty()) {
            return byId;
        }
        for (AuthorizationPartition partition : partitions.findAllById(partitionIds)) {
            byId.put(partition.getId(), rowOf(partition));
        }
        return byId;
    }

    public List<ProjectionRow> projectionsOf(UUID partitionId) {
        return projections.findByPartitionIdOrderByTargetKeyAsc(partitionId).stream()
                .map(ProjectionReconciler::rowOf)
                .toList();
    }

    /** Reconcile partition tại bất kỳ revision nào đang được mong muốn hiện tại. */
    public void reconcilePartition(UUID partitionId) {
        reconcile(partitionId, null);
    }

    /**
     * Chỉ reconcile partition nếu {@code expectedDesiredRevision} vẫn là revision được mong muốn.
     */
    public void reconcilePartition(UUID partitionId, long expectedDesiredRevision) {
        reconcile(partitionId, expectedDesiredRevision);
    }

    private void reconcile(UUID partitionId, @Nullable Long expectedDesiredRevision) {
        // Khóa trước khi đọc revision: worker chờ khóa không được ghi lại snapshot cũ.
        coordinator.withResourceLock(
                "partition:" + partitionId,
                () -> {
                    reconcileLocked(partitionId, expectedDesiredRevision);
                    return null;
                });
    }

    private void reconcileLocked(UUID partitionId, @Nullable Long expectedDesiredRevision) {
        PartitionRow partition = partition(partitionId);
        if (expectedDesiredRevision != null
                && partition.desiredRevision() != expectedDesiredRevision) {
            throw new ReconciliationException(
                    ERROR_REVISION_SUPERSEDED,
                    "Partition desired revision moved past " + expectedDesiredRevision);
        }
        if (partition.state() == AuthorizationPartition.State.READY) {
            if (partition.appliedRevision() == partition.desiredRevision()) {
                return;
            }
            throw new ReconciliationException(
                    ERROR_REVISION_SUPERSEDED, "READY partition has an unapplied revision");
        }
        List<ProjectionRow> projections = projectionsOf(partitionId);
        if (projections.isEmpty()) {
            // Không có gì đã ghi để đối chiếu: publish READY sẽ là đoán mò, nên fence
            // giữ nguyên đóng và operation tiếp tục retry trong ngân sách lần thử của nó.
            throw new ReconciliationException(
                    ERROR_NO_RECORDED_PROJECTION,
                    "Partition has no recorded projection to reconcile: " + partitionId);
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ProjectionRow projection : projections) {
            resolved.add(resolve(projection));
        }
        // Dependency phân biệt của mọi projection được nạp một lần theo lô thay vì một
        // query cho từng cặp projection × dependency; thứ tự kiểm tra vẫn là thứ tự xuất
        // hiện đầu tiên nên dependency lỗi đầu tiên được báo cáo không đổi.
        Set<UUID> dependencyIds = new LinkedHashSet<>();
        for (ProjectionRow projection : projections) {
            dependencyIds.addAll(projection.dependencyPartitionIds());
        }
        Map<UUID, PartitionRow> dependencyPartitions = partitionsById(dependencyIds);
        for (UUID dependency : dependencyIds) {
            PartitionRow dependencyPartition = dependencyPartitions.get(dependency);
            if (dependencyPartition == null) {
                // Giữ đúng ngoại lệ của partition(): partition chưa từng được ghi.
                throw new EmptyResultDataAccessException(1);
            }
            if (dependencyPartition.state() != AuthorizationPartition.State.READY
                    || dependencyPartition.appliedRevision()
                            != dependencyPartition.desiredRevision()) {
                throw new ReconciliationException(
                        ERROR_DEPENDENCY_NOT_READY,
                        "Dependency partition is not READY: " + dependency);
            }
        }
        // Group là tiền đề mà policy tham chiếu: writer chỉ lấy id của group đã tồn tại, nên
        // mọi group path mà các graph sắp áp tham chiếu phải tồn tại TRƯỚC khi áp bundle.
        // Membership sync của WORKSPACE partition chỉ tạo group khi có member ACTIVE cần vào
        // group đó, nên descriptor trỏ vào group chưa từng có member nào (như managers của
        // trung tâm nhận share chưa từng có quản lý) không được bước sync nào tạo ra — bảo
        // đảm tồn tại ở đây chỉ tạo group rỗng, không cấp quyền cho ai; membership vẫn do
        // các đường sync sở hữu. Đường eligibility giữ sau apply như cũ vì group đó đã tồn
        // tại sẵn từ fixture.
        ensureReferencedGroups(resolved);
        syncWorkspaceGroupMemberships(partitionId);
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < projections.size(); i++) {
            ProjectionRow projection = projections.get(i);
            Resolved step = resolved.get(i);
            if (step instanceof Resolved.Apply apply) {
                AppliedGraph applied = null;
                for (String scope : projectedScopes(projection.targetKey(), apply.scope())) {
                    PolicyGraph graph =
                            new PolicyGraph(
                                    projection.targetKey(),
                                    scope,
                                    apply.graph().commonGates(),
                                    apply.graph().sources());
                    AppliedGraph next = writer.apply(graph);
                    if (applied != null
                            && (!applied.resourceId().equals(next.resourceId())
                                    || !applied.policyNames().equals(next.policyNames()))) {
                        throw new ReconciliationException(
                                ERROR_DESIRED_SOURCE_MISMATCH,
                                "Profile permissions must share the same identity source");
                    }
                    applied = next;
                }
                outcomes.add(new Outcome(projection, step, Objects.requireNonNull(applied)));
            } else if (step instanceof Resolved.Retract retract) {
                for (String scope : projectedScopes(projection.targetKey(), retract.scope())) {
                    writer.retractRoot(projection.targetKey(), scope);
                }
                outcomes.add(new Outcome(projection, step, null));
            } else if (step instanceof Resolved.ApplyBundle bundle) {
                outcomes.add(
                        new Outcome(projection, step, applyBundle(projection.targetKey(), bundle)));
            } else if (step instanceof Resolved.RetractBundle retractBundle) {
                for (String scope : retractBundle.managedScopes()) {
                    writer.retractRoot(projection.targetKey(), scope);
                }
                outcomes.add(new Outcome(projection, step, null));
            }
        }
        syncEligibilityMembership(partitionId);
        publish(partition, outcomes);
    }

    /**
     * Áp bundle của một target: mọi scope còn nguồn được áp graph của nó trên cùng một native
     * resource của target, scope không còn nguồn thì root permission của nó bị thu hồi. Tập policy
     * đã ghi của bundle là hợp của mọi scope đã áp.
     */
    private AppliedGraph applyBundle(String targetKey, Resolved.ApplyBundle bundle) {
        AppliedGraph applied = null;
        java.util.Set<String> policyNames = new TreeSet<>();
        for (String scope : bundle.managedScopes()) {
            PolicyGraph graph = bundle.graphs().get(scope);
            if (graph == null) {
                writer.retractRoot(targetKey, scope);
                continue;
            }
            AppliedGraph next = writer.apply(graph);
            if (applied != null && !applied.resourceId().equals(next.resourceId())) {
                throw new ReconciliationException(
                        ERROR_DESIRED_SOURCE_MISMATCH,
                        "Bundle scopes must share one native resource: " + targetKey);
            }
            applied = next;
            policyNames.addAll(next.policyNames());
        }
        return new AppliedGraph(
                Objects.requireNonNull(applied, "bundle without any scope").resourceId(),
                List.copyOf(policyNames));
    }

    /**
     * ACCOUNT partition còn chiếu một trạng thái directory mà graph không biểu diễn được:
     * membership của chủ account trong group eligibility của danh tính — thứ mọi business graph đều
     * gate theo. Membership mong muốn được dựng lại từ chính các row account bền làm nguồn
     * eligibility (record do backend cấp, hoặc email verified đã ghi) và chỉ ghi khi membership
     * live đang khác, để reconcile không đổi gì thì vẫn chỉ là kiểm chứng đọc. Ghi lỗi thì
     * reconciliation lỗi theo: fence giữ đóng thay vì publish READY trên membership cũ.
     */
    private void syncEligibilityMembership(UUID partitionId) {
        Optional<AuthorizationPartition> partition = partitions.findById(partitionId);
        if (partition.isEmpty()
                || partition.get().getKind() != AuthorizationPartition.Kind.ACCOUNT) {
            return;
        }
        UUID userId = UUID.fromString(partition.get().getOwnerKey().substring("account:".length()));
        Optional<UserAccount> account = partitions.findAccountById(userId);
        if (account.isEmpty()) {
            return;
        }
        String subject = Objects.requireNonNull(account.get().getKeycloakSub(), "keycloak_sub");
        boolean eligible =
                account.get().getOrigin() == UserAccount.Origin.CENTER_ISSUED
                        || account.get().isEmailVerified();
        if (directory.isRealmGroupMember(subject, NativePolicyTemplates.ELIGIBILITY_GROUP)
                != eligible) {
            directory.setRealmGroupMembership(
                    subject, NativePolicyTemplates.ELIGIBILITY_GROUP, eligible);
        }
    }

    /**
     * WORKSPACE partition của một center còn một trạng thái directory mà graph không biểu diễn
     * được: membership thật của từng thành viên trong các realm group nguồn của workspace (group
     * policy chỉ cấp cho người đang ở trong group). Việc đối chiếu do {@link CenterPolicyProjector}
     * đảm nhiệm từ chính các dòng membership/role bền; chạy trước khi áp bundle để writer luôn gặp
     * group đã tồn tại khi lấy id cho policy.
     */
    private void syncWorkspaceGroupMemberships(UUID partitionId) {
        Optional<AuthorizationPartition> partition = partitions.findById(partitionId);
        if (partition.isEmpty()
                || partition.get().getKind() != AuthorizationPartition.Kind.WORKSPACE) {
            return;
        }
        UUID workspaceId =
                UUID.fromString(partition.get().getOwnerKey().substring("workspace:".length()));
        Optional<Workspace> workspace = partitions.findWorkspaceById(workspaceId);
        if (workspace.isEmpty() || workspace.get().getKind() != Workspace.Kind.CENTER) {
            return;
        }
        centerProjector.syncGroupMemberships(workspaceId);
        // Cùng một vòng đối chiếu: group nguồn giảng dạy (giáo viên/chủ nhiệm/học sinh theo
        // môn–khóa) cũng phải khớp membership hiện hành trước khi writer áp bundle.
        teachingProjector.syncGroupMemberships(workspaceId);
        // Và guardian group theo student: phép chiếu duy nhất biến một guardian link đã
        // duyệt thành tư cách thành viên group mà policy của kết quả tham chiếu.
        guardianProjector.syncGroupMemberships(workspaceId);
    }

    /**
     * Bảo đảm mọi realm group mà các graph sắp áp tham chiếu đều đã tồn tại trong Keycloak, tạo
     * group rỗng qua {@link AuthorizationProvisioningClient#ensureRealmGroup} cho path chưa có. Chỉ
     * các bước Apply/ApplyBundle mới tham chiếu group; bước thu hồi không áp graph nào nên không
     * cần bảo đảm. Group rỗng không cho ai qua gate của nó, và membership live vẫn do các đường
     * sync của workspace/guardian/teaching sở hữu như cũ.
     */
    private void ensureReferencedGroups(List<Resolved> resolved) {
        Set<String> groupPaths = new TreeSet<>();
        for (Resolved step : resolved) {
            if (step instanceof Resolved.Apply apply) {
                collectGroupPaths(apply.graph(), groupPaths);
            } else if (step instanceof Resolved.ApplyBundle bundle) {
                for (PolicyGraph graph : bundle.graphs().values()) {
                    collectGroupPaths(graph, groupPaths);
                }
            }
        }
        groupPaths.forEach(directory::ensureRealmGroup);
    }

    private static void collectGroupPaths(PolicyGraph graph, Set<String> groupPaths) {
        for (PolicyGraph.Gate gate : graph.commonGates()) {
            collectGroupPath(gate, groupPaths);
        }
        for (PolicyGraph.Source source : graph.sources()) {
            for (PolicyGraph.Gate gate : source.gates()) {
                collectGroupPath(gate, groupPaths);
            }
        }
    }

    private static void collectGroupPath(PolicyGraph.Gate gate, Set<String> groupPaths) {
        if (gate instanceof PolicyGraph.Gate.GroupMembership group) {
            groupPaths.add(group.groupPath());
        }
    }

    /** Fence một partition và các projection của nó; dùng trước khi sửa drift ngoài hệ thống. */
    public void fencePartition(UUID partitionId) {
        transactions.executeWithoutResult(_ -> {
            partitions.fenceIfReady(
                    partitionId,
                    AuthorizationPartition.State.PENDING,
                    AuthorizationPartition.State.READY);
            projections.fenceReadyByPartitionId(
                    partitionId,
                    AuthorizationProjection.State.PENDING,
                    AuthorizationProjection.State.READY);
        });
    }

    /**
     * Kiểm tra drift cho một partition READY: chỉ false khi một projection mà trạng thái mong muốn
     * của nó vẫn dựng lại đúng từ các dòng bền vững bị chứng minh là đã thay đổi trong Keycloak ở
     * mức {@link PolicyGraphWriter#isGraphIntact} xác minh — resource hoặc policy đã ghi bị thiếu,
     * hoặc root permission không còn liên kết đúng tập policy đã ghi tới resource đã ghi. Các dòng
     * mà nguồn của chúng không còn dựng lại được, và mọi lỗi đọc Keycloak, không phải bằng chứng
     * của drift và không bao giờ tự chúng fence partition.
     */
    public boolean verifyStoredGraph(UUID partitionId) {
        for (ProjectionRow projection : projectionsOf(partitionId)) {
            if (projection.appliedHash() == null || projection.keycloakResourceId() == null) {
                return false;
            }
            Resolved resolved;
            try {
                resolved = resolve(projection);
            } catch (ReconciliationException ex) {
                continue;
            }
            if (resolved instanceof Resolved.Retract
                    || resolved instanceof Resolved.RetractBundle) {
                return false;
            }
            if (resolved instanceof Resolved.ApplyBundle bundle) {
                boolean intact;
                try {
                    intact = true;
                    for (Map.Entry<String, PolicyGraph> entry : bundle.graphs().entrySet()) {
                        if (!writer.isGraphIntact(
                                projection.targetKey(),
                                entry.getKey(),
                                projection.keycloakResourceId(),
                                entry.getValue().rootPolicyNames())) {
                            intact = false;
                            break;
                        }
                    }
                } catch (RestClientException
                        | IllegalStateException
                        | OAuth2AuthorizationException ex) {
                    continue;
                }
                if (!intact) {
                    return false;
                }
                continue;
            }
            Resolved.Apply apply = (Resolved.Apply) resolved;
            boolean intact;
            try {
                intact = true;
                for (String scope : projectedScopes(projection.targetKey(), apply.scope())) {
                    if (!writer.isGraphIntact(
                            projection.targetKey(),
                            scope,
                            projection.keycloakResourceId(),
                            projection.policyNames())) {
                        intact = false;
                        break;
                    }
                }
            } catch (RestClientException
                    | IllegalStateException
                    | OAuth2AuthorizationException ex) {
                continue;
            }
            if (!intact) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reconciliation best-effort cho các partition không có operation đang sống: trả về false thay
     * vì ném lỗi khi partition chưa thể hội tụ lúc này, để caller đi tiếp và fence đơn giản giữ
     * đóng cho chu kỳ sau.
     */
    public boolean tryReconcilePartition(UUID partitionId) {
        return tryReconcilePartition(partitionId, 1);
    }

    /**
     * Như {@link #tryReconcilePartition(UUID)} nhưng biết số lần thất bại liên tiếp của caller để
     * chống lặp log: một lỗi đã phân loại chỉ được ghi ở lần thất bại đầu tiên và các mốc lũy thừa
     * hai. Lỗi đã phân loại (mã lỗi bền vững, điều kiện được dự kiến trong thiết kế fail-closed)
     * ghi kèm mã và thông điệp, không kèm stack trace — theo quy tắc SLF4J, stack trace chỉ in khi
     * Throwable được truyền làm đối số cuối; riêng {@link #ERROR_NO_RECORDED_PROJECTION} ghi ở mức
     * DEBUG. Lỗi hạ tầng chưa phân loại giữ WARN kèm stack trace như đường operation.
     */
    public boolean tryReconcilePartition(UUID partitionId, int consecutiveFailures) {
        try {
            reconcilePartition(partitionId);
            return true;
        } catch (ReconciliationException ex) {
            if (isLogMilestone(consecutiveFailures)) {
                if (ERROR_NO_RECORDED_PROJECTION.equals(ex.errorCode())) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "Partition {} did not reconcile with code {}: {}",
                                partitionId,
                                ex.errorCode(),
                                ex.getMessage());
                    }
                } else if (LOG.isWarnEnabled()) {
                    LOG.warn(
                            "Partition {} did not reconcile with code {}: {}",
                            partitionId,
                            ex.errorCode(),
                            ex.getMessage());
                }
            }
            return false;
        } catch (RestClientException
                | DataAccessException
                | IllegalStateException
                | OAuth2AuthorizationException ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Partition {} did not reconcile", partitionId, ex);
            }
            return false;
        }
    }

    /** Các mốc ghi log chống lặp cho cùng một partition và mã lỗi: 1, 2, 4, 8, ... */
    private static boolean isLogMilestone(int consecutiveFailures) {
        return consecutiveFailures > 0 && (consecutiveFailures & (consecutiveFailures - 1)) == 0;
    }

    private void publish(PartitionRow snapshot, List<Outcome> outcomes) {
        transactions.executeWithoutResult(_ -> {
            AuthorizationPartition current =
                    partitions
                            .lockById(snapshot.id())
                            .orElseThrow(() -> new EmptyResultDataAccessException(1));
            // Kiểm tra bằng revision hiện hành tại cơ sở dữ liệu, không phải entity đã nạp:
            // vòng reconcile chạy trong một session dài nên entity partition có thể cũ hơn
            // DB, và flush entity cũ đó sẽ ghi revision đi lùi (trigger identity chặn lại
            // thành lỗi dữ liệu thay vì lỗi superseded đúng giao thức).
            long currentDesiredRevision =
                    partitions
                            .findDesiredRevisionById(snapshot.id())
                            .orElseThrow(() -> new EmptyResultDataAccessException(1));
            if (currentDesiredRevision != snapshot.desiredRevision()) {
                throw new ReconciliationException(
                        ERROR_REVISION_SUPERSEDED,
                        "Desired revision changed during reconciliation");
            }
            for (Outcome outcome : outcomes) {
                ProjectionRow projection = outcome.projection();
                if (outcome.resolved() instanceof Resolved.Retract
                        || outcome.resolved() instanceof Resolved.RetractBundle) {
                    projections.deleteById(projection.id());
                } else {
                    AppliedGraph applied = Objects.requireNonNull(outcome.applied());
                    AuthorizationProjection entity =
                            projections
                                    .findById(projection.id())
                                    .orElseThrow(() -> new EmptyResultDataAccessException(1));
                    // applied_hash lấy theo desired_hash hiện hành tại DB như câu UPDATE của
                    // tầng JDBC cũ, không theo bản entity có thể đã cũ trong session.
                    entity.setAppliedHash(
                            projections
                                    .findDesiredHashById(projection.id())
                                    .orElseThrow(() -> new EmptyResultDataAccessException(1)));
                    entity.setKeycloakResourceId(applied.resourceId());
                    entity.setPolicyNames(applied.policyNames());
                    entity.setState(AuthorizationProjection.State.READY);
                }
            }
            // Mọi thay đổi projection phải xuống cơ sở dữ liệu trước khi partition chuyển
            // READY: trigger kiểm tra graph READY của partition ngay tại câu lệnh đổi trạng
            // thái của nó.
            projections.flush();
            current.setAppliedRevision(snapshot.desiredRevision());
            current.setState(AuthorizationPartition.State.READY);
        });
    }

    private Resolved resolve(ProjectionRow projection) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(projection.targetKey())
                        .orElseThrow(() ->
                                new ReconciliationException(
                                        ERROR_DESIRED_SOURCE_MISMATCH,
                                        NO_FIXED_TEMPLATE_MESSAGE + projection.targetKey()));
        if (type == NativePolicyTemplates.TargetType.WORKSPACE
                && isCenterWorkspace(projection.targetKey())) {
            return resolveCenterBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.WORKSPACE
                && isPersonalWorkspace(projection.targetKey())) {
            return resolvePersonalBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.PLATFORM) {
            return resolvePlatformBundle(projection);
        }
        if ((type == NativePolicyTemplates.TargetType.CLASS
                        || type == NativePolicyTemplates.TargetType.COURSE
                        || type == NativePolicyTemplates.TargetType.COURSE_SUBJECT)
                && isCenterWorkspace(workspaceKeyOf(projection.targetKey()))) {
            return resolveTeachingBundle(projection);
        }
        if ((type == NativePolicyTemplates.TargetType.CLASS
                        || type == NativePolicyTemplates.TargetType.COURSE
                        || type == NativePolicyTemplates.TargetType.COURSE_SUBJECT)
                && isPersonalWorkspace(workspaceKeyOf(projection.targetKey()))) {
            return resolvePersonalTeachingBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.RESULT) {
            return resolveResultBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.ACTIVITY) {
            return resolveActivityBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.RESOURCE_VERSION) {
            return resolveResourceBundle(projection);
        }
        if (type == NativePolicyTemplates.TargetType.GRANT) {
            return resolveShareBundle(projection);
        }
        for (String scope : type.scopes()) {
            if (desiredHash(projection.targetKey(), scope, List.of())
                    .equals(projection.desiredHash())) {
                return new Resolved.Retract(scope);
            }
        }
        for (String scope : type.scopes()) {
            List<String> sources = reconstructSources(projection.targetKey(), type, scope);
            if (!sources.isEmpty()
                    && desiredHash(projection.targetKey(), scope, sources)
                            .equals(projection.desiredHash())) {
                return new Resolved.Apply(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        throw new ReconciliationException(
                ERROR_DESIRED_SOURCE_MISMATCH,
                "Desired hash does not match any source rebuilt from durable rows: "
                        + projection.targetKey());
    }

    /**
     * Bundle của một CENTER workspace: desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * CenterPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành cho mọi scope; khớp thì mỗi scope
     * còn nguồn được một graph riêng trên cùng target. Hash được tính với scope danh nghĩa ENTER —
     * cùng scope mà đường ghi bundle dùng khi lưu projection.
     */
    private Resolved resolveCenterBundle(ProjectionRow projection) {
        UUID workspaceId =
                UUID.fromString(
                        projection
                                .targetKey()
                                .substring(NativePolicyTemplates.WORKSPACE_PREFIX.length()));
        List<String> bundle = centerProjector.bundleDescriptors(workspaceId);
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(CenterPolicyProjector.managedScopes());
        }
        String bundleScope = PermissionCatalog.WorkspaceScope.ENTER.scopeName();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the center sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : CenterPolicyProjector.managedScopes()) {
            List<String> sources = centerProjector.sourcesFor(workspaceId, scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, CenterPolicyProjector.managedScopes());
    }

    /**
     * Bundle của target platform: nguồn là hằng số hợp đồng nên tập descriptor dựng lại không cần
     * dữ kiện bền nào; desired hash đã ghi phải khớp đúng tập đó với scope danh nghĩa của
     * projector, hoặc là hash của tập rỗng khi hợp đồng đã rút toàn bộ grant.
     */
    private Resolved resolvePlatformBundle(ProjectionRow projection) {
        String targetKey = projection.targetKey();
        String bundleScope = PlatformPolicyProjector.bundleScope();
        List<String> managedScopes = PlatformPolicyProjector.managedScopes();
        if (desiredHash(targetKey, bundleScope, List.of()).equals(projection.desiredHash())) {
            return new Resolved.RetractBundle(managedScopes);
        }
        List<String> bundle = PlatformPolicyProjector.bundleDescriptors();
        if (!desiredHash(targetKey, bundleScope, bundle).equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the platform contract sources: " + targetKey);
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            graphs.put(
                    scope, graphFor(targetKey, scope, PlatformPolicyProjector.sourcesFor(scope)));
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    private boolean isCenterWorkspace(String targetKey) {
        UUID workspaceId =
                UUID.fromString(
                        targetKey.substring(NativePolicyTemplates.WORKSPACE_PREFIX.length()));
        return partitions
                .findWorkspaceById(workspaceId)
                .map(workspace -> workspace.getKind() == Workspace.Kind.CENTER)
                .orElse(false);
    }

    private boolean isPersonalWorkspace(String targetKey) {
        UUID workspaceId =
                UUID.fromString(
                        targetKey.substring(NativePolicyTemplates.WORKSPACE_PREFIX.length()));
        return partitions
                .findWorkspaceById(workspaceId)
                .map(workspace -> workspace.getKind() == Workspace.Kind.PERSONAL)
                .orElse(false);
    }

    /**
     * Bundle của một PERSONAL workspace: cùng hình dạng với bundle của center — desired hash đã ghi
     * phải khớp đúng tập descriptor mà {@link PersonalEntitlementProjector} dựng lại từ dữ kiện
     * nguồn hiện hành cho mọi scope, với scope danh nghĩa ENTER của projector.
     */
    private Resolved resolvePersonalBundle(ProjectionRow projection) {
        UUID workspaceId =
                UUID.fromString(
                        projection
                                .targetKey()
                                .substring(NativePolicyTemplates.WORKSPACE_PREFIX.length()));
        String bundleScope = PersonalEntitlementProjector.bundleScope();
        // Nguồn owner của personal không bao giờ tự rỗng khi workspace còn tồn tại, nên desired
        // đã ghi là tập rỗng chỉ đến từ một lần ghi thu hồi toàn bộ: rút cả bundle, cùng hình
        // dạng với bundle platform.
        if (desiredHash(projection.targetKey(), bundleScope, List.of())
                .equals(projection.desiredHash())) {
            return new Resolved.RetractBundle(PersonalEntitlementProjector.managedScopes());
        }
        List<String> bundle = personalEntitlementProjector.bundleDescriptors(workspaceId);
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(PersonalEntitlementProjector.managedScopes());
        }
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the personal sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : PersonalEntitlementProjector.managedScopes()) {
            List<String> sources = personalEntitlementProjector.sourcesFor(workspaceId, scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, PersonalEntitlementProjector.managedScopes());
    }

    /**
     * Bundle của một target giảng dạy (lớp/khóa/môn–khóa) thuộc PERSONAL workspace: cùng hình dạng
     * với bundle giảng dạy của center — desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * PersonalTeachingPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành, với scope danh nghĩa
     * READ của projector.
     */
    private Resolved resolvePersonalTeachingBundle(ProjectionRow projection) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(projection.targetKey())
                        .orElseThrow(() ->
                                new ReconciliationException(
                                        ERROR_DESIRED_SOURCE_MISMATCH,
                                        NO_FIXED_TEMPLATE_MESSAGE + projection.targetKey()));
        List<String> bundle = personalTeachingProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = PersonalTeachingPolicyProjector.managedScopes(type);
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = PersonalTeachingPolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the personal teaching sources rebuilt from"
                            + " durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources =
                    personalTeachingProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    /** Target key của workspace chứa một target con (lớp/khóa/môn–khóa) của cùng workspace đó. */
    private static String workspaceKeyOf(String targetKey) {
        return targetKey.substring(0, NativePolicyTemplates.WORKSPACE_PREFIX.length() + 36);
    }

    /**
     * Bundle của một target giảng dạy (lớp/khóa/môn–khóa) thuộc CENTER workspace: cùng hình dạng
     * với bundle của workspace — desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * TeachingPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành, với scope danh nghĩa READ của
     * projector.
     */
    private Resolved resolveTeachingBundle(ProjectionRow projection) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(projection.targetKey())
                        .orElseThrow(() ->
                                new ReconciliationException(
                                        ERROR_DESIRED_SOURCE_MISMATCH,
                                        NO_FIXED_TEMPLATE_MESSAGE + projection.targetKey()));
        List<String> bundle = teachingProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = TeachingPolicyProjector.managedScopes(type);
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = TeachingPolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the teaching sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources = teachingProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    /**
     * Bundle của một target kết quả thuộc workspace (center hoặc personal): cùng hình dạng với
     * bundle giảng dạy — desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * ResultPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành cho mọi scope, với scope danh
     * nghĩa READ của projector.
     */
    private Resolved resolveResultBundle(ProjectionRow projection) {
        List<String> bundle = resultProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = ResultPolicyProjector.managedScopes();
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = ResultPolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the result sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources = resultProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    /**
     * Bundle của một target hoạt động thuộc workspace (center hoặc personal): cùng hình dạng với
     * bundle kết quả — desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * ActivityPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành, với scope danh nghĩa SUBMIT của
     * projector.
     */
    private Resolved resolveActivityBundle(ProjectionRow projection) {
        List<String> bundle = activityProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = ActivityPolicyProjector.managedScopes();
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = ActivityPolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the activity sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources = activityProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    /**
     * Bundle của một target resource version thuộc workspace ngữ cảnh: cùng hình dạng với bundle
     * kết quả — desired hash đã ghi phải khớp đúng tập descriptor mà {@link
     * ResourcePolicyProjector} dựng lại từ dữ kiện nguồn hiện hành, với scope danh nghĩa
     * CONTENT_READ của projector.
     */
    private Resolved resolveResourceBundle(ProjectionRow projection) {
        List<String> bundle = resourceProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = ResourcePolicyProjector.managedScopes();
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = ResourcePolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the resource sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources = resourceProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    /**
     * Bundle của một share grant: cùng kỷ luật với bundle resource version — desired hash đã ghi
     * phải khớp đúng tập descriptor mà {@link SharePolicyProjector} dựng lại từ dòng grant hiện
     * hành; bundle rỗng (grant đã đóng hoặc không còn nguồn) thì retract cả hai scope.
     */
    private Resolved resolveShareBundle(ProjectionRow projection) {
        List<String> bundle = shareProjector.bundleDescriptors(projection.targetKey());
        List<String> managedScopes = SharePolicyProjector.managedScopes();
        if (bundle.isEmpty()) {
            return new Resolved.RetractBundle(managedScopes);
        }
        String bundleScope = SharePolicyProjector.bundleScope();
        if (!desiredHash(projection.targetKey(), bundleScope, bundle)
                .equals(projection.desiredHash())) {
            throw new ReconciliationException(
                    ERROR_DESIRED_SOURCE_MISMATCH,
                    "Desired hash does not match the share sources rebuilt from durable rows: "
                            + projection.targetKey());
        }
        Map<String, PolicyGraph> graphs = new LinkedHashMap<>();
        for (String scope : managedScopes) {
            List<String> sources = shareProjector.sourcesFor(projection.targetKey(), scope);
            if (!sources.isEmpty()) {
                graphs.put(scope, graphFor(projection.targetKey(), scope, sources));
            }
        }
        return new Resolved.ApplyBundle(graphs, managedScopes);
    }

    private List<String> reconstructSources(
            String targetKey, NativePolicyTemplates.TargetType type, String scope) {
        if (type == NativePolicyTemplates.TargetType.PROFILE) {
            UUID userId = UUID.fromString(targetKey.substring("account:".length()));
            return partitions
                    .findAccountById(userId)
                    .filter(account ->
                            account.getOrigin() == UserAccount.Origin.CENTER_ISSUED
                                    || account.isEmailVerified())
                    .map(account ->
                            List.of(
                                    eligibilityDescriptor(
                                            userId, account.getKeycloakSub(), targetKey, scope)))
                    .orElse(List.of());
        }
        if (type != NativePolicyTemplates.TargetType.WORKSPACE) {
            return List.of();
        }
        UUID workspaceId =
                UUID.fromString(
                        targetKey.substring(NativePolicyTemplates.WORKSPACE_PREFIX.length()));
        Optional<Workspace> workspace = partitions.findWorkspaceById(workspaceId);
        if (workspace.isEmpty() || workspace.get().getKind() != Workspace.Kind.PERSONAL) {
            return List.of();
        }
        UUID ownerUserId = workspace.get().getOwnerUserId();
        return partitions
                .findAccountById(ownerUserId)
                .map(owner ->
                        List.of(
                                personalOwnerDescriptor(
                                        workspaceId,
                                        ownerUserId,
                                        owner.getKeycloakSub(),
                                        targetKey,
                                        scope)))
                .orElse(List.of());
    }

    /** Canonical source descriptor của personal owner, dựng lại từ các dòng owner bền vững. */
    public static String personalOwnerDescriptor(
            UUID workspaceId, UUID userId, String keycloakSubject, String targetKey, String scope) {
        return "PERSONAL_OWNER|workspace="
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + keycloakSubject
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }

    /**
     * Descriptor chuẩn của nguồn eligibility cho account target, dựng lại từ row account bền khi
     * account đang eligible: record do backend cấp, hoặc email verified đã ghi của account tự đăng
     * ký.
     */
    public static String eligibilityDescriptor(
            UUID userId, String keycloakSubject, String targetKey, String scope) {
        return "IDENTITY_ELIGIBILITY|user="
                + userId
                + "|subject="
                + keycloakSubject
                + "|target="
                + targetKey
                + "|scope="
                + scope
                + profileBundleDescriptor(targetKey, scope);
    }

    // Hai scope đọc của US1 dùng cùng actor và eligibility; một checkpoint ghi theo target.
    private static List<String> projectedScopes(String targetKey, String scope) {
        return targetKey.startsWith("account:")
                        && PermissionCatalog.ProfileScope.WORKSPACE_LIST.scopeName().equals(scope)
                ? List.of(scope, PermissionCatalog.ProfileScope.HISTORY_READ.scopeName())
                : List.of(scope);
    }

    private static String profileBundleDescriptor(String targetKey, String scope) {
        List<String> scopes = projectedScopes(targetKey, scope);
        return scopes.size() > 1 ? "|scopes=" + String.join(",", scopes) : "";
    }

    /** Canonical descriptor của một graph không còn được tồn tại cho cặp đó nữa. */
    public static String retractDescriptor(String targetKey, String scope) {
        return "RETRACT|target="
                + targetKey
                + "|scope="
                + scope
                + profileBundleDescriptor(targetKey, scope);
    }

    /**
     * Desired hash ghi trên một projection: SHA-256 của các canonical source descriptor, sắp xếp và
     * nối bằng newline; một tập nguồn rỗng thay vào đó hash retract descriptor.
     */
    public static String desiredHash(
            String targetKey, String scope, List<String> sourceDescriptors) {
        List<String> sorted = sourceDescriptors.stream().sorted().distinct().toList();
        if (sorted.isEmpty()) {
            return Sha256.hex(retractDescriptor(targetKey, scope));
        }
        return Sha256.hex(String.join("\n", sorted));
    }

    public static PolicyGraph graphFor(
            String targetKey, String scope, List<String> sourceDescriptors) {
        List<PolicyGraph.Source> sources = new ArrayList<>();
        for (String descriptor : sourceDescriptors) {
            sources.add(sourceFor(descriptor));
        }
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        NO_FIXED_TEMPLATE_MESSAGE + targetKey));
        // Các scope hợp đồng miễn trừ eligibility (như account.recover) không được gắn gate đó:
        // constructor tiện ích luôn gắn sẽ thu hẹp quyền so với hợp đồng dù hash vẫn tự nhất quán.
        if (!NativePolicyTemplates.requiresEligibility(type, scope)) {
            return new PolicyGraph(targetKey, scope, List.of(), sources);
        }
        return new PolicyGraph(targetKey, scope, sources);
    }

    private static PolicyGraph.Source sourceFor(String descriptor) {
        if (descriptor.startsWith("CENTER_GROUP")) {
            return new PolicyGraph.Source(CenterPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("TEACHING_GROUP")) {
            return new PolicyGraph.Source(TeachingPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("RESULT_")) {
            return new PolicyGraph.Source(ResultPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("ACTIVITY_")) {
            return new PolicyGraph.Source(ActivityPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("RESOURCE_")) {
            return new PolicyGraph.Source(ResourcePolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("GUARDIAN_GROUP")) {
            return new PolicyGraph.Source(GuardianPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("PLATFORM_OPERATOR")) {
            return new PolicyGraph.Source(PlatformPolicyProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("PERSONAL_OWNER_PERIOD")) {
            return new PolicyGraph.Source(PersonalEntitlementProjector.gatesFor(descriptor));
        }
        if (descriptor.startsWith("PERSONAL_TEACHING")) {
            return new PolicyGraph.Source(PersonalTeachingPolicyProjector.gatesFor(descriptor));
        }
        return new PolicyGraph.Source(List.of(actorGate(descriptor)));
    }

    private static PolicyGraph.Gate actorGate(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !("PERSONAL_OWNER".equals(parts[0])
                        || "IDENTITY_ELIGIBILITY".equals(parts[0]))) {
            throw new IllegalArgumentException("Unsupported source descriptor: " + descriptor);
        }
        for (String part : parts) {
            if (part.startsWith("subject=") && part.length() > "subject=".length()) {
                return new PolicyGraph.Gate.Actor(part.substring("subject=".length()));
            }
        }
        throw new IllegalArgumentException("Source descriptor has no subject: " + descriptor);
    }
}
