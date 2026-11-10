import type { Metadata } from "next";
import { PlatformRecoveryContent } from "./platform-recovery-content";

export const metadata: Metadata = { title: "Yêu cầu khôi phục" };

export default function PlatformRecoveryPage() {
  return <PlatformRecoveryContent />;
}
