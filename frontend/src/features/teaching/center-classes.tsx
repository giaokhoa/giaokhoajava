"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  ApiError,
  requestAllPages,
  requestWhenReady,
  requestWorkspaces,
} from "@/lib/api-client";
import { SessionGate } from "@/features/identity/session-gate";
import { useSession } from "@/features/identity/session-provider";
import {
  memberPermissions,
  parseMemberships,
  type Membership,
} from "@/lib/memberships";
import { attemptKey, submitMutation } from "@/features/operations/operation-attempts";
import { OperationPanel } from "@/features/operations/operation-panel";
import { useOperationCheck } from "@/lib/use-operation-check";
import {
  operationStatus,
  parseTrackedOperation,
  pollOperationUntilSettled,
  type TrackedOperation,
} from "@/lib/operation-status";
import { parseWorkspaces, type WorkspaceView } from "@/features/identity/workspace-switcher";
import { CourseSubjectEditor } from "./course-subject-editor";
import { HomeroomAssignmentList } from "./homeroom-assignment-list";
import {
  ifMatchValue,
  nameBody,
  operationSucceeded,
  parseClasses,
  parsePage,
  parseRoster,
  parseSubjects,
  stateLabel,
  teachingErrorMessage,
  toggleSelection,
  type RosterEntry,
  type Subject,
  type TeachingClass,
  type TeachingMutate,
} from "./teaching-helpers";

export function CenterClassesContent({ workspaceId }: { workspaceId: string }) {
  const { session } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  return <CenterClassesInner key={`${userId ?? "anonymous"}|${workspaceId}`} workspaceId={workspaceId} />;
}

function CenterClassesInner({ workspaceId }: { workspaceId: string }) {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [memberships, setMemberships] = useState<Membership[] | null>(null);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [classes, setClasses] = useState<TeachingClass[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMoreClasses, setLoadingMoreClasses] = useState(false);
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
  const [operationResult, setOperationResult] = useState<{
    userId: string | null; workspaceId: string; value: TrackedOperation;
  } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const attempts = useRef(new Map<string, string>());
  const pendingMutation = useRef<AbortController | null>(null);
  const userId = session.status === "authenticated" ? session.user.id : null;

  const wid = encodeURIComponent(workspaceId);
  const classesPath = `/api/workspaces/${wid}/classes`;
  const { canManage } = memberPermissions(memberships ?? [], userId);
  const subscriptionExpired = workspace?.kind === "CENTER" && !workspace.subscription.effective;
  const selectedClass = (classes ?? []).find((item) => item.id === selectedClassId) ?? null;
  const operation = operationResult?.userId === userId && operationResult.workspaceId === workspaceId
    ? operationResult.value : null;
  const busy = pendingAction !== null || (operation !== null && operationStatus(operation).kind === "pending");

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

  // Một phép tải duy nhất cho các tài nguyên của trang; từng nơi gọi (tải đầu,
  // làm mới, poll sau operation) giữ nguyên cách áp kết quả và thông điệp lỗi riêng.
  const loadCore = useCallback(async (signal?: AbortSignal) => {
    const [classesResult, membershipResult, workspaceResult] = await Promise.allSettled([
      requestWhenReady<unknown>(classesPath, { signal }),
      requestAllPages(`/api/workspaces/${wid}/memberships`, { signal }),
      requestWorkspaces(userId ?? "", signal),
    ]);
    return { classesResult, membershipResult, workspaceResult };
  }, [classesPath, userId, wid]);

  const loadAll = useCallback(async (signal?: AbortSignal) => {
    const rosterPromise = selectedClassId
      ? requestAllPages(
          `${classesPath}/${encodeURIComponent(selectedClassId)}/students`,
          { signal },
        ).then(
          (value): PromiseSettledResult<unknown[]> => ({ status: "fulfilled", value }),
          (reason): PromiseSettledResult<unknown[]> => ({ status: "rejected", reason }),
        )
      : Promise.resolve<PromiseSettledResult<null>>({ status: "fulfilled", value: null });
    const [core, rosterResult] = await Promise.all([loadCore(signal), rosterPromise]);
    return { ...core, rosterResult };
  }, [classesPath, loadCore, selectedClassId]);

  function refresh() {
    setRefreshTick((tick) => tick + 1);
    setListError(null);
    if (selectedClassId) setRosterError(null);
    void loadAll().then(({ classesResult, membershipResult, workspaceResult, rosterResult }) => {
      if (classesResult.status === "fulfilled" && classesResult.value.kind === "data") {
        setClasses(parseClasses(classesResult.value.data));
        setNextCursor(parsePage(classesResult.value.data).nextCursor);
      } else {
        setClasses([]);
        setListError(teachingErrorMessage(
          classesResult.status === "rejected"
            ? classesResult.reason
            : new Error("Phản hồi danh sách lớp không hợp lệ."),
          "Không tải được danh sách lớp.",
        ));
      }
      if (rosterResult.status === "fulfilled") {
        if (rosterResult.value !== null) setRoster(parseRoster(rosterResult.value));
      } else {
        setRoster([]);
        setRosterError(teachingErrorMessage(
          rosterResult.reason,
          "Không tải được danh sách học sinh của lớp.",
        ));
      }
      setMemberships(membershipResult.status === "fulfilled"
        ? parseMemberships(membershipResult.value) : []);
      setWorkspace(workspaceResult.status === "fulfilled" && workspaceResult.value.kind === "data"
        ? parseWorkspaces(workspaceResult.value.data).find((item) => item.id === workspaceId) ?? null
        : null);
    });
  }

  // Cờ đang bay của tải thêm: hai lượt cùng cursor chạy song song sẽ append trùng
  // một trang, nên lượt gọi sau bị bỏ qua tới khi lượt trước xong (kể cả khi lỗi).
  async function loadMoreClasses() {
    if (!nextCursor || loadingMoreClasses) return;
    setLoadingMoreClasses(true);
    try {
      const result = await requestWhenReady<unknown>(
        `${classesPath}?cursor=${encodeURIComponent(nextCursor)}`,
      );
      if (result.kind !== "data") throw new Error("Phản hồi danh sách lớp không hợp lệ.");
      setClasses((current) => [...(current ?? []), ...parseClasses(result.data)]);
      setNextCursor(parsePage(result.data).nextCursor);
    } catch (error) {
      setListError(teachingErrorMessage(error, "Không tải thêm được danh sách lớp."));
    } finally {
      setLoadingMoreClasses(false);
    }
  }

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    void loadCore().then(({ classesResult, membershipResult, workspaceResult }) => {
      if (ignore) return;
      if (classesResult.status === "fulfilled" && classesResult.value.kind === "data") {
        setClasses(parseClasses(classesResult.value.data));
        setNextCursor(parsePage(classesResult.value.data).nextCursor);
        setListError(null);
      } else if (classesResult.status === "rejected") {
        setClasses([]);
        setListError(teachingErrorMessage(classesResult.reason, "Không tải được danh sách lớp."));
      }
      setMemberships(membershipResult.status === "fulfilled"
        ? parseMemberships(membershipResult.value) : []);
      if (workspaceResult.status === "fulfilled" && workspaceResult.value.kind === "data") {
        const found = parseWorkspaces(workspaceResult.value.data)
          .find((item) => item.id === workspaceId);
        setWorkspace(found ?? null);
      } else if (workspaceResult.status === "rejected") {
        setWorkspace(null);
      }
    });
    requestAllPages("/api/subjects")
      .then((items) => {
        if (ignore) return;
        setSubjects(parseSubjects(items));
      })
      .catch(() => {
        if (!ignore) setSubjects([]);
      });
    return () => {
      ignore = true;
      pendingMutation.current?.abort();
    };
  }, [userId, workspaceId, classesPath, wid, loadCore]);

  useEffect(() => {
    if (!selectedClassId) return;
    let ignore = false;
    requestAllPages(`${classesPath}/${encodeURIComponent(selectedClassId)}/students`)
      .then((items) => {
        if (ignore) return;
        setRoster(parseRoster(items));
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

  useEffect(() => {
    if (!operation || operationStatus(operation).kind !== "pending" || !userId) return;
    const controller = new AbortController();
    void pollOperationUntilSettled(operation.id, parseTrackedOperation, controller.signal)
      .then(async (next) => {
        if (controller.signal.aborted) return;
        if (next.completed && next.state === "SUCCEEDED") {
          const { classesResult, membershipResult, workspaceResult, rosterResult } =
            await loadAll(controller.signal);
          if (controller.signal.aborted) return;
          if (classesResult.status === "fulfilled" && classesResult.value.kind === "data") {
            setClasses(parseClasses(classesResult.value.data));
            setNextCursor(parsePage(classesResult.value.data).nextCursor);
          } else {
            setClasses([]);
            setListError("Không thể làm mới danh sách lớp sau thay đổi quyền.");
          }
          setRoster(rosterResult.status === "fulfilled" && rosterResult.value !== null
            ? parseRoster(rosterResult.value) : null);
          setMemberships(membershipResult.status === "fulfilled" ? parseMemberships(membershipResult.value) : []);
          setWorkspace(workspaceResult.status === "fulfilled" && workspaceResult.value.kind === "data"
            ? parseWorkspaces(workspaceResult.value.data).find((item) => item.id === workspaceId) ?? null
            : null);
          setRefreshTick((tick) => tick + 1);
        }
        setOperationResult({ userId, workspaceId, value: next });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setActionError(teachingErrorMessage(error, "Không kiểm tra được trạng thái thao tác."));
        }
      });
    return () => controller.abort();
  }, [operation, userId, workspaceId, classesPath, selectedClassId, wid, loadAll]);

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const nextOperation = await checkOperationStatus(operation.id, { whenReady: true });
      setOperationResult({ userId, workspaceId, value: nextOperation });
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
      const controller = new AbortController();
      pendingMutation.current = controller;
      const data = await submitMutation(path, init, key, controller.signal);
      const nextOperation = parseTrackedOperation(data);
      if (nextOperation) {
        // Receipt bền kết thúc attempt; lần thao tác mới phải có key mới.
        attempts.current.delete(signature);
        setOperationResult({ userId, workspaceId, value: nextOperation });
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") {
          refresh();
        }
      }
      return data;
    } catch (error) {
      if (error instanceof ApiError && error.code === "OPERATION_FAILED") {
        attempts.current.delete(signature);
      }
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
          anonymousText="Đăng nhập để quản lý lớp, khóa và phân công thuộc phạm vi của bạn."
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
              titleId="teaching-operation-title"
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
                disabled={busy || loadingMoreClasses}
                onClick={() => void loadMoreClasses()}
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
