import type { WorkspaceView } from "@/features/identity/workspace-switcher";

// Trạng thái gói suy từ WorkspaceView của backend. Backend chỉ cho biết kỳ gói còn
// hiệu lực hay không (subscription.effective) kèm mốc hết hạn của kỳ đang hiệu lực,
// không phân biệt "đã từng có gói rồi hết hạn" với "chưa từng có gói": cả hai đều là
// effective=false và không có mốc hết hạn. UI vì vậy chỉ nói "không còn hiệu lực",
// không tự khẳng định "đã hết hạn" ở những nơi dữ kiện không cho phép phân biệt.
export type SubscriptionPhase = "EFFECTIVE" | "LAPSED";

export function subscriptionPhase(
  workspace: Pick<WorkspaceView, "subscription">,
): SubscriptionPhase {
  return workspace.subscription.effective ? "EFFECTIVE" : "LAPSED";
}

// Khi gói không còn hiệu lực, workspace ở chế độ bảo lưu: dữ liệu giữ nguyên, thao tác
// mở rộng (tạo/sửa học liệu, tạo lớp/khóa, thêm quan hệ, giao bài mới, mở rộng hoạt
// động) bị chặn; đọc dữ liệu còn quyền, hoàn tất bài đã giao và thu hồi vẫn dùng được.
// Trạng thái gói không phải là một role: nó không đổi roles của người dùng và không
// được hiển thị như một vai trò mới ở bất kỳ màn hình nào.
export function isExpansionBlocked(
  workspace: Pick<WorkspaceView, "subscription">,
): boolean {
  return subscriptionPhase(workspace) === "LAPSED";
}

// Câu thông báo chế độ chỉ đọc theo đúng loại workspace; null khi gói còn hiệu lực
// hoặc workspace không theo gói (hệ thống).
export function readOnlyNotice(
  workspace: Pick<WorkspaceView, "kind" | "subscription">,
): string | null {
  if (!isExpansionBlocked(workspace)) return null;
  if (workspace.kind === "CENTER") {
    return "Gói của trung tâm hiện không còn hiệu lực: dữ liệu được giữ nguyên ở chế độ"
      + " chỉ đọc — vẫn xem được dữ liệu còn quyền, vẫn hoàn tất bài đã giao và thu hồi"
      + " được; không tạo mới hay mở rộng hoạt động cho đến khi gói được kích hoạt lại.";
  }
  if (workspace.kind === "PERSONAL") {
    return "Gói cá nhân hiện không còn hiệu lực: dữ liệu được giữ nguyên ở chế độ"
      + " chỉ đọc — vẫn xem được dữ liệu còn quyền, vẫn hoàn tất bài đã giao và thu hồi"
      + " được; không tạo mới hay mở rộng hoạt động cho đến khi gói được kích hoạt lại.";
  }
  return null;
}

// Hành vi của sample action khi gói của workspace hết hạn, theo FR-042–FR-046: hoàn
// tất bài đã giao (nộp/chấm/công bố), nhận cập nhật theo quyền đã cấp, thu hồi và
// luyện tập trên tài nguyên hệ thống (không cần gói) vẫn dùng được; mọi thao tác mở
// rộng còn lại bị chặn. Action lạ mặc định thuộc nhóm bị chặn để không hứa quá quyền.
export type SampleActionExpiryBehavior = "BLOCKED_WHEN_LAPSED" | "ALLOWED_WHEN_LAPSED";

const ALLOWED_WHEN_LAPSED: ReadonlySet<string> = new Set([
  "PRACTICE_SYSTEM_VERSION",
  "RECEIVE_UPDATE",
  "REVOKE_SHARE",
  "SUBMIT_ACTIVITY",
  "GRADE_RESULT",
  "PUBLISH_RESULT",
]);

export function sampleActionExpiryBehavior(action: string): SampleActionExpiryBehavior {
  return ALLOWED_WHEN_LAPSED.has(action) ? "ALLOWED_WHEN_LAPSED" : "BLOCKED_WHEN_LAPSED";
}

const blockedReasonsByCode: Record<string, string> = {
  SUBSCRIPTION_INACTIVE:
    "Gói của workspace không còn hiệu lực nên thao tác mở rộng này bị chặn. Dữ liệu"
    + " vẫn được giữ nguyên: hoàn tất bài đã giao và thu hồi vẫn thực hiện được.",
};

// Lý do dễ hiểu cho mã lỗi chặn thao tác đã biết; mã lạ trả null để nơi gọi giữ
// nguyên thông điệp gốc thay vì dịch sai.
export function blockedActionReason(code: string | undefined): string | null {
  if (!code) return null;
  return blockedReasonsByCode[code] ?? null;
}
