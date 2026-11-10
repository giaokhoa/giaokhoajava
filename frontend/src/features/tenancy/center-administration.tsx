"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  isRecord,
  listItems,
  requestAllPages,
  requestWhenReady,
  requestWorkspaces,
} from "../../lib/api-client";
import { SessionGate } from "../identity/session-gate";
import { useSession } from "../identity/session-provider";
import { attemptKey, submitMutation } from "../operations/operation-attempts";
import { OperationPanel, useOperationCheck } from "../operations/operation-panel";
import {
  operationStatus,
  parseTrackedOperation,
  pollOperationUntilSettled,
  type TrackedOperation,
} from "../operations/operation-status";
import { parseWorkspaces, type WorkspaceView } from "../identity/workspace-switcher";

// Danh mục vai trò cố định của center; không có đường tạo vai trò tùy chỉnh.
export const CENTER_ROLES = ["OWNER", "MANAGER", "TEACHER", "STUDENT", "PARENT"] as const;
export type CenterRole = (typeof CENTER_ROLES)[number];

// OWNER không nằm trong danh mục mời: chủ sở hữu chỉ được chỉ định khi operator tạo center.
export const INVITABLE_ROLES = ["MANAGER", "TEACHER", "STUDENT", "PARENT"] as const;

const roleLabels: Record<CenterRole, string> = {
  OWNER: "Chủ sở hữu",
  MANAGER: "Quản lý",
  TEACHER: "Giáo viên",
  STUDENT: "Học sinh",
  PARENT: "Phụ huynh",
};

export type CenterSummary = {
  id: string;
  name: string;
};

export type Membership = {
  id: string;
  user: { id: string; username: string; displayName?: string };
  roles: CenterRole[];
  state: string;
  version: number;
};

export type StudentHandoff = {
  username: string;
  temporaryPassword: string;
};

function isCenterRole(value: unknown): value is CenterRole {
  return typeof value === "string" && (CENTER_ROLES as readonly string[]).includes(value);
}

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

export function parseMemberships(data: unknown): Membership[] {
  const memberships: Membership[] = [];
  for (const item of listItems(data)) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || !isRecord(item.user)
        || typeof item.user.id !== "string" || !item.user.id
        || typeof item.user.username !== "string" || !item.user.username
        || !Array.isArray(item.roles)
        || typeof item.state !== "string" || !item.state
        || typeof item.version !== "number" || !Number.isSafeInteger(item.version) || item.version < 0) {
      continue;
    }
    const roles = item.roles.filter(isCenterRole);
    // Vai trò ngoài danh mục cố định làm bản ghi không đáng tin; bỏ thay vì hiển thị sai quyền.
    if (roles.length !== item.roles.length) continue;
    memberships.push({
      id: item.id,
      user: {
        id: item.user.id,
        username: item.user.username,
        displayName: typeof item.user.displayName === "string" && item.user.displayName
          ? item.user.displayName
          : undefined,
      },
      roles,
      state: item.state,
      version: item.version,
    });
  }
  return memberships;
}

// Cursor của trang thành viên vừa tải, null là đã hết trang; cùng hình
// {items, nextCursor} mà parsePage ở tính năng giảng dạy đọc cho danh sách lớp.
function pageNextCursor(data: unknown): string | null {
  return isRecord(data) && typeof data.nextCursor === "string" && data.nextCursor
    ? data.nextCursor
    : null;
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

// Lời mời có đúng một đích (email hoặc userId) và chỉ chứa vai trò trong danh mục mời.
export function invitationBody(
  email: string,
  userId: string,
  roles: readonly string[],
): { email: string; roles: string[] } | { userId: string; roles: string[] } | null {
  const trimmedEmail = email.trim();
  const trimmedUserId = userId.trim();
  if ((trimmedEmail === "") === (trimmedUserId === "")) return null;
  const selected = CENTER_ROLES.filter(
    (role) => role !== "OWNER" && roles.includes(role),
  );
  if (selected.length === 0 || selected.length !== new Set(roles).size) return null;
  return trimmedEmail
    ? { email: trimmedEmail, roles: selected }
    : { userId: trimmedUserId, roles: selected };
}

export function membershipRolesBody(
  roles: readonly string[],
): { roles: CenterRole[] } | null {
  const selected = CENTER_ROLES.filter((role) => roles.includes(role));
  if (selected.length === 0 || selected.length !== new Set(roles).size) return null;
  return { roles: selected };
}

export function studentIssueBody(
  username: string,
  displayName: string,
  email: string,
): { username: string; displayName: string; email?: string } | null {
  const trimmedUsername = username.trim();
  const trimmedDisplayName = displayName.trim();
  const trimmedEmail = email.trim();
  if (!trimmedUsername || !trimmedDisplayName) return null;
  return trimmedEmail
    ? { username: trimmedUsername, displayName: trimmedDisplayName, email: trimmedEmail }
    : { username: trimmedUsername, displayName: trimmedDisplayName };
}

// Handoff chỉ tồn tại trong response CREATE đầu tiên; poll/list/retry không có trường này
// nên không có đường nào để UI hiển thị lại mật khẩu tạm ngoài state của lần tạo đó.
export function parseStudentIssue(
  data: unknown,
): { operation: TrackedOperation; handoff?: StudentHandoff } | null {
  const operation = parseTrackedOperation(data);
  if (!operation || !isRecord(data)) return null;
  const raw = data.handoff;
  if (isRecord(raw)
      && typeof raw.username === "string" && raw.username
      && typeof raw.temporaryPassword === "string" && raw.temporaryPassword) {
    return {
      operation,
      handoff: { username: raw.username, temporaryPassword: raw.temporaryPassword },
    };
  }
  return { operation };
}

// Ẩn chức năng theo capability do backend trả về: vai trò của chính actor trong center này.
// Đây chỉ là hiển thị; backend vẫn xét lại mọi mutation và lỗi 403 được báo nguyên văn.
export function memberPermissions(
  memberships: readonly Membership[],
  userId: string | null,
): { canManage: boolean; canAssignManager: boolean } {
  const own = userId ? memberships.find((item) => item.user.id === userId) : undefined;
  const roles = own?.roles ?? [];
  return {
    canManage: roles.includes("OWNER") || roles.includes("MANAGER"),
    canAssignManager: roles.includes("OWNER"),
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

function toggleRole(current: readonly CenterRole[], role: CenterRole, checked: boolean): CenterRole[] {
  return checked ? [...current, role] : current.filter((item) => item !== role);
}

export function CenterMembersContent({ workspaceId }: { workspaceId: string }) {
  const { session } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  return <CenterMembersInner key={`${userId ?? "anonymous"}|${workspaceId}`} workspaceId={workspaceId} />;
}

function CenterMembersInner({ workspaceId }: { workspaceId: string }) {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [memberships, setMemberships] = useState<Membership[] | null>(null);
  const [membersNextCursor, setMembersNextCursor] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteUserId, setInviteUserId] = useState("");
  const [inviteRoles, setInviteRoles] = useState<CenterRole[]>(["TEACHER"]);
  const [studentUsername, setStudentUsername] = useState("");
  const [studentDisplayName, setStudentDisplayName] = useState("");
  const [studentEmail, setStudentEmail] = useState("");
  const [handoff, setHandoff] = useState<StudentHandoff | null>(null);
  const [roleDrafts, setRoleDrafts] = useState<Record<string, CenterRole[]>>({});
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const attempts = useRef(new Map<string, string>());
  const userId = session.status === "authenticated" ? session.user.id : null;

  const membersPath = `/api/workspaces/${encodeURIComponent(workspaceId)}/memberships`;
  const { canManage, canAssignManager } = memberPermissions(memberships ?? [], userId);
  const subscriptionExpired = workspace?.kind === "CENTER" && !workspace.subscription.effective;

  // Làm mới trở về trang đầu, đúng cách danh sách lớp xử lý sau mỗi thao tác:
  // các trang đã tải thêm được thay bằng trạng thái mới nhất từ đầu danh sách.
  const loadMemberships = useCallback(async (signal?: AbortSignal) => {
    if (!userId) return;
    setListError(null);
    const [memberResult, workspaceResult] = await Promise.allSettled([
      requestWhenReady<unknown>(membersPath, { signal }),
      requestWorkspaces(userId, signal),
    ]);
    if (signal?.aborted) return;
    const membersPage = memberResult.status === "fulfilled" && memberResult.value.kind === "data"
      ? memberResult.value.data
      : null;
    setMemberships(membersPage !== null ? parseMemberships(membersPage) : []);
    setMembersNextCursor(membersPage !== null ? pageNextCursor(membersPage) : null);
    setWorkspace(workspaceResult.status === "fulfilled" && workspaceResult.value.kind === "data"
      ? parseWorkspaces(workspaceResult.value.data).find((item) => item.id === workspaceId) ?? null
      : null);
    if (membersPage === null || workspaceResult.status === "rejected") {
      setListError("Không làm mới được toàn bộ quyền và dữ liệu; hãy tải lại.");
    }
  }, [userId, membersPath, workspaceId]);

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    const controller = new AbortController();
    requestWhenReady<unknown>(membersPath, { signal: controller.signal })
      .then((result) => {
        if (ignore) return;
        if (result.kind !== "data") throw new Error("Phản hồi danh sách thành viên không hợp lệ.");
        setMemberships(parseMemberships(result.data));
        setMembersNextCursor(pageNextCursor(result.data));
        setListError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setMemberships([]);
        setMembersNextCursor(null);
        setListError(error instanceof Error ? error.message : "Không tải được danh sách thành viên.");
      });
    requestWorkspaces(userId, controller.signal)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        const found = parseWorkspaces(result.data).find((item) => item.id === workspaceId);
        setWorkspace(found ?? null);
      })
      .catch(() => {
        if (!ignore) setWorkspace(null);
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [userId, workspaceId, membersPath]);

  useEffect(() => {
    if (!operation || operationStatus(operation).kind !== "pending" || !userId) return;
    const controller = new AbortController();
    void pollOperationUntilSettled(operation.id, parseTrackedOperation, controller.signal)
      .then(async (next) => {
        if (controller.signal.aborted) return;
        if (next.completed && next.state === "SUCCEEDED") {
          await loadMemberships(controller.signal);
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
  }, [operation, userId, workspaceId, membersPath, loadMemberships]);

  async function loadMoreMembers() {
    if (!membersNextCursor) return;
    try {
      const result = await requestWhenReady<unknown>(
        `${membersPath}?cursor=${encodeURIComponent(membersNextCursor)}`,
      );
      if (result.kind !== "data") throw new Error("Phản hồi danh sách thành viên không hợp lệ.");
      setMemberships((current) => [...(current ?? []), ...parseMemberships(result.data)]);
      setMembersNextCursor(pageNextCursor(result.data));
    } catch (error) {
      setListError(error instanceof Error ? error.message : "Không tải thêm được danh sách thành viên.");
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
        await loadMemberships();
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
    } finally {
      setPendingAction(null);
    }
  }

  async function runMutation(
    action: string,
    signature: string,
    path: string,
    init: { method: string; body?: string; headers?: Record<string, string> },
  ): Promise<unknown> {
    const key = attemptKey(attempts.current, signature);
    setPendingAction(action);
    setActionError(null);
    try {
      const data = await submitMutation(path, init, key);
      const nextOperation = parseTrackedOperation(data);
      if (nextOperation) {
        setOperation(nextOperation);
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
          await loadMemberships();
        }
      }
      return data;
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Thao tác không thành công.");
      return null;
    } finally {
      setPendingAction(null);
    }
  }

  async function inviteMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = invitationBody(inviteEmail, inviteUserId, inviteRoles);
    if (!body) {
      setActionError("Hãy nhập đúng một trong hai: email hoặc mã người dùng, và chọn ít nhất một vai trò thuộc danh mục mời.");
      return;
    }
    const data = await runMutation(
      "invite",
      JSON.stringify([userId, workspaceId, "invite", body]),
      `/api/workspaces/${encodeURIComponent(workspaceId)}/invitations`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (data !== null) {
      const nextOperation = parseTrackedOperation(data);
      // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên dữ kiện
      // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
      if (nextOperation?.completed && nextOperation.state === "SUCCEEDED") {
        setInviteEmail("");
        setInviteUserId("");
      }
    }
  }

  async function issueStudent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = studentIssueBody(studentUsername, studentDisplayName, studentEmail);
    if (!body) {
      setActionError("Hãy nhập tên đăng nhập và tên hiển thị của học sinh.");
      return;
    }
    const data = await runMutation(
      "student",
      JSON.stringify([userId, workspaceId, "student", body]),
      `/api/workspaces/${encodeURIComponent(workspaceId)}/students`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (data === null) return;
    const parsed = parseStudentIssue(data);
    if (!parsed) {
      setActionError("Phản hồi thao tác không hợp lệ.");
      return;
    }
    // Chỉ lần tạo có handoff mới được hiển thị; gửi lại cùng key trả biên nhận không
    // kèm handoff nên không bao giờ hiện lại mật khẩu tạm.
    if (parsed.handoff) setHandoff(parsed.handoff);
    // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên dữ kiện
    // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
    if (parsed.operation.completed && parsed.operation.state === "SUCCEEDED") {
      setStudentUsername("");
      setStudentDisplayName("");
      setStudentEmail("");
    }
  }

  async function saveRoles(member: Membership) {
    const draft = roleDrafts[member.id] ?? member.roles;
    const body = membershipRolesBody(draft);
    if (!body) {
      setActionError("Hãy chọn ít nhất một vai trò; để gỡ hẳn thành viên, dùng nút Gỡ thành viên.");
      return;
    }
    await runMutation(
      `roles:${member.id}`,
      JSON.stringify([userId, workspaceId, "roles", member.id, body, member.version]),
      `${membersPath}/${encodeURIComponent(member.id)}/roles`,
      {
        method: "PUT",
        body: JSON.stringify(body),
        headers: { "If-Match": `"${member.version}"` },
      },
    );
  }

  async function revokeMember(member: Membership) {
    await runMutation(
      `revoke:${member.id}`,
      JSON.stringify([userId, workspaceId, "revoke", member.id, member.version]),
      `${membersPath}/${encodeURIComponent(member.id)}`,
      { method: "DELETE", headers: { "If-Match": `"${member.version}"` } },
    );
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="members-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="members-title">Thành viên trung tâm</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để dùng chức năng quản trị thuộc phạm vi của bạn."
        >
          <p className="introduction">
            Trung tâm: {workspace ? <strong>{workspace.name}</strong> : <code>{workspaceId}</code>}
          </p>
          {workspace?.subscription.expiresAt ? (
            <p>Gói hiện tại có hiệu lực đến {workspace.subscription.expiresAt}.</p>
          ) : null}
          {subscriptionExpired ? (
            <p role="alert">
              Gói của trung tâm đã hết hạn: không mời, cấp tài khoản hay thêm vai trò được;
              việc gỡ quyền vẫn thực hiện theo thẩm quyền.
            </p>
          ) : null}

          <section aria-labelledby="member-list-title">
            <h2 id="member-list-title">Danh sách thành viên</h2>
            {memberships === null ? <p role="status">Đang tải danh sách thành viên…</p> : null}
            {listError ? <p role="alert">{listError}</p> : null}
            {memberships !== null && memberships.length === 0 && !listError ? (
              <p>Trung tâm chưa có thành viên nào trong phạm vi xem của bạn.</p>
            ) : null}
            {memberships !== null && memberships.length > 0 ? (
              <ul>
                {memberships.map((member) => {
                  const draft = roleDrafts[member.id] ?? member.roles;
                  return (
                    <li key={member.id}>
                      <dl className="details">
                        <div>
                          <dt>Tên đăng nhập</dt>
                          <dd>{member.user.username}</dd>
                        </div>
                        {member.user.displayName ? (
                          <div>
                            <dt>Tên hiển thị</dt>
                            <dd>{member.user.displayName}</dd>
                          </div>
                        ) : null}
                        <div>
                          <dt>Vai trò</dt>
                          <dd>{member.roles.map((role) => roleLabels[role]).join(", ")}</dd>
                        </div>
                        <div>
                          <dt>Trạng thái</dt>
                          <dd>{member.state}</dd>
                        </div>
                      </dl>
                      {canManage ? (
                        <div className="section">
                          <fieldset>
                            <legend>Vai trò của {member.user.username}</legend>
                            {CENTER_ROLES.map((role) => (
                              <div key={role}>
                                <label>
                                  <input
                                    type="checkbox"
                                    checked={draft.includes(role)}
                                    disabled={
                                      pendingAction !== null
                                      || role === "OWNER"
                                      || (role === "MANAGER" && !canAssignManager)
                                      // Gói hết hạn chặn thêm vai trò: role chưa có bị khóa,
                                      // role đang có vẫn bỏ chọn được qua cùng nút Lưu.
                                      || (subscriptionExpired && !member.roles.includes(role))
                                    }
                                    onChange={(event) => setRoleDrafts((current) => ({
                                      ...current,
                                      [member.id]: toggleRole(draft, role, event.target.checked),
                                    }))}
                                  />
                                  {roleLabels[role]}
                                </label>
                              </div>
                            ))}
                          </fieldset>
                          <p>
                            Vai trò chủ sở hữu được chỉ định khi tạo trung tâm và không thay
                            đổi tại đây; cấp quản lý chỉ dành cho chủ sở hữu.
                          </p>
                          <button
                            className="action-button"
                            type="button"
                            disabled={pendingAction !== null}
                            onClick={() => void saveRoles(member)}
                          >
                            Lưu vai trò
                          </button>
                          {" "}
                          <button
                            className="secondary-button"
                            type="button"
                            disabled={pendingAction !== null}
                            onClick={() => void revokeMember(member)}
                          >
                            Gỡ thành viên
                          </button>
                        </div>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            ) : null}
            {membersNextCursor ? (
              <button
                className="secondary-button"
                type="button"
                onClick={() => void loadMoreMembers()}
              >
                Tải thêm thành viên
              </button>
            ) : null}
            <div className="section">
              <button
                className="secondary-button"
                type="button"
                disabled={pendingAction !== null || memberships === null}
                onClick={() => void loadMemberships()}
              >
                Tải lại danh sách
              </button>
            </div>
          </section>

          {canManage ? (
            <>
              <section className="section" aria-labelledby="invite-title">
                <h2 id="invite-title">Mời thành viên</h2>
                <p>
                  Điền đúng một trong hai: email hoặc mã người dùng có sẵn. Lời mời không
                  cấp vai trò chủ sở hữu; cấp quản lý chỉ dành cho chủ sở hữu.
                </p>
                <form onSubmit={inviteMember}>
                  <div className="field">
                    <label htmlFor="invite-email">Email người được mời</label>
                    <input
                      id="invite-email"
                      type="email"
                      value={inviteEmail}
                      onChange={(event) => setInviteEmail(event.target.value)}
                    />
                  </div>
                  <div className="field">
                    <label htmlFor="invite-user">Mã người dùng có sẵn</label>
                    <input
                      id="invite-user"
                      value={inviteUserId}
                      onChange={(event) => setInviteUserId(event.target.value)}
                    />
                  </div>
                  <fieldset>
                    <legend>Vai trò được mời</legend>
                    {INVITABLE_ROLES.map((role) => (
                      <div key={role}>
                        <label>
                          <input
                            type="checkbox"
                            checked={inviteRoles.includes(role)}
                            disabled={role === "MANAGER" && !canAssignManager}
                            onChange={(event) => setInviteRoles((current) => toggleRole(current, role, event.target.checked))}
                          />
                          {roleLabels[role]}
                        </label>
                      </div>
                    ))}
                  </fieldset>
                  <button
                    className="action-button"
                    type="submit"
                    disabled={pendingAction !== null || subscriptionExpired}
                  >
                    Gửi lời mời
                  </button>
                </form>
              </section>

              <section className="section" aria-labelledby="student-title">
                <h2 id="student-title">Cấp tài khoản học sinh</h2>
                <p>
                  Chỉ tạo tài khoản học sinh mới; tên đăng nhập trùng sẽ bị từ chối và
                  không chiếm hay đặt lại tài khoản đã có.
                </p>
                <form onSubmit={issueStudent}>
                  <div className="field">
                    <label htmlFor="student-username">Tên đăng nhập</label>
                    <input
                      id="student-username"
                      value={studentUsername}
                      onChange={(event) => setStudentUsername(event.target.value)}
                      required
                    />
                  </div>
                  <div className="field">
                    <label htmlFor="student-display-name">Tên hiển thị</label>
                    <input
                      id="student-display-name"
                      value={studentDisplayName}
                      onChange={(event) => setStudentDisplayName(event.target.value)}
                      required
                    />
                  </div>
                  <div className="field">
                    <label htmlFor="student-email">Email (không bắt buộc)</label>
                    <input
                      id="student-email"
                      type="email"
                      value={studentEmail}
                      onChange={(event) => setStudentEmail(event.target.value)}
                    />
                  </div>
                  <button
                    className="action-button"
                    type="submit"
                    disabled={pendingAction !== null || subscriptionExpired}
                  >
                    Cấp tài khoản
                  </button>
                </form>

                {handoff ? (
                  <section className="section" aria-labelledby="handoff-title">
                    <h3 id="handoff-title">Thông tin bàn giao tài khoản học sinh</h3>
                    <p role="alert">
                      Mật khẩu tạm chỉ hiển thị một lần tại đây. Hãy bàn giao ngay cho học
                      sinh qua kênh an toàn; thông tin này không xuất hiện lại ở danh sách,
                      trạng thái thao tác hay khi tải lại trang.
                    </p>
                    <dl className="details">
                      <div>
                        <dt>Tên đăng nhập</dt>
                        <dd><code>{handoff.username}</code></dd>
                      </div>
                      <div>
                        <dt>Mật khẩu tạm</dt>
                        <dd><code>{handoff.temporaryPassword}</code></dd>
                      </div>
                    </dl>
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() => setHandoff(null)}
                    >
                      Đã bàn giao, ẩn thông tin
                    </button>
                  </section>
                ) : null}
              </section>
            </>
          ) : null}

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
