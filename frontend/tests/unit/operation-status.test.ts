import { describe, expect, it } from "vitest";
import { operationStatus } from "../../src/features/operations/operation-status";

describe("hiển thị trạng thái operation", () => {
  it("không thông báo thành công khi chưa đồng bộ xong", () => {
    for (const state of ["PENDING", "APPLYING", "RETRYABLE_FAILURE", "SUCCEEDED"] as const) {
      expect(operationStatus({ state, completed: false }).kind).toBe("pending");
    }
  });

  it("chỉ SUCCEEDED và completed true mới thành công", () => {
    expect(operationStatus({ state: "SUCCEEDED", completed: true }).kind).toBe("succeeded");
    expect(operationStatus({ state: "FAILED", completed: true }).kind).toBe("failed");
    expect(operationStatus({ state: "FAILED", completed: false }).kind).toBe("failed");
  });

  it("không gán mọi lỗi operation cho cập nhật quyền", () => {
    const result = operationStatus({ state: "FAILED", completed: false });
    expect(result.message).not.toContain("quyền");
    expect(result.message).toContain("Thao tác");
  });

  it("không coi trạng thái lạ là hoàn tất", () => {
    expect(operationStatus({ state: "UNKNOWN" as "PENDING", completed: true }).kind).toBe("pending");
  });
});

import { afterEach, vi } from "vitest";
import { pollOperationUntilSettled } from "../../src/features/operations/operation-status";

const parse = (data: unknown) => {
  if (!data || typeof data !== "object") return null;
  const value = data as Record<string, unknown>;
  if (typeof value.state !== "string" || typeof value.completed !== "boolean") return null;
  return { state: value.state as "PENDING" | "APPLYING" | "SUCCEEDED" | "FAILED", completed: value.completed };
};

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("theo dõi operation từ server", () => {
  it("chỉ coi thành công sau SUCCEEDED/completed thật, không coi HTTP 200 là success", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn()
      .mockResolvedValueOnce(Response.json({ state: "SUCCEEDED", completed: false }))
      .mockResolvedValueOnce(Response.json({ state: "APPLYING", completed: false }))
      .mockResolvedValueOnce(Response.json({ state: "SUCCEEDED", completed: true }));
    vi.stubGlobal("fetch", fetcher);
    const pending = pollOperationUntilSettled("op-123", parse);
    await vi.advanceTimersByTimeAsync(2_000);
    await expect(pending).resolves.toEqual({ state: "SUCCEEDED", completed: true });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("trả FAILED mà không thông báo hoàn tất thành công", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ state: "FAILED", completed: false })));
    await expect(pollOperationUntilSettled("op-123", parse)).resolves.toEqual({ state: "FAILED", completed: false });
  });

  it("hủy polling lập tức khi đổi actor hoặc workspace", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockResolvedValue(Response.json({ state: "PENDING", completed: false }));
    vi.stubGlobal("fetch", fetcher);
    const abort = new AbortController();
    const pending = pollOperationUntilSettled("op-123", parse, abort.signal);
    await vi.advanceTimersByTimeAsync(0);
    abort.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
