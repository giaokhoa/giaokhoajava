"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { requestWorkspaces } from "@/lib/api-client";
import { useSession } from "@/features/identity/session-provider";
import {
  parseWorkspaces,
  type WorkspaceView,
} from "@/features/identity/workspace-switcher";

// Workspace cá nhân của chính người đang đăng nhập: backend tự bootstrap khi đăng
// nhập nên luôn có, không cần gói hay membership nào để tồn tại.
export function parsePersonalWorkspace(data: unknown): WorkspaceView | null {
  return parseWorkspaces(data).find((item) => item.kind === "PERSONAL") ?? null;
}

// Personal không copy role center: người sở hữu vẫn được xem/thu hồi lớp khi hết gói.
// Quyền mở rộng (tạo lớp, thêm học sinh, khóa/môn) bị chặn riêng theo kỳ gói tại UI
// và backend; không dùng trạng thái gói để ẩn luôn quyền thu hồi.
export function canManagePersonalClasses(workspace: WorkspaceView | null): boolean {
  return workspace?.kind === "PERSONAL";
}

export function PersonalEntitlements() {
  const { session } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    requestWorkspaces(userId)
      .then((response) => {
        if (ignore || response.kind !== "data") return;
        setWorkspace(parsePersonalWorkspace(response.data));
        setLoadError(null);
        setLoaded(true);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setWorkspace(null);
        setLoadError(error instanceof Error ? error.message : "Không tải được trạng thái gói.");
        setLoaded(true);
      });
    return () => {
      ignore = true;
    };
  }, [userId, reload]);

  return (
    <section className="section" aria-labelledby="personal-plan-title">
      <h2 id="personal-plan-title">Gói cá nhân</h2>
      {!loaded ? <p role="status">Đang tải trạng thái gói…</p> : null}
      {loadError ? (
        <>
          <p role="alert">{loadError}</p>
          <button
            type="button"
            className="secondary-button"
            onClick={() => { setLoaded(false); setLoadError(null); setReload((value) => value + 1); }}
          >
            Tải lại trạng thái gói
          </button>
        </>
      ) : null}
      {loaded && !loadError && workspace === null ? (
        <p>Chưa có không gian cá nhân. Hoàn tất xác minh danh tính để tiếp tục.</p>
      ) : null}
      {loaded && !loadError && workspace !== null ? (
        <>
          {workspace.subscription.effective ? (
            <p>
              Gói cá nhân đang hiệu lực
              {workspace.subscription.expiresAt ? ` đến ${workspace.subscription.expiresAt}` : ""}.
              Lớp và kho cá nhân mở theo gói của chính bạn.
            </p>
          ) : (
            <p>
              Chưa có gói cá nhân hiệu lực. Gói do người vận hành kích hoạt thủ công;
              lớp và kho cá nhân chỉ mở khi gói còn hiệu lực, còn lịch sử luyện tập
              trên tài nguyên hệ thống vẫn đọc được.
            </p>
          )}
          <p>
            <Link href="/personal/classes">Xem lớp cá nhân</Link>
          </p>
        </>
      ) : null}
    </section>
  );
}
