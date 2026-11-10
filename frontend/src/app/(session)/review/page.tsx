import type { Metadata } from "next";
import { SampleActionsPanel } from "@/features/resources/sample-actions-panel";

export const metadata: Metadata = { title: "Kiểm chứng quyền tài nguyên" };

export default function ReviewPage() {
  return <SampleActionsPanel />;
}
