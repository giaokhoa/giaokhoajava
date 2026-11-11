package com.giaokhoa.guardians;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.guardians.model.GuardianLink;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Đóng các liên kết phụ huynh phụ thuộc khi nguồn tiền đề của chúng bị gỡ, trong cùng transaction
 * (và do đó cùng revision của operation) với thay đổi đã gỡ tiền đề đó. Đây là phía dữ kiện nguồn
 * của bất biến hợp đồng "gỡ role/link thì đóng nguồn trong cùng revision": link APPROVED chuyển hẳn
 * sang REVOKED kèm revoked_at, giữ nguyên lịch sử; liên kết lại sau đó là hàng mới, không bao giờ
 * phục hồi hàng đã đóng ở đây.
 *
 * <p>Các điểm gỡ tiền đề gọi contributor này:
 *
 * <ul>
 *   <li>Gỡ role PARENT của một membership: đóng các link đã duyệt mà người đó là parent trong
 *       center.
 *   <li>Gỡ role STUDENT: đóng các link đã duyệt mà người đó là học sinh trong center.
 *   <li>Thu hồi membership: đóng link đã duyệt ở cả hai phía của người đó trong center.
 * </ul>
 *
 * <p>Chỉ link APPROVED mới bị đóng: REQUESTED chưa phải nguồn quyền nào (group chỉ chứa parent của
 * link APPROVED), trigger giữ lịch sử ở DB chặn đường REQUESTED → REVOKED, và thời điểm duyệt xét
 * lại tiền đề hiện hành của cả hai phía — nên request còn chờ của một parent vừa mất role chỉ nằm
 * yên, không tự mở quyền và không duyệt thêm được. Membership thật của guardian group do {@link
 * GuardianPolicyProjector} đối chiếu lại từ dữ kiện nguồn ở vòng reconcile của chính operation đã
 * đóng link, nên contributor chỉ cập nhật dữ kiện nguồn, không gọi Keycloak.
 *
 * <p>Bảng guardian_link nằm dưới RLS theo context workspace của transaction, mà các lời gọi ở tầng
 * tenancy không tự đặt context: contributor tự đặt context của đúng workspace qua {@link
 * WorkspaceTransaction} ở đầu mỗi phép đóng, trước truy vấn đầu tiên. Mỗi phép đóng trả lại các
 * guardian group path mà desired membership của chúng vừa đổi vì phép đóng.
 */
@Component
public class GuardianRevocationContributor {
    private final GuardianLinkRepository links;
    private final WorkspaceTransaction workspaceTransaction;

    public GuardianRevocationContributor(
            GuardianLinkRepository links, WorkspaceTransaction workspaceTransaction) {
        this.links = links;
        this.workspaceTransaction = workspaceTransaction;
    }

    /** Gỡ role PARENT: đóng link đã duyệt của parent này trong center. */
    public List<String> revokeLinksOfParent(UUID workspaceId, UUID parentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(parentUserId, "parentUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<GuardianLink> approved =
                links.findByWorkspaceIdAndParentUserIdAndState(
                        workspaceId, parentUserId, GuardianLink.APPROVED);
        List<UUID> studentIds =
                approved.stream().map(GuardianLink::getStudentUserId).distinct().toList();
        closeAll(approved);
        return groupPaths(workspaceId, studentIds);
    }

    /** Gỡ role STUDENT: đóng link đã duyệt của học sinh này trong center. */
    public List<String> revokeLinksOfStudent(UUID workspaceId, UUID studentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(studentUserId, "studentUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<GuardianLink> approved =
                links.findByWorkspaceIdAndStudentUserIdAndState(
                        workspaceId, studentUserId, GuardianLink.APPROVED);
        closeAll(approved);
        // Group bị ảnh hưởng là group của chính học sinh này, không phụ thuộc số parent.
        return approved.isEmpty()
                ? List.of()
                : List.of(NativePolicyTemplates.guardiansGroup(workspaceId, studentUserId));
    }

    /** Thu hồi membership: đóng link đã duyệt ở cả hai phía của người này trong center. */
    public List<String> revokeAllLinksOfMember(UUID workspaceId, UUID userId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        List<String> targets = new ArrayList<>(revokeLinksOfParent(workspaceId, userId));
        targets.addAll(revokeLinksOfStudent(workspaceId, userId));
        return List.copyOf(new TreeSet<>(targets));
    }

    /** Đóng toàn bộ link đã nạp và flush ngay để phép đóng thấy được trong cùng transaction. */
    private void closeAll(List<GuardianLink> approved) {
        if (approved.isEmpty()) {
            return;
        }
        Instant revokedAt = Instant.now();
        approved.forEach(link -> link.revoke(revokedAt));
        links.flush();
    }

    private static List<String> groupPaths(UUID workspaceId, List<UUID> studentIds) {
        return studentIds.stream()
                .map(studentId -> NativePolicyTemplates.guardiansGroup(workspaceId, studentId))
                .sorted()
                .distinct()
                .toList();
    }
}
