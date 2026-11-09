"use client";

import { useRef, useState, type FormEvent } from "react";
import { WorkspaceSwitcher } from "../../features/identity/workspace-switcher";
import { useSession } from "../../features/identity/session-provider";
import {
  operationStatus,
  type OperationState,
  type OperationView,
} from "../../features/operations/operation-status";
import { apiRequest, submitLogout } from "../../lib/api-client";

type TrackedOperation = Pick<OperationView, "id" | "state" | "completed" | "errorCode">;

type PendingAction = "verification" | "recovery" | "operation" | "logout" | null;

const operationStates: OperationState[] = [
  "PENDING",
  "APPLYING",
  "SUCCEEDED",
  "RETRYABLE_FAILURE",
  "FAILED",
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseTrackedOperation(data: unknown): TrackedOperation | null {
  if (!isRecord(data)) return null;
  const source = isRecord(data.operation) ? data.operation : data;
  const id = typeof source.id === "string" && source.id
    ? source.id
    : typeof data.operationId === "string" && data.operationId
      ? data.operationId
      : null;
  if (!id
      || typeof source.state !== "string"
      || !operationStates.includes(source.state as OperationState)
      || typeof source.completed !== "boolean") {
    return null;
  }
  return {
    id,
    state: source.state as OperationState,
    completed: source.completed,
    errorCode: typeof source.errorCode === "string" && source.errorCode ? source.errorCode : undefined,
  };
}

export function OnboardingContent() {
  const { session, refreshSession } = useSession();
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const recoveryAttempt = useRef<{ payload: string; key: string } | null>(null);
  const [reason, setReason] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const userId = session.status === "authenticated" ? session.user.id : null;
  const [trackedUserId, setTrackedUserId] = useState(userId);

  if (userId !== trackedUserId) {
    // Operation và nội dung khôi phục thuộc về đúng tài khoản hiện hành.
    setTrackedUserId(userId);
    setOperation(null);
    setReason("");
    setActionError(null);
  }

  async function runMutation(action: Exclude<PendingAction, null>, path: string, body?: unknown) {
    const payload = body === undefined ? undefined : JSON.stringify(body);
    if (action === "recovery" && payload && recoveryAttempt.current?.payload !== payload) {
      recoveryAttempt.current = { payload, key: crypto.randomUUID() };
    }
    setPendingAction(action);
    setActionError(null);
    try {
      const result = await apiRequest<unknown>(path, {
        method: "POST",
        body: payload,
        headers: action === "recovery" && recoveryAttempt.current
          ? { "Idempotency-Key": recoveryAttempt.current.key } : undefined,
      });
      const nextOperation = parseTrackedOperation(result.data);
      if (!nextOperation) {
        throw new Error("Phản hồi thao tác không hợp lệ.");
      }
      setOperation(nextOperation);
      if (action === "recovery") {
        // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên lý do
        // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
          setReason("");
        }
        recoveryAttempt.current = null;
      }
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
      const result = await apiRequest<unknown>(`/api/me/operations/${encodeURIComponent(operation.id)}`);
      const nextOperation = result.kind === "data" ? parseTrackedOperation(result.data) : null;
      if (!nextOperation) {
        throw new Error("Phản hồi trạng thái thao tác không hợp lệ.");
      }
      setOperation(nextOperation);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
    } finally {
      setPendingAction(null);
    }
  }

  async function logout() {
    setPendingAction("logout");
    setActionError(null);
    try {
      await submitLogout();
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không đăng xuất được.");
      setPendingAction(null);
    }
  }

  function submitRecovery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (session.status !== "authenticated") return;
    const trimmedReason = reason.trim();
    if (!trimmedReason) {
      setActionError("Hãy nhập lý do cần hỗ trợ khôi phục.");
      return;
    }
    void runMutation("recovery", "/api/me/recovery-requests", {
      targetUserId: session.user.id,
      reason: trimmedReason,
    });
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="onboarding-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="onboarding-title">Hoàn tất tài khoản</h1>

        {session.status === "loading" ? (
          <p role="status">Đang tải trạng thái tài khoản…</p>
        ) : null}

        {session.status === "error" ? (
          <>
            <p role="alert">{session.message}</p>
            <button className="action-button" type="button" onClick={() => void refreshSession()}>
              Thử lại
            </button>
          </>
        ) : null}

        {session.status === "anonymous" ? (
          <>
            <p className="introduction">
              Đăng nhập để xem trạng thái xác minh và tiếp tục sử dụng tài khoản của bạn.
            </p>
            <a className="login-link" href="/oauth2/authorization/keycloak">
              Đăng nhập
            </a>
          </>
        ) : null}

        {session.status === "authenticated" ? (
          <>
            <p className="introduction">
              Xem trạng thái danh tính, gửi yêu cầu xác minh hoặc khôi phục, và chọn không gian
              mà bạn được phép sử dụng.
            </p>

            <section aria-labelledby="account-title">
              <h2 id="account-title">Tài khoản</h2>
              <dl className="details">
                <div>
                  <dt>Tên đăng nhập</dt>
                  <dd>{session.user.username}</dd>
                </div>
                <div>
                  <dt>Email</dt>
                  <dd>{session.user.email ?? "Chưa có email"}</dd>
                </div>
                <div>
                  <dt>Xác minh email</dt>
                  <dd>{session.user.emailVerified ? "Đã xác minh" : "Chưa xác minh"}</dd>
                </div>
                <div>
                  <dt>Trạng thái danh tính</dt>
                  <dd>{session.user.identityState}</dd>
                </div>
              </dl>

              {session.user.email && !session.user.emailVerified ? (
                <button
                  className="action-button"
                  type="button"
                  disabled={pendingAction !== null}
                  onClick={() => void runMutation("verification", "/api/identity/verification")}
                >
                  Gửi yêu cầu xác minh email
                </button>
              ) : null}
              {!session.user.email ? (
                <p>
                  Tài khoản chưa có email. Hãy gửi yêu cầu khôi phục bên dưới để được hỗ trợ bổ
                  sung và xác minh email.
                </p>
              ) : null}
            </section>

            <section className="section" aria-labelledby="recovery-title">
              <h2 id="recovery-title">Khôi phục tài khoản</h2>
              <p>
                Yêu cầu này chỉ tạo đề nghị hỗ trợ cho chính tài khoản của bạn; mật khẩu không
                được thay đổi tại trang này.
              </p>
              <form onSubmit={submitRecovery}>
                <div className="field">
                  <label htmlFor="recovery-reason">Lý do cần hỗ trợ</label>
                  <textarea
                    id="recovery-reason"
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    rows={4}
                    required
                  />
                </div>
                <button
                  className="action-button"
                  type="submit"
                  disabled={pendingAction !== null || !reason.trim()}
                >
                  Gửi yêu cầu khôi phục
                </button>
              </form>
            </section>

            {operation ? (
              <section className="section" aria-labelledby="operation-title">
                <h2 id="operation-title">Trạng thái thao tác</h2>
                <p>{operationStatus(operation).message}</p>
                <p>
                  Mã thao tác: <code>{operation.id}</code>
                </p>
                {operation.errorCode ? <p>Mã lỗi: {operation.errorCode}</p> : null}
                <button
                  className="secondary-button"
                  type="button"
                  disabled={pendingAction !== null}
                  onClick={() => void checkOperation()}
                >
                  Kiểm tra trạng thái
                </button>
              </section>
            ) : null}

            {actionError ? <p role="alert">{actionError}</p> : null}

            <div className="section">
              <WorkspaceSwitcher />
            </div>

            <div className="section">
              <button
                className="secondary-button"
                type="button"
                disabled={pendingAction !== null}
                onClick={() => void logout()}
              >
                Đăng xuất
              </button>
            </div>
          </>
        ) : null}
      </section>
    </main>
  );
}
