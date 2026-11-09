import { requestWhenReady, waitForRetry } from "../../lib/api-client";

export type OperationState =
  | "PENDING"
  | "APPLYING"
  | "SUCCEEDED"
  | "RETRYABLE_FAILURE"
  | "FAILED";

export type OperationView = {
  id: string;
  state: OperationState;
  completed: boolean;
  targetType: string;
  targetId?: string;
  errorCode?: string;
};

export type OperationStatus = {
  kind: "pending" | "succeeded" | "failed";
  message: string;
};

export function operationStatus(
  operation: Pick<OperationView, "state" | "completed">,
): OperationStatus {
  if (operation.state === "FAILED") {
    return { kind: "failed", message: "Thao tác không hoàn tất. Vui lòng thử lại hoặc liên hệ hỗ trợ." };
  }
  if (operation.state === "SUCCEEDED" && operation.completed === true) {
    return { kind: "succeeded", message: "Thao tác đã hoàn tất." };
  }
  if (operation.state === "RETRYABLE_FAILURE") {
    return { kind: "pending", message: "Đang thử đồng bộ lại quyền." };
  }
  return { kind: "pending", message: "Đang cập nhật quyền. Thao tác chưa hoàn tất." };
}

/** Theo dõi operation thật; 202/PENDING tuyệt đối không trở thành success do UI cache. */
export async function pollOperationUntilSettled<T extends Pick<OperationView, "state" | "completed">>(
  operationId: string,
  parse: (value: unknown) => T | null,
  signal?: AbortSignal,
): Promise<T> {
  if (!operationId) throw new Error("Thiếu mã thao tác.");
  for (let attempt = 0; attempt < 60; attempt++) {
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    const response = await requestWhenReady<unknown>(
      `/api/me/operations/${encodeURIComponent(operationId)}`, { signal },
    );
    const operation = response.kind === "data" ? parse(response.data) : null;
    if (!operation) throw new Error("Phản hồi trạng thái thao tác không hợp lệ.");
    if (operationStatus(operation).kind !== "pending" || attempt === 59) {
      return operation;
    }
    await waitForRetry(1_000, signal);
  }
  throw new Error("Không thể kiểm tra trạng thái thao tác.");
}
