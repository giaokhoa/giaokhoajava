import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  apiRequest,
  requestAllPages,
  requestWorkspaces,
  setSessionIdentity,
  submitLogout,
} from "@/lib/api-client";

afterEach(() => vi.unstubAllGlobals());

let identitySequence = 0;

const session = (token: string) =>
  Response.json({ authenticated: true, csrf: { headerName: "X-CSRF-TOKEN", token } });

describe("browser API client", () => {
  // Mỗi test một danh tính phiên riêng để bộ nhớ đệm CSRF không rò qua test.
  beforeEach(() => setSessionIdentity(`test-user-${++identitySequence}`));

  it("chỉ fetch same-origin, không cache và không tự gửi token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ items: [] }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await apiRequest<{ items: unknown[] }>("/api/me/workspaces")).toMatchObject({
      kind: "data",
      status: 200,
      data: { items: [] },
    });
    expect(fetchMock).toHaveBeenCalledWith("/api/me/workspaces", expect.objectContaining({
      cache: "no-store",
      credentials: "same-origin",
    }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].headers.has("Authorization")).toBe(false);
  });

  it("bootstrap CSRF cho POST, gắn idempotency và giữ 202 ở trạng thái pending", async () => {
    const operation = { operationId: "op-1", state: "PENDING", completed: false, statusUrl: "/api/me/operations/op-1" };
    const fetchMock = vi.fn().mockResolvedValueOnce(session("csrf-one"))
      .mockResolvedValueOnce(Response.json(operation, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);
    const result = await apiRequest("/api/workspaces/abc/classes", {
      method: "POST", body: JSON.stringify({ name: "Lớp 1" }),
    });
    expect(result).toEqual({ kind: "pending", status: 202, data: operation });
    expect(fetchMock.mock.calls[0][0]).toBe("/api/session");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ cache: "no-store", credentials: "same-origin" });
    const options = fetchMock.mock.calls[1][1] as RequestInit;
    const headers = new Headers(options.headers);
    expect(headers.get("X-CSRF-TOKEN")).toBe("csrf-one");
    expect(headers.get("Idempotency-Key")).toBeTruthy();
    expect(options.cache).toBe("no-store");
    expect(options.credentials).toBe("same-origin");
  });

  it("gửi JSON đúng Content-Type và không ghi đè FormData hay form encode", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(session("csrf-shared"))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    await apiRequest("/api/classes", { method: "POST", body: JSON.stringify({ name: "Lớp" }) });
    const jsonRequest = new Request("https://same-origin.test/api/classes", fetchMock.mock.calls[1][1]);
    expect(jsonRequest.headers.get("Content-Type")).toBe("application/json");

    const formData = new FormData();
    formData.set("name", "Lớp");
    await apiRequest("/api/classes", { method: "POST", body: formData });
    const formRequest = new Request("https://same-origin.test/api/classes", fetchMock.mock.calls[2][1]);
    expect(formRequest.headers.get("Content-Type")).toMatch(/^multipart\/form-data; boundary=/);

    await apiRequest("/api/classes", { method: "POST", body: new URLSearchParams({ name: "Lớp" }) });
    const encoded = new Request("https://same-origin.test/api/classes", fetchMock.mock.calls[3][1]);
    expect(encoded.headers.get("Content-Type")).toContain("application/x-www-form-urlencoded");
    // Ba mutation cùng phiên dùng chung một lần lấy CSRF.
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/session")).toHaveLength(1);
  });

  it("giữ trạng thái 202 khi response chứa operation lồng trong StudentIssueView", async () => {
    const studentIssue = {
      operation: { id: "op-2", state: "APPLYING", completed: false, targetType: "STUDENT" },
      handoff: { username: "student", temporaryPassword: "only-on-create" },
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(studentIssue, { status: 202 })));
    expect(await apiRequest("/api/issued-student")).toEqual({
      kind: "pending", status: 202, data: studentIssue,
    });
  });

  it("giữ CSRF trong bộ nhớ theo danh tính phiên, chỉ lấy lại khi danh tính đổi", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(session("user-one"))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(session("user-two"))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(session("anonymous"))
      .mockResolvedValueOnce(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    setSessionIdentity("user-1");
    await apiRequest("/api/classes", { method: "POST" });
    await apiRequest("/api/classes", { method: "POST" });
    // Cùng danh tính: mutation thứ hai dùng lại token, không gọi /api/session nữa.
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/session")).toHaveLength(1);
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get("X-CSRF-TOKEN")).toBe("user-one");
    // Đổi người dùng: token của phiên trước bị bỏ, phải lấy token của phiên mới.
    setSessionIdentity("user-2");
    await apiRequest("/api/classes", { method: "POST" });
    expect(new Headers(fetchMock.mock.calls[4][1].headers).get("X-CSRF-TOKEN")).toBe("user-two");
    // Mất phiên (đăng xuất/ẩn danh) cũng là một lần đổi danh tính.
    setSessionIdentity(null);
    await apiRequest("/api/classes", { method: "POST" });
    expect(new Headers(fetchMock.mock.calls[6][1].headers).get("X-CSRF-TOKEN")).toBe("anonymous");
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/session")).toHaveLength(3);
  });

  it("403 với token lấy từ bộ nhớ: bỏ token, lấy mới và thử lại đúng một lần", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(session("stale"))
      .mockResolvedValueOnce(Response.json({ ok: true }))
      .mockResolvedValueOnce(Response.json({ code: "CSRF" }, { status: 403 }))
      .mockResolvedValueOnce(session("fresh"))
      .mockResolvedValueOnce(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    await apiRequest("/api/classes", { method: "POST" });
    const result = await apiRequest<{ ok: boolean }>("/api/classes", { method: "POST" });
    expect(result).toMatchObject({ kind: "data", data: { ok: true } });
    // Lần thử lại dùng token mới nhưng giữ nguyên Idempotency-Key của lần đầu.
    const firstKey = new Headers(fetchMock.mock.calls[2][1].headers).get("Idempotency-Key");
    const retryHeaders = new Headers(fetchMock.mock.calls[4][1].headers);
    expect(retryHeaders.get("X-CSRF-TOKEN")).toBe("fresh");
    expect(retryHeaders.get("Idempotency-Key")).toBe(firstKey);
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it("403 với token vừa lấy mới: không thử lại và không giữ token đó cho lần sau", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(session("fresh"))
      .mockResolvedValueOnce(Response.json({ code: "PERMISSION" }, { status: 403 }))
      .mockResolvedValueOnce(session("next"))
      .mockResolvedValueOnce(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("/api/classes", { method: "POST" })).rejects.toMatchObject({ status: 403 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // Token vừa bị 403 đã bị bỏ: mutation sau phải lấy lại từ /api/session.
    await apiRequest("/api/classes", { method: "POST" });
    expect(fetchMock.mock.calls[2][0]).toBe("/api/session");
    expect(new Headers(fetchMock.mock.calls[3][1].headers).get("X-CSRF-TOKEN")).toBe("next");
  });

  it("logout dùng POST form điều hướng và không bị JSON fetch giữ lại redirect", async () => {
    const fetchMock = vi.fn().mockResolvedValue(session("logout-token"));
    vi.stubGlobal("fetch", fetchMock);
    const submit = vi.fn();
    const appendInput = vi.fn();
    const appendForm = vi.fn();
    const form = { method: "", action: "", appendChild: appendInput, submit };
    const input = { type: "", name: "", value: "" };
    vi.stubGlobal("document", {
      body: { appendChild: appendForm },
      createElement: vi.fn((tag: string) => tag === "form" ? form : input),
    });
    await submitLogout();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/session");
    expect(form).toMatchObject({ method: "POST", action: "/api/logout" });
    expect(input).toMatchObject({ type: "hidden", name: "_csrf", value: "logout-token" });
    expect(appendInput).toHaveBeenCalledWith(input);
    expect(appendForm).toHaveBeenCalledWith(form);
    expect(submit).toHaveBeenCalledOnce();
    await expect(apiRequest("/api/logout", { method: "POST" })).rejects.toThrow(/logout/i);
    await expect(apiRequest("/api/logout?returnTo=%2F", { method: "POST" })).rejects.toThrow(/logout/i);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([403, 409, 428, 503])("xử lý problem+json %i theo mã lỗi", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(
      { status, code: "API_PROBLEM", detail: "Thông báo an toàn", traceId: "trace-1" },
      { status, headers: { "Content-Type": "application/problem+json" } },
    )));
    await expect(apiRequest("/api/secure")).rejects.toMatchObject({
      status, code: "API_PROBLEM", traceId: "trace-1",
    });
  });

  it.each([403, 409, 428, 503])("hiển thị detail của server cho %i, không tự chế thông điệp", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(
      { status, code: "API_PROBLEM", detail: "Chi tiết từ server" },
      { status, headers: { "Content-Type": "application/problem+json" } },
    )));
    const error = await apiRequest("/api/secure").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe("Chi tiết từ server");
  });

  it("mang theo violations của problem+json và bỏ bản ghi lệch", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(
      {
        status: 400,
        code: "VALIDATION_FAILED",
        detail: "Dữ liệu không hợp lệ",
        violations: [
          { field: "name", code: "NotBlank", message: "Không được để trống" },
          { field: "email", code: "Email" },
          "không phải object",
        ],
      },
      { status: 400, headers: { "Content-Type": "application/problem+json" } },
    )));
    const error = await apiRequest("/api/secure").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).violations).toEqual([
      { field: "name", code: "NotBlank", message: "Không được để trống" },
    ]);
  });

  it("dùng thông điệp dự phòng khi problem+json không có detail", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(
      { status: 500, code: "INTERNAL" },
      { status: 500, headers: { "Content-Type": "application/problem+json" } },
    )));
    const error = await apiRequest("/api/secure").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe("Yêu cầu không thành công.");
    expect((error as ApiError).violations).toEqual([]);
  });

  it("thất bại an toàn khi thiếu CSRF, không gửi mutation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ authenticated: true }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("/api/classes", { method: "POST" })).rejects.toThrow(/CSRF/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("không nhận 202 báo hoàn tất hoặc statusUrl khác origin", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      operationId: "x", state: "SUCCEEDED", completed: true, statusUrl: "/api/me/operations/x",
    }, { status: 202 })).mockResolvedValueOnce(Response.json({
      operationId: "x", state: "PENDING", completed: false, statusUrl: "https://external.example/steal",
    }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("/api/thing")).rejects.toThrow(/202/);
    await expect(apiRequest("/api/thing")).rejects.toThrow(/202/);
  });

  it("từ chối URL ngoài /api, escape path và Authorization", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    for (const path of ["https://other.example/api/me", "//other.example/api", "/api/../private", "/api/%2e%2e/private"]) {
      await expect(apiRequest(path)).rejects.toThrow();
    }
    await expect(apiRequest("/api/me", { headers: { Authorization: "Bearer secret" } })).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("HEAD 200 không đòi JSON body và lỗi HEAD vẫn bị từ chối", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 403 }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await apiRequest("/api/health", { method: "HEAD" })).toEqual({
      kind: "data", status: 200, data: undefined,
    });
    await expect(apiRequest("/api/health", { method: "HEAD" })).rejects.toMatchObject({ status: 403 });
  });

  it("giữ idempotency do caller cấp và trả dữ liệu rỗng cho 204", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(session("csrf"))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await apiRequest("/api/thing", {
      method: "DELETE", headers: { "Idempotency-Key": "stable-key", "If-Match": "\"7\"" },
    })).toEqual({ kind: "data", status: 204, data: undefined });
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("stable-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("If-Match")).toBe("\"7\"");
  });
});


describe("danh sách workspace dùng chung", () => {
  it("gộp các lời gọi đang bay của cùng người dùng, tải lại sau khi đã xong", async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(Response.json([{ id: "w-1" }])));
    vi.stubGlobal("fetch", fetchMock);
    const [first, second] = await Promise.all([
      requestWorkspaces("user-1"),
      requestWorkspaces("user-1"),
    ]);
    expect(first).toEqual(second);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/me/workspaces");
    // Đã kết thúc thì không giữ cache: lần gọi sau phải đọc trạng thái mới nhất.
    await requestWorkspaces("user-1");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await requestWorkspaces("user-2");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});

describe("đọc đủ trang danh sách bảo vệ", () => {
  it("giữ bộ lọc và theo cursor để không làm mất người ở trang sau", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ items: [{ id: "first" }], nextCursor: "page 2" }))
      .mockResolvedValueOnce(Response.json({ items: [{ id: "last" }] }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await requestAllPages("/api/workspaces/w/memberships?limit=20"))
      .toEqual([{ id: "first" }, { id: "last" }]);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/workspaces/w/memberships?limit=20&cursor=page+2");
  });

  it("không trả danh sách thiếu khi một trang bị từ chối", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(Response.json({ items: ["first"], nextCursor: "next" }))
      .mockResolvedValueOnce(Response.json({ code: "PERMISSION_DENIED" }, { status: 403 })));
    await expect(requestAllPages("/api/workspaces/w/memberships"))
      .rejects.toMatchObject({ status: 403 });
  });

  it("dừng với lỗi khi server lặp cursor thay vì lặp request vô hạn", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(
      Response.json({ items: ["first"], nextCursor: "repeated" }),
    ));
    vi.stubGlobal("fetch", fetchMock);
    await expect(requestAllPages("/api/workspaces/w/memberships"))
      .rejects.toThrow("Cursor danh sách không hợp lệ");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
