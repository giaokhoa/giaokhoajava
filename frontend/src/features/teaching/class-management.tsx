"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../../lib/api-client";
import { operationStatus } from "../operations/operation-status";
import {
  parseTrackedOperation,
  type TrackedOperation,
} from "../identity/recovery-request-form";
import { useSession } from "../identity/session-provider";
import { parseWorkspaces, type WorkspaceView } from "../identity/workspace-switcher";
import {
  memberPermissions,
  parseMemberships,
  type Membership,
} from "../tenancy/center-administration";
import { CourseSubjectEditor } from "./course-subject-editor";

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

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

const FENCE_RETRY_LIMIT_MS = 60_000;
const FENCE_RETRY_INTERVAL_MS = 1_000;

// Cùng mẫu mutation của quản trị thành viên: lần mutation đầu của actor mới có thể gặp
// 503 AUTHORIZATION_NOT_READY khi fence chưa READY; giữ nguyên Idempotency-Key khi thử
// lại vì key chưa từng được ghi. Không thử lại 400/403/409/428 hay 503 với mã khác.
export async function submitTeachingMutation(
  path: string,
  init: { method: string; body?: string; headers?: Record<string, string> },
  idempotencyKey: string,
): Promise<unknown> {
  const startedAt = Date.now();
  for (;;) {
    try {
      const result = await apiRequest<unknown>(path, {
        method: init.method,
        body: init.body,
        headers: { ...init.headers, "Idempotency-Key": idempotencyKey },
      });
      return result.data;
    } catch (error) {
      const retryable = error instanceof ApiError
        && error.status === 503
        && error.code === "AUTHORIZATION_NOT_READY"
        && Date.now() - startedAt < FENCE_RETRY_LIMIT_MS;
      if (!retryable) throw error;
      await new Promise((resolve) => setTimeout(resolve, FENCE_RETRY_INTERVAL_MS));
    }
  }
}

// Cùng một payload (kèm version đích) luôn dùng lại một key giữa các lần gửi lại sau lỗi.
function attemptKey(store: Map<string, string>, signature: string): string {
  let key = store.get(signature);
  if (!key) {
    key = crypto.randomUUID();
    store.set(signature, key);
  }
  return key;
}

function stateLabel(state: string): string {
  if (state === "ACTIVE") return "Đang hoạt động";
  if (state === "REVOKED") return "Đã thu hồi";
  return state;
}

function OperationPanel({
  operation,
  checking,
  onCheck,
}: {
  operation: TrackedOperation;
  checking: boolean;
  onCheck: () => void;
}) {
  return (
    <section className="section" aria-labelledby="teaching-operation-title">
      <h2 id="teaching-operation-title">Trạng thái thao tác</h2>
      <p>{operationStatus(operation).message}</p>
      <p>
        Mã thao tác: <code>{operation.id}</code>
      </p>
      {operation.errorCode ? <p>Mã lỗi: {operation.errorCode}</p> : null}
      <button
        className="secondary-button"
        type="button"
        disabled={checking}
        onClick={onCheck}
      >
        Kiểm tra trạng thái
      </button>
    </section>
  );
}

function SessionGate({
  status,
  message,
  onRetry,
  children,
}: {
  status: string;
  message?: string;
  onRetry: () => void;
  children: React.ReactNode;
}) {
  if (status === "loading") return <p role="status">Đang tải trạng thái tài khoản…</p>;
  if (status === "error") {
    return (
      <>
        <p role="alert">{message}</p>
        <button className="action-button" type="button" onClick={onRetry}>
          Thử lại
        </button>
      </>
    );
  }
  if (status === "anonymous") {
    return (
      <>
        <p className="introduction">
          Đăng nhập để quản lý lớp, khóa và phân công thuộc phạm vi của bạn.
        </p>
        <a className="login-link" href="/oauth2/authorization/keycloak">
          Đăng nhập
        </a>
      </>
    );
  }
  return <>{children}</>;
}

function HomeroomAssignmentList({
  workspaceId,
  classId,
  teachers,
  canManage,
  busy,
  refreshTick,
  userId,
  mutate,
}: {
  workspaceId: string;
  classId: string;
  teachers: Membership[];
  canManage: boolean;
  busy: boolean;
  refreshTick: number;
  userId: string | null;
  mutate: TeachingMutate;
}) {
  const [assignments, setAssignments] = useState<HomeroomAssignment[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const wid = encodeURIComponent(workspaceId);
  const assignmentsPath = `/api/workspaces/${wid}/classes/${encodeURIComponent(classId)}/homeroom-assignments`;
  const nameOf = new Map(
    teachers.map((member) => [member.user.id, member.user.displayName ?? member.user.username]),
  );

  async function loadAssignments() {
    setLoadError(null);
    try {
      const result = await apiRequest<unknown>(assignmentsPath);
      if (result.kind !== "data") throw new Error("Phản hồi danh sách chủ nhiệm không hợp lệ.");
      setAssignments(parseHomeroomAssignments(result.data));
    } catch (error) {
      setAssignments([]);
      setLoadError(teachingErrorMessage(error, "Không tải được danh sách chủ nhiệm."));
    }
  }

  useEffect(() => {
    let ignore = false;
    apiRequest<unknown>(assignmentsPath)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setAssignments(parseHomeroomAssignments(result.data));
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setAssignments([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách chủ nhiệm."));
      });
    return () => {
      ignore = true;
    };
    // refreshTick tăng sau mỗi thao tác hoàn tất ở trang cha để nạp lại đúng lớp đang mở.
  }, [assignmentsPath, refreshTick]);

  async function revokeAssignment(item: HomeroomAssignment) {
    const data = await mutate(
      `revoke-homeroom:${item.id}`,
      JSON.stringify([userId, workspaceId, "revoke-homeroom", classId, item.id, item.version]),
      `${assignmentsPath}/${encodeURIComponent(item.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) await loadAssignments();
  }

  return (
    <section aria-labelledby="homeroom-assignments-title">
      <h3 id="homeroom-assignments-title">Chủ nhiệm hiện hành của lớp</h3>
      {assignments === null ? <p role="status">Đang tải danh sách chủ nhiệm…</p> : null}
      {loadError ? <p role="alert">{loadError}</p> : null}
      {assignments !== null && assignments.length === 0 && !loadError ? (
        <p>Lớp chưa có chủ nhiệm hiện hành.</p>
      ) : null}
      {assignments !== null && assignments.length > 0 ? (
        <ul>
          {assignments.map((item) => (
            <li key={item.id}>
              {nameOf.get(item.teacherUserId) ?? item.teacherUserId} ({stateLabel(item.state)})
              {canManage && item.state !== "REVOKED" ? (
                <>
                  {" "}
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={() => void revokeAssignment(item)}
                  >
                    Thu hồi chủ nhiệm
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      <p>
        Thu hồi chỉ dừng phân công chủ nhiệm hiện hành của giáo viên đó, không xoá giáo
        viên khỏi trung tâm.
      </p>
    </section>
  );
}

export function CenterClassesContent({ workspaceId }: { workspaceId: string }) {
  const { session, refreshSession } = useSession();
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [memberships, setMemberships] = useState<Membership[] | null>(null);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [classes, setClasses] = useState<TeachingClass[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedClassId, setSelectedClassId] = useState<string | null>(null);
  const [roster, setRoster] = useState<RosterEntry[] | null>(null);
  const [rosterError, setRosterError] = useState<string | null>(null);
  const [createName, setCreateName] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [renameDrafts, setRenameDrafts] = useState<Record<string, string>>({});
  const [rosterDraft, setRosterDraft] = useState<string[]>([]);
  const [rosterFormError, setRosterFormError] = useState<string | null>(null);
  const [homeroomTeacher, setHomeroomTeacher] = useState("");
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const attempts = useRef(new Map<string, string>());
  const userId = session.status === "authenticated" ? session.user.id : null;

  const wid = encodeURIComponent(workspaceId);
  const classesPath = `/api/workspaces/${wid}/classes`;
  const { canManage } = memberPermissions(memberships ?? [], userId);
  const subscriptionExpired = workspace?.kind === "CENTER" && !workspace.subscription.effective;
  const selectedClass = (classes ?? []).find((item) => item.id === selectedClassId) ?? null;
  const busy = pendingAction !== null;

  const activeStudents = (memberships ?? []).filter(
    (member) => member.state === "ACTIVE" && member.roles.includes("STUDENT"),
  );
  const activeTeachers = (memberships ?? []).filter(
    (member) => member.state === "ACTIVE" && member.roles.includes("TEACHER"),
  );
  const rosterStudentIds = new Set(
    (roster ?? []).filter((entry) => entry.state === "ACTIVE").map((entry) => entry.studentUserId),
  );
  const addableStudents = activeStudents.filter((member) => !rosterStudentIds.has(member.user.id));

  async function loadClasses() {
    setListError(null);
    try {
      const result = await apiRequest<unknown>(classesPath);
      if (result.kind !== "data") throw new Error("Phản hồi danh sách lớp không hợp lệ.");
      setClasses(parseClasses(result.data));
      setNextCursor(parsePage(result.data).nextCursor);
    } catch (error) {
      setClasses([]);
      setListError(teachingErrorMessage(error, "Không tải được danh sách lớp."));
    }
  }

  async function loadRoster(classId: string) {
    setRosterError(null);
    try {
      const result = await apiRequest<unknown>(
        `${classesPath}/${encodeURIComponent(classId)}/students`,
      );
      if (result.kind !== "data") throw new Error("Phản hồi danh sách học sinh của lớp không hợp lệ.");
      setRoster(parseRoster(result.data));
    } catch (error) {
      setRoster([]);
      setRosterError(teachingErrorMessage(error, "Không tải được danh sách học sinh của lớp."));
    }
  }

  function refresh() {
    setRefreshTick((tick) => tick + 1);
    void loadClasses();
    if (selectedClassId) void loadRoster(selectedClassId);
  }

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    apiRequest<unknown>(classesPath)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setClasses(parseClasses(result.data));
        setNextCursor(parsePage(result.data).nextCursor);
        setListError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setClasses([]);
        setListError(teachingErrorMessage(error, "Không tải được danh sách lớp."));
      });
    apiRequest<unknown>(`/api/workspaces/${wid}/memberships`)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setMemberships(parseMemberships(result.data));
      })
      .catch(() => {
        if (!ignore) setMemberships([]);
      });
    apiRequest<unknown>("/api/me/workspaces")
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        const found = parseWorkspaces(result.data).find((item) => item.id === workspaceId);
        setWorkspace(found ?? null);
      })
      .catch(() => {
        if (!ignore) setWorkspace(null);
      });
    apiRequest<unknown>("/api/subjects")
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setSubjects(parseSubjects(result.data));
      })
      .catch(() => {
        if (!ignore) setSubjects([]);
      });
    return () => {
      ignore = true;
    };
  }, [userId, workspaceId, classesPath, wid]);

  useEffect(() => {
    if (!selectedClassId) return;
    let ignore = false;
    apiRequest<unknown>(`${classesPath}/${encodeURIComponent(selectedClassId)}/students`)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setRoster(parseRoster(result.data));
        setRosterError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setRoster([]);
        setRosterError(teachingErrorMessage(error, "Không tải được danh sách học sinh của lớp."));
      });
    return () => {
      ignore = true;
    };
  }, [selectedClassId, classesPath]);

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const result = await apiRequest<unknown>(`/api/me/operations/${encodeURIComponent(operation.id)}`);
      const nextOperation = result.kind === "data" ? parseTrackedOperation(result.data) : null;
      if (!nextOperation) {
        throw new Error("Phản hồi trạng thái thao tác không hợp lệ.");
      }
      setOperation(nextOperation);
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
        refresh();
      }
    } catch (error) {
      setActionError(teachingErrorMessage(error, "Không kiểm tra được trạng thái thao tác."));
    } finally {
      setPendingAction(null);
    }
  }

  const mutate: TeachingMutate = async (action, signature, path, init) => {
    const key = attemptKey(attempts.current, signature);
    setPendingAction(action);
    setActionError(null);
    try {
      const data = await submitTeachingMutation(path, init, key);
      const nextOperation = parseTrackedOperation(data);
      if (nextOperation) {
        setOperation(nextOperation);
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
          refresh();
        }
      }
      return data;
    } catch (error) {
      setActionError(teachingErrorMessage(error));
      return null;
    } finally {
      setPendingAction(null);
    }
  };

  async function createClass(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = nameBody(createName);
    if (!body) {
      setCreateError("Hãy nhập tên lớp.");
      return;
    }
    setCreateError(null);
    const data = await mutate(
      "create-class",
      JSON.stringify([userId, workspaceId, "create-class", body]),
      classesPath,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (data !== null && operationSucceeded(data)) {
      setCreateName("");
    }
  }

  async function renameClass(item: TeachingClass) {
    const body = nameBody(renameDrafts[item.id] ?? item.name);
    if (!body) {
      setActionError("Tên lớp không được để trống.");
      return;
    }
    await mutate(
      `rename-class:${item.id}`,
      JSON.stringify([userId, workspaceId, "rename-class", item.id, body, item.version]),
      `${classesPath}/${encodeURIComponent(item.id)}`,
      {
        method: "PATCH",
        body: JSON.stringify(body),
        headers: { "If-Match": ifMatchValue(item.version) },
      },
    );
  }

  async function revokeClass(item: TeachingClass) {
    await mutate(
      `revoke-class:${item.id}`,
      JSON.stringify([userId, workspaceId, "revoke-class", item.id, item.version]),
      `${classesPath}/${encodeURIComponent(item.id)}`,
      { method: "DELETE" },
    );
  }

  async function addStudents(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedClass) return;
    if (rosterDraft.length === 0) {
      setRosterFormError("Hãy chọn ít nhất một học sinh để thêm vào lớp.");
      return;
    }
    setRosterFormError(null);
    const body = { studentUserIds: [...rosterDraft] };
    const data = await mutate(
      "add-students",
      JSON.stringify([userId, workspaceId, "add-students", selectedClass.id, body]),
      `${classesPath}/${encodeURIComponent(selectedClass.id)}/students`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (data !== null && operationSucceeded(data)) {
      setRosterDraft([]);
    }
  }

  async function revokeStudent(entry: RosterEntry) {
    if (!selectedClass) return;
    await mutate(
      `revoke-student:${entry.id}`,
      JSON.stringify([userId, workspaceId, "revoke-student", selectedClass.id, entry.id, entry.version]),
      `${classesPath}/${encodeURIComponent(selectedClass.id)}/students/${encodeURIComponent(entry.id)}`,
      { method: "DELETE" },
    );
  }

  async function assignHomeroom(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedClass) return;
    if (!homeroomTeacher) {
      setRosterFormError("Hãy chọn giáo viên làm chủ nhiệm.");
      return;
    }
    setRosterFormError(null);
    const body = { teacherUserId: homeroomTeacher };
    await mutate(
      "assign-homeroom",
      JSON.stringify([userId, workspaceId, "assign-homeroom", selectedClass.id, body]),
      `${classesPath}/${encodeURIComponent(selectedClass.id)}/homeroom-assignments`,
      { method: "POST", body: JSON.stringify(body) },
    );
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="classes-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="classes-title">Lớp, khóa và phân công</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
        >
          <p className="introduction">
            Trung tâm: {workspace ? <strong>{workspace.name}</strong> : <code>{workspaceId}</code>}
            {selectedClass ? (
              <>
                {" "}› Lớp: <strong>{selectedClass.name}</strong>
              </>
            ) : null}
          </p>
          {subscriptionExpired ? (
            <p role="alert">
              Gói của trung tâm đã hết hạn: không tạo mới hay thêm quan hệ được;
              việc thu hồi vẫn thực hiện theo thẩm quyền.
            </p>
          ) : null}
          {actionError ? <p role="alert">{actionError}</p> : null}
          {operation ? (
            <OperationPanel
              operation={operation}
              checking={pendingAction === "operation"}
              onCheck={() => void checkOperation()}
            />
          ) : null}

          <section aria-labelledby="class-list-title">
            <h2 id="class-list-title">Danh sách lớp</h2>
            {classes === null ? <p role="status">Đang tải danh sách lớp…</p> : null}
            {listError ? <p role="alert">{listError}</p> : null}
            {classes !== null && classes.length === 0 && !listError ? (
              <p>Chưa có lớp nào trong phạm vi xem của bạn.</p>
            ) : null}
            {classes !== null && classes.length > 0 ? (
              <ul>
                {classes.map((item) => (
                  <li key={item.id}>
                    <button
                      className="secondary-button"
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        setSelectedClassId(item.id);
                        setRoster(null);
                        setRosterError(null);
                      }}
                    >
                      {item.name}
                    </button>
                    {" "}({stateLabel(item.state)})
                    {canManage && item.state === "ACTIVE" ? (
                      <div className="section">
                        <div className="field">
                          <label htmlFor={`rename-class-${item.id}`}>Tên lớp</label>
                          <input
                            id={`rename-class-${item.id}`}
                            value={renameDrafts[item.id] ?? item.name}
                            disabled={busy}
                            onChange={(event) => setRenameDrafts((current) => ({
                              ...current,
                              [item.id]: event.target.value,
                            }))}
                          />
                        </div>
                        <button
                          className="action-button"
                          type="button"
                          disabled={busy}
                          onClick={() => void renameClass(item)}
                        >
                          Đổi tên lớp
                        </button>
                        {" "}
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={busy}
                          onClick={() => void revokeClass(item)}
                        >
                          Thu hồi lớp
                        </button>
                        <p>
                          Đổi tên gửi kèm phiên bản hiện tại của lớp; nếu dữ liệu đã đổi
                          ở nơi khác, hệ thống từ chối và cần tải lại.
                        </p>
                      </div>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : null}
            {nextCursor ? (
              <button
                className="secondary-button"
                type="button"
                disabled={busy}
                onClick={() => {
                  void (async () => {
                    try {
                      const result = await apiRequest<unknown>(
                        `${classesPath}?cursor=${encodeURIComponent(nextCursor)}`,
                      );
                      if (result.kind !== "data") throw new Error("Phản hồi danh sách lớp không hợp lệ.");
                      setClasses((current) => [...(current ?? []), ...parseClasses(result.data)]);
                      setNextCursor(parsePage(result.data).nextCursor);
                    } catch (error) {
                      setListError(teachingErrorMessage(error, "Không tải thêm được danh sách lớp."));
                    }
                  })();
                }}
              >
                Tải thêm lớp
              </button>
            ) : null}
          </section>

          {canManage ? (
            <section className="section" aria-labelledby="create-class-title">
              <h2 id="create-class-title">Tạo lớp mới</h2>
              <form onSubmit={createClass}>
                <div className="field">
                  <label htmlFor="class-name">Tên lớp</label>
                  <input
                    id="class-name"
                    value={createName}
                    disabled={busy || subscriptionExpired}
                    onChange={(event) => setCreateName(event.target.value)}
                  />
                </div>
                {createError ? <p role="alert">{createError}</p> : null}
                <button
                  className="action-button"
                  type="submit"
                  disabled={busy || subscriptionExpired}
                >
                  Tạo lớp
                </button>
              </form>
            </section>
          ) : null}

          {selectedClass ? (
            <section className="section" aria-labelledby="class-detail-title">
              <h2 id="class-detail-title">Lớp {selectedClass.name}</h2>

              <section aria-labelledby="roster-title">
                <h3 id="roster-title">Học sinh của lớp</h3>
                {roster === null ? <p role="status">Đang tải danh sách học sinh…</p> : null}
                {rosterError ? <p role="alert">{rosterError}</p> : null}
                {roster !== null && roster.length === 0 && !rosterError ? (
                  <p>Lớp chưa có học sinh nào trong phạm vi xem của bạn.</p>
                ) : null}
                {roster !== null && roster.length > 0 ? (
                  <ul>
                    {roster.map((entry) => (
                      <li key={entry.id}>
                        {entry.username} ({stateLabel(entry.state)})
                        {canManage && entry.state === "ACTIVE" && selectedClass.state === "ACTIVE" ? (
                          <>
                            {" "}
                            <button
                              className="secondary-button"
                              type="button"
                              disabled={busy}
                              onClick={() => void revokeStudent(entry)}
                            >
                              Thu hồi khỏi lớp
                            </button>
                          </>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                ) : null}

                {canManage && selectedClass.state === "ACTIVE" ? (
                  <>
                    <form onSubmit={addStudents}>
                      <fieldset>
                        <legend>Thêm học sinh của trung tâm vào lớp</legend>
                        {addableStudents.length === 0 ? (
                          <p>Không còn học sinh đang hoạt động nào ngoài lớp này.</p>
                        ) : null}
                        {addableStudents.map((member) => (
                          <div key={member.user.id}>
                            <label>
                              <input
                                type="checkbox"
                                checked={rosterDraft.includes(member.user.id)}
                                disabled={busy || subscriptionExpired}
                                onChange={(event) => setRosterDraft((current) =>
                                  toggleSelection(current, member.user.id, event.target.checked))}
                              />
                              {member.user.displayName ?? member.user.username}
                            </label>
                          </div>
                        ))}
                      </fieldset>
                      {rosterFormError ? <p role="alert">{rosterFormError}</p> : null}
                      <button
                        className="action-button"
                        type="submit"
                        disabled={busy || subscriptionExpired || addableStudents.length === 0}
                      >
                        Thêm học sinh đã chọn
                      </button>
                    </form>

                    <form className="section" onSubmit={assignHomeroom}>
                      <h3>Chủ nhiệm lớp</h3>
                      <div className="field">
                        <label htmlFor="homeroom-teacher">Giáo viên chủ nhiệm</label>
                        <select
                          id="homeroom-teacher"
                          value={homeroomTeacher}
                          disabled={busy || subscriptionExpired}
                          onChange={(event) => setHomeroomTeacher(event.target.value)}
                        >
                          <option value="">Chọn giáo viên</option>
                          {activeTeachers.map((member) => (
                            <option key={member.user.id} value={member.user.id}>
                              {member.user.displayName ?? member.user.username}
                            </option>
                          ))}
                        </select>
                      </div>
                      <button
                        className="action-button"
                        type="submit"
                        disabled={busy || subscriptionExpired || activeTeachers.length === 0}
                      >
                        Phân công chủ nhiệm
                      </button>
                    </form>
                  </>
                ) : null}
              </section>

              <HomeroomAssignmentList
                workspaceId={workspaceId}
                classId={selectedClass.id}
                teachers={memberships ?? []}
                canManage={canManage}
                busy={busy}
                refreshTick={refreshTick}
                userId={userId}
                mutate={mutate}
              />

              <CourseSubjectEditor
                workspaceId={workspaceId}
                classId={selectedClass.id}
                subjects={subjects}
                teachers={memberships ?? []}
                roster={roster ?? []}
                userId={userId}
                canManage={canManage}
                subscriptionExpired={subscriptionExpired}
                busy={busy}
                refreshTick={refreshTick}
                mutate={mutate}
              />
            </section>
          ) : null}
        </SessionGate>
      </section>
    </main>
  );
}
