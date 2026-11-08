package com.giaokhoa.guardians;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final WorkspaceTransaction workspaceTransaction;

    public GuardianRevocationContributor(
            JdbcTemplate jdbc, WorkspaceTransaction workspaceTransaction) {
        this.jdbc = jdbc;
        this.workspaceTransaction = workspaceTransaction;
    }

    /** Gỡ role PARENT: đóng link đã duyệt của parent này trong center. */
    public List<String> revokeLinksOfParent(UUID workspaceId, UUID parentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(parentUserId, "parentUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<UUID> studentIds =
                jdbc.queryForList(
                        "SELECT DISTINCT student_user_id FROM guardian_link"
                                + " WHERE workspace_id = ? AND parent_user_id = ?"
                                + " AND state = 'APPROVED'",
                        UUID.class,
                        workspaceId,
                        parentUserId);
        jdbc.update(
                "UPDATE guardian_link SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND parent_user_id = ?"
                        + " AND state = 'APPROVED'",
                workspaceId,
                parentUserId);
        return groupPaths(workspaceId, studentIds);
    }

    /** Gỡ role STUDENT: đóng link đã duyệt của học sinh này trong center. */
    public List<String> revokeLinksOfStudent(UUID workspaceId, UUID studentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(studentUserId, "studentUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<UUID> parentIds =
                jdbc.queryForList(
                        "SELECT DISTINCT parent_user_id FROM guardian_link"
                                + " WHERE workspace_id = ? AND student_user_id = ?"
                                + " AND state = 'APPROVED'",
                        UUID.class,
                        workspaceId,
                        studentUserId);
        jdbc.update(
                "UPDATE guardian_link SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now()"
                        + " WHERE workspace_id = ? AND student_user_id = ?"
                        + " AND state = 'APPROVED'",
                workspaceId,
                studentUserId);
        // Group bị ảnh hưởng là group của chính học sinh này, không phụ thuộc số parent.
        return parentIds.isEmpty()
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

    private static List<String> groupPaths(UUID workspaceId, List<UUID> studentIds) {
        return studentIds.stream()
                .map(studentId -> NativePolicyTemplates.guardiansGroup(workspaceId, studentId))
                .sorted()
                .distinct()
                .toList();
    }
}
