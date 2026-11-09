import { SessionProvider } from "../../../features/identity/session-provider";
import { PersonalClassesContent } from "./personal-classes-content";

export const dynamic = "force-dynamic";

export default function PersonalClassesPage() {
  return (
    <SessionProvider>
      <PersonalClassesContent />
    </SessionProvider>
  );
}
