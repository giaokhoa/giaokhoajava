import Link from "next/link";

// Trang 404 theo file convention của App Router cho mọi đường dẫn không khớp
// route, giữ khung hiển thị chung của site như trang lỗi.
export default function NotFound() {
  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="not-found-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h2 id="not-found-title">Không tìm thấy trang</h2>
        <p>Đường dẫn này không tồn tại hoặc đã thay đổi.</p>
        <p>
          <Link className="action-button" href="/">
            Về trang chủ
          </Link>
        </p>
      </section>
    </main>
  );
}
