import { describe, expect, it } from "vitest";
import { parsePracticeHistory } from "../../src/app/personal/personal-content";
import {
  parsePlatformRecoveryRequests,
  resolveRequestBody,
} from "../../src/app/platform/recovery-requests/platform-recovery-content";
import {
  recoveryRequestBody,
} from "../../src/features/identity/recovery-request-form";
import {
  parseTrackedOperation,
} from "../../src/lib/operation-status";

describe("form khôi phục chỉ gửi target của chính actor", () => {
  it("gửi đúng targetUserId của actor và lý do đã cắt khoảng trắng", () => {
    expect(recoveryRequestBody("user-1", "  Quên mật khẩu  ")).toEqual({
      targetUserId: "user-1",
      reason: "Quên mật khẩu",
    });
  });

  it("không tạo body khi thiếu userId hoặc lý do trống", () => {
    expect(recoveryRequestBody("", "Quên mật khẩu")).toBeNull();
    expect(recoveryRequestBody("user-1", "   ")).toBeNull();
  });

  it("parse operation phẳng và operation lồng nhau, fail-closed khi thiếu trường", () => {
    expect(parseTrackedOperation({
      id: "op-1",
      state: "PENDING",
      completed: false,
    })).toEqual({ id: "op-1", state: "PENDING", completed: false, errorCode: undefined });
    expect(parseTrackedOperation({
      operation: { id: "op-2", state: "SUCCEEDED", completed: true },
      operationId: "op-2",
    })).toEqual({ id: "op-2", state: "SUCCEEDED", completed: true, errorCode: undefined });
    expect(parseTrackedOperation({ id: "op-3", state: "PENDING" })).toBeNull();
    expect(parseTrackedOperation({ id: "op-4", state: "KHONG_CO", completed: false })).toBeNull();
    expect(parseTrackedOperation("khong-phai-object")).toBeNull();
  });
});

describe("lịch sử luyện tập mẫu của chính user", () => {
  it("chỉ giữ bản ghi đúng hợp đồng, bỏ bản ghi thiếu trường", () => {
    expect(parsePracticeHistory([
      {
        id: "attempt-1",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcome: "DONE",
      },
      { id: "attempt-2", attemptedAt: "2026-11-07T00:00:00Z" },
      "khong-phai-object",
    ])).toEqual([
      {
        id: "attempt-1",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcome: "DONE",
      },
    ]);
  });

  it("chấp nhận cả outcomeStub (hợp đồng camelCase) lẫn outcome", () => {
    expect(parsePracticeHistory([
      {
        id: "attempt-1",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcomeStub: "DONE",
      },
      {
        id: "attempt-2",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcome: "DONE",
      },
    ])).toEqual([
      {
        id: "attempt-1",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcome: "DONE",
      },
      {
        id: "attempt-2",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
        outcome: "DONE",
      },
    ]);
  });

  it("chấp nhận dạng {items} và trả rỗng khi phản hồi không phải danh sách", () => {
    expect(parsePracticeHistory({
      items: [{
        id: "attempt-1",
        attemptedAt: "2026-11-07T00:00:00Z",
        systemResourceId: "resource-1",
        versionId: "version-1",
      }],
    })).toHaveLength(1);
    expect(parsePracticeHistory({})).toEqual([]);
    expect(parsePracticeHistory(null)).toEqual([]);
  });
});

describe("danh sách khôi phục của platform", () => {
  it("chỉ giữ yêu cầu đúng trạng thái, bỏ trạng thái lạ và bản ghi thiếu target", () => {
    expect(parsePlatformRecoveryRequests({
      items: [
        {
          id: "req-1",
          targetUserId: "user-9",
          state: "REQUESTED",
          reason: "Quên mật khẩu",
          version: 0,
          createdAt: "2026-11-07T00:00:00Z",
        },
        { id: "req-2", targetUserId: "user-8", state: "CHO_XU_LY" },
        { id: "req-3", state: "REQUESTED" },
      ],
    })).toEqual([
      {
        id: "req-1",
        targetUserId: "user-9",
        requesterUserId: undefined,
        reason: "Quên mật khẩu",
        version: 0,
        state: "REQUESTED",
        createdAt: "2026-11-07T00:00:00Z",
      },
    ]);
  });

  it("body resolve chỉ nhận APPROVE/REJECT, bỏ ghi chú trống", () => {
    expect(resolveRequestBody("APPROVE", "  Đã xác minh email  ")).toEqual({
      decision: "APPROVE",
      verificationNote: "Đã xác minh email",
    });
    expect(resolveRequestBody("REJECT", "   ")).toEqual({ decision: "REJECT" });
    expect(resolveRequestBody("COMPLETE", "Đã khôi phục và bàn giao")).toEqual({
      decision: "COMPLETE", verificationNote: "Đã khôi phục và bàn giao",
    });
    expect(resolveRequestBody("APPROVE", "")).toBeNull();
    expect(resolveRequestBody("COMPLETE", "")).toBeNull();
    expect(resolveRequestBody("VERIFIED", "ghi chú")).toBeNull();
    expect(resolveRequestBody("", "")).toBeNull();
  });
});
