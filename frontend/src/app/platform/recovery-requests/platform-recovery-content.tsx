"use client";

import { useEffect, useRef, useState } from "react";
import { apiRequest, isRecord, listItems, requestWhenReady } from "../../../lib/api-client";
import { SessionGate } from "../../../features/identity/session-gate";
import { OperationPanel, useOperationCheck } from "../../../features/operations/operation-panel";
import {
  parseTrackedOperation,
  type TrackedOperation,
} from "../../../features/operations/operation-status";
import { useSession } from "../../../features/identity/session-provider";

export type RecoveryRequestState = "REQUESTED" | "VERIFIED" | "RESOLVED" | "REJECTED";

export type PlatformRecoveryRequest = {
  id: string;
  targetUserId: string;
  requesterUserId?: string;
  reason?: string;
  state: RecoveryRequestState;
  createdAt?: string;
  version: number;
};

export type ResolveDecision = "APPROVE" | "COMPLETE" | "REJECT";

const requestStates: RecoveryRequestState[] = ["REQUESTED", "VERIFIED", "RESOLVED", "REJECTED"];

const stateLabels: Record<RecoveryRequestState, string> = {
  REQUESTED: "Đang chờ xử lý",
  VERIFIED: "Đã xác minh",
  RESOLVED: "Đã giải quyết",
  REJECTED: "Đã từ chối",
};

function parseRequest(item: unknown): PlatformRecoveryRequest | null {
  if (!isRecord(item)
      || typeof item.id !== "string" || !item.id
      || typeof item.targetUserId !== "string" || !item.targetUserId
      || typeof item.state !== "string"
      || typeof item.version !== "number" || !Number.isSafeInteger(item.version) || item.version < 0
      || !requestStates.includes(item.state as RecoveryRequestState)) {
    return null;
  }
  return {
    id: item.id,
    targetUserId: item.targetUserId,
    requesterUserId: typeof item.requesterUserId === "string" && item.requesterUserId
      ? item.requesterUserId
      : undefined,
    reason: typeof item.reason === "string" && item.reason ? item.reason : undefined,
    state: item.state as RecoveryRequestState,
    createdAt: typeof item.createdAt === "string" && item.createdAt ? item.createdAt : undefined,
    version: item.version,
  };
}

export function parsePlatformRecoveryRequests(data: unknown): PlatformRecoveryRequest[] {
  const items = listItems(data);
  const requests: PlatformRecoveryRequest[] = [];
  for (const item of items) {
    const request = parseRequest(item);
    if (request) requests.push(request);
  }
  return requests;
}

// Hợp đồng chỉ ghi {decision, verificationNote?}; giá trị quyết định theo tiền lệ
// duy nhất trong hợp đồng là GuardianDecision (APPROVE/REJECT).
export function resolveRequestBody(
  decision: string,
  verificationNote: string,
): { decision: ResolveDecision; verificationNote?: string } | null {
  if (decision !== "APPROVE" && decision !== "COMPLETE" && decision !== "REJECT") return null;
  const note = verificationNote.trim();
  if ((decision !== "REJECT" && !note) || note.length > 1000) return null;
  return note ? { decision, verificationNote: note } : { decision };
}

export function PlatformRecoveryContent() {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const [requests, setRequests] = useState<PlatformRecoveryRequest[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const lastAttempt = useRef<{ signature: string; key: string } | null>(null);
  const userId = session.status === "authenticated" ? session.user.id : null;

  async function loadRequests() {
    setListError(null);
    try {
      const result = await requestWhenReady("/api/platform/recovery-requests");
      if (result.kind !== "data") throw new Error("Phản hồi danh sách yêu cầu không hợp lệ.");
      setRequests(parsePlatformRecoveryRequests(result.data));
    } catch (error) {
      setRequests([]);
      setListError(error instanceof Error ? error.message : "Không tải được danh sách yêu cầu khôi phục.");
    }
  }

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    const controller = new AbortController();
    requestWhenReady("/api/platform/recovery-requests", { signal: controller.signal })
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setRequests(parsePlatformRecoveryRequests(result.data));
        setListError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setRequests([]);
        setListError(error instanceof Error ? error.message : "Không tải được danh sách yêu cầu khôi phục.");
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [userId]);

  async function resolveRequest(requestId: string, decision: ResolveDecision) {
    const body = resolveRequestBody(decision, notes[requestId] ?? "");
    if (!body) {
      setActionError("Cần ghi chú việc xác minh hoặc việc khôi phục và bàn giao đã hoàn tất (tối đa 1000 ký tự).");
      return;
    }
    const request = requests?.find((item) => item.id === requestId);
    if (!request) return;
    const signature = JSON.stringify([userId, requestId, body, request.version]);
    if (lastAttempt.current?.signature !== signature) {
      lastAttempt.current = { signature, key: crypto.randomUUID() };
    }
    setPendingAction(requestId);
    setActionError(null);
    try {
      const result = await apiRequest<unknown>(
        `/api/platform/recovery-requests/${encodeURIComponent(requestId)}/resolve`,
        { method: "POST", body: JSON.stringify(body), headers: {
          "Idempotency-Key": lastAttempt.current.key,
          "If-Match": `"${request.version}"`,
        } },
      );
      const nextOperation = parseTrackedOperation(result.data);
      if (!nextOperation) {
        throw new Error("Phản hồi thao tác không hợp lệ.");
      }
      setOperation(nextOperation);
      await loadRequests();
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Thao tác không thành công.");
    } finally {
      setPendingAction(null);
    }
  }

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const nextOperation = await checkOperationStatus(operation.id);
      setOperation(nextOperation);
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        await loadRequests();
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="platform-recovery-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="platform-recovery-title">Yêu cầu khôi phục</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để xem các yêu cầu khôi phục thuộc phạm vi vận hành của bạn."
        >
            <p className="introduction">
              Danh sách chỉ gồm mã yêu cầu, tài khoản đích và trạng thái — operator không xem
              nội dung học tập từ chức vụ và không tạo gói hoặc lớp khi chỉ có quyền nền.
            </p>

            <p>Xác minh danh tính trước. Sau đó khôi phục tài khoản và bàn giao credential qua kênh riêng cho đúng người dùng; chỉ xác nhận hoàn tất khi đã thực hiện xong.</p>
            {requests === null ? <p role="status">Đang tải danh sách yêu cầu…</p> : null}
            {listError ? <p role="alert">{listError}</p> : null}
            {requests !== null && requests.length === 0 && !listError ? (
              <p>Chưa có yêu cầu khôi phục nào.</p>
            ) : null}

            {requests !== null && requests.length > 0 ? (
              <ul>
                {requests.map((request) => (
                  <li key={request.id}>
                    <dl className="details">
                      <div>
                        <dt>Mã yêu cầu</dt>
                        <dd><code>{request.id}</code></dd>
                      </div>
                      <div>
                        <dt>Tài khoản đích</dt>
                        <dd><code>{request.targetUserId}</code></dd>
                      </div>
                      {request.reason ? (
                        <div>
                          <dt>Lý do cần hỗ trợ</dt>
                          <dd>{request.reason}</dd>
                        </div>
                      ) : null}
                      <div>
                        <dt>Trạng thái</dt>
                        <dd>{stateLabels[request.state]}</dd>
                      </div>
                      {request.createdAt ? (
                        <div>
                          <dt>Thời gian tạo</dt>
                          <dd>{request.createdAt}</dd>
                        </div>
                      ) : null}
                    </dl>
                    {request.state === "REQUESTED" || request.state === "VERIFIED" ? (
                      <div className="field">
                        <label htmlFor={`verification-note-${request.id}`}>Ghi chú xác minh</label>
                        <textarea
                          id={`verification-note-${request.id}`}
                          value={notes[request.id] ?? ""}
                          onChange={(event) => setNotes((current) => ({
                            ...current,
                            [request.id]: event.target.value,
                          }))}
                          rows={3}
                        />
                      </div>
                    ) : null}
                    {request.state === "REQUESTED" || request.state === "VERIFIED" ? (
                      <div>
                        <button
                          className="action-button"
                          type="button"
                          disabled={pendingAction !== null}
                          onClick={() => void resolveRequest(request.id, request.state === "REQUESTED" ? "APPROVE" : "COMPLETE")}
                        >
                          {request.state === "REQUESTED" ? "Đã xác minh danh tính" : "Xác nhận đã khôi phục và bàn giao"}
                        </button>
                        {" "}
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={pendingAction !== null}
                          onClick={() => void resolveRequest(request.id, "REJECT")}
                        >
                          Từ chối
                        </button>
                      </div>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : null}

            <div className="section">
              <button
                className="secondary-button"
                type="button"
                disabled={pendingAction !== null || requests === null}
                onClick={() => void loadRequests()}
              >
                Tải lại danh sách
              </button>
            </div>

            {operation ? (
              <OperationPanel
                operation={operation}
                checking={pendingAction !== null}
                onCheck={() => void checkOperation()}
              />
            ) : null}

            {actionError ? <p role="alert">{actionError}</p> : null}
        </SessionGate>
      </section>
    </main>
  );
}
