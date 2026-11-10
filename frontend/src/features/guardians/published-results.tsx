"use client";

import { useEffect, useState } from "react";
import { isRecord, listItems, requestAllPages } from "@/lib/api-client";
import { SessionGate } from "@/features/identity/session-gate";
import { useSession } from "@/features/identity/session-provider";

// Hình hiển thị duy nhất của đường đọc kết quả: đúng các trường của
// PublishedResultView. Bài làm, đáp án và dữ liệu chấm nội bộ không có trong DTO
// này nên không có đường nào để trang hiển thị chúng.
export type PublishedResult = {
  id: string;
  student: { id: string; displayName: string };
  className: string;
  courseName: string;
  subjectName: string;
  score: string | null;
  comment: string | null;
  publishedAt: string | null;
};

function isNullableText(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

// Chấp nhận cả danh sách trần và dạng {items} theo quy tắc query danh sách của hợp
// đồng. Object kết quả được dựng lại chỉ từ các trường hiển thị đã biết: trường lạ
// trong phản hồi (nếu có) không đi tiếp vào dữ liệu hiển thị.
export function parsePublishedResults(data: unknown): PublishedResult[] {
  const items = listItems(data);
  const results: PublishedResult[] = [];
  for (const item of items) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || !isRecord(item.student)
        || typeof item.student.id !== "string" || !item.student.id
        || typeof item.student.displayName !== "string" || !item.student.displayName
        || typeof item.className !== "string" || !item.className
        || typeof item.courseName !== "string" || !item.courseName
        || typeof item.subjectName !== "string" || !item.subjectName
        || !isNullableText(item.score)
        || !isNullableText(item.comment)
        || !isNullableText(item.publishedAt)) {
      continue;
    }
    results.push({
      id: item.id,
      student: { id: item.student.id, displayName: item.student.displayName },
      className: item.className,
      courseName: item.courseName,
      subjectName: item.subjectName,
      score: item.score,
      comment: item.comment,
      publishedAt: item.publishedAt,
    });
  }
  return results;
}

export function ChildResultsContent({
  workspaceId,
  studentId,
}: {
  workspaceId: string;
  studentId: string;
}) {
  const { session, refreshSession } = useSession();
  const userId = session.status === "authenticated" ? session.user.id : null;
  // Kết quả đã tải gắn khóa ngữ cảnh (actor + workspace + học sinh): đổi trung tâm,
  // đổi con hoặc đổi tài khoản thì dữ liệu cũ không còn khớp khóa và không được hiển
  // thị nữa; lỗi (kể cả 403 sau khi liên kết bị gỡ) cũng thay hiển thị cũ bằng thông
  // báo lỗi thay vì giữ dữ liệu của ngữ cảnh trước.
  const contextKey = `${userId ?? ""}|${workspaceId}|${studentId}`;
  const [loaded, setLoaded] = useState<{ key: string; results: PublishedResult[] } | null>(null);
  const [loadError, setLoadError] = useState<{ key: string; message: string } | null>(null);

  const results = loaded?.key === contextKey ? loaded.results : null;
  const listError = loadError?.key === contextKey ? loadError.message : null;

  const resultsPath =
    `/api/workspaces/${encodeURIComponent(workspaceId)}/students/${encodeURIComponent(studentId)}/results`;

  useEffect(() => {
    if (!userId) return;
    let ignore = false;
    const controller = new AbortController();
    requestAllPages(resultsPath, { signal: controller.signal })
      .then((items) => {
        if (ignore) return;
        setLoaded({ key: contextKey, results: parsePublishedResults(items) });
        setLoadError(null);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        setLoaded({ key: contextKey, results: [] });
        setLoadError({
          key: contextKey,
          message: error instanceof Error ? error.message : "Không tải được kết quả đã công bố.",
        });
      });
    return () => {
      ignore = true;
      controller.abort();
    };
  }, [userId, workspaceId, studentId, contextKey, resultsPath]);

  const studentName = results !== null && results.length > 0
    ? results[0].student.displayName
    : null;

  return (
    <main className="site-main">
      <section className="hero" aria-labelledby="child-results-title">
        <p className="eyebrow">Giáo Khoa</p>
        <h1 id="child-results-title">Kết quả đã công bố{studentName ? ` của ${studentName}` : ""}</h1>

        <SessionGate
          status={session.status}
          message={session.status === "error" ? session.message : undefined}
          onRetry={() => void refreshSession()}
          anonymousText="Đăng nhập để xem kết quả đã công bố của con."
        >
          <p className="introduction">
            Chỉ các kết quả đã được công bố của học sinh này tại trung tâm hiện tại mới
            hiển thị ở đây. Kết quả còn nháp và bài làm không thuộc phạm vi trang này.
          </p>

          {results === null ? <p role="status">Đang tải kết quả đã công bố…</p> : null}
          {listError ? <p role="alert">{listError}</p> : null}
          {results !== null && results.length === 0 && !listError ? (
            <p>Chưa có kết quả nào được công bố cho học sinh này.</p>
          ) : null}
          {results !== null && results.length > 0 ? (
            <ul>
              {results.map((result) => (
                <li key={result.id}>
                  <dl className="details">
                    <div>
                      <dt>Môn học</dt>
                      <dd>{result.subjectName}</dd>
                    </div>
                    <div>
                      <dt>Lớp</dt>
                      <dd>{result.className}</dd>
                    </div>
                    <div>
                      <dt>Khóa</dt>
                      <dd>{result.courseName}</dd>
                    </div>
                    <div>
                      <dt>Điểm</dt>
                      <dd>{result.score ?? "—"}</dd>
                    </div>
                    {result.comment ? (
                      <div>
                        <dt>Nhận xét</dt>
                        <dd>{result.comment}</dd>
                      </div>
                    ) : null}
                    {result.publishedAt ? (
                      <div>
                        <dt>Công bố lúc</dt>
                        <dd>
                          <time dateTime={result.publishedAt}>{result.publishedAt}</time>
                        </dd>
                      </div>
                    ) : null}
                  </dl>
                </li>
              ))}
            </ul>
          ) : null}
        </SessionGate>
      </section>
    </main>
  );
}
