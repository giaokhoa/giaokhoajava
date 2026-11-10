package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.authorization.policy.PolicyGraphWriter.AppliedGraph;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.guardians.GuardianPolicyProjector;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.teaching.PersonalTeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

    public static final String ERROR_NO_RECORDED_PROJECTION = "NO_RECORDED_PROJECTION";
    public static final String ERROR_DESIRED_SOURCE_MISMATCH = "DESIRED_SOURCE_MISMATCH";
    public static final String ERROR_DEPENDENCY_NOT_READY = "DEPENDENCY_NOT_READY";
    public static final String ERROR_REVISION_SUPERSEDED = "REVISION_SUPERSEDED";

    private static final RowMapper<PartitionRow> PARTITION_MAPPER =
            (rs, _) ->
                    new PartitionRow(
                            rs.getObject("id", UUID.class),
                            rs.getLong("desired_revision"),
                            rs.getLong("applied_revision"),
                            AuthorizationPartition.State.valueOf(rs.getString("state")));
    private static final RowMapper<ProjectionRow> PROJECTION_MAPPER =
            (rs, _) ->
                    new ProjectionRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("partition_id", UUID.class),
                            rs.getString("target_key"),
                            rs.getString("keycloak_resource_id"),
                            stringList(rs, "policy_names"),
                            uuidList(rs, "dependency_partition_ids"),
                            rs.getString("desired_hash"),
                            rs.getString("applied_hash"),
                            AuthorizationProjection.State.valueOf(rs.getString("state")));

    private final JdbcTemplate jdbc;
    private final PolicyGraphWriter writer;
    private final KeycloakProvisioningClient directory;
    private final CenterPolicyProjector centerProjector;
    private final TeachingPolicyProjector teachingProjector;
    private final GuardianPolicyProjector guardianProjector;
    private final ResultPolicyProjector resultProjector;
    private final PersonalEntitlementProjector personalEntitlementProjector;
    private final PersonalTeachingPolicyProjector personalTeachingProjector;
    private final TransactionTemplate transactions;
    private final ResourceMutationCoordinator coordinator;

    public ProjectionReconciler(
            JdbcTemplate jdbc,
            PolicyGraphWriter writer,
            KeycloakProvisioningClient directory,
            CenterPolicyProjector centerProjector,
            TeachingPolicyProjector teachingProjector,
            GuardianPolicyProjector guardianProjector,
            ResultPolicyProjector resultProjector,
            PersonalEntitlementProjector personalEntitlementProjector,
            PersonalTeachingPolicyProjector personalTeachingProjector,
            PlatformTransactionManager transactionManager,
            ResourceMutationCoordinator coordinator) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.directory = directory;
        this.centerProjector = centerProjector;
        this.teachingProjector = teachingProjector;
        this.guardianProjector = guardianProjector;
        this.resultProjector = resultProjector;
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

    public PartitionRow partition(UUID partitionId) {
        return jdbc.queryForObject(
                "SELECT id, desired_revision, applied_revision, state FROM authz_partition"
                        + " WHERE id = ?",
                PARTITION_MAPPER,
                partitionId);
    }

    /** Nạp nhiều partition trong một query, lập chỉ mục theo id cho các kiểm tra theo lô. */
    private Map<UUID, PartitionRow> partitionsById(Set<UUID> partitionIds) {
        Map<UUID, PartitionRow> byId = new HashMap<>();
        if (partitionIds.isEmpty()) {
            return byId;
        }
        for (PartitionRow row :
                jdbc.query(
                        "SELECT id, desired_revision, applied_revision, state"
                                + " FROM authz_partition WHERE id = ANY(?)",
                        ps ->
                                ps.setArray(
                                        1,
                                        ps.getConnection()
                                                .createArrayOf(
                                                        "uuid",
                                                        partitionIds.toArray(new UUID[0]))),
                        PARTITION_MAPPER)) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    public List<ProjectionRow> projectionsOf(UUID partitionId) {
        return jdbc.query(
                "SELECT id, partition_id, target_key, keycloak_resource_id, policy_names,"
                        + " dependency_partition_ids, desired_hash, applied_hash, state"
                        + " FROM authz_projection WHERE partition_id = ? ORDER BY target_key",
                PROJECTION_MAPPER,
                partitionId);
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
        // Group của workspace là tiền đề mà policy tham chiếu: writer chỉ lấy id của group đã
        // tồn tại, nên phải đối chiếu membership (tạo group qua ensureRealmGroup khi có member
        // ACTIVE) TRƯỚC khi áp bundle; mọi descriptor trong bundle đều có ít nhất một member
        // ACTIVE nên group của nó luôn được tạo ở bước này. Đường eligibility giữ sau apply
        // như cũ vì group đó đã tồn tại sẵn từ fixture.
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
        List<Map<String, Object>> accounts =
                jdbc.queryForList(
                        "SELECT u.keycloak_sub, u.origin, u.email_verified FROM authz_partition p"
                                + " JOIN user_account u ON 'account:' || u.id = p.owner_key"
                                + " WHERE p.id = ? AND p.kind = 'ACCOUNT'",
                        partitionId);
        if (accounts.isEmpty()) {
            return;
        }
        Map<String, Object> account = accounts.getFirst();
        String subject =
                Objects.requireNonNull((String) account.get("keycloak_sub"), "keycloak_sub");
        boolean eligible =
                "CENTER_ISSUED".equals(account.get("origin"))
                        || Boolean.TRUE.equals(account.get("email_verified"));
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
        List<UUID> centers =
                jdbc.queryForList(
                        "SELECT w.id FROM authz_partition p"
                                + " JOIN workspace w ON 'workspace:' || w.id = p.owner_key"
                                + " WHERE p.id = ? AND p.kind = 'WORKSPACE' AND w.kind = 'CENTER'",
                        UUID.class,
                        partitionId);
        if (!centers.isEmpty()) {
            centerProjector.syncGroupMemberships(centers.getFirst());
            // Cùng một vòng đối chiếu: group nguồn giảng dạy (giáo viên/chủ nhiệm/học sinh theo
            // môn–khóa) cũng phải khớp membership hiện hành trước khi writer áp bundle.
            teachingProjector.syncGroupMemberships(centers.getFirst());
            // Và guardian group theo student: phép chiếu duy nhất biến một guardian link đã
            // duyệt thành tư cách thành viên group mà policy của kết quả tham chiếu.
            guardianProjector.syncGroupMemberships(centers.getFirst());
        }
    }

    /** Fence một partition và các projection của nó; dùng trước khi sửa drift ngoài hệ thống. */
    public void fencePartition(UUID partitionId) {
        transactions.executeWithoutResult(_ -> {
            jdbc.update(
                    "UPDATE authz_partition SET state = 'PENDING', updated_at = now()"
                            + " WHERE id = ? AND state = 'READY'",
                    partitionId);
            jdbc.update(
                    "UPDATE authz_projection SET state = 'PENDING'"
                            + " WHERE partition_id = ? AND state = 'READY'",
                    partitionId);
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
        try {
            reconcilePartition(partitionId);
            return true;
        } catch (ReconciliationException
                | RestClientException
                | DataAccessException
                | IllegalStateException
                | OAuth2AuthorizationException ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Partition {} did not reconcile: {}", partitionId, ex.toString());
            }
            return false;
        }
    }

    private void publish(PartitionRow snapshot, List<Outcome> outcomes) {
        transactions.executeWithoutResult(_ -> {
            PartitionRow current =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id, desired_revision, applied_revision, state"
                                            + " FROM authz_partition WHERE id = ? FOR UPDATE",
                                    PARTITION_MAPPER,
                                    snapshot.id()),
                            "Authorization partition missing during reconciliation: "
                                    + snapshot.id());
            if (current.desiredRevision() != snapshot.desiredRevision()) {
                throw new ReconciliationException(
                        ERROR_REVISION_SUPERSEDED,
                        "Desired revision changed during reconciliation");
            }
            for (Outcome outcome : outcomes) {
                ProjectionRow projection = outcome.projection();
                if (outcome.resolved() instanceof Resolved.Retract
                        || outcome.resolved() instanceof Resolved.RetractBundle) {
                    jdbc.update("DELETE FROM authz_projection WHERE id = ?", projection.id());
                } else {
                    AppliedGraph applied = Objects.requireNonNull(outcome.applied());
                    jdbc.update(
                            "UPDATE authz_projection SET applied_hash = desired_hash,"
                                    + " keycloak_resource_id = ?, policy_names = ?,"
                                    + " state = 'READY' WHERE id = ?",
                            ps -> {
                                ps.setString(1, applied.resourceId());
                                ps.setArray(
                                        2,
                                        ps.getConnection()
                                                .createArrayOf(
                                                        "text",
                                                        applied.policyNames()
                                                                .toArray(new String[0])));
                                ps.setObject(3, projection.id());
                            });
                }
            }
            jdbc.update(
                    "UPDATE authz_partition SET applied_revision = ?, state = 'READY',"
                            + " updated_at = now() WHERE id = ?",
                    snapshot.desiredRevision(),
                    snapshot.id());
        });
    }

    private Resolved resolve(ProjectionRow projection) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(projection.targetKey())
                        .orElseThrow(() ->
                                new ReconciliationException(
                                        ERROR_DESIRED_SOURCE_MISMATCH,
                                        "Target key matches no fixed template: "
                                                + projection.targetKey()));
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
        UUID workspaceId = UUID.fromString(projection.targetKey().substring("workspace:".length()));
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
        UUID workspaceId = UUID.fromString(targetKey.substring("workspace:".length()));
        return !jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ? AND kind = 'CENTER'",
                        UUID.class,
                        workspaceId)
                .isEmpty();
    }

    private boolean isPersonalWorkspace(String targetKey) {
        UUID workspaceId = UUID.fromString(targetKey.substring("workspace:".length()));
        return !jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        workspaceId)
                .isEmpty();
    }

    /**
     * Bundle của một PERSONAL workspace: cùng hình dạng với bundle của center — desired hash đã ghi
     * phải khớp đúng tập descriptor mà {@link PersonalEntitlementProjector} dựng lại từ dữ kiện
     * nguồn hiện hành cho mọi scope, với scope danh nghĩa ENTER của projector.
     */
    private Resolved resolvePersonalBundle(ProjectionRow projection) {
        UUID workspaceId = UUID.fromString(projection.targetKey().substring("workspace:".length()));
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
                                        "Target key matches no fixed template: "
                                                + projection.targetKey()));
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
        return targetKey.substring(0, "workspace:".length() + 36);
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
                                        "Target key matches no fixed template: "
                                                + projection.targetKey()));
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
     * ResultPolicyProjector} dựng lại từ dữ kiện nguồn hiện hành, với scope danh nghĩa READ của
     * projector.
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

    private List<String> reconstructSources(
            String targetKey, NativePolicyTemplates.TargetType type, String scope) {
        if (type == NativePolicyTemplates.TargetType.PROFILE) {
            UUID userId = UUID.fromString(targetKey.substring("account:".length()));
            return jdbc.query(
                    "SELECT keycloak_sub FROM user_account WHERE id = ?"
                            + " AND (origin = 'CENTER_ISSUED' OR email_verified)",
                    (rs, _) -> eligibilityDescriptor(userId, rs.getString(1), targetKey, scope),
                    userId);
        }
        if (type != NativePolicyTemplates.TargetType.WORKSPACE) {
            return List.of();
        }
        UUID workspaceId = UUID.fromString(targetKey.substring("workspace:".length()));
        Optional<String> owner =
                jdbc
                        .query(
                                "SELECT u.id, u.keycloak_sub FROM workspace w"
                                        + " JOIN user_account u ON u.id = w.owner_user_id"
                                        + " WHERE w.id = ? AND w.kind = 'PERSONAL'",
                                (rs, _) ->
                                        personalOwnerDescriptor(
                                                workspaceId,
                                                rs.getObject(1, UUID.class),
                                                rs.getString(2),
                                                targetKey,
                                                scope),
                                workspaceId)
                        .stream()
                        .findFirst();
        return owner.map(List::of).orElse(List.of());
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
            return sha256(retractDescriptor(targetKey, scope));
        }
        return sha256(String.join("\n", sorted));
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
                                        "Target key matches no fixed template: " + targetKey));
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

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] raw = (Object[]) array.getArray();
        List<String> out = new ArrayList<>(raw.length);
        for (Object item : raw) {
            out.add(String.valueOf(item));
        }
        return List.copyOf(out);
    }

    private static List<UUID> uuidList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] raw = (Object[]) array.getArray();
        List<UUID> out = new ArrayList<>(raw.length);
        for (Object item : raw) {
            out.add(item instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(item)));
        }
        return List.copyOf(out);
    }
}
