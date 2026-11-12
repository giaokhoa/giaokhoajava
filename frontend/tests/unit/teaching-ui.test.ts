import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api-client";
import {
  ifMatchValue,
  nameBody,
  operationSucceeded,
  parseClasses,
  parseHomeroomAssignments,
  parsePage,
  parseRoster,
  parseSubjects,
  teachingErrorMessage,
  toggleSelection,
} from "@/features/teaching/class-management";
import {
  courseCreateBody,
  parseCourses,
  parseTeacherAssignments,
  subjectAddBody,
  teacherAssignBody,
} from "@/features/teaching/course-subject-editor";
import {
  enrollmentBatchBody,
  courseCompletionBody,
  parseEnrollments,
} from "@/features/teaching/enrollment-editor";

describe("parse dữ liệu giảng dạy theo DTO của backend", () => {
  it("chỉ giữ lớp đúng ClassView và đọc nextCursor của trang", () => {
    const data = {
      items: [
        { id: "c-1", workspaceId: "w-1", name: "Lớp 1", state: "ACTIVE", version: 0 },
        { id: "c-2", workspaceId: "w-1", name: "Thiếu trạng thái", version: 0 },
        { id: "c-3", workspaceId: "w-1", name: "Version âm", state: "ACTIVE", version: -1 },
      ],
      nextCursor: "cursor-1",
    };
    expect(parseClasses(data)).toEqual([
      { id: "c-1", workspaceId: "w-1", name: "Lớp 1", state: "ACTIVE", version: 0 },
    ]);
    expect(parsePage(data).nextCursor).toBe("cursor-1");
    expect(parsePage({ items: [] }).nextCursor).toBeNull();
    expect(parsePage("khong-phai-object")).toEqual({ items: [], nextCursor: null });
  });

  it("chỉ giữ roster và môn đúng DTO, bỏ bản ghi thiếu trường bắt buộc", () => {
    expect(parseRoster({ items: [
      { id: "r-1", studentUserId: "u-1", username: "hs1", state: "ACTIVE", version: 2 },
      { id: "r-2", studentUserId: "u-2", state: "ACTIVE", version: 0 },
    ] })).toEqual([
      { id: "r-1", studentUserId: "u-1", username: "hs1", state: "ACTIVE", version: 2 },
    ]);
    expect(parseSubjects({ items: [
      { id: "s-1", code: "TOAN", name: "Toán" },
      { id: "s-2", name: "Thiếu mã" },
    ] })).toEqual([{ id: "s-1", code: "TOAN", name: "Toán" }]);
  });

  it("khóa phải có mọi môn đúng SubjectEntry mới được giữ", () => {
    expect(parseCourses({ items: [
      {
        id: "k-1",
        classId: "c-1",
        name: "Khóa A",
        subjects: [{ id: "cs-1", subjectId: "s-1", name: "Toán" }],
        version: 1,
      },
      {
        id: "k-2",
        classId: "c-1",
        name: "Khóa lỗi môn",
        subjects: [{ id: "cs-2", name: "Thiếu subjectId" }],
        version: 0,
      },
    ] })).toEqual([{
      id: "k-1",
      classId: "c-1",
      name: "Khóa A",
      subjects: [{ id: "cs-1", subjectId: "s-1", name: "Toán" }],
      version: 1,
    }]);
  });

  it("chỉ giữ ghi danh đúng EnrollmentView", () => {
    expect(parseEnrollments({ items: [
      {
        id: "e-1",
        classStudentId: "r-1",
        courseSubjectId: "cs-1",
        studentUserId: "u-1",
        state: "ACTIVE",
        version: 0,
      },
      { id: "e-2", classStudentId: "r-2", courseSubjectId: "cs-1", state: "ACTIVE", version: 0 },
    ] })).toHaveLength(1);
  });

  it("chỉ giữ phân công giáo viên/chủ nhiệm đúng DTO, bỏ bản ghi thiếu trường bắt buộc", () => {
    expect(parseTeacherAssignments({ items: [
      { id: "a-1", courseSubjectId: "cs-1", teacherUserId: "gv-1", state: "ACTIVE", version: 0 },
      { id: "a-2", courseSubjectId: "cs-1", state: "ACTIVE", version: 0 },
      { id: "a-3", courseSubjectId: "cs-1", teacherUserId: "gv-2", state: "ACTIVE", version: -1 },
    ] })).toEqual([
      { id: "a-1", courseSubjectId: "cs-1", teacherUserId: "gv-1", state: "ACTIVE", version: 0 },
    ]);
    expect(parseHomeroomAssignments({ items: [
      { id: "h-1", classId: "c-1", teacherUserId: "gv-1", state: "ACTIVE", version: 3 },
      { id: "h-2", classId: "c-1", teacherUserId: "gv-2", state: "ACTIVE" },
    ] })).toEqual([
      { id: "h-1", classId: "c-1", teacherUserId: "gv-1", state: "ACTIVE", version: 3 },
    ]);
  });
});

describe("chỉ dọn draft sau thao tác thành công", () => {
  it("operation hoàn tất nhưng FAILED thì không tính là thành công", () => {
    expect(operationSucceeded({ id: "op-1", state: "SUCCEEDED", completed: true })).toBe(true);
    expect(operationSucceeded({ id: "op-1", state: "FAILED", completed: true })).toBe(false);
    expect(operationSucceeded({ id: "op-1", state: "RETRYABLE_FAILURE", completed: true })).toBe(false);
    expect(operationSucceeded({ id: "op-1", state: "SUCCEEDED", completed: false })).toBe(false);
    expect(operationSucceeded(null)).toBe(false);
    expect(operationSucceeded("khong-phai-operation")).toBe(false);
  });
});

describe("body tạo lớp/khóa và chọn môn bằng checkbox", () => {
  it("tên sau cắt khoảng trắng không được rỗng", () => {
    expect(nameBody("  Lớp 1  ")).toEqual({ name: "Lớp 1" });
    expect(nameBody("   ")).toBeNull();
  });

  it("tạo khóa buộc ít nhất một môn hiện có và không trùng môn", () => {
    expect(courseCreateBody("Khóa A", ["s-1", "s-2"]))
      .toEqual({ name: "Khóa A", subjectIds: ["s-1", "s-2"] });
    expect(courseCreateBody("Khóa A", [])).toBeNull();
    expect(courseCreateBody("Khóa A", ["s-1", "s-1"])).toBeNull();
    expect(courseCreateBody("  ", ["s-1"])).toBeNull();
    expect(subjectAddBody(["s-3"])).toEqual({ subjectIds: ["s-3"] });
    expect(subjectAddBody([])).toBeNull();
  });

  it("toggle chọn giữ thứ tự chọn và bỏ chọn đúng phần tử", () => {
    expect(toggleSelection(["s-1"], "s-2", true)).toEqual(["s-1", "s-2"]);
    expect(toggleSelection(["s-1", "s-2"], "s-1", false)).toEqual(["s-2"]);
  });

  it("phân công nhiều giáo viên giữ toàn bộ danh sách đã chọn, chặn lô rỗng", () => {
    expect(teacherAssignBody(["gv-1", "gv-2", "gv-3"]))
      .toEqual({ teacherUserIds: ["gv-1", "gv-2", "gv-3"] });
    expect(teacherAssignBody([])).toBeNull();
    expect(teacherAssignBody(["gv-1", "gv-1"])).toBeNull();
  });
});

describe("ghi danh theo lô", () => {
  it("lô là tích của học sinh và môn–khóa đã chọn; hai phía không được rỗng", () => {
    expect(enrollmentBatchBody(["r-1", "r-2"], ["cs-1"]))
      .toEqual({ classStudentIds: ["r-1", "r-2"], courseSubjectIds: ["cs-1"] });
    expect(enrollmentBatchBody([], ["cs-1"])).toBeNull();
    expect(enrollmentBatchBody(["r-1"], [])).toBeNull();
    expect(enrollmentBatchBody(["r-1", "r-1"], ["cs-1"])).toBeNull();
  });
});

describe("hoàn thành khóa đa môn theo snapshot", () => {
  it("giữ đầy đủ mọi môn của đúng học sinh, sắp ID ổn định và gửi version", () => {
    const sample = (id: string, studentUserId: string, version: number, state = "ACTIVE") => ({
      id, studentUserId, version, state, classStudentId: "r-1", courseSubjectId: `cs-${id}`,
    });
    expect(courseCompletionBody([
      sample("e-3", "s-1", 5), sample("e-1", "s-1", 0),
      sample("e-2", "s-1", 2), sample("e-other", "s-2", 0),
    ], "s-1")).toEqual({ enrollments: [
      { id: "e-1", expectedVersion: 0 },
      { id: "e-2", expectedVersion: 2 },
      { id: "e-3", expectedVersion: 5 },
    ] });
    expect(courseCompletionBody([], "s-1")).toBeNull();
    expect(courseCompletionBody([sample("e-1", "s-1", 0), sample("e-1", "s-1", 0)], "s-1"))
      .toBeNull();
    expect(courseCompletionBody([sample("e-1", "s-1", 0, "PENDING")], "s-1")).toBeNull();
    expect(courseCompletionBody([sample("e-1", "s-1", -1)], "s-1")).toBeNull();
  });
});

describe("đổi tên có điều kiện If-Match và dịch lỗi 409/428", () => {
  it("If-Match là strong ETag chứa đúng row version", () => {
    expect(ifMatchValue(0)).toBe("\"0\"");
    expect(ifMatchValue(12)).toBe("\"12\"");
  });

  it("409 VERSION_CONFLICT yêu cầu tải lại thay vì gửi tiếp version cũ", () => {
    const error = new ApiError(409, { code: "VERSION_CONFLICT" });
    expect(teachingErrorMessage(error)).toContain("tải lại");
  });

  it("428 PRECONDITION_REQUIRED báo thiếu điều kiện cập nhật", () => {
    const error = new ApiError(428, { code: "PRECONDITION_REQUIRED" });
    expect(teachingErrorMessage(error)).toContain("Thiếu điều kiện cập nhật");
  });

  it("mã nghiệp vụ giảng dạy có câu giải thích ngắn riêng", () => {
    expect(teachingErrorMessage(new ApiError(409, { code: "ENROLLMENT_CLASS_MISMATCH" })))
      .toContain("cùng một lớp");
    expect(teachingErrorMessage(new ApiError(403, { code: "SUBSCRIPTION_INACTIVE" })))
      .toContain("hết hạn");
    expect(teachingErrorMessage(new ApiError(403, { code: "TEACHER_NOT_CENTER_TEACHER" })))
      .toContain("giáo viên");
    expect(teachingErrorMessage(new Error("Lỗi lạ"))).toBe("Lỗi lạ");
    expect(teachingErrorMessage("khong-phai-error", "Dự phòng.")).toBe("Dự phòng.");
  });
});
