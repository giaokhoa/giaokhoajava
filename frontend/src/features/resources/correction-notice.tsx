export type CorrectionNotice = {
  id?: string;
  resourceId: string;
  affectedVersionId: string;
  replacementVersionId: string;
  reason: string;
  createdAt?: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requiredId(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

// Thông báo đính chính chỉ chứa metadata của hợp đồng; payload thiếu một định
// danh bắt buộc thì không hiển thị như một thông báo hợp lệ.
export function parseCorrectionNotice(data: unknown): CorrectionNotice | null {
  if (!isRecord(data)) return null;
  const resourceId = requiredId(data.resourceId);
  const affectedVersionId = requiredId(data.affectedVersionId);
  const replacementVersionId = requiredId(data.replacementVersionId);
  if (!resourceId || !affectedVersionId || !replacementVersionId) return null;
  return {
    id: requiredId(data.id) ?? undefined,
    resourceId,
    affectedVersionId,
    replacementVersionId,
    reason: typeof data.reason === "string" ? data.reason : "",
    createdAt: requiredId(data.createdAt) ?? undefined,
  };
}

export function CorrectionNoticeView({
  notice,
  updateReceived,
  onReceiveUpdate,
}: {
  notice: CorrectionNotice;
  updateReceived: boolean;
  onReceiveUpdate?: (notice: CorrectionNotice) => void;
}) {
  return (
    <section className="section" aria-labelledby="correction-notice-title">
      <h2 id="correction-notice-title">Thông báo đính chính nguồn</h2>
      <p>
        Nguồn đã phát hành phiên bản thay thế cho một phiên bản có lỗi. Thông báo chỉ cho biết
        định danh và lý do; việc nhận cập nhật là chủ động và không tự đổi bản đã lưu hay bài đã giao.
      </p>
      <dl className="details">
        <div>
          <dt>Tài nguyên</dt>
          <dd>{notice.resourceId}</dd>
        </div>
        <div>
          <dt>Phiên bản bị ảnh hưởng</dt>
          <dd>{notice.affectedVersionId}</dd>
        </div>
        <div>
          <dt>Phiên bản thay thế</dt>
          <dd>{notice.replacementVersionId}</dd>
        </div>
        <div>
          <dt>Lý do</dt>
          <dd>{notice.reason || "Không ghi lý do"}</dd>
        </div>
        {notice.createdAt ? (
          <div>
            <dt>Thời điểm</dt>
            <dd>{notice.createdAt}</dd>
          </div>
        ) : null}
        <div>
          <dt>Trạng thái cập nhật</dt>
          <dd>{updateReceived ? "Đã nhận cập nhật" : "Chưa nhận cập nhật"}</dd>
        </div>
      </dl>
      {!updateReceived && onReceiveUpdate ? (
        <button
          className="secondary-button"
          type="button"
          onClick={() => onReceiveUpdate(notice)}
        >
          Nhận cập nhật
        </button>
      ) : null}
    </section>
  );
}
