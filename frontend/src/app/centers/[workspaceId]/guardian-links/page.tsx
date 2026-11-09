import { SessionProvider } from "../../../../features/identity/session-provider";
import { GuardianLinksContent } from "../../../../features/guardians/guardian-link-review";

export const dynamic = "force-dynamic";

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterGuardianLinksPage({
  params,
}: {
  params: Promise<{ workspaceId: string }>;
}) {
  const { workspaceId } = await params;
  return (
    <SessionProvider>
      <GuardianLinksContent workspaceId={workspaceId} />
    </SessionProvider>
  );
}
