// Next.js tự bọc page của segment trong <Suspense> với fallback này: khi điều
// hướng giữa các route trong nhóm (session), trạng thái tải hiện ngay trong
// lúc layout (SessionProvider) vẫn hiển thị, thay vì màn trắng chờ page render.
export default function SessionLoading() {
  return <p role="status">Đang tải…</p>;
}
