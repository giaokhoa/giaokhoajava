"use client";

import { useEffect, useState } from "react";
import { apiRequest, isRecord, listItems } from "@/lib/api-client";
import { SessionGate } from "@/features/identity/session-gate";
import { RecoveryRequestForm } from "@/features/identity/recovery-request-form";
import { useSession } from "@/features/identity/session-provider";
import { WorkspaceSwitcher } from "@/features/identity/workspace-switcher";
import { PersonalEntitlements } from "@/features/subscriptions/personal-entitlements";

export type PracticeAttempt = {
  id: string;
  attemptedAt: string;
  systemResourceId: string;
  versionId: string;
  outcome?: string;
};

function parseAttempt(item: unknown): PracticeAttempt | null {
  if (!isRecord(item)
      || typeof item.id !== "string" || !item.id
      || typeof item.attemptedAt !== "string" || !item.attemptedAt
      || typeof item.systemResourceId !== "string" || !item.systemResourceId
      || typeof item.versionId !== "string" || !item.versionId) {
    return null;
  }
  return {
    id: item.id,
    attemptedAt: item.attemptedAt,
    systemResourceId: item.systemResourceId,
    versionId: item.versionId,
    // outcome_stub trong model serialize thành outcomeStub theo quy tắc camelCase của hợp đồng; giữ outcome cho tương thích.
    outcome: typeof item.outcomeStub === "string" && item.outcomeStub ? item.outcomeStub
      : typeof item.outcome === "string" && item.outcome ? item.outcome : undefined,
  };
}

export function parsePracticeHistory(data: unknown): PracticeAttempt[] {
  const items = listItems(data);
  const attempts: PracticeAttempt[] = [];
  for (const item of items) {
    const attempt = parseAttempt(item);
    if (attempt) attempts.push(attempt);
  }
  return attempts;
}

export function PersonalContent() {
  const { session, refreshSession } = useSession();
  const [reload, setReload] = useState(0);
  const [attempts, setAttempts] = useState<PracticeAttempt[] | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const userId = session.status === "authenticated" ? session.user.id : null;

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    apiRequest<unknown>("/api/me/practice-history")
      .then((response) => {
        if (ignore || response.kind !== "data") return;
        setAttempts(parsePracticeHistory(response.data));
        setHistoryError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setAttempts([]);
        setHistoryError(error instanceof Error ? error.message : "Không tải được lịch sử luyện tập.");
      });
    return () => {
      ignore = true;
    };
  }, [userId, reload]);

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="personal-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="personal-title">Không gian cá nhân</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để xem không gian cá nhân và lịch sử luyện tập mẫu của bạn."
        >
          {session.status === "authenticated" ? (
          <>
            <p className="introduction">
              Đây là không gian cá nhân trung lập của bạn: chỉ dữ liệu mẫu của chính bạn,
              không cần gói soạn học liệu hay lớp học để luyện tập.
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
            </section>

            <div className="section">
              <WorkspaceSwitcher />
            </div>

            <PersonalEntitlements />

            <section className="section" aria-labelledby="practice-history-title">
              <h2 id="practice-history-title">Lịch sử luyện tập mẫu</h2>
              <p>
                Chỉ các lần luyện tập trên tài nguyên hệ thống của chính bạn. Trang này không
                hiển thị nội dung bài làm và không tạo gói hoặc lớp học.
              </p>
              {attempts === null ? <p role="status">Đang tải lịch sử luyện tập…</p> : null}
              {historyError ? <>
                <p role="alert">{historyError}</p>
                <button type="button" className="secondary-button" onClick={() => { setAttempts(null); setHistoryError(null); setReload((value) => value + 1); }}>Tải lại lịch sử</button>
              </> : null}
              {attempts !== null && attempts.length === 0 && !historyError ? (
                <p>Chưa có lần luyện tập mẫu nào.</p>
              ) : null}
              {attempts !== null && attempts.length > 0 ? (
                <ul>
                  {attempts.map((attempt) => (
                    <li key={attempt.id}>
                      <p>
                        {attempt.attemptedAt}
                        {attempt.outcome ? ` — Kết quả: ${attempt.outcome}` : " — Chưa có kết quả"}
                      </p>
                      <p>
                        Tài nguyên: <code>{attempt.systemResourceId}</code>
                        {" "}phiên bản: <code>{attempt.versionId}</code>
                      </p>
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>

            <div className="section">
              <RecoveryRequestForm userId={session.user.id} />
            </div>
          </>
          ) : null}
        </SessionGate>
      </section>
    </main>
  );
}
