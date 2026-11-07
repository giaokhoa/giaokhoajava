package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Đường ghi projection cho các thay đổi bắt nguồn từ membership: invite, accept và cấp học sinh.
 * Mọi thay đổi dữ kiện nguồn về membership đều phải kết thúc bằng một authorization operation ghi
 * lại toàn bộ bundle desired của workspace tại dữ kiện nguồn hiện hành, vì bundle là thứ {@code
 * ProjectionReconciler} đối chiếu để áp native graph và đồng bộ membership thật vào các realm group
 * nguồn; không có operation thì thay đổi membership không bao giờ tới được Keycloak và cũng không
 * có biên nhận để endpoint trả 202/poll. Cách ghi dùng chung một DesiredProjection trên target
 * {@code workspace:{id}} với scope danh nghĩa {@code enter}, đúng mẫu đường ghi bundle của
 * CenterService: hash desired là hash của cả bundle, không phải của một scope riêng lẻ.
 *
 * <p>Đây cũng là cơ chế giữ ràng buộc nguồn phụ thuộc của membership: gỡ role hay thu hồi
 * membership làm bundle và group nguồn thay đổi trong cùng revision, còn closure phụ thuộc của
 * operation fence mọi projection đang dựa vào các partition đã đổi trước khi mở lại — nguồn phụ
 * thuộc (phân công, ghi danh ở các task sau) không sống sót sau khi role tiền đề của nó bị gỡ, và
 * các role còn lại của cùng người vẫn giữ nguồn độc lập của chúng vì bundle tính lại từ toàn bộ dữ
 * kiện nguồn hiện hành chứ không trừ dần.
 */
@Component
public class MembershipPolicyProjector {
    private final JdbcTemplate jdbc;
    private final CenterPolicyProjector centerProjector;
    private final AuthorizationOperationService operations;

    public MembershipPolicyProjector(
            JdbcTemplate jdbc,
            CenterPolicyProjector centerProjector,
            AuthorizationOperationService operations) {
        this.jdbc = jdbc;
        this.centerProjector = centerProjector;
        this.operations = operations;
    }

    /**
     * Ghi operation cho một thay đổi membership vừa được ghi trong cùng transaction: bump desired
     * revision của WORKSPACE partition của workspace, fence nó và lưu bundle hiện hành. Replay cùng
     * idempotency key và payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác
     * payload là 409 — cả hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordMembershipChange(
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
        var projection =
                new AuthorizationOperationService.DesiredProjection(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                        centerProjector.bundleDescriptors(workspaceId),
                        List.of());
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partitions.getFirst(), List.of(projection))));
    }
}
