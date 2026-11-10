"use client";

import { useCallback, useEffect, useState } from "react";
import { isRecord, requestAllPages, requestWhenReady } from "../../lib/api-client";
import type { Membership } from "../tenancy/center-administration";
import {
  ifMatchValue,
  nameBody,
  operationSucceeded,
  parsePage,
  teachingErrorMessage,
  toggleSelection,
  type RosterEntry,
  type Subject,
  type TeachingMutate,
} from "./class-management";
import { EnrollmentEditor } from "./enrollment-editor";

export type CourseSubjectEntry = {
  id: string;
  subjectId: string;
  name: string;
};

export type Course = {
  id: string;
  classId: string;
  name: string;
  subjects: CourseSubjectEntry[];
  version: number;
};

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

// Khóa chỉ đáng tin khi đúng CourseView: có classId, version và mọi môn đúng SubjectEntry.
export function parseCourses(data: unknown): Course[] {
  const courses: Course[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.classId !== "string" || !item.classId
        || typeof item.name !== "string" || !item.name
        || !Array.isArray(item.subjects)
        || !isVersion(item.version)) {
      continue;
    }
    const subjects: CourseSubjectEntry[] = [];
    let valid = true;
    for (const raw of item.subjects) {
      if (!isRecord(raw)
          || typeof raw.id !== "string" || !raw.id
          || typeof raw.subjectId !== "string" || !raw.subjectId
          || typeof raw.name !== "string" || !raw.name) {
        valid = false;
        break;
      }
      subjects.push({ id: raw.id, subjectId: raw.subjectId, name: raw.name });
    }
    if (!valid) continue;
    courses.push({
      id: item.id,
      classId: item.classId,
      name: item.name,
      subjects,
      version: item.version,
    });
  }
  return courses;
}

// Tạo khóa buộc ít nhất một môn hiện có được chọn; backend từ chối danh sách môn rỗng.
export function courseCreateBody(
  name: string,
  subjectIds: readonly string[],
): { name: string; subjectIds: string[] } | null {
  const base = nameBody(name);
  if (!base || subjectIds.length === 0) return null;
  if (new Set(subjectIds).size !== subjectIds.length) return null;
  return { name: base.name, subjectIds: [...subjectIds] };
}

// Bổ sung môn cho khóa cũng không nhận danh sách rỗng hoặc trùng.
export function subjectAddBody(
  subjectIds: readonly string[],
): { subjectIds: string[] } | null {
  if (subjectIds.length === 0) return null;
  if (new Set(subjectIds).size !== subjectIds.length) return null;
  return { subjectIds: [...subjectIds] };
}

export type TeacherAssignment = {
  id: string;
  courseSubjectId: string;
  teacherUserId: string;
  state: string;
  version: number;
};

// Chỉ giữ phân công giáo viên đúng TeachingAssignmentView của backend; backend chỉ
// trả phân công hiện hành, kèm id dòng để route thu hồi dùng.
export function parseTeacherAssignments(data: unknown): TeacherAssignment[] {
  const assignments: TeacherAssignment[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.courseSubjectId !== "string" || !item.courseSubjectId
        || typeof item.teacherUserId !== "string" || !item.teacherUserId
        || typeof item.state !== "string" || !item.state
        || !isVersion(item.version)) {
      continue;
    }
    assignments.push({
      id: item.id,
      courseSubjectId: item.courseSubjectId,
      teacherUserId: item.teacherUserId,
      state: item.state,
      version: item.version,
    });
  }
  return assignments;
}

// Phân công giáo viên theo hợp tập: lô rỗng không có ý nghĩa gửi đi.
export function teacherAssignBody(
  teacherUserIds: readonly string[],
): { teacherUserIds: string[] } | null {
  if (teacherUserIds.length === 0) return null;
  if (new Set(teacherUserIds).size !== teacherUserIds.length) return null;
  return { teacherUserIds: [...teacherUserIds] };
}

function TeacherAssignmentList({
  workspaceId,
  courseSubjectId,
  teachers,
  canManage,
  busy,
  refreshTick,
  userId,
  mutate,
}: {
  workspaceId: string;
  courseSubjectId: string;
  teachers: Membership[];
  canManage: boolean;
  busy: boolean;
  refreshTick: number;
  userId: string | null;
  mutate: TeachingMutate;
}) {
  const [assignments, setAssignments] = useState<TeacherAssignment[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const wid = encodeURIComponent(workspaceId);
  const assignmentsPath = `/api/workspaces/${wid}/course-subjects/${encodeURIComponent(courseSubjectId)}/teachers`;
  const nameOf = new Map(
    teachers.map((member) => [member.user.id, member.user.displayName ?? member.user.username]),
  );

  const loadAssignments = useCallback(async (): Promise<TeacherAssignment[]> => {
    return parseTeacherAssignments(await requestAllPages(assignmentsPath));
  }, [assignmentsPath]);

  useEffect(() => {
    let ignore = false;
    loadAssignments()
      .then((items) => {
        if (ignore) return;
        setAssignments(items);
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setAssignments([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách phân công."));
      });
    return () => {
      ignore = true;
    };
    // refreshTick tăng sau mỗi thao tác hoàn tất ở trang cha để nạp lại đúng môn–khóa đang mở.
  }, [loadAssignments, refreshTick]);

  async function revokeAssignment(item: TeacherAssignment) {
    const data = await mutate(
      `revoke-teacher:${item.id}`,
      JSON.stringify([userId, workspaceId, "revoke-teacher", courseSubjectId, item.id, item.version]),
      `${assignmentsPath}/${encodeURIComponent(item.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) {
      try {
        setAssignments(await loadAssignments());
        setLoadError(null);
      } catch (error) {
        setAssignments([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách phân công."));
      }
    }
  }

  return (
    <div className="section">
      <h5>Giáo viên đang phân công cho môn này</h5>
      {assignments === null ? <p role="status">Đang tải danh sách phân công…</p> : null}
      {loadError ? <p role="alert">{loadError}</p> : null}
      {assignments !== null && assignments.length === 0 && !loadError ? (
        <p>Chưa có giáo viên nào đang phân công môn này.</p>
      ) : null}
      {assignments !== null && assignments.length > 0 ? (
        <ul>
          {assignments.map((item) => (
            <li key={item.id}>
              {nameOf.get(item.teacherUserId) ?? item.teacherUserId}
              {canManage && item.state !== "REVOKED" ? (
                <>
                  {" "}
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={() => void revokeAssignment(item)}
                  >
                    Thu hồi phân công
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export function CourseSubjectEditor({
  workspaceId,
  classId,
  subjects,
  teachers,
  roster,
  userId,
  canManage,
  subscriptionExpired,
  busy,
  refreshTick,
  mutate,
  // Không gian cá nhân không có phân công giáo viên theo center: owner tự dạy lớp
  // của mình, nên ẩn hẳn khối phân công thay vì hiện nhãn center sai chỗ.
  showTeacherAssignment = true,
}: {
  workspaceId: string;
  classId: string;
  subjects: Subject[];
  teachers: Membership[];
  roster: RosterEntry[];
  userId: string | null;
  canManage: boolean;
  subscriptionExpired: boolean;
  busy: boolean;
  refreshTick: number;
  mutate: TeachingMutate;
  showTeacherAssignment?: boolean;
}) {
  const [courses, setCourses] = useState<Course[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [createName, setCreateName] = useState("");
  const [createSubjects, setCreateSubjects] = useState<string[]>([]);
  const [createError, setCreateError] = useState<string | null>(null);
  const [renameDrafts, setRenameDrafts] = useState<Record<string, string>>({});
  const [addDrafts, setAddDrafts] = useState<Record<string, string[]>>({});
  const [teacherDrafts, setTeacherDrafts] = useState<Record<string, string[]>>({});
  const [assignError, setAssignError] = useState<string | null>(null);
  const [openSubjectId, setOpenSubjectId] = useState<string | null>(null);

  const wid = encodeURIComponent(workspaceId);
  const coursesPath = `/api/workspaces/${wid}/classes/${encodeURIComponent(classId)}/courses`;
  const activeTeachers = teachers.filter(
    (member) => member.state === "ACTIVE" && member.roles.includes("TEACHER"),
  );

  async function loadCourses() {
    setLoadError(null);
    try {
      const result = await requestWhenReady<unknown>(coursesPath);
      if (result.kind !== "data") throw new Error("Phản hồi danh sách khóa không hợp lệ.");
      setCourses(parseCourses(result.data));
      setNextCursor(parsePage(result.data).nextCursor);
    } catch (error) {
      setCourses([]);
      setLoadError(teachingErrorMessage(error, "Không tải được danh sách khóa."));
    }
  }

  useEffect(() => {
    let ignore = false;
    requestWhenReady<unknown>(coursesPath)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setCourses(parseCourses(result.data));
        setNextCursor(parsePage(result.data).nextCursor);
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setCourses([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách khóa."));
      });
    return () => {
      ignore = true;
    };
    // refreshTick tăng sau mỗi thao tác hoàn tất ở trang cha để nạp lại danh sách khóa.
  }, [coursesPath, refreshTick]);

  async function loadMoreCourses() {
    if (!nextCursor) return;
    try {
      const result = await requestWhenReady<unknown>(
        `${coursesPath}?cursor=${encodeURIComponent(nextCursor)}`,
      );
      if (result.kind !== "data") throw new Error("Phản hồi danh sách khóa không hợp lệ.");
      setCourses((current) => [...(current ?? []), ...parseCourses(result.data)]);
      setNextCursor(parsePage(result.data).nextCursor);
    } catch (error) {
      setLoadError(teachingErrorMessage(error, "Không tải thêm được danh sách khóa."));
    }
  }

  async function createCourse(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = courseCreateBody(createName, createSubjects);
    if (!body) {
      setCreateError("Hãy nhập tên khóa và chọn ít nhất một môn hiện có.");
      return;
    }
    setCreateError(null);
    const data = await mutate(
      "create-course",
      JSON.stringify([userId, workspaceId, classId, "create-course", body]),
      coursesPath,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (operationSucceeded(data)) {
      setCreateName("");
      setCreateSubjects([]);
      await loadCourses();
    }
  }

  async function renameCourse(course: Course) {
    const body = nameBody(renameDrafts[course.id] ?? course.name);
    if (!body) {
      setAssignError("Tên khóa không được để trống.");
      return;
    }
    const data = await mutate(
      `rename-course:${course.id}`,
      JSON.stringify([userId, workspaceId, "rename-course", course.id, body, course.version]),
      `/api/workspaces/${wid}/courses/${encodeURIComponent(course.id)}`,
      {
        method: "PATCH",
        body: JSON.stringify(body),
        headers: { "If-Match": ifMatchValue(course.version) },
      },
    );
    if (operationSucceeded(data)) {
      await loadCourses();
    }
  }

  async function revokeCourse(course: Course) {
    const data = await mutate(
      `revoke-course:${course.id}`,
      JSON.stringify([userId, workspaceId, "revoke-course", course.id, course.version]),
      `/api/workspaces/${wid}/courses/${encodeURIComponent(course.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) {
      await loadCourses();
    }
  }

  async function addSubjects(course: Course) {
    const body = subjectAddBody(addDrafts[course.id] ?? []);
    if (!body) {
      setAssignError("Hãy chọn ít nhất một môn để thêm vào khóa.");
      return;
    }
    const data = await mutate(
      `add-subjects:${course.id}`,
      JSON.stringify([userId, workspaceId, "add-subjects", course.id, body]),
      `/api/workspaces/${wid}/courses/${encodeURIComponent(course.id)}/subjects`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (operationSucceeded(data)) {
      setAddDrafts((current) => ({ ...current, [course.id]: [] }));
      await loadCourses();
    }
  }

  async function revokeSubject(course: Course, entry: CourseSubjectEntry) {
    const data = await mutate(
      `revoke-subject:${entry.id}`,
      JSON.stringify([userId, workspaceId, "revoke-subject", course.id, entry.id]),
      `/api/workspaces/${wid}/courses/${encodeURIComponent(course.id)}/subjects/${encodeURIComponent(entry.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) {
      if (openSubjectId === entry.id) setOpenSubjectId(null);
      await loadCourses();
    }
  }

  async function assignTeachers(entry: CourseSubjectEntry) {
    const body = teacherAssignBody(teacherDrafts[entry.id] ?? []);
    if (!body) {
      setAssignError("Hãy chọn ít nhất một giáo viên để phân công.");
      return;
    }
    setAssignError(null);
    const data = await mutate(
      `assign-teachers:${entry.id}`,
      JSON.stringify([userId, workspaceId, "assign-teachers", entry.id, body]),
      `/api/workspaces/${wid}/course-subjects/${encodeURIComponent(entry.id)}/teachers`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (operationSucceeded(data)) {
      setTeacherDrafts((current) => ({ ...current, [entry.id]: [] }));
    }
  }

  return (
    <section aria-labelledby="courses-title">
      <h3 id="courses-title">Khóa và môn của lớp</h3>
      {courses === null ? <p role="status">Đang tải danh sách khóa…</p> : null}
      {loadError ? <p role="alert">{loadError}</p> : null}
      {courses !== null && courses.length === 0 && !loadError ? (
        <p>Lớp này chưa có khóa nào trong phạm vi xem của bạn.</p>
      ) : null}
      {assignError ? <p role="alert">{assignError}</p> : null}

      {(courses ?? []).map((course) => {
        const availableSubjects = subjects.filter(
          (subject) => !course.subjects.some((entry) => entry.subjectId === subject.id),
        );
        return (
          <div className="section" key={course.id}>
            <h4>Khóa: {course.name}</h4>
            {canManage ? (
              <div>
                <div className="field">
                  <label htmlFor={`rename-course-${course.id}`}>Tên khóa</label>
                  <input
                    id={`rename-course-${course.id}`}
                    value={renameDrafts[course.id] ?? course.name}
                    disabled={busy}
                    onChange={(event) => setRenameDrafts((current) => ({
                      ...current,
                      [course.id]: event.target.value,
                    }))}
                  />
                </div>
                <button
                  className="action-button"
                  type="button"
                  disabled={busy}
                  onClick={() => void renameCourse(course)}
                >
                  Đổi tên khóa
                </button>
                {" "}
                <button
                  className="secondary-button"
                  type="button"
                  disabled={busy}
                  onClick={() => void revokeCourse(course)}
                >
                  Thu hồi khóa
                </button>
                <p>
                  Đổi tên gửi kèm phiên bản hiện tại của khóa; nếu dữ liệu đã đổi ở nơi
                  khác, hệ thống từ chối và cần tải lại.
                </p>
              </div>
            ) : null}

            <h5>Môn trong khóa</h5>
            {course.subjects.length === 0 ? <p>Khóa chưa có môn hiện hành.</p> : null}
            <ul>
              {course.subjects.map((entry) => (
                <li key={entry.id}>
                  {entry.name}
                  {" "}
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={() => setOpenSubjectId(openSubjectId === entry.id ? null : entry.id)}
                  >
                    {openSubjectId === entry.id
                      ? "Đóng ghi danh" + (showTeacherAssignment ? "/phân công" : "")
                      : "Ghi danh" + (showTeacherAssignment ? " và phân công" : "")}
                  </button>
                  {canManage ? (
                    <>
                      {" "}
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() => void revokeSubject(course, entry)}
                      >
                        Gỡ môn khỏi khóa
                      </button>
                    </>
                  ) : null}
                  {openSubjectId === entry.id ? (
                    <>
                      {canManage && showTeacherAssignment ? (
                        <div className="section">
                          <h5>Phân công giáo viên cho môn {entry.name}</h5>
                          <fieldset>
                            <legend>Giáo viên của trung tâm (chọn nhiều)</legend>
                            {activeTeachers.length === 0 ? (
                              <p>Trung tâm chưa có giáo viên đang hoạt động.</p>
                            ) : null}
                            {activeTeachers.map((teacher) => (
                              <div key={teacher.user.id}>
                                <label>
                                  <input
                                    type="checkbox"
                                    checked={(teacherDrafts[entry.id] ?? []).includes(teacher.user.id)}
                                    disabled={busy || subscriptionExpired}
                                    onChange={(event) => setTeacherDrafts((current) => ({
                                      ...current,
                                      [entry.id]: toggleSelection(
                                        current[entry.id] ?? [],
                                        teacher.user.id,
                                        event.target.checked,
                                      ),
                                    }))}
                                  />
                                  {teacher.user.displayName ?? teacher.user.username}
                                </label>
                              </div>
                            ))}
                          </fieldset>
                          <button
                            className="action-button"
                            type="button"
                            disabled={busy || subscriptionExpired}
                            onClick={() => void assignTeachers(entry)}
                          >
                            Phân công giáo viên đã chọn
                          </button>
                          <p>
                            Phân công theo hợp tập: giáo viên đã phân công trước đó được giữ
                            nguyên. Để dừng phân công của một giáo viên, thu hồi đúng phân
                            công hiện hành của giáo viên đó trong danh sách bên dưới — chỉ
                            dừng phân công, không xoá giáo viên khỏi trung tâm.
                          </p>
                        </div>
                      ) : null}
                      {showTeacherAssignment ? (
                        <TeacherAssignmentList
                          workspaceId={workspaceId}
                          courseSubjectId={entry.id}
                          teachers={teachers}
                          canManage={canManage}
                          busy={busy}
                          refreshTick={refreshTick}
                          userId={userId}
                          mutate={mutate}
                        />
                      ) : null}
                      <EnrollmentEditor
                        workspaceId={workspaceId}
                        classId={classId}
                        courseSubjects={course.subjects}
                        focusCourseSubjectId={entry.id}
                        roster={roster}
                        userId={userId}
                        canManage={canManage}
                        subscriptionExpired={subscriptionExpired}
                        busy={busy}
                        refreshTick={refreshTick}
                        mutate={mutate}
                      />
                    </>
                  ) : null}
                </li>
              ))}
            </ul>

            {canManage ? (
              <form onSubmit={(event) => {
                event.preventDefault();
                void addSubjects(course);
              }}>
                <fieldset>
                  <legend>Thêm môn hiện có vào khóa {course.name}</legend>
                  {availableSubjects.length === 0 ? (
                    <p>Không còn môn nào ngoài các môn đã có trong khóa.</p>
                  ) : null}
                  {availableSubjects.map((subject) => (
                    <div key={subject.id}>
                      <label>
                        <input
                          type="checkbox"
                          checked={(addDrafts[course.id] ?? []).includes(subject.id)}
                          disabled={busy || subscriptionExpired}
                          onChange={(event) => setAddDrafts((current) => ({
                            ...current,
                            [course.id]: toggleSelection(
                              current[course.id] ?? [],
                              subject.id,
                              event.target.checked,
                            ),
                          }))}
                        />
                        {subject.name} ({subject.code})
                      </label>
                    </div>
                  ))}
                </fieldset>
                <button
                  className="action-button"
                  type="submit"
                  disabled={busy || subscriptionExpired || availableSubjects.length === 0}
                >
                  Thêm môn đã chọn
                </button>
                <p>Thêm môn không tự ghi danh học sinh và không tự phân công giáo viên.</p>
              </form>
            ) : null}
          </div>
        );
      })}

      {nextCursor ? (
        <button
          className="secondary-button"
          type="button"
          disabled={busy}
          onClick={() => void loadMoreCourses()}
        >
          Tải thêm khóa
        </button>
      ) : null}

      {canManage ? (
        <form className="section" onSubmit={createCourse}>
          <h4>Tạo khóa mới</h4>
          <div className="field">
            <label htmlFor="course-name">Tên khóa</label>
            <input
              id="course-name"
              value={createName}
              disabled={busy || subscriptionExpired}
              onChange={(event) => setCreateName(event.target.value)}
            />
          </div>
          <fieldset>
            <legend>Chọn các môn hiện có của khóa</legend>
            {subjects.length === 0 ? <p>Danh mục môn đang trống.</p> : null}
            {subjects.map((subject) => (
              <div key={subject.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={createSubjects.includes(subject.id)}
                    disabled={busy || subscriptionExpired}
                    onChange={(event) => setCreateSubjects((current) =>
                      toggleSelection(current, subject.id, event.target.checked))}
                  />
                  {subject.name} ({subject.code})
                </label>
              </div>
            ))}
          </fieldset>
          {createError ? <p role="alert">{createError}</p> : null}
          <button
            className="action-button"
            type="submit"
            disabled={busy || subscriptionExpired}
          >
            Tạo khóa
          </button>
        </form>
      ) : null}
    </section>
  );
}
