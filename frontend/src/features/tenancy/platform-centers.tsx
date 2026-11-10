"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  isRecord,
  listItems,
  requestAllPages,
  requestWorkspaces,
} from "@/lib/api-client";
import { SessionGate } from "@/features/identity/session-gate";
import { useSession } from "@/features/identity/session-provider";
import { attemptKey, submitMutation } from "@/features/operations/operation-attempts";
import { OperationPanel } from "@/features/operations/operation-panel";
import { useOperationCheck } from "@/lib/use-operation-check";
import {
  operationStatus,
  parseTrackedOperation,
  pollOperationUntilSettled,
  type TrackedOperation,
} from "@/lib/operation-status";
import { parseWorkspaces, type WorkspaceView } from "@/features/identity/workspace-switcher";

export type CenterSummary = {
  id: string;
  name: string;
};

export function parseCenters(data: unknown): CenterSummary[] {
  const centers: CenterSummary[] = [];
  for (const item of listItems(data)) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.name !== "string" || !item.name) {
      continue;
    }
    centers.push({ id: item.id, name: item.name });
  }
  return centers;
}

export function centerCreateBody(
  name: string,
  ownerUserId: string,
): { name: string; ownerUserId: string } | null {
  const trimmedName = name.trim();
  const trimmedOwner = ownerUserId.trim();
  if (!trimmedName || !trimmedOwner) return null;
  return { name: trimmedName, ownerUserId: trimmedOwner };
}

export function subscriptionConfirmBody(
  workspaceId: string,
  validFrom: string,
  expiresAt: string,
  confirmationRef: string,
): { workspaceId: string; validFrom: string; expiresAt: string; confirmationRef: string } | null {
  const ref = confirmationRef.trim();
  const from = Date.parse(validFrom);
  const until = Date.parse(expiresAt);
  if (!workspaceId || !ref || Number.isNaN(from) || Number.isNaN(until) || until <= from) {
    return null;
  }
  return {
    workspaceId,
    validFrom: new Date(from).toISOString(),
    expiresAt: new Date(until).toISOString(),
    confirmationRef: ref,
  };
}

export function PlatformCentersContent() {
  const { session } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  return <PlatformCentersInner key={userId ?? "anonymous"} />;
}

function PlatformCentersInner() {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const [centers, setCenters] = useState<CenterSummary[] | null>(null);
  const [ownWorkspaces, setOwnWorkspaces] = useState<WorkspaceView[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [centerName, setCenterName] = useState("");
  const [ownerUserId, setOwnerUserId] = useState("");
  const [subscriptionWorkspaceId, setSubscriptionWorkspaceId] = useState("");
  const [validFrom, setValidFrom] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [confirmationRef, setConfirmationRef] = useState("");
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const attempts = useRef(new Map<string, string>());
  const userId = session.status === "authenticated" ? session.user.id : null;

  const loadCenters = useCallback(async (signal?: AbortSignal) => {
    if (!userId) return;
    setListError(null);
    const [centerResult, workspaceResult] = await Promise.allSettled([
      requestAllPages("/api/platform/centers", { signal }),
      requestWorkspaces(userId, signal),
    ]);
    if (signal?.aborted) return;
    setCenters(centerResult.status === "fulfilled" ? parseCenters(centerResult.value) : []);
    setOwnWorkspaces(workspaceResult.status === "fulfilled" && workspaceResult.value.kind === "data"
      ? parseWorkspaces(workspaceResult.value.data) : null);
    if (centerResult.status === "rejected" || workspaceResult.status === "rejected") {
      setListError("Không làm mới được toàn bộ quyền và dữ liệu; hãy tải lại.");
    }
  }, [userId]);

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    const controller = new AbortController();
    requestAllPages("/api/platform/centers", { signal: controller.signal })
      .then((items) => {
        if (ignore) return;
        setCenters(parseCenters(items));
        setListError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setCenters([]);
        setListError(error instanceof Error ? error.message : "Không tải được danh sách trung tâm.");
      });
    requestWorkspaces(userId, controller.signal)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setOwnWorkspaces(parseWorkspaces(result.data));
      })
      .catch(() => {
        if (!ignore) setOwnWorkspaces(null);
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [userId]);

  useEffect(() => {
    if (!operation || operationStatus(operation).kind !== "pending" || !userId) return;
    const controller = new AbortController();
    void pollOperationUntilSettled(operation.id, parseTrackedOperation, controller.signal)
      .then(async (next) => {
        if (controller.signal.aborted) return;
        if (next.completed && next.state === "SUCCEEDED") {
          await loadCenters(controller.signal);
          if (controller.signal.aborted) return;
        }
        setOperation(next);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
        }
      });
    return () => controller.abort();
  }, [operation, userId, loadCenters]);

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const nextOperation = await checkOperationStatus(operation.id);
      setOperation(nextOperation);
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        await loadCenters();
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
    } finally {
      setPendingAction(null);
    }
  }

  async function createCenter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = centerCreateBody(centerName, ownerUserId);
    if (!body) {
      setActionError("Hãy nhập tên trung tâm và mã người dùng của chủ sở hữu.");
      return;
    }
    const key = attemptKey(attempts.current, JSON.stringify([userId, "center", body]));
    setPendingAction("center");
    setActionError(null);
    try {
      const data = await submitMutation(
        "/api/platform/centers",
        { method: "POST", body: JSON.stringify(body) },
        key,
      );
      const nextOperation = parseTrackedOperation(data);
      if (!nextOperation) throw new Error("Phản hồi thao tác không hợp lệ.");
      setOperation(nextOperation);
      // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên dữ kiện
      // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        setCenterName("");
        setOwnerUserId("");
        await loadCenters();
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Thao tác không thành công.");
    } finally {
      setPendingAction(null);
    }
  }

  async function confirmSubscription(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = subscriptionConfirmBody(
      subscriptionWorkspaceId, validFrom, expiresAt, confirmationRef,
    );
    if (!body) {
      setActionError("Hãy chọn trung tâm, nhập mã xác nhận và khoảng hiệu lực hợp lệ (ngày kết thúc sau ngày bắt đầu).");
      return;
    }
    const key = attemptKey(attempts.current, JSON.stringify([userId, "subscription", body]));
    setPendingAction("subscription");
    setActionError(null);
    try {
      const data = await submitMutation(
        "/api/platform/subscriptions",
        { method: "POST", body: JSON.stringify(body) },
        key,
      );
      const nextOperation = parseTrackedOperation(data);
      if (!nextOperation) throw new Error("Phản hồi thao tác không hợp lệ.");
      setOperation(nextOperation);
      // Chỉ dọn draft sau thao tác THÀNH CÔNG (như createCenter ở trên).
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        setConfirmationRef("");
        await loadCenters();
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Thao tác không thành công.");
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="platform-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="platform-title">Quản trị nền tảng</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để dùng chức năng quản trị thuộc phạm vi của bạn."
        >
          <p className="introduction">
            Chỉ quản trị viên Giáo Khoa tạo trung tâm và chỉ định chủ sở hữu; gói được xác
            nhận thủ công tại đây, không qua cổng thanh toán. Quyền vận hành nền tảng không
            bao gồm nội dung học tập của trung tâm.
          </p>
          <p>
            <Link href="/platform/recovery-requests">Yêu cầu khôi phục tài khoản</Link>
          </p>

          <section aria-labelledby="center-list-title">
            <h2 id="center-list-title">Danh sách trung tâm</h2>
            {centers === null ? <p role="status">Đang tải danh sách trung tâm…</p> : null}
            {listError ? <p role="alert">{listError}</p> : null}
            {centers !== null && centers.length === 0 && !listError ? (
              <p>Chưa có trung tâm nào.</p>
            ) : null}
            {centers !== null && centers.length > 0 ? (
              <ul>
                {centers.map((center) => (
                  <li key={center.id}>
                    <dl className="details">
                      <div>
                        <dt>Tên trung tâm</dt>
                        <dd>{center.name}</dd>
                      </div>
                      <div>
                        <dt>Mã trung tâm</dt>
                        <dd><code>{center.id}</code></dd>
                      </div>
                    </dl>
                    <p>
                      <Link href={`/centers/${encodeURIComponent(center.id)}/members`}>
                        Quản lý thành viên
                      </Link>
                    </p>
                  </li>
                ))}
              </ul>
            ) : null}
            <div className="section">
              <button
                className="secondary-button"
                type="button"
                disabled={pendingAction !== null || centers === null}
                onClick={() => void loadCenters()}
              >
                Tải lại danh sách
              </button>
            </div>
          </section>

          {ownWorkspaces !== null && ownWorkspaces.length > 0 ? (
            <section className="section" aria-labelledby="own-workspaces-title">
              <h2 id="own-workspaces-title">Không gian của bạn</h2>
              <ul>
                {ownWorkspaces.map((item) => (
                  <li key={item.id}>
                    {item.name}
                    {item.kind === "CENTER" ? (
                      <>
                        {" — "}
                        <Link href={`/centers/${encodeURIComponent(item.id)}/members`}>
                          Quản lý thành viên
                        </Link>
                      </>
                    ) : null}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <section className="section" aria-labelledby="center-create-title">
            <h2 id="center-create-title">Tạo trung tâm</h2>
            <form onSubmit={createCenter}>
              <div className="field">
                <label htmlFor="center-name">Tên trung tâm</label>
                <input
                  id="center-name"
                  value={centerName}
                  onChange={(event) => setCenterName(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="center-owner">Mã người dùng của chủ sở hữu</label>
                <input
                  id="center-owner"
                  value={ownerUserId}
                  onChange={(event) => setOwnerUserId(event.target.value)}
                  required
                />
              </div>
              <button
                className="action-button"
                type="submit"
                disabled={pendingAction !== null}
              >
                Tạo trung tâm
              </button>
            </form>
          </section>

          <section className="section" aria-labelledby="subscription-title">
            <h2 id="subscription-title">Xác nhận gói thủ công</h2>
            <form onSubmit={confirmSubscription}>
              <div className="field">
                <label htmlFor="subscription-workspace">Trung tâm</label>
                <select
                  id="subscription-workspace"
                  value={subscriptionWorkspaceId}
                  onChange={(event) => setSubscriptionWorkspaceId(event.target.value)}
                  required
                >
                  <option value="">Chọn trung tâm</option>
                  {(centers ?? []).map((center) => (
                    <option key={center.id} value={center.id}>{center.name}</option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="subscription-from">Hiệu lực từ</label>
                <input
                  id="subscription-from"
                  type="datetime-local"
                  value={validFrom}
                  onChange={(event) => setValidFrom(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="subscription-until">Hiệu lực đến</label>
                <input
                  id="subscription-until"
                  type="datetime-local"
                  value={expiresAt}
                  onChange={(event) => setExpiresAt(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="subscription-ref">Mã xác nhận thanh toán</label>
                <input
                  id="subscription-ref"
                  value={confirmationRef}
                  onChange={(event) => setConfirmationRef(event.target.value)}
                  required
                />
              </div>
              <button
                className="action-button"
                type="submit"
                disabled={pendingAction !== null}
              >
                Xác nhận gói
              </button>
            </form>
          </section>

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
