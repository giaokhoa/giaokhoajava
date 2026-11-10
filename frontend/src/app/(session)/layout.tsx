import { SessionProvider } from "../../features/identity/session-provider";

// Mọi route trong nhóm đều làm việc theo phiên đăng nhập nên provider nằm ở
// layout này; trang chủ công khai ngoài nhóm không hydrate provider và khách
// ẩn danh không phát GET /api/session.
export default function SessionLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return <SessionProvider>{children}</SessionProvider>;
}
