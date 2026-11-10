import type { Metadata } from "next";
import { CenterClassesContent } from "@/features/teaching/class-management";

export const metadata: Metadata = { title: "Lớp, khóa và phân công" };

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterClassesPage({
  params,
}: {
  params: Promise<{ workspaceId: string }>;
}) {
  const { workspaceId } = await params;
  return (
    /*
      key theo workspace: đổi trung tâm thì component là instance mới nên toàn
      bộ state riêng (panel thao tác, draft, lựa chọn) reset, không bao giờ
      hiện dữ kiện của trung tâm trước.
    */
    <CenterClassesContent key={workspaceId} workspaceId={workspaceId} />
  );
}
