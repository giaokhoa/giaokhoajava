import type { Metadata } from "next";
import { PersonalClassesContent } from "./personal-classes-content";

export const metadata: Metadata = { title: "Lớp cá nhân" };

export default function PersonalClassesPage() {
  return <PersonalClassesContent />;
}
