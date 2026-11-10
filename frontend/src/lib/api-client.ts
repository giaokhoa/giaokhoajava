export type ApiResult<T> =
  | { kind: "data"; status: number; data: T }
  | { kind: "pending"; status: 202; data: T };

type Problem = Record<string, unknown>;

const messages: Record<number, string> = {
  403: "Bạn không có quyền thực hiện thao tác này.",
  409: "Dữ liệu đã thay đổi hoặc yêu cầu bị trùng. Vui lòng tải lại.",
  428: "Yêu cầu thiếu điều kiện cập nhật dữ liệu.",
  503: "Dịch vụ hoặc quyền đang đồng bộ. Vui lòng thử lại sau.",
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly traceId?: string;

  constructor(status: number, problem: Problem = {}) {
    super(messages[status] ?? (typeof problem.detail === "string" ? problem.detail : "Yêu cầu không thành công."));
    this.name = "ApiError";
    this.status = status;
    this.code = typeof problem.code === "string" ? problem.code : undefined;
    this.traceId = typeof problem.traceId === "string" ? problem.traceId : undefined;
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// Chấp nhận cả danh sách trần và dạng {items} theo quy tắc query danh sách của hợp đồng.
export function listItems(data: unknown): unknown[] {
  if (Array.isArray(data)) return data;
  return isRecord(data) && Array.isArray(data.items) ? data.items : [];
}

// Không nhận URL tuyệt đối, protocol-relative hay path thoát khỏi /api.
export function apiPath(path: string): string {
  if (!path.startsWith("/api") || path.includes("\\") || path.includes("#")
      || /%2e|%2f|%5c/i.test(path.split("?")[0])) {
    throw new Error("Chỉ được gọi API same-origin dưới /api.");
  }
  const url = new URL(path, "http://same-origin.invalid");
  if (url.origin !== "http://same-origin.invalid"
      || (url.pathname !== "/api" && !url.pathname.startsWith("/api/"))) {
    throw new Error("Chỉ được gọi API same-origin dưới /api.");
  }
  return url.pathname + url.search;
}

export async function readApiResponse<T>(
  response: Response,
  withoutBody = false,
): Promise<ApiResult<T>> {
  let body: unknown;
  if (!withoutBody && response.status !== 204 && response.status !== 205) {
    try {
      body = await response.json();
    } catch {
      if (response.ok) throw new Error("Phản hồi API không phải JSON hợp lệ.");
    }
  }

  if (!response.ok) throw new ApiError(response.status, isRecord(body) ? body : {});

  if (response.status === 202) {
    const payload = isRecord(body) ? body : null;
    const nested = payload && isRecord(payload.operation) ? payload.operation : null;
    const operation = nested ?? payload;
    const id = nested ? nested.id : payload?.operationId;
    if (!operation || typeof id !== "string" || !id
        || typeof operation.state !== "string"
        || operation.completed !== false
        || (!nested && typeof payload?.statusUrl !== "string")) {
      throw new Error("Phản hồi 202 không hợp lệ.");
    }
    if (typeof payload?.statusUrl === "string") {
      try {
        apiPath(payload.statusUrl);
      } catch {
        throw new Error("Phản hồi 202 không hợp lệ.");
      }
    }
    return { kind: "pending", status: 202, data: payload as T };
  }

  return { kind: "data", status: response.status, data: body as T };
}

async function getCsrf(signal?: AbortSignal): Promise<[string, string]> {
  const response = await fetch("/api/session", {
    signal,
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    redirect: "manual",
    headers: { Accept: "application/json" },
  });
  const result = await readApiResponse<{ csrf?: { headerName: string; token: string } }>(response);
  const csrf = result.kind === "data" ? result.data?.csrf : undefined;
  if (!csrf || !/^X-[A-Za-z0-9-]+$/i.test(csrf.headerName) || !csrf.token) {
    throw new Error("Không lấy được CSRF hợp lệ từ /api/session.");
  }
  return [csrf.headerName, csrf.token];
}

// Token CSRF được giữ trong bộ nhớ, gắn với danh tính phiên mà SessionProvider
// báo qua setSessionIdentity: phiên đổi (đăng nhập, đăng xuất, làm mới sang
// người dùng khác) thì token của phiên trước bị bỏ ngay, nên token dùng cho
// mutation luôn theo đúng phiên hiện hành như khi lấy mới cho từng mutation.
let csrfIdentity: string | null = null;
let csrfCached: [string, string] | null = null;
let csrfPending: Promise<[string, string]> | null = null;

export function setSessionIdentity(identity: string | null): void {
  if (identity === csrfIdentity) return;
  csrfIdentity = identity;
  csrfCached = null;
  csrfPending = null;
}

function forgetCsrf(): void {
  csrfCached = null;
  csrfPending = null;
}

// Lời gọi có AbortSignal tự quản vòng đời nên không chia sẻ lời gọi đang bay,
// khớp cách requestWorkspaces xử lý bên dưới.
async function sessionCsrf(
  signal?: AbortSignal,
): Promise<{ pair: [string, string]; fromCache: boolean }> {
  if (csrfCached) return { pair: csrfCached, fromCache: true };
  if (signal) {
    const identity = csrfIdentity;
    const pair = await getCsrf(signal);
    if (identity === csrfIdentity) csrfCached = pair;
    return { pair, fromCache: false };
  }
  if (!csrfPending) {
    const pending = getCsrf();
    csrfPending = pending;
    pending.then(
      (pair) => {
        if (csrfPending === pending) {
          csrfCached = pair;
          csrfPending = null;
        }
      },
      () => {
        if (csrfPending === pending) csrfPending = null;
      },
    );
  }
  return { pair: await csrfPending, fromCache: false };
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
  const url = apiPath(path);
  if (url === "/api/logout" || url.startsWith("/api/logout?")) {
    throw new Error("Dùng submitLogout để điều hướng đăng xuất.");
  }
  const headers = new Headers(init.headers);
  if (headers.has("Authorization") || headers.has("Cookie") || headers.has("X-Tenant-Id")) {
    throw new Error("Không gửi token, cookie hay tenant override từ API client.");
  }
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  if (typeof init.body === "string" && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const method = (init.method ?? "GET").toUpperCase();
  const mutating = !["GET", "HEAD", "OPTIONS"].includes(method);
  let csrfFromCache = false;
  if (mutating) {
    const csrf = await sessionCsrf(init.signal ?? undefined);
    headers.set(csrf.pair[0], csrf.pair[1]);
    csrfFromCache = csrf.fromCache;
    if (!headers.has("Idempotency-Key")) headers.set("Idempotency-Key", crypto.randomUUID());
  }

  const send = () => fetch(url, {
    ...init,
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    redirect: "manual",
  });
  let response = await send();
  if (mutating && response.status === 403) {
    // Token vừa dùng có thể đã lệch phiên hiện hành: bỏ khỏi bộ nhớ đệm. Token
    // lấy từ đệm thì lấy mới và thử lại đúng một lần bằng cùng Idempotency-Key;
    // token vừa lấy mới mà vẫn 403 là phán quyết thật của backend, trả nguyên.
    forgetCsrf();
    if (csrfFromCache) {
      const csrf = await sessionCsrf(init.signal ?? undefined);
      headers.set(csrf.pair[0], csrf.pair[1]);
      response = await send();
      if (response.status === 403) forgetCsrf();
    }
  }
  return readApiResponse<T>(response, method === "HEAD");
}

// Chỉ tự thử lại fence chưa sẵn sàng; giữ nguyên request/key và không tự thử
// lại lỗi khác (riêng 403 do token CSRF trong bộ nhớ đệm đã lệch phiên được
// apiRequest tự xử lý bên trong bằng một lần thử lại với token mới).
const FENCE_RETRY_LIMIT_MS = 60_000;
const FENCE_RETRY_INTERVAL_MS = 1_000;

/** Chờ có thể hủy khi đổi phiên/workspace, không tiếp tục request bằng ngữ cảnh cũ. */
export function waitForRetry(ms: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) return Promise.reject(new DOMException("Aborted", "AbortError"));
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

export async function requestWhenReady<T = unknown>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
  const startedAt = Date.now();
  for (;;) {
    if (init.signal?.aborted) throw new DOMException("Aborted", "AbortError");
    try {
      return await apiRequest<T>(path, init);
    } catch (error) {
      const retryable = error instanceof ApiError
        && error.status === 503
        && error.code === "AUTHORIZATION_NOT_READY"
        && Date.now() - startedAt < FENCE_RETRY_LIMIT_MS;
      if (!retryable) throw error;
      await waitForRetry(FENCE_RETRY_INTERVAL_MS, init.signal ?? undefined);
    }
  }
}

// Nhiều component cùng gắn trên một trang từng gọi trùng GET /api/me/workspaces.
// Gộp các lời gọi đang bay của cùng một người dùng thành một request; lời gọi có
// AbortSignal tự quản vòng đời nên không gộp. Chỉ gộp lúc request chưa kết thúc —
// bản đã tải xong không giữ lại vì các trang quản trị phải đọc trạng thái
// workspace/gói mới nhất sau mỗi thao tác.
const inflightWorkspaces = new Map<string, Promise<ApiResult<unknown>>>();

export function requestWorkspaces(userId: string, signal?: AbortSignal): Promise<ApiResult<unknown>> {
  if (signal) {
    return requestWhenReady<unknown>("/api/me/workspaces", { signal });
  }
  const inflight = inflightWorkspaces.get(userId);
  if (inflight) return inflight;
  const request = requestWhenReady<unknown>("/api/me/workspaces");
  inflightWorkspaces.set(userId, request);
  const forget = () => {
    if (inflightWorkspaces.get(userId) === request) inflightWorkspaces.delete(userId);
  };
  request.then(forget, forget);
  return request;
}

// Danh sách dùng làm đầu vào chọn người/quan hệ phải đọc đủ trang trước khi hiển thị.
export async function requestAllPages(
  path: string,
  init: Pick<RequestInit, "signal"> = {},
): Promise<unknown[]> {
  const items: unknown[] = [];
  const seen = new Set<string>();
  let next = apiPath(path);
  for (;;) {
    const result = await requestWhenReady<unknown>(next, init);
    if (result.kind !== "data" || !isRecord(result.data) || !Array.isArray(result.data.items)) {
      throw new Error("Phản hồi danh sách không hợp lệ.");
    }
    items.push(...result.data.items);
    const cursor = result.data.nextCursor;
    if (cursor === undefined || cursor === null) return items;
    if (typeof cursor !== "string" || !cursor || seen.has(cursor)) {
      throw new Error("Cursor danh sách không hợp lệ.");
    }
    seen.add(cursor);
    const url = new URL(apiPath(path), "http://same-origin.invalid");
    url.searchParams.set("cursor", cursor);
    next = url.pathname + url.search;
  }
}

// Điều hướng bằng HTML form là bắt buộc cho RP-Initiated OIDC logout redirect của Spring.
export async function submitLogout(): Promise<void> {
  const [, token] = await getCsrf();
  const form = document.createElement("form");
  form.method = "POST";
  form.action = "/api/logout";

  const csrf = document.createElement("input");
  csrf.type = "hidden";
  csrf.name = "_csrf";
  csrf.value = token;

  form.appendChild(csrf);
  document.body.appendChild(form);
  form.submit();
}
