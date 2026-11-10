import { GuardianLinksContent } from "@/features/guardians/guardian-link-review";

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterGuardianLinksPage({
  params,
}: {
  params: Promise<{ workspaceId: string }>;
}) {
  const { workspaceId } = await params;
  return <GuardianLinksContent key={workspaceId} workspaceId={workspaceId} />;
}
