package com.giaokhoa.authorization.projection;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.teaching.PersonalTeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ghi operation cho một lần thu hồi (hoặc cấp) nguồn quyền, kèm toàn bộ dependency closure của nó
 * trong cùng revision. {@link com.giaokhoa.authorization.projection.ProjectionReconciler} đối chiếu
 * desired hash của MỌI projection trong partition khi reconcile, nên một mutation chỉ được ghi lại
 * bundle của target nó chạm trực tiếp sẽ làm operation chết {@code DESIRED_SOURCE_MISMATCH} ngay
 * khi một projection khác của cùng workspace đổi nguồn theo — đúng lỗi B67: gỡ ghi danh chỉ ghi
 * bundle môn–khóa trong khi bundle kết quả mất nguồn học sinh của {@code read.published}. Vì vậy
 * mọi đường ghi ở đây luôn ghi kèm bundle kết quả của toàn bộ workspace: nguồn của kết quả là phân
 * công giảng dạy, ghi danh, liên kết phụ huynh đã duyệt và membership/role tiền đề của chúng, tức
 * đúng các nguồn mà một lần thu hồi role/membership/lớp/ phân công/ghi danh/phụ huynh có thể đổi;
 * bundle nào không đổi thì hash khớp nguyên nên chỉ là ghi lại, không đổi graph.
 *
 * <p>Closure này chỉ bao phủ các bundle thuộc WORKSPACE partition của workspace đó. Mọi nguồn của
 * sáu nhóm đều là dòng bền trong workspace và mọi projection bị ảnh hưởng đều thuộc cùng partition
 * đó, nên operation chỉ cần bump+fence đúng partition này; fence của protected request luôn chứa
 * partition workspace (xem {@link com.giaokhoa.authorization.fence.DependencyManifestResolver}) nên
 * request sau thu hồi không thể quyết trên revision cũ. Partition ACCOUNT không bị chạm vì
 * projection eligibility của nó chỉ phụ thuộc danh tính, không phụ thuộc membership/giảng dạy.
 * Không ghi gì thêm vào {@link com.giaokhoa.authorization.fence.DependencyManifestResolver}:
 * closure truyền dẫn của nó chỉ có ý nghĩa với phụ thuộc khác partition, mà ở đây không có.
 *
 * <p>Coordinator không tự quyết quyền và không chạm {@code ProjectionReconciler} (file do owner #68
 * giữ): nó chỉ soạn danh sách desired projection rồi ghi qua {@code AuthorizationOperationService}
 * trong cùng transaction của service đã đổi dữ kiện nguồn — đúng giao thức mutation của hợp đồng
 * authorization mục 6 (ghi desired state + tăng revision + PENDING trong một transaction, commit
 * trước khi gọi mạng). Đường ghi guardian giữ nguyên ở {@code GuardianPolicyProjector} vì đã ghi
 * kèm toàn bộ bundle kết quả theo mẫu B58; đường ghi gói giữ nguyên ở {@code SubscriptionService}
 * vì bundle kết quả không có gate kỳ gói.
 */
@Component
public class SourceRevocationCoordinator {
    private final JdbcTemplate jdbc;
    private final AuthorizationOperationService operations;
    private final CenterPolicyProjector centerProjector;
    private final TeachingPolicyProjector teachingProjector;
    private final PersonalTeachingPolicyProjector personalTeachingProjector;
    private final ResultPolicyProjector resultProjector;

    public SourceRevocationCoordinator(
            JdbcTemplate jdbc,
            AuthorizationOperationService operations,
            CenterPolicyProjector centerProjector,
            TeachingPolicyProjector teachingProjector,
            PersonalTeachingPolicyProjector personalTeachingProjector,
            ResultPolicyProjector resultProjector) {
        this.jdbc = jdbc;
        this.operations = operations;
        this.centerProjector = centerProjector;
        this.teachingProjector = teachingProjector;
        this.personalTeachingProjector = personalTeachingProjector;
        this.resultProjector = resultProjector;
    }

    /**
     * Ghi operation cho một thay đổi giảng dạy vừa được ghi trong cùng transaction của service: ghi
     * danh/phân công/chủ nhiệm (cấp hoặc thu hồi) và thu hồi lớp/khóa/môn–khóa/roster qua route.
     * Ghi lại bundle hiện hành của các target giảng dạy bị ảnh hưởng, kèm toàn bộ bundle kết quả
     * của workspace — phân công/ghi danh là nguồn của bundle kết quả nên gỡ chúng mà không ghi lại
     * bundle kết quả sẽ làm reconcile chết {@code DESIRED_SOURCE_MISMATCH} (B67). Replay cùng
     * idempotency key và payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác
     * payload là 409 — cả hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordTeachingRevocation(
            UUID initiatorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            List<String> teachingTargetKeys) {
        boolean personal = isPersonalWorkspace(workspaceId);
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : teachingTargetKeys) {
            projections.add(
                    new AuthorizationOperationService.DesiredProjection(
                            targetKey,
                            TeachingPolicyProjector.bundleScope(),
                            personal
                                    ? personalTeachingProjector.bundleDescriptors(targetKey)
                                    : teachingProjector.bundleDescriptors(targetKey),
                            List.of()));
        }
        projections.addAll(resultProjector.allBundleProjections(workspaceId));
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                workspacePartition(workspaceId), List.copyOf(projections))));
    }

    /**
     * Ghi operation cho một thay đổi membership/role vừa được ghi trong cùng transaction của
     * service: tạo center, đổi role, thu hồi membership, chấp nhận lời mời, cấp học sinh. Ghi lại
     * bundle workspace hiện hành, toàn bộ bundle giảng dạy (nguồn quản trị owner/manager có mặt
     * trong mọi bundle, và phép đóng nguồn phụ thuộc vừa đổi dữ kiện nguồn của các bundle đó), kèm
     * toàn bộ bundle kết quả của workspace — membership/role là tiền đề của nguồn học sinh và nguồn
     * phụ huynh trong bundle kết quả nên gỡ chúng mà không ghi lại bundle kết quả sẽ làm reconcile
     * chết {@code DESIRED_SOURCE_MISMATCH} như B67. Replay và 409 như trên.
     */
    public AuthorizationOperation recordMembershipRevocation(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        projections.add(
                new AuthorizationOperationService.DesiredProjection(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                        centerProjector.bundleDescriptors(workspaceId),
                        List.of()));
        projections.addAll(teachingProjector.allBundleProjections(workspaceId));
        projections.addAll(resultProjector.allBundleProjections(workspaceId));
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                workspacePartition(workspaceId), List.copyOf(projections))));
    }

    /** Bundle giảng dạy của personal do projector riêng của personal dựng, tách khỏi center. */
    private boolean isPersonalWorkspace(UUID workspaceId) {
        return !jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        workspaceId)
                .isEmpty();
    }

    private UUID workspacePartition(UUID workspaceId) {
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
        return partitions.getFirst();
    }
}
