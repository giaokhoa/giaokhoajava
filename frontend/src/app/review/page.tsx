import { SessionProvider } from "../../features/identity/session-provider";
import { SampleActionsPanel } from "../../features/resources/sample-actions-panel";

export const dynamic = "force-dynamic";

export default function ReviewPage() {
  return (
    <SessionProvider>
      <SampleActionsPanel />
    </SessionProvider>
  );
}
