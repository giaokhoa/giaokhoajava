"use client";

// Ranh giới lỗi của chính root layout: thay thế toàn bộ layout khi nó lỗi nên
// phải tự mang <html>/<body> và không dùng class của globals.css (global
// styles không được kèm theo global-error theo tài liệu Next.js).
export default function GlobalError({
  retry,
}: {
  error: unknown;
  retry: () => void;
}) {
  return (
    <html lang="vi">
      <body>
        <h2>Đã xảy ra lỗi</h2>
        <p role="alert">Ứng dụng gặp lỗi không xử lý được. Thử tải lại trang.</p>
        <button type="button" onClick={() => retry()}>
          Thử lại
        </button>
      </body>
    </html>
  );
}
