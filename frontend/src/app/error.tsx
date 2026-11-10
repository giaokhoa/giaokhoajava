"use client";

// Ranh giới lỗi của toàn bộ segment dưới root layout: lỗi render không bắt
// được ở bất kỳ trang nào cũng chỉ tháo phần trang, không tháo cả cây React.
// Không render chi tiết lỗi ra UI (bản production của Next đã che chi tiết
// nhạy cảm, và repo không lộ dữ kiện nội bộ qua thông điệp lỗi).
export default function Error({
  retry,
}: {
  error: unknown;
  retry: () => void;
}) {
  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="error-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h2 id="error-title">Đã xảy ra lỗi</h2>
        <p role="alert">Trang này gặp lỗi không xử lý được. Thử tải lại phần này.</p>
        <button className="action-button" type="button" onClick={() => retry()}>
          Thử lại
        </button>
      </section>
    </main>
  );
}
