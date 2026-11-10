import type { Metadata } from "next";
import { PersonalContent } from "./personal-content";

export const metadata: Metadata = { title: "Không gian cá nhân" };

export default function PersonalPage() {
  return <PersonalContent />;
}
