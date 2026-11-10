"use client";

import { useRef, useState, type FormEvent } from "react";
import { OperationPanel } from "@/features/operations/operation-panel";
import { useOperationCheck } from "@/lib/use-operation-check";
import {
  parseTrackedOperation,
  type TrackedOperation,
} from "@/lib/operation-status";
import { apiRequest } from "@/lib/api-client";

type PendingAction = "recovery" | "operation" | null;

// Form chỉ gửi target của chính actor; lý do trống hoặc thiếu userId thì không gửi.
export function recoveryRequestBody(
  userId: string,
  reason: string,
): { targetUserId: string; reason: string } | null {
  const trimmedReason = reason.trim();
  if (!userId || !trimmedReason) return null;
  return { targetUserId: userId, reason: trimmedReason };
}

export function RecoveryRequestForm({ userId }: { userId: string }) {
  const checkOperationStatus = useOperationCheck();
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const recoveryAttempt = useRef<{ payload: string; key: string } | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setError(null);
    try {
      setOperation(await checkOperationStatus(operation.id));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Không kiểm tra được trạng thái thao tác.");
    } finally {
      setPendingAction(null);
    }
  }

  async function submitRecovery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = recoveryRequestBody(userId, reason);
    if (!body) {
      setError("Hãy nhập lý do cần hỗ trợ khôi phục.");
      return;
    }
    const payload = JSON.stringify(body);
    if (recoveryAttempt.current?.payload !== payload) {
      recoveryAttempt.current = { payload, key: crypto.randomUUID() };
    }
    setPendingAction("recovery");
    setError(null);
    try {
      const result = await apiRequest<unknown>("/api/me/recovery-requests", {
        method: "POST",
        body: payload,
        headers: { "Idempotency-Key": recoveryAttempt.current.key },
      });
      const nextOperation = parseTrackedOperation(result.data);
      if (!nextOperation) {
        throw new Error("Phản hồi thao tác không hợp lệ.");
      }
      setOperation(nextOperation);
      // Chỉ dọn draft sau thao tác THÀNH CÔNG: PENDING/FAILED giữ nguyên lý do
      // đã nhập để người dùng theo dõi hoặc sửa rồi gửi lại.
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        setReason("");
      }
      // Key chỉ được xóa khi operation đã hoàn tất: gửi lại cùng payload trong
      // lúc operation đầu còn PENDING phải replay biên nhận của nó, không tạo
      // yêu cầu khôi phục thứ hai.
      if (nextOperation.completed) {
        recoveryAttempt.current = null;
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Thao tác không thành công.");
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <section aria-labelledby="recovery-title">
      <h2 id="recovery-title">Khôi phục tài khoản</h2>
      <p>
        Yêu cầu này chỉ tạo đề nghị hỗ trợ cho chính tài khoản của bạn; mật khẩu không
        được thay đổi tại trang này.
      </p>
      <form onSubmit={submitRecovery}>
        <div className="field">
          <label htmlFor="recovery-reason">Lý do cần hỗ trợ</label>
          <textarea
            id="recovery-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={4}
            required
          />
        </div>
        <button
          className="action-button"
          type="submit"
          disabled={pendingAction !== null || !reason.trim()}
        >
          Gửi yêu cầu khôi phục
        </button>
      </form>

      {operation ? (
        <OperationPanel
          operation={operation}
          checking={pendingAction !== null}
          onCheck={() => void checkOperation()}
        />
      ) : null}

      {error ? <p role="alert">{error}</p> : null}
    </section>
  );
}
