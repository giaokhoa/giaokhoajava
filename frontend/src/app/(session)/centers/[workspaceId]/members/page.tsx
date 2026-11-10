import type { Metadata } from "next";
import { CenterMembersContent } from "@/features/tenancy/center-administration";

export const metadata: Metadata = { title: "Thành viên trung tâm" };

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterMembersPage({
  params,
}: {
  params: Promise<{ workspaceId: string }>;
}) {
  const { workspaceId } = await params;
  return (
    /*
      key theo workspace: đổi trung tâm thì component là instance mới nên toàn
      bộ state riêng (panel thao tác, mật khẩu tạm, draft, lựa chọn) reset,
      không bao giờ hiện dữ kiện của trung tâm trước.
    */
    <CenterMembersContent key={workspaceId} workspaceId={workspaceId} />
  );
}
