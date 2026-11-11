package com.giaokhoa.guardians;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.guardians.model.GuardianLink;
import com.giaokhoa.guardians.repository.GuardianAuthorizationPartitionRepository;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.guardians.repository.GuardianUserAccountRepository;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.MembershipService;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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

    private final GuardianLinkRepository links;
    private final GuardianUserAccountRepository userAccounts;
    private final GuardianAuthorizationPartitionRepository partitions;
    private final MembershipService memberships;
    private final WorkspaceTransaction workspaceTransaction;
    private final AuthorizationProvisioningClient directory;
    private final AuthorizationOperationService operations;
    private final CenterPolicyProjector centerProjector;
    private final ResultPolicyProjector resultProjector;
    private final TransactionTemplate transactions;

    public GuardianPolicyProjector(
            GuardianLinkRepository links,
            GuardianUserAccountRepository userAccounts,
            GuardianAuthorizationPartitionRepository partitions,
            MembershipService memberships,
            WorkspaceTransaction workspaceTransaction,
            AuthorizationProvisioningClient directory,
            AuthorizationOperationService operations,
            CenterPolicyProjector centerProjector,
            ResultPolicyProjector resultProjector,
            PlatformTransactionManager transactionManager) {
        this.links = links;
        this.userAccounts = userAccounts;
        this.partitions = partitions;
        this.memberships = memberships;
        this.workspaceTransaction = workspaceTransaction;
        this.directory = directory;
        this.operations = operations;
        this.centerProjector = centerProjector;
        this.resultProjector = resultProjector;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Ghi operation cho một thay đổi vòng đời guardian link vừa được ghi trong cùng transaction của
     * service: bump desired revision của WORKSPACE partition và ghi lại bundle hiện hành của
     * workspace target — bundle không đổi vì link không phải nguồn của scope workspace, nhưng
     * revision mới buộc một vòng reconcile, và chính vòng đó đồng bộ membership của guardian group
     * trước khi partition publish READY. Cùng operation ghi lại cả bundle hiện hành của mọi kết quả
     * của workspace: nguồn {@code read.published} của một kết quả chỉ tồn tại khi còn liên kết
     * APPROVED, nên duyệt/gỡ link đổi bundle của kết quả đó; ghi lại cùng lúc để desired hash của
     * nó luôn khớp dữ kiện nguồn hiện hành — nếu không, vòng reconcile của operation này sẽ lật
     * projection kết quả sang PENDING rồi đối chiếu thất bại với hash cũ ({@code
     * DESIRED_SOURCE_MISMATCH}). Replay cùng idempotency key và payload do cơ chế operation dùng
     * chung xử lý.
     */
    public AuthorizationOperation recordGuardianChange(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        List<UUID> partitionIds =
                partitions
                        .findByKindAndOwnerKey(
                                AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId)
                        .stream()
                        .map(AuthorizationPartition::getId)
                        .toList();
        if (partitionIds.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace authorization partition is missing: " + workspaceId);
        }
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        projections.add(
                new AuthorizationOperationService.DesiredProjection(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                        centerProjector.bundleDescriptors(workspaceId),
                        List.of()));
        projections.addAll(resultProjector.allBundleProjections(workspaceId));
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partitionIds.getFirst(), List.copyOf(projections))));
    }

    /**
     * Đưa membership thật trong các guardian group của workspace về đúng dữ kiện nguồn: mọi parent
     * từng có link ở workspace này thuộc đúng các group mà link APPROVED hiện hành, membership
     * ACTIVE và role tiền đề hiện hành của cả hai phía suy ra, không thuộc guardian group nào khác
     * của workspace. Chỉ ghi khi live đang lệch, để reconcile không đổi gì vẫn là kiểm chứng đọc.
     * Tự đặt context workspace vì phía reconcile không có transaction bao quanh sẵn và bảng
     * guardian_link nằm trong schema tenant của đúng workspace đó.
     */
    public void syncGroupMemberships(UUID workspaceId) {
        transactions.executeWithoutResult(_ -> {
            workspaceTransaction.applyContext(workspaceId);
            List<UUID> studentIds = links.findDistinctStudentUserIds(workspaceId);
            if (studentIds.isEmpty()) {
                return;
            }
            Set<String> groupPaths =
                    Set.copyOf(
                            studentIds.stream()
                                    .map(studentId ->
                                            NativePolicyTemplates.guardiansGroup(
                                                    workspaceId, studentId))
                                    .toList());
            for (UUID parentUserId : links.findDistinctParentUserIds(workspaceId)) {
                // Join với directory ở tầng ứng dụng: parent không còn tài khoản thì không có
                // subject nào để đồng bộ, đúng như join nội bộ trước đây loại hàng đó.
                var account = userAccounts.findById(parentUserId).orElse(null);
                if (account == null) {
                    continue;
                }
                String subject = Objects.requireNonNull(account.getKeycloakSub(), "keycloak_sub");
                Set<String> desired = desiredGroups(workspaceId, parentUserId);
                // Một request nhóm cho mỗi phụ huynh, thay vì N_student lần cùng GET.
                Set<String> current = directory.realmGroupPaths(subject);
                for (String groupPath : groupPaths) {
                    boolean shouldBeMember = desired.contains(groupPath);
                    if (current.contains(groupPath) != shouldBeMember) {
                        directory.setRealmGroupMembership(subject, groupPath, shouldBeMember);
                    }
                }
            }
        });
    }

    private Set<String> desiredGroups(UUID workspaceId, UUID parentUserId) {
        Set<String> groups = new TreeSet<>();
        if (!memberships
                .activeRoles(parentUserId, workspaceId)
                .contains(MembershipRole.Role.PARENT)) {
            return groups;
        }
        links
                .findByWorkspaceIdAndParentUserIdAndState(
                        workspaceId, parentUserId, GuardianLink.APPROVED)
                .stream()
                .map(GuardianLink::getStudentUserId)
                .distinct()
                .filter(studentId ->
                        memberships
                                .activeRoles(studentId, workspaceId)
                                .contains(MembershipRole.Role.STUDENT))
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
}
