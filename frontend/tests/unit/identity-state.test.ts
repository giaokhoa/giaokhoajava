import { describe, expect, it } from "vitest";
import { parseSessionView } from "@/features/identity/session-provider";
import { parseWorkspaces } from "@/features/identity/workspace-switcher";

describe("trạng thái phiên và không gian", () => {
  it("phân biệt phiên ẩn danh và phiên đã đăng nhập", () => {
    expect(parseSessionView({ authenticated: false })).toEqual({ status: "anonymous" });
    expect(parseSessionView({
      authenticated: true,
      user: {
        id: "user-1",
        username: "nguoi-dung",
        email: "user@example.com",
        emailVerified: true,
        identityState: "ACTIVE",
      },
    })).toEqual({
      status: "authenticated",
      user: {
        id: "user-1",
        username: "nguoi-dung",
        email: "user@example.com",
        emailVerified: true,
        identityState: "ACTIVE",
      },
    });
  });

  it("không coi phản hồi thiếu thông tin người dùng là đã đăng nhập", () => {
    expect(parseSessionView({ authenticated: true }).status).toBe("error");
  });

  it("chỉ giữ workspace đúng hợp đồng và mặc định fail-closed cho gói", () => {
    expect(parseWorkspaces({ items: [] })).toEqual([]);
    expect(parseWorkspaces([
      {
        id: "workspace-1",
        kind: "CENTER",
        name: "Trung tâm A",
        subscription: { effective: true, expiresAt: "2027-01-01T00:00:00Z" },
        roles: ["OWNER", 42, ""],
        syncState: "READY",
      },
      {
        id: "workspace-2",
        kind: "PERSONAL",
        name: "Cá nhân",
      },
      { id: "workspace-3", kind: "UNKNOWN", name: "Không hợp lệ" },
    ])).toEqual([
      {
        id: "workspace-1",
        kind: "CENTER",
        name: "Trung tâm A",
        subscription: { effective: true, expiresAt: "2027-01-01T00:00:00Z" },
        roles: ["OWNER"],
        syncState: "READY",
      },
      {
        id: "workspace-2",
        kind: "PERSONAL",
        name: "Cá nhân",
        subscription: { effective: false, expiresAt: undefined },
        roles: [],
        syncState: "UNKNOWN",
      },
    ]);
  });
});
