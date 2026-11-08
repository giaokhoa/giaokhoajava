package com.giaokhoa.guardians;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của liên kết phụ huynh: nguồn sự thật duy nhất về cách một guardian link đã duyệt trở
 * thành nguồn quyền native — group cố định {@code /gk/workspaces/{wid}/students/{sid}/guardians}
 * của đúng student trong đúng center, không có liên kết gia đình toàn cục nào.
 *
 * <p>Hai nửa của phép chiếu: (1) descriptor canonical {@code GUARDIAN_GROUP} mà read side kết quả
 * (task phía sau) gắn làm nguồn của scope {@code read.published} trên từng result target, kèm
 * {@link #gatesFor} để reconciliation dựng lại đúng gate group của nó; (2) membership thật của
 * group trong realm, được {@link #syncGroupMemberships} đối chiếu từ dữ kiện nguồn mỗi khi
 * partition của workspace được reconcile — chỉ parent có link APPROVED đồng thời còn membership
 * ACTIVE kèm role PARENT hiện hành, và student còn membership ACTIVE kèm role STUDENT hiện hành,
 * mới ở trong group. Mọi thay đổi vòng đời link ghi một authorization operation trên WORKSPACE
 * partition qua {@link #recordGuardianChange}: operation chỉ SUCCEEDED sau khi partition đã
 * reconcile tại revision mới, tức group đã khớp dữ kiện nguồn — kể cả đường gỡ, nên cùng một phiên
 * cũ bị chặn ngay ở lần đọc sau khi thu hồi hoàn tất.
 */
@Component
public class GuardianPolicyProjector {

    private static final String GROUP_DESCRIPTOR = "GUARDIAN_GROUP";

    private final JdbcTemplate jdbc;
    private final KeycloakProvisioningClient directory;
    private final AuthorizationOperationService operations;
    private final CenterPolicyProjector centerProjector;
    private final TransactionTemplate transactions;

    public GuardianPolicyProjector(
            JdbcTemplate jdbc,
            KeycloakProvisioningClient directory,
            AuthorizationOperationService operations,
            CenterPolicyProjector centerProjector,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.operations = operations;
        this.centerProjector = centerProjector;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Ghi operation cho một thay đổi vòng đời guardian link vừa được ghi trong cùng transaction của
     * service: bump desired revision của WORKSPACE partition và ghi lại bundle hiện hành của
     * workspace target — bundle không đổi vì link không phải nguồn của scope workspace, nhưng
     * revision mới buộc một vòng reconcile, và chính vòng đó đồng bộ membership của guardian group
     * trước khi partition publish READY. Replay cùng idempotency key và payload do cơ chế operation
     * dùng chung xử lý.
     */
    public AuthorizationOperation recordGuardianChange(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace authorization partition is missing: " + workspaceId);
        }
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partitions.getFirst(),
                                List.of(
                                        new AuthorizationOperationService.DesiredProjection(
                                                NativePolicyTemplates.workspaceKey(workspaceId),
                                                PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                                                centerProjector.bundleDescriptors(workspaceId),
                                                List.of())))));
    }

    /**
     * Đưa membership thật trong các guardian group của workspace về đúng dữ kiện nguồn: mọi parent
     * từng có link ở workspace này thuộc đúng các group mà link APPROVED hiện hành, membership
     * ACTIVE và role tiền đề hiện hành của cả hai phía suy ra, không thuộc guardian group nào khác
     * của workspace. Chỉ ghi khi live đang lệch, để reconcile không đổi gì vẫn là kiểm chứng đọc.
     * Tự đặt context workspace vì phía reconcile không có transaction bao quanh sẵn và bảng
     * guardian_link nằm dưới RLS theo đúng context đó.
     */
    public void syncGroupMemberships(UUID workspaceId) {
        transactions.executeWithoutResult(_ -> {
            applyContext(workspaceId);
            List<UUID> studentIds =
                    jdbc.queryForList(
                            "SELECT DISTINCT student_user_id FROM guardian_link"
                                    + " WHERE workspace_id = ?",
                            UUID.class,
                            workspaceId);
            if (studentIds.isEmpty()) {
                return;
            }
            List<String> groupPaths =
                    studentIds.stream()
                            .map(studentId ->
                                    NativePolicyTemplates.guardiansGroup(workspaceId, studentId))
                            .toList();
            List<Map<String, Object>> parents =
                    jdbc.queryForList(
                            "SELECT DISTINCT gl.parent_user_id, u.keycloak_sub"
                                    + " FROM guardian_link gl"
                                    + " JOIN user_account u ON u.id = gl.parent_user_id"
                                    + " WHERE gl.workspace_id = ?",
                            workspaceId);
            for (Map<String, Object> parent : parents) {
                UUID parentUserId =
                        (UUID)
                                Objects.requireNonNull(
                                        parent.get("parent_user_id"), "parent_user_id");
                String subject =
                        Objects.requireNonNull((String) parent.get("keycloak_sub"), "keycloak_sub");
                Set<String> desired = desiredGroups(workspaceId, parentUserId);
                for (String groupPath : groupPaths) {
                    boolean shouldBeMember = desired.contains(groupPath);
                    if (directory.isRealmGroupMember(subject, groupPath) != shouldBeMember) {
                        directory.setRealmGroupMembership(subject, groupPath, shouldBeMember);
                    }
                }
            }
        });
    }

    private Set<String> desiredGroups(UUID workspaceId, UUID parentUserId) {
        Set<String> groups = new TreeSet<>();
        jdbc.queryForList(
                        "SELECT gl.student_user_id FROM guardian_link gl"
                                + " WHERE gl.workspace_id = ? AND gl.parent_user_id = ?"
                                + " AND gl.state = 'APPROVED'"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = gl.workspace_id"
                                + " AND m.user_id = gl.parent_user_id AND m.state = 'ACTIVE'"
                                + " AND r.role = 'PARENT')"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = gl.workspace_id"
                                + " AND m.user_id = gl.student_user_id AND m.state = 'ACTIVE'"
                                + " AND r.role = 'STUDENT')",
                        UUID.class,
                        workspaceId,
                        parentUserId)
                .forEach(studentId ->
                        groups.add(NativePolicyTemplates.guardiansGroup(workspaceId, studentId)));
        return groups;
    }

    /** Canonical descriptor của nguồn guardian group cho một target và scope. */
    public static String guardianGroupDescriptor(
            UUID workspaceId, UUID studentUserId, String targetKey, String scope) {
        return GROUP_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|group="
                + NativePolicyTemplates.guardiansGroup(workspaceId, studentUserId)
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }

    /**
     * Gate native của một canonical descriptor guardian: chỉ membership trong guardian group của
     * đúng student — group policy tự nó đã bị khóa bởi membership/role hiện hành qua phép đồng bộ ở
     * trên. Dùng bởi reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2 || !GROUP_DESCRIPTOR.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported guardian descriptor: " + descriptor);
        }
        String groupPath = null;
        for (String part : parts) {
            if (part.startsWith("group=")) {
                groupPath = part.substring("group=".length());
            }
        }
        if (groupPath == null || groupPath.isBlank()) {
            throw new IllegalArgumentException("Guardian descriptor has no group: " + descriptor);
        }
        return List.of(new PolicyGraph.Gate.GroupMembership(groupPath));
    }

    private void applyContext(UUID workspaceId) {
        jdbc.queryForObject(
                "SELECT set_config('app.workspace_id', ?, true)",
                String.class,
                workspaceId.toString());
    }
}
