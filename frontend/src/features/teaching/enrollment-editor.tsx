"use client";

import { useEffect, useState } from "react";
import { isRecord, requestAllPages } from "@/lib/api-client";
import {
  operationSucceeded,
  parsePage,
  teachingErrorMessage,
  toggleSelection,
  type RosterEntry,
  type TeachingMutate,
} from "./class-management";
import type { CourseSubjectEntry } from "./course-subject-editor";

export type Enrollment = {
  id: string;
  classStudentId: string;
  courseSubjectId: string;
  studentUserId: string;
  state: string;
  version: number;
};

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

// Chỉ giữ dòng ghi danh đúng EnrollmentView của backend; bản ghi lệch bị bỏ thay vì hiển thị sai.
export function parseEnrollments(data: unknown): Enrollment[] {
  const enrollments: Enrollment[] = [];
  for (const item of parsePage(data).items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || typeof item.classStudentId !== "string" || !item.classStudentId
        || typeof item.courseSubjectId !== "string" || !item.courseSubjectId
        || typeof item.studentUserId !== "string" || !item.studentUserId
        || typeof item.state !== "string" || !item.state
        || !isVersion(item.version)) {
      continue;
    }
    enrollments.push({
      id: item.id,
      classStudentId: item.classStudentId,
      courseSubjectId: item.courseSubjectId,
      studentUserId: item.studentUserId,
      state: item.state,
      version: item.version,
    });
  }
  return enrollments;
}

// Lô ghi danh là tích của các dòng roster đã chọn với các môn–khóa đã chọn; hai phía đều
// không được rỗng, phía backend kiểm tra tiếp mọi cặp phải cùng một lớp.
export function enrollmentBatchBody(
  classStudentIds: readonly string[],
  courseSubjectIds: readonly string[],
): { classStudentIds: string[]; courseSubjectIds: string[] } | null {
  if (classStudentIds.length === 0 || courseSubjectIds.length === 0) return null;
  if (new Set(classStudentIds).size !== classStudentIds.length) return null;
  if (new Set(courseSubjectIds).size !== courseSubjectIds.length) return null;
  return {
    classStudentIds: [...classStudentIds],
    courseSubjectIds: [...courseSubjectIds],
  };
}

export type CompletionBody = { enrollments: { id: string; expectedVersion: number }[] };

// Chỉ tạo snapshot khi đã nhận đủ enrollment của TẤT CẢ các môn trong khóa.
// Backend so khớp tập này trong transaction workspace, không tin client đã đủ.
export function courseCompletionBody(
  items: readonly Enrollment[], studentUserId: string,
): CompletionBody | null {
  const relevant = items.filter((item) => item.studentUserId === studentUserId
    && (item.state === "ACTIVE" || item.state === "PENDING"));
  if (!relevant.length || relevant.some((item) => item.state !== "ACTIVE")) return null;
  const ids = new Set<string>();
  for (const item of relevant) {
    if (!item.id || ids.has(item.id) || !isVersion(item.version)) return null;
    ids.add(item.id);
  }
  return { enrollments: relevant
    .map((item) => ({ id: item.id, expectedVersion: item.version }))
    .sort((a, b) => a.id.localeCompare(b.id)) };
}

export function EnrollmentEditor({
  workspaceId,
  classId,
  courseId,
  courseSubjects,
  focusCourseSubjectId,
  roster,
  userId,
  canManage,
  subscriptionExpired,
  busy,
  refreshTick,
  mutate,
}: {
  workspaceId: string;
  classId: string;
  courseId: string;
  courseSubjects: CourseSubjectEntry[];
  focusCourseSubjectId: string;
  roster: RosterEntry[];
  userId: string | null;
  canManage: boolean;
  subscriptionExpired: boolean;
  busy: boolean;
  refreshTick: number;
  mutate: TeachingMutate;
}) {
  const [enrollments, setEnrollments] = useState<Enrollment[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedStudents, setSelectedStudents] = useState<string[]>([]);
  const [selectedSubjects, setSelectedSubjects] = useState<string[]>([focusCourseSubjectId]);
  const [formError, setFormError] = useState<string | null>(null);

  const wid = encodeURIComponent(workspaceId);
  const enrollmentsPath = `/api/workspaces/${wid}/course-subjects/${encodeURIComponent(focusCourseSubjectId)}/enrollments`;
  const activeRoster = roster.filter((entry) => entry.state === "ACTIVE");
  const usernameOf = new Map(roster.map((entry) => [entry.id, entry.username]));
  const currentEnrollments = (enrollments ?? []).filter(
    (item) => item.courseSubjectId === focusCourseSubjectId && item.state === "ACTIVE",
  );
  const enrolledStudentIds = new Set(currentEnrollments.map((item) => item.classStudentId));
  const availableRoster = activeRoster.filter((entry) => !enrolledStudentIds.has(entry.id));
  const subjectName = courseSubjects.find((item) => item.id === focusCourseSubjectId)?.name ?? "";

  async function loadEnrollments() {
    setLoadError(null);
    try {
      setEnrollments(parseEnrollments(await requestAllPages(enrollmentsPath)));
    } catch (error) {
      setEnrollments([]);
      setLoadError(teachingErrorMessage(error, "Không tải được danh sách ghi danh."));
    }
  }

  useEffect(() => {
    let ignore = false;
    requestAllPages(enrollmentsPath)
      .then((items) => {
        if (ignore) return;
        setEnrollments(parseEnrollments(items));
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setEnrollments([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách ghi danh."));
      });
    return () => {
      ignore = true;
    };
    // refreshTick tăng sau mỗi thao tác hoàn tất ở trang cha để nạp lại đúng môn–khóa đang mở.
  }, [enrollmentsPath, refreshTick]);

  // Component chỉ được dựng khi một môn–khóa đang mở và focus không đổi trong vòng
  // đời của nó, nên chọn sẵn môn đích là chính môn đang mở ngay từ state ban đầu.

  async function enroll(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = enrollmentBatchBody(selectedStudents, selectedSubjects);
    if (!body) {
      setFormError("Hãy chọn ít nhất một học sinh và một môn để ghi danh.");
      return;
    }
    setFormError(null);
    const data = await mutate(
      "enroll",
      JSON.stringify([userId, workspaceId, "enroll", body]),
      `/api/workspaces/${wid}/enrollments`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (operationSucceeded(data)) {
      setSelectedStudents([]);
      await loadEnrollments();
    }
  }

  async function completeCourse(studentUserId: string) {
    setFormError(null);
    try {
      const snapshots = await Promise.all(courseSubjects.map(async (subject) => {
        const path = `/api/workspaces/${wid}/course-subjects/${encodeURIComponent(subject.id)}/enrollments`;
        return parseEnrollments(await requestAllPages(path));
      }));
      const body = courseCompletionBody(snapshots.flat(), studentUserId);
      if (!body) {
        setFormError("Chưa có đủ ghi danh ACTIVE để hoàn thành. Hãy tải lại toàn khóa.");
        return;
      }
      const data = await mutate(
        `complete-course:${courseId}:${studentUserId}`,
        JSON.stringify([userId, workspaceId, courseId, studentUserId, body]),
        `/api/workspaces/${wid}/courses/${encodeURIComponent(courseId)}`
          + `/students/${encodeURIComponent(studentUserId)}/complete`,
        { method: "POST", body: JSON.stringify(body) },
      );
      if (operationSucceeded(data)) await loadEnrollments();
    } catch (error) {
      setFormError(teachingErrorMessage(error, "Không hoàn thành được khóa. Hãy tải lại."));
    }
  }

  async function revokeEnrollment(enrollment: Enrollment) {
    const data = await mutate(
      `revoke-enrollment:${enrollment.id}`,
      JSON.stringify([userId, workspaceId, "revoke-enrollment", enrollment.id, enrollment.version]),
      `/api/workspaces/${wid}/enrollments/${encodeURIComponent(enrollment.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) {
      await loadEnrollments();
    }
  }

  return (
    <div className="section">
      <h4>Ghi danh môn {subjectName}</h4>
      {enrollments === null ? <p role="status">Đang tải danh sách ghi danh…</p> : null}
      {loadError ? <p role="alert">{loadError}</p> : null}
      {enrollments !== null && currentEnrollments.length === 0 && !loadError ? (
        <p>Chưa có học sinh nào ghi danh môn này.</p>
      ) : null}
      {currentEnrollments.length > 0 ? (
        <ul>
          {currentEnrollments.map((enrollment) => (
            <li key={enrollment.id}>
              {usernameOf.get(enrollment.classStudentId) ?? enrollment.studentUserId}
              {canManage ? (
                <>
                  {" "}
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={() => void revokeEnrollment(enrollment)}
                  >
                    Thu hồi ghi danh
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {canManage && currentEnrollments.length > 0 ? (
        <div className="section">
          <h5>Hoàn thành khóa (mọi môn)</h5>
          <p>
            Hoàn thành sẽ đóng toàn bộ ghi danh ACTIVE của một học sinh trong khóa,
            không phải thu hồi. Học sinh giữ quyền xem kết quả đã công bố khi còn
            quyền tài khoản/quan hệ hợp lệ, nhưng không nộp bài mới.
          </p>
          {[...new Set(currentEnrollments.map((item) => item.studentUserId))].map((studentId) => (
            <button
              key={studentId}
              className="secondary-button"
              type="button"
              disabled={busy}
              onClick={() => void completeCourse(studentId)}
            >
              Hoàn thành khóa cho {usernameOf.get(currentEnrollments.find(
                (item) => item.studentUserId === studentId)?.classStudentId ?? "") ?? studentId}
            </button>
          ))}
        </div>
      ) : null}
      {canManage ? (
        <form onSubmit={enroll}>
          <fieldset>
            <legend>Học sinh trong lớp chưa ghi danh môn này</legend>
            {availableRoster.length === 0 ? (
              <p>Mọi học sinh hiện hành của lớp đã ghi danh môn này hoặc lớp chưa có học sinh.</p>
            ) : null}
            {availableRoster
              .map((entry) => (
                <div key={entry.id}>
                  <label>
                    <input
                      type="checkbox"
                      checked={selectedStudents.includes(entry.id)}
                      disabled={busy || subscriptionExpired}
                      onChange={(event) => setSelectedStudents((current) =>
                        toggleSelection(current, entry.id, event.target.checked))}
                    />
                    {entry.username}
                  </label>
                </div>
              ))}
          </fieldset>
          <fieldset>
            <legend>Môn đích của lô ghi danh</legend>
            {courseSubjects.map((entry) => (
              <div key={entry.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={selectedSubjects.includes(entry.id)}
                    disabled={busy || subscriptionExpired}
                    onChange={(event) => setSelectedSubjects((current) =>
                      toggleSelection(current, entry.id, event.target.checked))}
                  />
                  {entry.name}
                </label>
              </div>
            ))}
          </fieldset>
          <p>
            Mỗi học sinh được chọn sẽ ghi danh vào mọi môn đích được chọn; hệ thống kiểm tra
            toàn bộ lô cùng một lớp trước khi ghi.
          </p>
          {formError ? <p role="alert">{formError}</p> : null}
          <button className="action-button" type="submit" disabled={busy || subscriptionExpired}>
            Ghi danh theo lô
          </button>
        </form>
      ) : null}
      <p>
        Lớp đang xét: <code>{classId}</code>. Thêm môn vào khóa không tự ghi danh học sinh;
        ghi danh luôn là thao tác riêng tại đây.
      </p>
    </div>
  );
}
