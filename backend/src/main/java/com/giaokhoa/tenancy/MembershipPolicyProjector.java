package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Đường ghi projection cho các thay đổi bắt nguồn từ membership: invite, accept và cấp học sinh.
 * Mọi thay đổi dữ kiện nguồn về membership đều phải kết thúc bằng một authorization operation ghi
 * lại toàn bộ bundle desired của workspace tại dữ kiện nguồn hiện hành, vì bundle là thứ {@code
 * ProjectionReconciler} đối chiếu để áp native graph và đồng bộ membership thật vào các realm group
 * nguồn; không có operation thì thay đổi membership không bao giờ tới được Keycloak và cũng không
 * có biên nhận để endpoint trả 202/poll.
 *
 * <p>Việc soạn closure (bundle workspace + toàn bộ bundle giảng dạy + toàn bộ bundle kết quả) thuộc
 * {@link SourceRevocationCoordinator}: gỡ role hay thu hồi membership làm bundle và group nguồn
 * thay đổi trong cùng revision, còn closure phụ thuộc của operation fence mọi projection đang dựa
 * vào các partition đã đổi trước khi mở lại — nguồn phụ thuộc (phân công, ghi danh ở các task sau)
 * không sống sót sau khi role tiền đề của nó bị gỡ, và các role còn lại của cùng người vẫn giữ
 * nguồn độc lập của chúng vì bundle tính lại từ toàn bộ dữ kiện nguồn hiện hành chứ không trừ dần.
 * Bundle kết quả được ghi kèm vì membership/role là tiền đề của nguồn học sinh và nguồn phụ huynh
 * trong bundle đó; thiếu nó, reconcile sẽ chết {@code DESIRED_SOURCE_MISMATCH} như B67.
 */
@Component
public class MembershipPolicyProjector {
    private final SourceRevocationCoordinator revocations;

    public MembershipPolicyProjector(SourceRevocationCoordinator revocations) {
        this.revocations = revocations;
    }

    /**
     * Ghi operation cho một thay đổi membership vừa được ghi trong cùng transaction: bump desired
     * revision của WORKSPACE partition của workspace, fence nó và lưu bundle hiện hành. Replay cùng
     * idempotency key và payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác
     * payload là 409 — cả hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordMembershipChange(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        return revocations.recordMembershipRevocation(
                initiatorUserId, workspaceId, idempotencyKey, payloadHash);
    }
}
