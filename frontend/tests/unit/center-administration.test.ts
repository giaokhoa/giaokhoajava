import { requestWhenReady } from "@/lib/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  centerCreateBody,
  invitationBody,
  memberPermissions,
  membershipRolesBody,
  parseCenters,
  parseMemberships,
  parseStudentIssue,
  studentIssueBody,
  subscriptionConfirmBody,
} from "@/features/tenancy/center-administration";

describe("danh sách trung tâm và thành viên theo hợp đồng", () => {
  it("chấp nhận dạng {items} và danh sách trần, bỏ bản ghi thiếu trường", () => {
    expect(parseCenters({ items: [
      { id: "center-1", name: "Trung tâm A" },
      { id: "center-2" },
      { name: "Thiếu mã" },
    ] })).toEqual([{ id: "center-1", name: "Trung tâm A" }]);
    expect(parseCenters([{ id: "center-3", name: "Trung tâm B" }]))
      .toEqual([{ id: "center-3", name: "Trung tâm B" }]);
    expect(parseCenters("khong-phai-object")).toEqual([]);
  });

  it("chỉ giữ membership đúng MembershipView và vai trò thuộc danh mục cố định", () => {
    expect(parseMemberships({ items: [
      {
        id: "m-1",
        user: { id: "u-1", username: "manager1", displayName: "Quản lý Một" },
        roles: ["MANAGER"],
        state: "ACTIVE",
        version: 3,
      },
      {
        id: "m-2",
        user: { id: "u-2", username: "la" },
        roles: ["SUPER_ADMIN"],
        state: "ACTIVE",
        version: 0,
      },
      {
        id: "m-3",
        user: { id: "u-3", username: "thieu-version" },
        roles: ["TEACHER"],
        state: "ACTIVE",
      },
    ] })).toEqual([{
      id: "m-1",
      user: { id: "u-1", username: "manager1", displayName: "Quản lý Một" },
      roles: ["MANAGER"],
      state: "ACTIVE",
      version: 3,
    }]);
  });
});

describe("body tạo trung tâm và xác nhận gói", () => {
  it("cắt khoảng trắng và yêu cầu đủ tên cùng chủ sở hữu", () => {
    expect(centerCreateBody("  Trung tâm A  ", " owner-1 "))
      .toEqual({ name: "Trung tâm A", ownerUserId: "owner-1" });
    expect(centerCreateBody("", "owner-1")).toBeNull();
    expect(centerCreateBody("Trung tâm A", "   ")).toBeNull();
  });

  it("chuyển mốc thời gian sang ISO UTC và chặn khoảng hiệu lực ngược", () => {
    expect(subscriptionConfirmBody(
      "center-1", "2026-11-07T00:00", "2027-02-07T00:00", " ref-1 ",
    )).toEqual({
      workspaceId: "center-1",
      validFrom: new Date("2026-11-07T00:00").toISOString(),
      expiresAt: new Date("2027-02-07T00:00").toISOString(),
      confirmationRef: "ref-1",
    });
    expect(subscriptionConfirmBody(
      "center-1", "2027-02-07T00:00", "2026-11-07T00:00", "ref-1",
    )).toBeNull();
    expect(subscriptionConfirmBody("", "2026-11-07T00:00", "2027-02-07T00:00", "ref-1"))
      .toBeNull();
    expect(subscriptionConfirmBody("center-1", "khong-phai-ngay", "2027-02-07T00:00", "ref-1"))
      .toBeNull();
  });
});

describe("lời mời và trình sửa vai trò dùng danh mục cố định", () => {
  it("lời mời có đúng một đích và không bao giờ chứa OWNER", () => {
    expect(invitationBody("a@example.com", "", ["TEACHER"]))
      .toEqual({ email: "a@example.com", roles: ["TEACHER"] });
    expect(invitationBody("", "user-1", ["MANAGER", "TEACHER"]))
      .toEqual({ userId: "user-1", roles: ["MANAGER", "TEACHER"] });
    expect(invitationBody("a@example.com", "user-1", ["TEACHER"])).toBeNull();
    expect(invitationBody("", "", ["TEACHER"])).toBeNull();
    expect(invitationBody("a@example.com", "", ["OWNER"])).toBeNull();
    expect(invitationBody("a@example.com", "", ["TEACHER", "OWNER"])).toBeNull();
    expect(invitationBody("a@example.com", "", [])).toBeNull();
    expect(invitationBody("a@example.com", "", ["KHONG_CO"])).toBeNull();
  });

  it("bộ vai trò gửi lên giữ đúng thứ tự danh mục và không rỗng", () => {
    expect(membershipRolesBody(["STUDENT", "MANAGER"]))
      .toEqual({ roles: ["MANAGER", "STUDENT"] });
    expect(membershipRolesBody([])).toBeNull();
    expect(membershipRolesBody(["KHONG_CO"])).toBeNull();
  });

  it("quyền hiển thị theo vai trò backend trả về của chính actor", () => {
    const memberships = parseMemberships([
      { id: "m-1", user: { id: "owner-1", username: "owner" }, roles: ["OWNER"], state: "ACTIVE", version: 0 },
      { id: "m-2", user: { id: "manager-1", username: "manager" }, roles: ["MANAGER"], state: "ACTIVE", version: 0 },
      { id: "m-3", user: { id: "teacher-1", username: "teacher" }, roles: ["TEACHER"], state: "ACTIVE", version: 0 },
    ]);
    expect(memberPermissions(memberships, "owner-1"))
      .toEqual({ canManage: true, canAssignManager: true });
    expect(memberPermissions(memberships, "manager-1"))
      .toEqual({ canManage: true, canAssignManager: false });
    expect(memberPermissions(memberships, "teacher-1"))
      .toEqual({ canManage: false, canAssignManager: false });
    expect(memberPermissions(memberships, "nguoi-ngoai"))
      .toEqual({ canManage: false, canAssignManager: false });
    expect(memberPermissions(memberships, null))
      .toEqual({ canManage: false, canAssignManager: false });
  });
});

describe("cấp học sinh và handoff một lần", () => {
  it("body cấp học sinh không chứa origin, vai trò toàn cục hay mật khẩu do client chọn", () => {
    expect(studentIssueBody(" hs-1 ", " Học sinh Một ", ""))
      .toEqual({ username: "hs-1", displayName: "Học sinh Một" });
    expect(studentIssueBody("hs-1", "Học sinh Một", " hs@example.com "))
      .toEqual({ username: "hs-1", displayName: "Học sinh Một", email: "hs@example.com" });
    expect(studentIssueBody("", "Học sinh Một", "")).toBeNull();
    expect(studentIssueBody("hs-1", "  ", "")).toBeNull();
  });

  it("chỉ response CREATE có handoff mới trích được mật khẩu tạm", () => {
    expect(parseStudentIssue({
      operationId: "op-1",
      state: "PENDING",
      completed: false,
      statusUrl: "/api/me/operations/op-1",
      handoff: { username: "hs-1", temporaryPassword: "tam-123" },
    })).toEqual({
      operation: { id: "op-1", state: "PENDING", completed: false, errorCode: undefined },
      handoff: { username: "hs-1", temporaryPassword: "tam-123" },
    });
  });

  it("poll và retry cùng key không có handoff nên không trích lại được mật khẩu tạm", () => {
    const poll = parseStudentIssue({ id: "op-1", state: "SUCCEEDED", completed: true });
    expect(poll?.handoff).toBeUndefined();
    expect(poll?.operation.state).toBe("SUCCEEDED");
    expect(parseStudentIssue({ id: "op-2", state: "PENDING" })).toBeNull();
    expect(parseStudentIssue("khong-phai-object")).toBeNull();
  });
});

describe("vào trực tiếp trang quản trị khi eligibility đang đồng bộ", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("thử lại danh sách sau fence 503 rồi nhận dữ liệu, không cần gọi workspace trước", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "AUTHORIZATION_NOT_READY" }), { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: "c1", name: "Trung tâm" }]), { status: 200 }));
    vi.stubGlobal("fetch", fetcher);
    const pending = requestWhenReady("/api/platform/centers");
    await vi.advanceTimersByTimeAsync(1_000);
    expect((await pending).data).toEqual([{ id: "c1", name: "Trung tâm" }]);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.every(([path]) => path === "/api/platform/centers")).toBe(true);
  });

  it.each([
    [403, "PERMISSION_DENIED"],
    [503, "IDENTITY_UNAVAILABLE"],
  ])("không thử lại lỗi %s %s", async (status, code) => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code }), { status }));
    vi.stubGlobal("fetch", fetcher);
    await expect(requestWhenReady("/api/platform/centers")).rejects.toMatchObject({ status, code });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
