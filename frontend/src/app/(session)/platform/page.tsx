import type { Metadata } from "next";
import { PlatformCentersContent } from "@/features/tenancy/center-administration";

export const metadata: Metadata = { title: "Quản trị nền tảng" };

export default function PlatformPage() {
  return <PlatformCentersContent />;
}
