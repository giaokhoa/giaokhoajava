import { SessionProvider } from "../../../../features/identity/session-provider";
import { CenterClassesContent } from "../../../../features/teaching/class-management";

export const dynamic = "force-dynamic";

// Next 16: params của dynamic route là Promise, trang server phải await trước khi dùng.
export default async function CenterClassesPage({
  params,
}: {
  params: Promise<{ workspaceId: string }>;
}) {
  const { workspaceId } = await params;
  return (
    <SessionProvider>
      <CenterClassesContent workspaceId={workspaceId} />
    </SessionProvider>
  );
}
