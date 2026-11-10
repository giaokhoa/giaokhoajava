"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { isRecord, listItems, requestAllPages, requestWhenReady } from "../../lib/api-client";
import { SessionGate } from "../identity/session-gate";
import { useSession } from "../identity/session-provider";
import {
  attemptKey,
  closeAttempt,
  submitMutation,
} from "../operations/operation-attempts";
import { OperationPanel } from "../operations/operation-panel";
import { useOperationCheck } from "../../lib/use-operation-check";
import {
  operationStatus,
  parseTrackedOperation,
  pollOperationUntilSettled,
  type TrackedOperation,
} from "../../lib/operation-status";
import {
  parseMemberships,
  type Membership,
} from "../../lib/memberships";

// Vòng đời liên kết theo hợp đồng HTTP: REQUESTED → APPROVED/REJECTED, APPROVED → REVOKED.
// REJECTED là kết thúc: muốn liên kết lại phải gửi yêu cầu mới.
export const GUARDIAN_LINK_STATES = ["REQUESTED", "APPROVED", "REJECTED", "REVOKED"] as const;
export type GuardianLinkState = (typeof GUARDIAN_LINK_STATES)[number];

const stateLabels: Record<GuardianLinkState, string> = {
  REQUESTED: "Đang chờ duyệt",
  APPROVED: "Đã duyệt",
  REJECTED: "Đã từ chối",
  REVOKED: "Đã gỡ",
};

export type GuardianLink = {
  id: string;
  workspaceId: string;
  parentUserId: string;
  studentUserId: string;
  state: GuardianLinkState;
  version: number;
};

export type GuardianDecision = "APPROVE" | "REJECT";

function isGuardianLinkState(value: unknown): value is GuardianLinkState {
  return typeof value === "string"
    && (GUARDIAN_LINK_STATES as readonly string[]).includes(value);
}

// Chỉ giữ liên kết đúng GuardianLinkView của hợp đồng. Trạng thái ngoài vòng đời cố
// định làm bản ghi không đáng tin; bỏ thay vì hiển thị sai vòng đời.
export function parseGuardianLinks(data: unknown): GuardianLink[] {
  const links: GuardianLink[] = [];
  for (const item of listItems(data)) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.workspaceId !== "string" || !item.workspaceId
        || typeof item.parentUserId !== "string" || !item.parentUserId
        || typeof item.studentUserId !== "string" || !item.studentUserId
        || !isGuardianLinkState(item.state)
        || typeof item.version !== "number" || !Number.isSafeInteger(item.version)
        || item.version < 0) {
      continue;
    }
    links.push({
      id: item.id,
      workspaceId: item.workspaceId,
      parentUserId: item.parentUserId,
      studentUserId: item.studentUserId,
      state: item.state,
      version: item.version,
    });
  }
  return links;
}

// Parent chỉ gửi mã học sinh; server tự lấy phụ huynh là actor hiện hành nên body
// không mang parentId và không có đường gửi yêu cầu hộ người khác.
export function guardianRequestBody(
  studentUserId: string,
): { studentUserId: string } | null {
  const trimmed = studentUserId.trim();
  return trimmed ? { studentUserId: trimmed } : null;
}

// Quyết định duyệt luôn mang expectedVersion lấy từ bản đọc hiện tại của liên kết.
export function guardianDecisionBody(
  link: GuardianLink,
  decision: GuardianDecision,
): { decision: GuardianDecision; expectedVersion: number } {
  return { decision, expectedVersion: link.version };
}

// Ẩn chức năng theo vai trò của chính actor trong center này, suy từ danh sách thành
// viên đã tải. Đây chỉ là hiển thị; backend vẫn xét lại mọi thao tác.
export function guardianPermissions(
  memberships: readonly Membership[],
  userId: string | null,
): { canManage: boolean; canRequest: boolean } {
  const own = userId ? memberships.find((item) => item.user.id === userId) : undefined;
  const roles = own?.roles ?? [];
  return {
    canManage: roles.includes("OWNER") || roles.includes("MANAGER"),
    canRequest: roles.includes("PARENT"),
  };
}

export function GuardianLinksContent({ workspaceId }: { workspaceId: string }) {
  const { session } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  return <GuardianLinksInner key={`${userId ?? "anonymous"}|${workspaceId}`} workspaceId={workspaceId} />;
}

function GuardianLinksInner({ workspaceId }: { workspaceId: string }) {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const userId = session.status === "authenticated" ? session.user.id : null;
  // Mọi dữ liệu đã tải đều gắn khóa ngữ cảnh (actor + workspace): đổi trung tâm thì
  // dữ liệu của trung tâm trước không còn khớp khóa và không được hiển thị nữa, kể cả
  // trong lúc bản mới đang tải.
  const contextKey = `${userId ?? ""}|${workspaceId}`;
  const [loadedLinks, setLoadedLinks] = useState<{ key: string; links: GuardianLink[] } | null>(null);
  const [loadedMemberships, setLoadedMemberships] = useState<{ key: string; items: Membership[] } | null>(null);
  const [loadError, setLoadError] = useState<{ key: string; message: string } | null>(null);
  const [studentUserId, setStudentUserId] = useState("");
  const [tracked, setTracked] = useState<{ key: string; operation: TrackedOperation; signature: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const attempts = useRef(new Map<string, string>());

  const links = loadedLinks?.key === contextKey ? loadedLinks.links : null;
  const listError = loadError?.key === contextKey ? loadError.message : null;
  const operation = tracked?.key === contextKey ? tracked.operation : null;
  const operationSignature = tracked?.key === contextKey ? tracked.signature : null;
  const memberships = loadedMemberships?.key === contextKey ? loadedMemberships.items : [];
  const { canManage, canRequest } = guardianPermissions(memberships, userId);

  const linksPath = `/api/workspaces/${encodeURIComponent(workspaceId)}/guardian-links`;
  const membersPath = `/api/workspaces/${encodeURIComponent(workspaceId)}/memberships`;

  async function loadLinks() {
    setLoadError(null);
    const [linksResult, membersResult] = await Promise.allSettled([
      requestWhenReady(linksPath),
      requestAllPages(membersPath),
    ]);
    setLoadedMemberships({ key: contextKey,
      items: membersResult.status === "fulfilled" ? parseMemberships(membersResult.value) : [] });
    if (linksResult.status === "fulfilled" && linksResult.value.kind === "data") {
      setLoadedLinks({ key: contextKey, links: parseGuardianLinks(linksResult.value.data) });
    } else {
      setLoadedLinks({ key: contextKey, links: [] });
      setLoadError({ key: contextKey,
        message: linksResult.status === "rejected" && linksResult.reason instanceof Error
          ? linksResult.reason.message : "Không tải được danh sách liên kết." });
    }
  }

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    const controller = new AbortController();
    requestWhenReady(linksPath, { signal: controller.signal })
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setLoadedLinks({ key: contextKey, links: parseGuardianLinks(result.data) });
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setLoadedLinks({ key: contextKey, links: [] });
        setLoadError({
          key: contextKey,
          message: error instanceof Error ? error.message : "Không tải được danh sách liên kết.",
        });
      });
    // Quyền hiển thị suy từ membership của chính actor; lỗi tải danh sách thành viên
    // chỉ làm ẩn nút, không chặn danh sách liên kết mà server đã quyết quyền.
    requestAllPages(membersPath, { signal: controller.signal })
      .then((items) => {
        if (ignore) return;
        setLoadedMemberships({ key: contextKey, items: parseMemberships(items) });
      })
      .catch(() => {
        if (!ignore) setLoadedMemberships({ key: contextKey, items: [] });
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [userId, workspaceId, contextKey, linksPath, membersPath]);

  // Operation 202 chỉ thành công sau poll SUCCEEDED; refresh lại nguồn quyền hiện hành.
  useEffect(() => {
    if (!operation || operationStatus(operation).kind !== "pending" || !userId) return;
    const controller = new AbortController();
    const signature = operationSignature;
    void pollOperationUntilSettled(operation.id, parseTrackedOperation, controller.signal)
      .then(async (next) => {
        if (controller.signal.aborted) return;
        if (next.completed && next.state === "SUCCEEDED") {
          const [linkResponse, memberResponse] = await Promise.allSettled([
            requestWhenReady(linksPath, { signal: controller.signal }),
            requestAllPages(membersPath, { signal: controller.signal }),
          ]);
          if (controller.signal.aborted) return;
          if (linkResponse.status === "fulfilled" && linkResponse.value.kind === "data") {
            setLoadedLinks({ key: contextKey, links: parseGuardianLinks(linkResponse.value.data) });
            setLoadError(null);
          } else {
            setLoadedLinks({ key: contextKey, links: [] });
            setLoadError({ key: contextKey, message: "Không làm mới được danh sách liên kết sau thay đổi quyền." });
          }
          // Nếu membership chưa tải lại được, ẩn quyền UI thay vì giữ role cũ.
          setLoadedMemberships({ key: contextKey,
            items: memberResponse.status === "fulfilled" ? parseMemberships(memberResponse.value) : [] });
        }
        setTracked({ key: contextKey, operation: next, signature: signature ?? "" });
        if (signature) closeAttempt(attempts.current, signature, next);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setActionError(error instanceof Error ? error.message : "Không kiểm tra được trạng thái thao tác.");
        }
      });
    return () => controller.abort();
  }, [operation, contextKey, userId, operationSignature, linksPath, membersPath]);

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const nextOperation = await checkOperationStatus(operation.id);
      setTracked({ key: contextKey, operation: nextOperation, signature: operationSignature ?? "" });
      if (operationSignature) {
        closeAttempt(attempts.current, operationSignature, nextOperation);
      }
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        await loadLinks();
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
        setTracked({ key: contextKey, operation: nextOperation, signature });
        closeAttempt(attempts.current, signature, nextOperation);
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
          await loadLinks();
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

  async function requestLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = guardianRequestBody(studentUserId);
    if (!body) {
      setActionError("Hãy nhập mã học sinh cần liên kết.");
      return;
    }
    const data = await runMutation(
      "request",
      JSON.stringify([userId, workspaceId, "request", body]),
      linksPath,
      { method: "POST", body: JSON.stringify(body) },
    );
    // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên dữ kiện
    // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
    if (data !== null) {
      const nextOperation = parseTrackedOperation(data);
      if (nextOperation?.completed && nextOperation.state === "SUCCEEDED") {
        setStudentUserId("");
      }
    }
  }

  async function decideLink(link: GuardianLink, decision: GuardianDecision) {
    const body = guardianDecisionBody(link, decision);
    await runMutation(
      `decision:${link.id}`,
      JSON.stringify([userId, workspaceId, "decision", link.id, body]),
      `${linksPath}/${encodeURIComponent(link.id)}/decision`,
      { method: "POST", body: JSON.stringify(body) },
    );
  }

  async function revokeLink(link: GuardianLink) {
    await runMutation(
      `revoke:${link.id}`,
      JSON.stringify([userId, workspaceId, "revoke", link.id, link.version]),
      `${linksPath}/${encodeURIComponent(link.id)}`,
      { method: "DELETE" },
    );
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="guardian-links-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="guardian-links-title">Liên kết phụ huynh</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để gửi yêu cầu liên kết hoặc duyệt yêu cầu của phụ huynh."
        >
          <p className="introduction">
            Phụ huynh gửi yêu cầu liên kết tới con; chủ sở hữu hoặc quản lý của trung tâm
            duyệt yêu cầu và gỡ liên kết khi cần. Liên kết đang chờ duyệt chưa cho xem
            kết quả của con.
          </p>

          {canRequest ? (
            <section className="section" aria-labelledby="request-title">
              <h2 id="request-title">Gửi yêu cầu liên kết</h2>
              <p>
                Yêu cầu được gửi cho chính bạn tới một học sinh của trung tâm này và chỉ
                có hiệu lực sau khi quản lý duyệt. Nếu yêu cầu bị từ chối, hãy gửi yêu
                cầu mới thay vì chờ yêu cầu cũ được mở lại.
              </p>
              <form onSubmit={requestLink}>
                <div className="field">
                  <label htmlFor="guardian-student-id">Mã học sinh</label>
                  <input
                    id="guardian-student-id"
                    value={studentUserId}
                    onChange={(event) => setStudentUserId(event.target.value)}
                    required
                  />
                </div>
                <button
                  className="action-button"
                  type="submit"
                  disabled={pendingAction !== null}
                >
                  Gửi yêu cầu liên kết
                </button>
              </form>
            </section>
          ) : null}

          <section aria-labelledby="link-list-title">
            <h2 id="link-list-title">
              {canManage ? "Danh sách liên kết của trung tâm" : "Liên kết của tôi"}
            </h2>
            {links === null ? <p role="status">Đang tải danh sách liên kết…</p> : null}
            {listError ? <p role="alert">{listError}</p> : null}
            {links !== null && links.length === 0 && !listError ? (
              <p>Chưa có liên kết nào trong phạm vi xem của bạn.</p>
            ) : null}
            {links !== null && links.length > 0 ? (
              <ul>
                {links.map((link) => (
                  <li key={link.id}>
                    <dl className="details">
                      <div>
                        <dt>Phụ huynh</dt>
                        <dd><code>{link.parentUserId}</code></dd>
                      </div>
                      <div>
                        <dt>Học sinh</dt>
                        <dd><code>{link.studentUserId}</code></dd>
                      </div>
                      <div>
                        <dt>Trạng thái</dt>
                        <dd>{stateLabels[link.state]}</dd>
                      </div>
                    </dl>
                    {link.state === "APPROVED" ? (
                      <p>
                        <Link
                          href={`/centers/${encodeURIComponent(workspaceId)}/children/${encodeURIComponent(link.studentUserId)}/results`}
                        >
                          Xem kết quả của con
                        </Link>
                      </p>
                    ) : null}
                    {canManage && link.state === "REQUESTED" ? (
                      <div className="section">
                        <button
                          className="action-button"
                          type="button"
                          disabled={pendingAction !== null}
                          onClick={() => void decideLink(link, "APPROVE")}
                        >
                          Duyệt liên kết
                        </button>
                        {" "}
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={pendingAction !== null}
                          onClick={() => void decideLink(link, "REJECT")}
                        >
                          Từ chối
                        </button>
                      </div>
                    ) : null}
                    {canManage && link.state === "APPROVED" ? (
                      <div className="section">
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={pendingAction !== null}
                          onClick={() => void revokeLink(link)}
                        >
                          Gỡ liên kết
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
                disabled={pendingAction !== null || links === null}
                onClick={() => void loadLinks()}
              >
                Tải lại danh sách
              </button>
            </div>
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
