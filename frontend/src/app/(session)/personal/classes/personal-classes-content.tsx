"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, requestAllPages, requestWorkspaces } from "@/lib/api-client";
import { SessionGate } from "@/features/identity/session-gate";
import { useSession } from "@/features/identity/session-provider";
import { submitMutation } from "@/features/operations/operation-attempts";
import { OperationPanel } from "@/features/operations/operation-panel";
import { useOperationCheck } from "@/lib/use-operation-check";
import {
  operationStatus,
  parseTrackedOperation,
  type TrackedOperation,
} from "@/lib/operation-status";
import {
  canManagePersonalClasses,
  parsePersonalWorkspace,
} from "@/features/subscriptions/personal-entitlements";
import type { WorkspaceView } from "@/features/identity/workspace-switcher";
import {
  nameBody,
  operationSucceeded,
  parseClasses,
  parseRoster,
  parseSubjects,
  stateLabel,
  teachingErrorMessage,
  type RosterEntry,
  type Subject,
  type TeachingClass,
  type TeachingMutate,
} from "@/features/teaching/class-management";
import { CourseSubjectEditor } from "@/features/teaching/course-subject-editor";

export function PersonalClassesContent() {
  const { session, refreshSession } = useSession();
  const checkOperationStatus = useOperationCheck();
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [workspaceLoaded, setWorkspaceLoaded] = useState(false);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [workspaceReload, setWorkspaceReload] = useState(0);
  const [classes, setClasses] = useState<TeachingClass[] | null>(null);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedClassId, setSelectedClassId] = useState<string | null>(null);
  const [roster, setRoster] = useState<RosterEntry[] | null>(null);
  const [rosterError, setRosterError] = useState<string | null>(null);
  const [createName, setCreateName] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [studentUserId, setStudentUserId] = useState("");
  const [rosterFormError, setRosterFormError] = useState<string | null>(null);
  const [operation, setOperation] = useState<TrackedOperation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const attempts = useRef(new Map<string, string>());
  const pendingMutation = useRef<AbortController | null>(null);
  const userId = session.status === "authenticated" ? session.user.id : null;

  const personalId = workspace?.id ?? null;
  const wid = personalId ? encodeURIComponent(personalId) : "";
  const classesPath = personalId ? `/api/workspaces/${wid}/classes` : "";
  const canManage = canManagePersonalClasses(workspace);
  const planExpired = workspaceLoaded && workspace !== null && !workspace.subscription.effective;
  const selectedClass = (classes ?? []).find((item) => item.id === selectedClassId) ?? null;
  const busy = pendingAction !== null
    || (operation !== null && operationStatus(operation).kind === "pending");

  const loadClasses = useCallback(async () => {
    if (!classesPath) return;
    setListError(null);
    try {
      setClasses(parseClasses(await requestAllPages(classesPath)));
    } catch (error) {
      setClasses([]);
      setListError(teachingErrorMessage(error, "Không tải được danh sách lớp."));
    }
  }, [classesPath]);

  const loadRoster = useCallback(async (classId: string) => {
    if (!classesPath) return;
    setRosterError(null);
    try {
      setRoster(parseRoster(await requestAllPages(
        `${classesPath}/${encodeURIComponent(classId)}/students`,
      )));
    } catch (error) {
      setRoster([]);
      setRosterError(teachingErrorMessage(error, "Không tải được danh sách học sinh của lớp."));
    }
  }, [classesPath]);

  function refresh() {
    setRefreshTick((tick) => tick + 1);
    void loadClasses();
    if (selectedClassId) void loadRoster(selectedClassId);
  }

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    requestWorkspaces(userId)
      .then((result) => {
        if (ignore || result.kind !== "data") return;
        setWorkspace(parsePersonalWorkspace(result.data));
        setWorkspaceError(null);
        setWorkspaceLoaded(true);
      })
      .catch((error: unknown) => {
        if (!ignore) {
          setWorkspace(null);
          setWorkspaceError(
            error instanceof Error ? error.message : "Không tải được không gian cá nhân.",
          );
          setWorkspaceLoaded(true);
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
  }, [userId, workspaceReload]);

  useEffect(() => {
    if (!classesPath) return;
    let ignore = false;
    requestAllPages(classesPath)
      .then((items) => {
        if (ignore) return;
        setClasses(parseClasses(items));
        setListError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setClasses([]);
        setListError(teachingErrorMessage(error, "Không tải được danh sách lớp."));
      });
    return () => {
      ignore = true;
    };
  }, [classesPath]);

  useEffect(() => {
    if (!selectedClassId || !classesPath) return;
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

  async function checkOperation() {
    if (!operation) return;
    setPendingAction("operation");
    setActionError(null);
    try {
      const nextOperation = await checkOperationStatus(operation.id, { whenReady: true });
      setOperation(nextOperation);
      if (nextOperation.completed && nextOperation.state === "SUCCEEDED") refresh();
    } catch (error) {
      setActionError(teachingErrorMessage(error, "Không kiểm tra được trạng thái thao tác."));
    } finally {
      setPendingAction(null);
    }
  }

  const mutate: TeachingMutate = async (action, signature, path, init) => {
    const key = attempts.current.get(signature) ?? crypto.randomUUID();
    attempts.current.set(signature, key);
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
        setOperation(nextOperation);
        if (nextOperation.completed && nextOperation.state === "SUCCEEDED") refresh();
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
      JSON.stringify([userId, personalId, "create-class", body]),
      classesPath,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (data !== null && operationSucceeded(data)) setCreateName("");
  }

  async function revokeClass(item: TeachingClass) {
    await mutate(
      `revoke-class:${item.id}`,
      JSON.stringify([userId, personalId, "revoke-class", item.id, item.version]),
      `${classesPath}/${encodeURIComponent(item.id)}`,
      { method: "DELETE" },
    );
  }

  async function addStudent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedClass) return;
    const id = studentUserId.trim();
    if (!id) {
      setRosterFormError("Hãy nhập mã người dùng của học sinh.");
      return;
    }
    setRosterFormError(null);
    const data = await mutate(
      "add-student",
      JSON.stringify([userId, personalId, "add-student", selectedClass.id, id]),
      `${classesPath}/${encodeURIComponent(selectedClass.id)}/students`,
      { method: "POST", body: JSON.stringify({ studentUserIds: [id] }) },
    );
    if (data !== null && operationSucceeded(data)) setStudentUserId("");
  }

  async function revokeStudent(entry: RosterEntry) {
    if (!selectedClass) return;
    await mutate(
      `revoke-student:${entry.id}`,
      JSON.stringify([userId, personalId, "revoke-student", selectedClass.id, entry.id, entry.version]),
      `${classesPath}/${encodeURIComponent(selectedClass.id)}/students/${encodeURIComponent(entry.id)}`,
      { method: "DELETE" },
    );
  }

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="personal-classes-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="personal-classes-title">Lớp cá nhân</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để xem lớp cá nhân của bạn."
        >
            <p className="introduction">
              Lớp, khóa và ghi danh trong không gian cá nhân của chính bạn, mở theo gói
              cá nhân. Không có vai trò trung tâm nào được copy sang đây.
            </p>
            {planExpired ? (
              <p role="alert">
                Chưa có gói cá nhân hiệu lực: không tạo mới hay thêm quan hệ được;
                việc thu hồi vẫn thực hiện theo thẩm quyền.
              </p>
            ) : null}
            {workspaceError ? (
              <>
                <p role="alert">{workspaceError}</p>
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => {
                    setWorkspaceLoaded(false);
                    setWorkspaceError(null);
                    setWorkspaceReload((value) => value + 1);
                  }}
                >
                  Tải lại danh sách lớp
                </button>
              </>
            ) : null}
            {actionError ? <p role="alert">{actionError}</p> : null}
            {operation ? (
              <OperationPanel
                operation={operation}
                checking={pendingAction === "operation"}
                onCheck={() => void checkOperation()}
                titleId="personal-operation-title"
              />
            ) : null}

            <section aria-labelledby="personal-class-list-title">
              <h2 id="personal-class-list-title">Danh sách lớp</h2>
              {classes === null && (workspace !== null || !workspaceLoaded) ? (
                <p role="status">Đang tải danh sách lớp…</p>
              ) : null}
              {workspaceLoaded && workspace === null && !workspaceError ? (
                <p>Chưa có không gian cá nhân. Hoàn tất xác minh danh tính để tiếp tục.</p>
              ) : null}
              {listError ? <p role="alert">{listError}</p> : null}
              {classes !== null && classes.length === 0 && !listError ? (
                <p>Chưa có lớp cá nhân nào trong phạm vi xem của bạn.</p>
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
                        <>
                          {" "}
                          <button
                            className="secondary-button"
                            type="button"
                            disabled={busy}
                            onClick={() => void revokeClass(item)}
                          >
                            Thu hồi lớp
                          </button>
                        </>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>

            {canManage ? (
              <section className="section" aria-labelledby="personal-create-class-title">
                <h2 id="personal-create-class-title">Tạo lớp mới</h2>
                <form onSubmit={createClass}>
                  <div className="field">
                    <label htmlFor="personal-class-name">Tên lớp</label>
                    <input
                      id="personal-class-name"
                      value={createName}
                      disabled={busy}
                      onChange={(event) => setCreateName(event.target.value)}
                    />
                  </div>
                  {createError ? <p role="alert">{createError}</p> : null}
                  <button className="action-button" type="submit" disabled={busy}>
                    Tạo lớp
                  </button>
                </form>
              </section>
            ) : null}

            {selectedClass ? (
              <section className="section" aria-labelledby="personal-class-detail-title">
                <h2 id="personal-class-detail-title">Lớp {selectedClass.name}</h2>

                <section aria-labelledby="personal-roster-title">
                  <h3 id="personal-roster-title">Học sinh của lớp</h3>
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
                    <form onSubmit={addStudent}>
                      <div className="field">
                        <label htmlFor="personal-student-id">Mã người dùng của học sinh</label>
                        <input
                          id="personal-student-id"
                          value={studentUserId}
                          disabled={busy}
                          onChange={(event) => setStudentUserId(event.target.value)}
                        />
                      </div>
                      <p>
                        Học sinh cá nhân chỉ cần danh tính hợp lệ, không qua membership
                        trung tâm.
                      </p>
                      {rosterFormError ? <p role="alert">{rosterFormError}</p> : null}
                      <button
                        className="action-button"
                        type="submit"
                        disabled={busy}
                      >
                        Thêm học sinh
                      </button>
                    </form>
                  ) : null}
                </section>

                <CourseSubjectEditor
                  workspaceId={personalId ?? ""}
                  classId={selectedClass.id}
                  subjects={subjects}
                  teachers={[]}
                  roster={roster ?? []}
                  userId={userId}
                  canManage={canManage}
                  subscriptionExpired={planExpired}
                  busy={busy}
                  refreshTick={refreshTick}
                  mutate={mutate}
                  showTeacherAssignment={false}
                />
              </section>
            ) : null}
        </SessionGate>
      </section>
    </main>
  );
}
