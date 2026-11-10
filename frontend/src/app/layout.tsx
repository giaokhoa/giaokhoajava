import type { Metadata } from "next";
import { SessionProvider } from "../features/identity/session-provider";
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
      <body>
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  );
}
