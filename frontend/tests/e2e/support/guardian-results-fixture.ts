import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";

type ResultSeed = {
  workspaceId: string;
  teacherUserId: string;
  studentUserId: string;
  courseSubjectId: string;
  subjectId: string;
  score: string;
  comment: string;
  published: boolean;
};

const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const literal = (value: string): string => `'${value.replaceAll("'", "''")}'`;

/** Chỉ seed primitive trong schema của runner TEST; quyền vẫn do PAP/PDP thật dựng và xét. */
export function seedGuardianResult(seed: ResultSeed): string {
  const pairedTarget = [
    ["jdbc:postgresql://127.0.0.1:15432/giaokhoa_test", "http://127.0.0.1:18081"],
    ["jdbc:postgresql://127.0.0.1:15433/giaokhoa_test", "http://127.0.0.1:18082"],
  ].some(([database, keycloak]) => process.env.TEST_DATABASE_URL === database
    && process.env.TEST_KEYCLOAK_BASE_URL === keycloak);
  const schema = process.env.TEST_E2E_SCHEMA ?? "";
  if (!pairedTarget || process.env.TEST_KEYCLOAK_REALM !== "giaokhoa-it"
      || !/^e2e_[a-f0-9]{32}$/.test(schema)) {
    throw new Error("Fixture chỉ chạy trên cặp DB/Keycloak TEST và schema riêng của Playwright.");
  }
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  const match = (process.env.TEST_DATABASE_URL ?? "")
    .match(/^jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/);
  const host = match?.[1];
  const database = match?.[3];
  if (!username || !password || !host || !database) {
    throw new Error("Fixture cần TEST_DATABASE_URL/USERNAME/PASSWORD của làn TEST.");
  }
  const port = match?.[2] ?? "5432";
  for (const value of [seed.workspaceId, seed.teacherUserId, seed.studentUserId,
    seed.courseSubjectId, seed.subjectId]) {
    if (!uuid.test(value)) throw new Error("Fixture cần UUID hợp lệ.");
  }
  const resource = randomUUID();
  const version = randomUUID();
  const activity = randomUUID();
  const submission = randomUUID();
  const result = randomUUID();
  const w = literal(seed.workspaceId);
  const teacher = literal(seed.teacherUserId);
  const student = literal(seed.studentUserId);
  const courseSubject = literal(seed.courseSubjectId);
  const sql = `BEGIN;
SET LOCAL search_path TO ${schema},public;
SELECT set_config('app.workspace_id', ${w}, true);
INSERT INTO resource (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)
SELECT '${resource}', ${w}, ${teacher}, 'EXAM', ${literal(seed.subjectId)}, 'ACTIVE'
FROM course_subject WHERE id = ${courseSubject} AND workspace_id = ${w}
  AND subject_id = ${literal(seed.subjectId)};
INSERT INTO resource_version (id, resource_id, version_no, label, body_stub, answer_stub, review_state)
VALUES ('${version}', '${resource}', 1, 'Fixture US4', 'body-hidden', 'answer-hidden', 'APPROVED');
INSERT INTO sample_activity
  (id, workspace_id, course_subject_id, resource_id, version_id, assigned_by,
   assigned_at, due_at, attempt_limit, subscription_period_id, state)
VALUES ('${activity}', ${w}, ${courseSubject}, '${resource}', '${version}', ${teacher},
  now(), now() + interval '7 days', 1,
  (SELECT id FROM subscription_period WHERE workspace_id = ${w}
   AND valid_from <= now() AND expires_at > now() ORDER BY valid_from DESC LIMIT 1), 'ASSIGNED');
INSERT INTO sample_submission
  (id, workspace_id, activity_id, student_user_id, attempt_no, submitted_at, content_stub, state)
VALUES ('${submission}', ${w}, '${activity}', ${student}, 1, now(), 'submission-hidden', 'SUBMITTED');
INSERT INTO sample_result
  (id, workspace_id, submission_id, student_user_id, course_subject_id,
   score_stub, comment_stub, state, published_at, graded_by)
VALUES ('${result}', ${w}, '${submission}', ${student}, ${courseSubject},
  ${literal(seed.score)}, ${literal(seed.comment)}, '${seed.published ? "PUBLISHED" : "DRAFT"}',
  ${seed.published ? "now()" : "NULL"}, ${teacher});
COMMIT;`;
  const ketQua = spawnSync("psql",
    ["-X", "-v", "ON_ERROR_STOP=1", "-qAt", "-f", "-"],
    {
      input: sql,
      env: {
        ...process.env,
        PGHOST: host,
        PGPORT: port,
        PGDATABASE: database,
        PGUSER: username,
        PGPASSWORD: password,
      },
      timeout: 60_000,
      encoding: "utf8",
    });
  if (ketQua.error) {
    throw new Error(`Không chạy được psql để seed kết quả: ${ketQua.error.message}`);
  }
  if (ketQua.status !== 0) {
    throw new Error(`Seed kết quả thất bại (psql ${ketQua.status}): ${(ketQua.stderr ?? "").trim()}`);
  }
  return result;
}
