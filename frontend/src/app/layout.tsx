import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Giáo Khoa",
  description: "Không gian học tập dành cho cá nhân và trung tâm.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
