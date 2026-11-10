import { useCallback } from "react";
import { apiRequest, requestWhenReady } from "./api-client";
import {
  parseTrackedOperation,
  type TrackedOperation,
} from "./operation-status";

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
