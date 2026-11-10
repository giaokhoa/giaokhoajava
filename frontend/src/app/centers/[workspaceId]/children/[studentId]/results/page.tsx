import { ChildResultsContent } from "../../../../../../features/guardians/published-results";

export const dynamic = "force-dynamic";

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterChildResultsPage({
  params,
}: {
  params: Promise<{ workspaceId: string; studentId: string }>;
}) {
  const { workspaceId, studentId } = await params;
  return <ChildResultsContent workspaceId={workspaceId} studentId={studentId} />;
}
