import { requestWhenReady } from "../../lib/api-client";
import type { OperationView } from "../../lib/operation-status";

// Gửi mutation kèm Idempotency-Key cho trước và trả dữ liệu của phản hồi.
export async function submitMutation(
  path: string,
  init: { method: string; body?: string; headers?: Record<string, string> },
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<unknown> {
  const result = await requestWhenReady(path, {
    ...init,
    signal,
    headers: { ...init.headers, "Idempotency-Key": idempotencyKey },
  });
  return result.data;
}

// Cùng một payload (kèm version đích) dùng lại một key giữa các lần gửi lại khi
// operation chưa hoàn tất, đúng mẫu biên nhận idempotency của các form quản trị.
export function attemptKey(store: Map<string, string>, signature: string): string {
  let key = store.get(signature);
  if (!key) {
    key = crypto.randomUUID();
    store.set(signature, key);
  }
  return key;
}

// Operation đã hoàn tất thì backend chỉ replay đúng operation đó theo key cũ, nên
// giữ key sẽ chặn thao tác mới cùng payload (ví dụ gửi lại yêu cầu sau khi liên kết
// cũ bị từ chối hoặc bị gỡ). Bỏ key khi hoàn tất; chưa hoàn tất thì giữ cho lần gửi lại.
export function closeAttempt(
  store: Map<string, string>,
  signature: string,
  operation: Pick<OperationView, "completed">,
): void {
  if (operation.completed) {
    store.delete(signature);
  }
}
