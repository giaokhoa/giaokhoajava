import { describe, expect, it } from "vitest";
import {
  guardianDecisionBody,
  guardianPermissions,
  guardianRequestBody,
  parseGuardianLinks,
  type GuardianLink,
} from "@/features/guardians/guardian-link-review";
import {
  attemptKey,
  closeAttempt,
} from "@/features/operations/operation-attempts";
import type { TrackedOperation } from "@/lib/operation-status";
import { parsePublishedResults } from "@/features/guardians/published-results";
import type { Membership } from "@/lib/memberships";

const lienKetDung: GuardianLink = {
  id: "l-1",
  workspaceId: "w-1",
  parentUserId: "u-parent",
  studentUserId: "u-student",
  state: "REQUESTED",
  version: 2,
};

function membership(roles: Membership["roles"]): Membership {
  return {
    id: "m-1",
    user: { id: "u-actor", username: "actor" },
    roles,
    state: "ACTIVE",
    version: 0,
  };
}

describe("parse danh sách liên kết theo GuardianLinkView của backend", () => {
  it("giữ liên kết đúng DTO từ cả dạng {items} và mảng trần", () => {
    expect(parseGuardianLinks({ items: [lienKetDung] })).toEqual([lienKetDung]);
    expect(parseGuardianLinks([lienKetDung])).toEqual([lienKetDung]);
    expect(parseGuardianLinks("khong-phai-object")).toEqual([]);
  });

  it("bỏ bản ghi thiếu trường bắt buộc hoặc có trạng thái ngoài vòng đời", () => {
    expect(parseGuardianLinks({ items: [
      lienKetDung,
      { ...lienKetDung, id: "l-2", state: "PENDING" },
      { ...lienKetDung, id: "l-3", version: -1 },
      { id: "l-4", workspaceId: "w-1", studentUserId: "u-student", state: "APPROVED", version: 0 },
      { ...lienKetDung, id: "l-5", state: "REVOKED", version: 3 },
    ] })).toEqual([
      lienKetDung,
      { ...lienKetDung, id: "l-5", state: "REVOKED", version: 3 },
    ]);
  });
});

describe("body thao tác liên kết", () => {
  it("yêu cầu liên kết chỉ mang studentUserId đã cắt khoảng trắng", () => {
    expect(guardianRequestBody("  u-student  ")).toEqual({ studentUserId: "u-student" });
    expect(guardianRequestBody("   ")).toBeNull();
  });

  it("quyết định duyệt mang expectedVersion của bản đọc hiện tại", () => {
    expect(guardianDecisionBody(lienKetDung, "APPROVE"))
      .toBeNull();
    expect(guardianDecisionBody(lienKetDung, "APPROVE", "  proof-outside-123  "))
      .toEqual({ decision: "APPROVE", expectedVersion: 2, verificationRef: "proof-outside-123" });
    expect(guardianDecisionBody(lienKetDung, "APPROVE", " ".repeat(160)))
      .toBeNull();
    expect(guardianDecisionBody({ ...lienKetDung, version: 5 }, "REJECT"))
      .toEqual({ decision: "REJECT", expectedVersion: 5 });
  });
});

describe("vòng đời idempotency key của một lần gửi", () => {
  const signature = JSON.stringify(["u-parent", "w-1", "request", { studentUserId: "u-student" }]);
  const operation = (completed: boolean): Pick<TrackedOperation, "completed"> => ({ completed });

  it("gửi lại khi operation chưa hoàn tất vẫn giữ key cũ để backend replay đúng operation", () => {
    const store = new Map<string, string>();
    const key = attemptKey(store, signature);
    closeAttempt(store, signature, operation(false));
    expect(attemptKey(store, signature)).toBe(key);
  });

  it("operation đã hoàn tất thì lần gửi mới cho cùng học sinh sinh key mới", () => {
    const store = new Map<string, string>();
    const key = attemptKey(store, signature);
    closeAttempt(store, signature, operation(true));
    const nextKey = attemptKey(store, signature);
    expect(nextKey).not.toBe(key);
    expect(attemptKey(store, signature)).toBe(nextKey);
  });

  it("payload khác nhau dùng key khác nhau", () => {
    const store = new Map<string, string>();
    const other = JSON.stringify(["u-parent", "w-1", "request", { studentUserId: "u-student-2" }]);
    expect(attemptKey(store, other)).not.toBe(attemptKey(store, signature));
  });
});

describe("quyền hiển thị theo vai trò của chính actor", () => {
  it("owner/manager duyệt được, parent gửi yêu cầu được, giáo viên không có cả hai", () => {
    expect(guardianPermissions([membership(["OWNER"])], "u-actor"))
      .toEqual({ canManage: true, canRequest: false });
    expect(guardianPermissions([membership(["MANAGER", "PARENT"])], "u-actor"))
      .toEqual({ canManage: true, canRequest: true });
    expect(guardianPermissions([membership(["PARENT"])], "u-actor"))
      .toEqual({ canManage: false, canRequest: true });
    expect(guardianPermissions([membership(["TEACHER"])], "u-actor"))
      .toEqual({ canManage: false, canRequest: false });
    expect(guardianPermissions([], "u-actor"))
      .toEqual({ canManage: false, canRequest: false });
    expect(guardianPermissions([membership(["OWNER"])], null))
      .toEqual({ canManage: false, canRequest: false });
  });
});

describe("parse kết quả đã công bố theo PublishedResultView", () => {
  it("chỉ giữ đúng các trường hiển thị, trường lạ không đi vào dữ liệu", () => {
    const parsed = parsePublishedResults({ items: [{
      id: "r-1",
      student: { id: "u-student", displayName: "Học sinh Một" },
      className: "Lớp 1",
      courseName: "Khóa 1",
      subjectName: "Toán",
      score: "9.5",
      comment: "Làm tốt",
      publishedAt: "2026-11-09T00:00:00Z",
      submission: { body: "bai-lam" },
      answer: "dap-an",
      contentStub: "noi-dung",
    }] });
    expect(parsed).toEqual([{
      id: "r-1",
      student: { id: "u-student", displayName: "Học sinh Một" },
      className: "Lớp 1",
      courseName: "Khóa 1",
      subjectName: "Toán",
      score: "9.5",
      comment: "Làm tốt",
      publishedAt: "2026-11-09T00:00:00Z",
    }]);
    expect(Object.keys(parsed[0]).sort()).toEqual([
      "className", "comment", "courseName", "id", "publishedAt", "score", "student", "subjectName",
    ]);
  });

  it("giữ điểm/nhận xét null và bỏ bản ghi thiếu trường hiển thị bắt buộc", () => {
    expect(parsePublishedResults({ items: [
      {
        id: "r-1",
        student: { id: "u-student", displayName: "Học sinh Một" },
        className: "Lớp 1",
        courseName: "Khóa 1",
        subjectName: "Toán",
        score: null,
        comment: null,
        publishedAt: null,
      },
      {
        id: "r-2",
        student: { id: "u-student", displayName: "Học sinh Một" },
        className: "Lớp 1",
        courseName: "Khóa 1",
        score: "8.0",
        comment: null,
        publishedAt: null,
      },
      {
        id: "r-3",
        student: { id: "u-student", displayName: "Học sinh Một" },
        className: "Lớp 1",
        courseName: "Khóa 1",
        subjectName: "Toán",
        score: 8,
        comment: null,
        publishedAt: null,
      },
    ] })).toEqual([{
      id: "r-1",
      student: { id: "u-student", displayName: "Học sinh Một" },
      className: "Lớp 1",
      courseName: "Khóa 1",
      subjectName: "Toán",
      score: null,
      comment: null,
      publishedAt: null,
    }]);
  });
});
