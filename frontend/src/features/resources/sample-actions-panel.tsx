"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, requestWhenReady } from "@/lib/api-client";
import { useSession } from "@/features/identity/session-provider";
import {
  attemptKey,
  closeAttempt,
} from "@/features/operations/operation-attempts";
import {
  operationStatus,
  pollOperationUntilSettled,
  type OperationView,
} from "@/lib/operation-status";
import {
  CorrectionNoticeView,
  parseCorrectionNotice,
  type CorrectionNotice,
} from "./correction-notice";
import {
  blockedActionReason,
  sampleActionExpiryBehavior,
} from "@/features/subscriptions/subscription-state";

type FieldKind = "text" | "number" | "mode";

export type SampleActionSpec = {
  action: string;
  fields: { name: string; kind: FieldKind }[];
};

const text = (name: string) => ({ name, kind: "text" as const });

// Danh mục discriminator và trường đúng hợp đồng SampleAction; thứ tự trường
// chỉ để hiển thị, payload luôn gửi đúng tên trường của hợp đồng.
export const SAMPLE_ACTION_SPECS: SampleActionSpec[] = [
  { action: "PRACTICE_SYSTEM_VERSION", fields: [text("resourceId"), text("versionId"), text("contentStub")] },
  { action: "CREATE_RESOURCE", fields: [text("courseSubjectId"), text("subjectId"), text("kind"), text("label")] },
  { action: "SAVE_VERSION", fields: [text("resourceId"), text("versionId")] },
  { action: "FORK_VERSION", fields: [text("resourceId"), text("versionId"), text("label")] },
  {
    action: "SHARE_VERSION",
    fields: [
      text("resourceId"),
      text("versionId"),
      text("recipientWorkspaceId"),
      { name: "mode", kind: "mode" },
      text("actions"),
      text("allowedWorkspaceIds"),
      text("expiresAt"),
    ],
  },
  { action: "ACCEPT_SHARE", fields: [text("grantId")] },
  { action: "REVOKE_SHARE", fields: [text("grantId")] },
  { action: "RECEIVE_UPDATE", fields: [text("libraryItemId"), text("replacementVersionId")] },
  {
    action: "CORRECT_SOURCE",
    fields: [text("resourceId"), text("affectedVersionId"), text("replacementVersionId"), text("reason")],
  },
  {
    action: "ASSIGN_ACTIVITY",
    fields: [
      text("courseSubjectId"),
      text("resourceId"),
      text("versionId"),
      text("dueAt"),
      { name: "attemptLimit", kind: "number" },
    ],
  },
  { action: "SUBMIT_ACTIVITY", fields: [text("activityId"), text("contentStub")] },
  { action: "GRADE_RESULT", fields: [text("resultId"), text("scoreStub"), text("commentStub")] },
  { action: "PUBLISH_RESULT", fields: [text("resultId")] },
  { action: "EXTEND_DEADLINE", fields: [text("activityId"), text("dueAt")] },
  { action: "OPEN_ATTEMPT", fields: [text("activityId"), text("studentUserId")] },
  { action: "REASSIGN_ACTIVITY", fields: [text("activityId"), text("dueAt")] },
];

export function buildSampleActionPayload(
  action: string,
  values: Record<string, string>,
): Record<string, unknown> {
  const spec = SAMPLE_ACTION_SPECS.find((item) => item.action === action);
  const payload: Record<string, unknown> = { action };
  if (!spec) return payload;
  for (const field of spec.fields) {
    const raw = (values[field.name] ?? "").trim();
    if (!raw) continue;
    if (field.kind === "number") {
      const parsed = Number(raw);
      payload[field.name] = Number.isFinite(parsed) ? parsed : raw;
    } else if (field.name === "allowedWorkspaceIds") {
      payload[field.name] = raw.split(",").map((item) => item.trim()).filter(Boolean);
    } else {
      payload[field.name] = raw;
    }
  }
  return payload;
}

// Cùng workspace + cùng payload là một ý định: dùng lại một key giữa các lần
// gửi lại khi operation chưa hoàn tất, để retry fence không sinh key mới.
// Dạng tuple theo đúng quy ước chữ ký của các form quản trị; phạm vi theo
// user đã có sẵn vì form được dựng lại từ đầu khi đổi tài khoản (key theo user).
export function sampleActionAttemptSignature(
  workspaceId: string,
  payload: Record<string, unknown>,
): string {
  return JSON.stringify([workspaceId, payload]);
}

type Entry = [string, string];

function entriesOf(data: unknown): Entry[] {
  if (typeof data !== "object" || data === null || Array.isArray(data)) return [];
  const entries: Entry[] = [];
  for (const [key, value] of Object.entries(data)) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
      entries.push([key, String(value)]);
    }
  }
  return entries;
}

export type SampleActionResultView = {
  state: string;
  completed: boolean;
  entries: Entry[];
};

// Kết quả đồng bộ của sample action mang thẳng state/completed; dạng lồng
// trong operation được chuẩn hoá về cùng một hình để hiển thị.
export function parseSampleActionResult(data: unknown): SampleActionResultView | null {
  if (typeof data !== "object" || data === null || Array.isArray(data)) return null;
  const record = data as Record<string, unknown>;
  const nested = typeof record.operation === "object" && record.operation !== null
    ? (record.operation as Record<string, unknown>)
    : null;
  const source = nested ?? record;
  if (typeof source.state !== "string" || !source.state || typeof source.completed !== "boolean") {
    return null;
  }
  return { state: source.state, completed: source.completed, entries: entriesOf(source) };
}

function parseOperationView(data: unknown): OperationView | null {
  const parsed = parseSampleActionResult(data);
  if (!parsed) return null;
  const record = (typeof data === "object" && data !== null ? data : {}) as Record<string, unknown>;
  const nested = typeof record.operation === "object" && record.operation !== null
    ? (record.operation as Record<string, unknown>)
    : record;
  const id = typeof nested.id === "string" && nested.id
    ? nested.id
    : typeof record.operationId === "string" ? record.operationId : "";
  if (!id || typeof nested.targetType !== "string") return null;
  return {
    id,
    state: parsed.state as OperationView["state"],
    completed: parsed.completed,
    targetType: nested.targetType,
    targetId: typeof nested.targetId === "string" ? nested.targetId : undefined,
    errorCode: typeof nested.errorCode === "string" ? nested.errorCode : undefined,
  };
}

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    // Mã nghiệp vụ đã biết được kèm lý do dễ hiểu; vẫn giữ mã để đối chiếu bằng chứng.
    const reason = blockedActionReason(error.code);
    if (reason) return error.code ? `${error.code} — ${reason}` : reason;
    return error.code ? `${error.code}: ${error.message}` : error.message;
  }
  return error instanceof Error ? error.message : "Yêu cầu không thành công.";
}

type Outcome = {
  state: string;
  message: string;
  sentEntries: Entry[];
  resultEntries: Entry[];
};

type ContentState =
  | { kind: "body" | "answer"; text: string }
  | { kind: "error"; message: string };

function contentText(data: unknown): string {
  if (typeof data === "string") return data;
  const entries = entriesOf(data);
  if (entries.length) return entries.map(([key, value]) => `${key}: ${value}`).join("\n");
  return data === null || data === undefined ? "" : JSON.stringify(data);
}

function SampleActionForm() {
  const [action, setAction] = useState(SAMPLE_ACTION_SPECS[0].action);
  const [values, setValues] = useState<Record<string, string>>({ mode: "REVOCABLE" });
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<CorrectionNotice | null>(null);
  const [receivedUpdates, setReceivedUpdates] = useState<ReadonlySet<string>>(new Set());
  const [content, setContent] = useState<ContentState | null>(null);
  const [contentBusy, setContentBusy] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const attemptsRef = useRef(new Map<string, string>());

  useEffect(() => () => abortRef.current?.abort(), []);

  const spec = SAMPLE_ACTION_SPECS.find((item) => item.action === action) ?? SAMPLE_ACTION_SPECS[0];
  const workspaceId = (values.workspaceId ?? "").trim();

  function setField(name: string, value: string) {
    setValues((current) => ({ ...current, [name]: value }));
  }

  function beginOperation(): AbortSignal {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    return controller.signal;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!workspaceId) {
      setActionError("Thiếu workspaceId của ngữ cảnh đang kiểm chứng.");
      return;
    }
    const signal = beginOperation();
    setBusy(true);
    setContentBusy(false);
    setOutcome(null);
    setActionError(null);
    const payload = buildSampleActionPayload(action, values);
    const signature = sampleActionAttemptSignature(workspaceId, payload);
    const sentEntries: Entry[] = [["workspaceId", workspaceId], ...entriesOf(payload)];
    try {
      const result = await requestWhenReady<unknown>(
        `/api/workspaces/${encodeURIComponent(workspaceId)}/samples/actions`,
        {
          method: "POST",
          body: JSON.stringify(payload),
          signal,
          headers: { "Idempotency-Key": attemptKey(attemptsRef.current, signature) },
        },
      );
      if (result.kind === "pending") {
        const pendingView = parseOperationView(result.data);
        if (!pendingView) throw new Error("Phản hồi 202 thao tác không hợp lệ.");
        const settled = await pollOperationUntilSettled(pendingView.id, parseOperationView, signal);
        closeAttempt(attemptsRef.current, signature, settled);
        const status = operationStatus(settled);
        setOutcome({
          state: settled.state,
          message: status.message,
          sentEntries,
          resultEntries: entriesOf(settled),
        });
        if (status.kind === "succeeded") markUpdateReceived(action, payload);
        return;
      }
      const parsed = parseSampleActionResult(result.data);
      if (!parsed) throw new Error("Phản hồi action mẫu không hợp lệ.");
      closeAttempt(attemptsRef.current, signature, parsed);
      // State lạ vẫn được operationStatus xếp vào nhóm chưa hoàn tất, không thành công giả.
      const status = operationStatus({
        state: parsed.state as OperationView["state"],
        completed: parsed.completed,
      });
      setOutcome({
        state: parsed.state,
        message: status.message,
        sentEntries,
        resultEntries: parsed.entries,
      });
      if (status.kind === "succeeded") {
        markUpdateReceived(action, payload);
        if (action === "CORRECT_SOURCE") {
          const data = result.data as Record<string, unknown>;
          setNotice(
            parseCorrectionNotice(data.correctionNotice ?? data.notice ?? data),
          );
        }
      }
    } catch (error) {
      if (!signal.aborted) setActionError(describeError(error));
    } finally {
      if (!signal.aborted) setBusy(false);
    }
  }

  function markUpdateReceived(doneAction: string, payload: Record<string, unknown>) {
    if (doneAction !== "RECEIVE_UPDATE") return;
    const replacement = payload.replacementVersionId;
    if (typeof replacement === "string" && replacement) {
      setReceivedUpdates((current) => new Set(current).add(replacement));
    }
  }

  // Nội dung và đáp án là hai lời gọi tách riêng theo hợp đồng; kết quả cũ bị
  // bỏ trước mỗi lần gọi để nội dung đã thu hồi không còn hiện trên trang.
  async function loadSample(kind: "body" | "answer") {
    const resourceId = (values.resourceId ?? "").trim();
    const versionId = (values.versionId ?? "").trim();
    if (!workspaceId || !resourceId || !versionId) {
      setContent({ kind: "error", message: "Cần workspaceId, resourceId và versionId để mở nội dung mẫu." });
      return;
    }
    const signal = beginOperation();
    setContentBusy(true);
    setBusy(false);
    setContent(null);
    const base = `/api/workspaces/${encodeURIComponent(workspaceId)}/samples/resources/`
      + `${encodeURIComponent(resourceId)}/versions/${encodeURIComponent(versionId)}`;
    try {
      const result = await requestWhenReady<unknown>(
        kind === "answer" ? `${base}/answer` : base,
        { signal },
      );
      if (result.kind !== "data") throw new Error("Phản hồi nội dung mẫu không hợp lệ.");
      setContent({ kind, text: contentText(result.data) });
    } catch (error) {
      if (!signal.aborted) setContent({ kind: "error", message: describeError(error) });
    } finally {
      if (!signal.aborted) setContentBusy(false);
    }
  }

  function receiveUpdate(target: CorrectionNotice) {
    setAction("RECEIVE_UPDATE");
    setValues((current) => ({ ...current, replacementVersionId: target.replacementVersionId }));
    setActionError(null);
  }

  return (
    <>
  <p className="introduction">
    Chạy các action mẫu trên tài nguyên trong workspace đã nhập. Trạng thái hiển thị
    là trạng thái backend trả về; ẩn hay hiện control không thay thế kiểm tra quyền
    ở backend.
  </p>

  <form onSubmit={(event) => void submit(event)}>
    <div className="field">
      <label htmlFor="sample-action">action</label>
      <select
        id="sample-action"
        name="action"
        value={action}
        onChange={(event) => setAction(event.target.value)}
      >
        <optgroup label="Mở rộng — bị chặn khi gói hết hạn">
          {SAMPLE_ACTION_SPECS.filter(
            (item) => sampleActionExpiryBehavior(item.action) === "BLOCKED_WHEN_LAPSED",
          ).map((item) => (
            <option key={item.action} value={item.action}>
              {item.action}
            </option>
          ))}
        </optgroup>
        <optgroup label="Hoàn tất và thu hồi — vẫn dùng được khi gói hết hạn">
          {SAMPLE_ACTION_SPECS.filter(
            (item) => sampleActionExpiryBehavior(item.action) === "ALLOWED_WHEN_LAPSED",
          ).map((item) => (
            <option key={item.action} value={item.action}>
              {item.action}
            </option>
          ))}
        </optgroup>
      </select>
    </div>

    <div className="field">
      <label htmlFor="field-workspaceId">workspaceId</label>
      <input
        id="field-workspaceId"
        name="workspaceId"
        value={values.workspaceId ?? ""}
        onChange={(event) => setField("workspaceId", event.target.value)}
      />
    </div>

    {spec.fields.map((field) => (
      <div className="field" key={field.name}>
        <label htmlFor={`field-${field.name}`}>{field.name}</label>
        {field.kind === "mode" ? (
          <select
            id={`field-${field.name}`}
            name={field.name}
            value={values[field.name] ?? "REVOCABLE"}
            onChange={(event) => setField(field.name, event.target.value)}
          >
            <option value="REVOCABLE">REVOCABLE</option>
            <option value="LONG_TERM">LONG_TERM</option>
          </select>
        ) : (
          <input
            id={`field-${field.name}`}
            name={field.name}
            type={field.kind === "number" ? "number" : "text"}
            value={values[field.name] ?? ""}
            onChange={(event) => setField(field.name, event.target.value)}
          />
        )}
      </div>
    ))}

    <button className="action-button" type="submit" disabled={busy}>
      {busy ? "Đang thực hiện…" : "Thực hiện"}
    </button>
  </form>

  {actionError ? <p role="alert">{actionError}</p> : null}

  {outcome ? (
    <section className="section" aria-labelledby="sample-outcome-title">
      <h2 id="sample-outcome-title">Kết quả action</h2>
      <p role="status">
        {outcome.state} — {outcome.message}
      </p>
      <h3>Đã gửi</h3>
      <dl className="details">
        {outcome.sentEntries.map(([key, value]) => (
          <div key={`sent-${key}`}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {outcome.resultEntries.length ? (
        <>
          <h3>Trả về</h3>
          <dl className="details">
            {outcome.resultEntries.map(([key, value]) => (
              <div key={`result-${key}`}>
                <dt>{key}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </>
      ) : null}
    </section>
  ) : null}

  {notice ? (
    <CorrectionNoticeView
      notice={notice}
      updateReceived={receivedUpdates.has(notice.replacementVersionId)}
      onReceiveUpdate={receiveUpdate}
    />
  ) : null}

  <section className="section" aria-labelledby="sample-content-title">
    <h2 id="sample-content-title">Nội dung và đáp án mẫu</h2>
    <p>
      Nội dung và đáp án được tải bằng hai control tách riêng, dùng workspaceId,
      resourceId và versionId đang nhập ở form trên. Đáp án không đi kèm nội dung.
    </p>
    <p>
      <button
        className="secondary-button"
        type="button"
        disabled={contentBusy}
        onClick={() => void loadSample("body")}
      >
        Nội dung
      </button>{" "}
      <button
        className="secondary-button"
        type="button"
        disabled={contentBusy}
        onClick={() => void loadSample("answer")}
      >
        Đáp án
      </button>
    </p>
    {content?.kind === "error" ? <p role="alert">{content.message}</p> : null}
    {content && content.kind !== "error" ? (
      <>
        <h3>{content.kind === "answer" ? "Đáp án" : "Nội dung"}</h3>
        <pre>{content.text}</pre>
      </>
    ) : null}
  </section>
    </>
  );
}

export function SampleActionsPanel() {
  const { session, refreshSession } = useSession();

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="review-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="review-title">Kiểm chứng quyền tài nguyên</h1>

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
              Panel chỉ chạy trong hồ sơ kiểm chứng (review) cho tài khoản đã đăng nhập.
              Mọi action đi qua cùng dịch vụ và quyết định quyền như thao tác thật;
              panel không tự quyết định quyền.
            </p>
            <a className="login-link" href="/oauth2/authorization/keycloak">
              Đăng nhập
            </a>
          </>
        ) : null}

        {/* Key theo user để đổi tài khoản là state của panel được dựng lại từ đầu. */}
        {session.status === "authenticated" ? (
          <SampleActionForm key={session.user.id} />
        ) : null}
      </section>
    </main>
  );
}
