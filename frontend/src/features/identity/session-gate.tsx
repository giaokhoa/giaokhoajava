"use client";

import type { ReactNode } from "react";

// Cổng phiên dùng chung cho mọi trang: nội dung chỉ render khi đã đăng nhập; ba
// trạng thái còn lại hiển thị theo đúng một mẫu, chỉ khác câu giới thiệu khi ẩn danh.
export function SessionGate({
  status,
  message,
  onRetry,
  anonymousText,
  children,
}: {
  status: string;
  message?: string;
  onRetry: () => void;
  anonymousText: string;
  children: ReactNode;
}) {
  if (status === "loading") return <p role="status">Đang tải trạng thái tài khoản…</p>;
  if (status === "error") {
    return (
      <>
        <p role="alert">{message}</p>
        <button className="action-button" type="button" onClick={onRetry}>
          Thử lại
        </button>
      </>
    );
  }
  if (status === "anonymous") {
    return (
      <>
        <p className="introduction">{anonymousText}</p>
        <a className="login-link" href="/oauth2/authorization/keycloak">
          Đăng nhập
        </a>
      </>
    );
  }
  return <>{children}</>;
}
