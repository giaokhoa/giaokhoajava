"use client";

import { useEffect, useRef, useState } from "react";
import { isRecord, requestWorkspaces } from "@/lib/api-client";
import { useSession } from "./session-provider";

export type WorkspaceKind = "PERSONAL" | "CENTER" | "SYSTEM";

export type WorkspaceView = {
  id: string;
  kind: WorkspaceKind;
  name: string;
  subscription: {
    effective: boolean;
    expiresAt?: string;
  };
  roles: string[];
  syncState: string;
};

type LoadState = "idle" | "loading" | "ready" | "error";

type WorkspaceResult = {
  userId: string;
  workspaces: WorkspaceView[];
  loadState: Exclude<LoadState, "idle" | "loading">;
  message: string | null;
};

const kindLabels: Record<WorkspaceKind, string> = {
  PERSONAL: "Cá nhân",
  CENTER: "Trung tâm",
  SYSTEM: "Hệ thống",
};

function isWorkspaceKind(value: unknown): value is WorkspaceKind {
  return value === "PERSONAL" || value === "CENTER" || value === "SYSTEM";
}

export function parseWorkspaces(data: unknown): WorkspaceView[] {
  if (!Array.isArray(data)) return [];

  const workspaces: WorkspaceView[] = [];
  for (const item of data) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || !isWorkspaceKind(item.kind)
        || typeof item.name !== "string" || !item.name) {
      continue;
    }
    const subscription = isRecord(item.subscription) ? item.subscription : {};
    workspaces.push({
      id: item.id,
      kind: item.kind,
      name: item.name,
      subscription: {
        effective: subscription.effective === true,
        expiresAt: typeof subscription.expiresAt === "string" && subscription.expiresAt
          ? subscription.expiresAt
          : undefined,
      },
      roles: Array.isArray(item.roles)
        ? item.roles.filter((role): role is string => typeof role === "string" && Boolean(role))
        : [],
      syncState: typeof item.syncState === "string" && item.syncState ? item.syncState : "UNKNOWN",
    });
  }
  return workspaces;
}

export function WorkspaceSwitcher() {
  const { session, activeWorkspaceId, selectWorkspace } = useSession();
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<WorkspaceResult | null>(null);
  const activeWorkspaceIdRef = useRef(activeWorkspaceId);
  const userId = session.status === "authenticated" ? session.user.id : null;

  useEffect(() => {
    activeWorkspaceIdRef.current = activeWorkspaceId;
  }, [activeWorkspaceId]);

  useEffect(() => {
    if (!userId) return;

    let ignore = false;
    requestWorkspaces(userId)
      .then((response) => {
        if (ignore) return;
        if (response.kind !== "data") {
          throw new Error("Phản hồi danh sách không gian không hợp lệ.");
        }
        const next = parseWorkspaces(response.data);
        setResult({ userId, workspaces: next, loadState: "ready", message: null });
        if (!next.some((workspace) => workspace.id === activeWorkspaceIdRef.current)) {
          selectWorkspace(next[0]?.id ?? null);
        }
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setResult({
          userId,
          workspaces: [],
          loadState: "error",
          message: error instanceof Error ? error.message : "Không tải được danh sách không gian.",
        });
        selectWorkspace(null);
      });

    return () => {
      ignore = true;
    };
  }, [selectWorkspace, userId, reload]);

  if (session.status !== "authenticated") return null;

  // Kết quả được gắn với user đã tải; khi đổi tài khoản, không hiển thị dữ liệu cũ
  // trong lúc danh sách của tài khoản mới đang tải.
  const currentResult = result?.userId === userId ? result : null;
  const workspaces = currentResult?.workspaces ?? [];
  const loadState: LoadState = currentResult?.loadState ?? "loading";
  const message = currentResult?.message ?? null;

  const activeWorkspace = workspaces.find((workspace) => workspace.id === activeWorkspaceId)
    ?? workspaces[0];

  return (
    <section aria-labelledby="workspace-switcher-title">
      <h2 id="workspace-switcher-title">Không gian làm việc</h2>

      {loadState === "loading" ? <p>Đang tải danh sách không gian…</p> : null}
      {loadState === "error" ? <>
        <p role="alert">{message}</p>
        <button type="button" className="secondary-button" onClick={() => { setResult(null); setReload((value) => value + 1); }}>Tải lại danh sách không gian</button>
      </> : null}
      {loadState === "ready" && workspaces.length === 0 ? (
        <p>Chưa có không gian khả dụng. Hoàn tất xác minh danh tính để tiếp tục.</p>
      ) : null}

      {workspaces.length > 0 && activeWorkspace ? (
        <>
          <div className="field">
            <label htmlFor="workspace-select">Không gian hiện tại</label>
            <select
              id="workspace-select"
              value={activeWorkspace.id}
              onChange={(event) => selectWorkspace(event.target.value)}
            >
              {workspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name} ({kindLabels[workspace.kind]})
                </option>
              ))}
            </select>
          </div>

          <dl className="details">
            <div>
              <dt>Loại không gian</dt>
              <dd>{kindLabels[activeWorkspace.kind]}</dd>
            </div>
            <div>
              <dt>Vai trò của bạn</dt>
              <dd>{activeWorkspace.roles.length > 0 ? activeWorkspace.roles.join(", ") : "Chưa có vai trò"}</dd>
            </div>
            {activeWorkspace.kind !== "SYSTEM" ? (
              <div>
                <dt>Gói sử dụng</dt>
                <dd>
                  {activeWorkspace.subscription.effective
                    ? activeWorkspace.subscription.expiresAt
                      ? `Đang hiệu lực đến ${activeWorkspace.subscription.expiresAt}`
                      : "Đang hiệu lực"
                    : "Hiện không còn hiệu lực"}
                </dd>
              </div>
            ) : null}
          </dl>
        </>
      ) : null}
    </section>
  );
}
