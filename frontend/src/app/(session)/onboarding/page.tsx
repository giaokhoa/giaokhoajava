import type { Metadata } from "next";
import { OnboardingContent } from "./onboarding-content";

export const metadata: Metadata = { title: "Hoàn tất tài khoản" };

export default function OnboardingPage() {
  return <OnboardingContent />;
}
