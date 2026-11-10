"use client";

import { useCallback, useEffect, useState } from "react";
import { requestAllPages } from "../../lib/api-client";
import type { Membership } from "../../lib/memberships";
import {
  operationSucceeded,
  parseHomeroomAssignments,
  stateLabel,
  teachingErrorMessage,
  type HomeroomAssignment,
  type TeachingMutate,
} from "./teaching-helpers";

export function HomeroomAssignmentList({
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

  const loadAssignments = useCallback(async (): Promise<HomeroomAssignment[]> => {
    return parseHomeroomAssignments(await requestAllPages(assignmentsPath));
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
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách chủ nhiệm."));
      });
    return () => {
      ignore = true;
    };
    // refreshTick tăng sau mỗi thao tác hoàn tất ở trang cha để nạp lại đúng lớp đang mở.
  }, [loadAssignments, refreshTick]);

  async function revokeAssignment(item: HomeroomAssignment) {
    const data = await mutate(
      `revoke-homeroom:${item.id}`,
      JSON.stringify([userId, workspaceId, "revoke-homeroom", classId, item.id, item.version]),
      `${assignmentsPath}/${encodeURIComponent(item.id)}`,
      { method: "DELETE" },
    );
    if (operationSucceeded(data)) {
      try {
        setAssignments(await loadAssignments());
        setLoadError(null);
      } catch (error) {
        setAssignments([]);
        setLoadError(teachingErrorMessage(error, "Không tải được danh sách chủ nhiệm."));
      }
    }
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
