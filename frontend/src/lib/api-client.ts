export type PendingOperation = {
  operationId: string;
  state: string;
  completed: false;
  statusUrl: string;
};

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

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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

  if (!response.ok) throw new ApiError(response.status, isObject(body) ? body : {});

  if (response.status === 202) {
    const payload = isObject(body) ? body : null;
    const nested = payload && isObject(payload.operation) ? payload.operation : null;
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

async function getCsrf(): Promise<[string, string]> {
  const response = await fetch("/api/session", {
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
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    const [name, token] = await getCsrf();
    headers.set(name, token);
    if (!headers.has("Idempotency-Key")) headers.set("Idempotency-Key", crypto.randomUUID());
  }

  const response = await fetch(url, {
    ...init,
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    redirect: "manual",
  });
  return readApiResponse<T>(response, method === "HEAD");
}

// Chỉ tự thử lại fence chưa sẵn sàng; giữ nguyên request/key và không thử lại 403 hay lỗi dịch vụ khác.
const FENCE_RETRY_LIMIT_MS = 60_000;
const FENCE_RETRY_INTERVAL_MS = 1_000;

export async function requestWhenReady<T = unknown>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
  const startedAt = Date.now();
  for (;;) {
    try {
      return await apiRequest<T>(path, init);
    } catch (error) {
      const retryable = error instanceof ApiError
        && error.status === 503
        && error.code === "AUTHORIZATION_NOT_READY"
        && Date.now() - startedAt < FENCE_RETRY_LIMIT_MS;
      if (!retryable) throw error;
      await new Promise((resolve) => setTimeout(resolve, FENCE_RETRY_INTERVAL_MS));
    }
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
