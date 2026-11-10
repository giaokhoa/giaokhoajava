"use client";

import { useCallback } from "react";
import { apiRequest, requestWhenReady } from "@/lib/api-client";
import {
  operationStatus,
  parseTrackedOperation,
  type TrackedOperation,
} from "./operation-status";

export function OperationPanel({
  operation,
  checking,
  onCheck,
  titleId = "operation-title",
}: {
  operation: TrackedOperation;
  checking: boolean;
  onCheck: () => void;
  titleId?: string;
}) {
  return (
    <section className="section" aria-labelledby={titleId}>
      <h2 id={titleId}>Trạng thái thao tác</h2>
      <p>{operationStatus(operation).message}</p>
      <p>
        Mã thao tác: <code>{operation.id}</code>
      </p>
      {operation.errorCode ? <p>Mã lỗi: {operation.errorCode}</p> : null}
      <button
        className="secondary-button"
        type="button"
        disabled={checking}
        onClick={onCheck}
      >
        Kiểm tra trạng thái
      </button>
    </section>
  );
}

// Đọc lại trạng thái operation từ backend; phản hồi không parse được là lỗi, không
// bao giờ suy diễn thành công. whenReady chọn bản có thử lại fence mà các trang
// giảng dạy vốn đã dùng cho chính endpoint này.
export function useOperationCheck() {
  return useCallback(
    async (
      operationId: string,
      options?: { whenReady?: boolean },
    ): Promise<TrackedOperation> => {
      const path = `/api/me/operations/${encodeURIComponent(operationId)}`;
      const result = options?.whenReady
        ? await requestWhenReady<unknown>(path)
        : await apiRequest<unknown>(path);
      const nextOperation = result.kind === "data" ? parseTrackedOperation(result.data) : null;
      if (!nextOperation) {
        throw new Error("Phản hồi trạng thái thao tác không hợp lệ.");
      }
      return nextOperation;
    },
    [],
  );
}
