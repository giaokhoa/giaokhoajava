import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { SampleActionsPanel } from "@/features/resources/sample-actions-panel";

export const metadata: Metadata = { title: "Kiểm chứng quyền tài nguyên" };

export default async function ReviewPage() {
  await connection(); // Flag server-only phải được đánh giá tại request, không cache trang review.
  if (process.env.NODE_ENV === "production" && process.env.GIAOKHOA_REVIEW_PANEL_ENABLED !== "true") {
    notFound();
  }
  return <SampleActionsPanel />;
}
