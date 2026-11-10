import { ApiError, isRecord } from "../../lib/api-client";
import { parseTrackedOperation } from "../../lib/operation-status";

export type TeachingClass = {
  id: string;
  workspaceId: string;
  name: string;
  state: string;
  version: number;
};

export type RosterEntry = {
  id: string;
  studentUserId: string;
  username: string;
  state: string;
  version: number;
};

export type Subject = {
  id: string;
  code: string;
  name: string;
};

export type TeachingMutate = (
  action: string,
  signature: string,
  path: string,
  init: { method: string; body?: string; headers?: Record<string, string> },
) => Promise<unknown>;

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

// Mọi danh sách giảng dạy đều là trang {items, nextCursor?} đã lọc quyền ở backend.
export function parsePage(data: unknown): { items: unknown[]; nextCursor: string | null } {
  if (Array.isArray(data)) return { items: data, nextCursor: null };
  if (!isRecord(data) || !Array.isArray(data.items)) return { items: [], nextCursor: null };
  return {
    items: data.items,
    nextCursor: typeof data.nextCursor === "string" && data.nextCursor ? data.nextCursor : null,
  };
}

// Chỉ giữ lớp đúng ClassView của backend; bản ghi lệch bị bỏ thay vì hiển thị sai dữ kiện.
export function parseClasses(data: unknown): TeachingClass[] {
  const classes: TeachingClass[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.workspaceId !== "string" || !item.workspaceId
        || typeof item.name !== "string" || !item.name
        || typeof item.state !== "string" || !item.state
        || !isVersion(item.version)) {
      continue;
    }
    classes.push({
      id: item.id,
      workspaceId: item.workspaceId,
      name: item.name,
      state: item.state,
      version: item.version,
    });
  }
  return classes;
}

export function parseRoster(data: unknown): RosterEntry[] {
  const roster: RosterEntry[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.studentUserId !== "string" || !item.studentUserId
        || typeof item.username !== "string" || !item.username
        || typeof item.state !== "string" || !item.state
        || !isVersion(item.version)) {
      continue;
    }
    roster.push({
      id: item.id,
      studentUserId: item.studentUserId,
      username: item.username,
      state: item.state,
      version: item.version,
    });
  }
  return roster;
}

// Danh mục môn là dữ kiện tham chiếu dùng chung: mã và tên đều bắt buộc để phân biệt môn.
export function parseSubjects(data: unknown): Subject[] {
  const subjects: Subject[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.code !== "string" || !item.code
        || typeof item.name !== "string" || !item.name) {
      continue;
    }
    subjects.push({ id: item.id, code: item.code, name: item.name });
  }
  return subjects;
}

// Tên lớp/khóa sau cắt khoảng trắng không được rỗng; backend cũng từ chối tên rỗng.
export function nameBody(name: string): { name: string } | null {
  const trimmed = name.trim();
  return trimmed ? { name: trimmed } : null;
}

// If-Match của PATCH đổi tên là strong ETag chứa đúng row version hiện tại.
export function ifMatchValue(version: number): string {
  return `"${version}"`;
}

// Chỉ dọn draft/nạp lại sau thao tác THÀNH CÔNG: operation hoàn tất nhưng FAILED thì
// giữ nguyên dữ kiện người dùng đã nhập để họ sửa rồi gửi lại, thay vì xoá mất.
export function operationSucceeded(data: unknown): boolean {
  const operation = parseTrackedOperation(data);
  return operation !== null && operation.completed && operation.state === "SUCCEEDED";
}

export type HomeroomAssignment = {
  id: string;
  classId: string;
  teacherUserId: string;
  state: string;
  version: number;
};

// Chỉ giữ phân công chủ nhiệm đúng HomeroomAssignmentView của backend; backend chỉ
// trả phân công hiện hành, kèm id dòng để route thu hồi dùng.
export function parseHomeroomAssignments(data: unknown): HomeroomAssignment[] {
  const assignments: HomeroomAssignment[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.classId !== "string" || !item.classId
        || typeof item.teacherUserId !== "string" || !item.teacherUserId
        || typeof item.state !== "string" || !item.state
        || !isVersion(item.version)) {
      continue;
    }
    assignments.push({
      id: item.id,
      classId: item.classId,
      teacherUserId: item.teacherUserId,
      state: item.state,
      version: item.version,
    });
  }
  return assignments;
}

export function toggleSelection(
  current: readonly string[],
  id: string,
  checked: boolean,
): string[] {
  return checked ? [...current, id] : current.filter((item) => item !== id);
}

const errorMessagesByCode: Record<string, string> = {
  VERSION_CONFLICT: "Dữ liệu đã thay đổi ở nơi khác. Vui lòng tải lại rồi thử lại.",
  PRECONDITION_REQUIRED: "Thiếu điều kiện cập nhật. Vui lòng tải lại dữ liệu mới nhất rồi thử lại.",
  INVALID_IF_MATCH: "Điều kiện cập nhật không hợp lệ. Vui lòng tải lại dữ liệu mới nhất rồi thử lại.",
  AUTHORIZATION_NOT_READY: "Quyền đang được đồng bộ. Vui lòng thử lại sau giây lát.",
  OPERATION_FAILED: "Đồng bộ quyền không thành công. Vui lòng thử lại.",
  SUBSCRIPTION_INACTIVE: "Gói của trung tâm đã hết hạn nên không thêm mới được; việc thu hồi vẫn thực hiện theo thẩm quyền.",
  ENROLLMENT_CLASS_MISMATCH: "Học sinh và môn được chọn không thuộc cùng một lớp.",
  CLASS_NOT_ACTIVE: "Lớp đã bị thu hồi nên không thay đổi được nữa.",
  COURSE_NOT_ACTIVE: "Khóa đã bị thu hồi nên không thay đổi được nữa.",
  COURSE_SUBJECT_NOT_ACTIVE: "Môn trong khóa đã bị gỡ nên không thay đổi được nữa.",
  TEACHER_NOT_CENTER_TEACHER: "Người được chọn không phải là giáo viên đang hoạt động của trung tâm.",
  STUDENT_NOT_CENTER_STUDENT: "Người được chọn không còn vai trò học sinh đang hoạt động của trung tâm.",
  TEACHING_CONTEXT_NOT_ACTIVE: "Lớp hoặc khóa đã bị thu hồi nên không mở rộng hoạt động được nữa.",
  STUDENT_NOT_CENTER_MEMBER: "Học sinh được chọn không phải là thành viên đang hoạt động của trung tâm.",
  PERMISSION_DENIED: "Bạn không có quyền thực hiện thao tác này.",
};

// Dịch lỗi API thành câu ngắn cho người dùng; mã nghiệp vụ đã biết được ưu tiên hơn
// thông điệp thô, không hiển thị chi tiết policy hay dữ kiện nội bộ.
export function teachingErrorMessage(error: unknown, fallback = "Thao tác không thành công."): string {
  if (error instanceof ApiError) {
    if (error.code && errorMessagesByCode[error.code]) return errorMessagesByCode[error.code];
    return error.message;
  }
  return error instanceof Error ? error.message : fallback;
}

export function stateLabel(state: string): string {
  if (state === "ACTIVE") return "Đang hoạt động";
  if (state === "REVOKED") return "Đã thu hồi";
  return state;
}
