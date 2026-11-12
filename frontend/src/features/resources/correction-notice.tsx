"use client";

import { useEffect, useState } from "react";
import { requestAllPages } from "@/lib/api-client";
import { useSession } from "@/features/identity/session-provider";

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

export type RecipientCorrectionNotice = CorrectionNotice & {
  id: string;
  acknowledgedAt: string | null;
};

// Backend chỉ trả metadata được PDP cho xem; UI vẫn parse đúng DTO, bỏ phần lạ.
export function parseRecipientCorrectionNotices(data: unknown): RecipientCorrectionNotice[] {
  if (!isRecord(data) || !Array.isArray(data.items)) return [];
  const results: RecipientCorrectionNotice[] = [];
  for (const raw of data.items) {
    const notice = parseCorrectionNotice(raw);
    if (!isRecord(raw) || !notice || !notice.id
        || !(raw.acknowledgedAt === null || typeof raw.acknowledgedAt === "string")) {
      continue;
    }
    results.push({ ...notice, id: notice.id, acknowledgedAt: raw.acknowledgedAt });
  }
  return results;
}

export function CorrectionNoticeInbox({
  workspaceId,
  refreshTick,
  onReceiveUpdate,
}: {
  workspaceId: string;
  refreshTick: number;
  onReceiveUpdate: (notice: CorrectionNotice) => void;
}) {
  const { session } = useSession();
  const actorId = session.status === "authenticated" ? session.user.id : null;
  const [revision, setRevision] = useState(0);
  const contextKey = `${actorId ?? ""}|${workspaceId}|${refreshTick}|${revision}`;
  const [loaded, setLoaded] = useState<{ key: string; items: RecipientCorrectionNotice[] } | null>(null);
  const [error, setError] = useState<{ key: string; message: string } | null>(null);

  useEffect(() => {
    if (!actorId || !workspaceId) return;
    let ignore = false;
    const controller = new AbortController();
    requestAllPages(
      `/api/workspaces/${encodeURIComponent(workspaceId)}/correction-notices`,
      { signal: controller.signal },
    ).then((items) => {
      if (ignore) return;
      setLoaded({ key: contextKey, items: parseRecipientCorrectionNotices({ items }) });
      setError(null);
    }).catch((reason: unknown) => {
      if (ignore) return;
      setLoaded({ key: contextKey, items: [] });
      setError({ key: contextKey,
        message: reason instanceof Error ? reason.message : "Không tải được thông báo." });
    });
    return () => { ignore = true; controller.abort(); };
  }, [actorId, workspaceId, contextKey]);

  const items = loaded?.key === contextKey ? loaded.items : null;
  const currentError = error?.key === contextKey ? error.message : null;
  return (
    <section className="section" aria-labelledby="recipient-correction-inbox">
      <h2 id="recipient-correction-inbox">Thông báo đính chính đã nhận</h2>
      <p>
        Chỉ hiển thị metadata nguồn mà tài khoản còn quyền xem hoặc sử dụng.
        Nhận cập nhật là thao tác riêng trên một `libraryItemId` đã lưu;
        thông báo không tự cấp quyền phiên bản mới và không sửa bài đã giao.
      </p>
      <button className="secondary-button" type="button" onClick={() => setRevision((v) => v + 1)}>
        Tải lại thông báo
      </button>
      {!workspaceId ? <p>Nhập workspaceId ở biểu mẫu phía trên để xem thông báo.</p> : null}
      {workspaceId && actorId && items === null ? <p role="status">Đang tải thông báo…</p> : null}
      {currentError ? <p role="alert">{currentError}</p> : null}
      {items?.length === 0 && !currentError ? <p>Chưa có thông báo phù hợp quyền.</p> : null}
      {items?.map((notice) => (
        <CorrectionNoticeView
          key={notice.id}
          notice={notice}
          updateReceived={notice.acknowledgedAt !== null}
          onReceiveUpdate={onReceiveUpdate}
        />
      ))}
    </section>
  );
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
  const titleId = `correction-notice-${notice.id ?? `${notice.resourceId}-${notice.affectedVersionId}`}`;
  return (
    <section className="section" aria-labelledby={titleId}>
      <h2 id={titleId}>Thông báo đính chính nguồn</h2>
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
