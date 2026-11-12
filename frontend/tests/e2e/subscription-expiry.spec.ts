import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse, type Page } from "@playwright/test";
import {
  TestEnvironment,
  appRequestWhenReady,
  loginViaKeycloakForm,
  luuPhienHienTai,
  khoiPhucPhienDaLuu,
  moBoiCanhTuPhienDaLuu,
  DEFAULT_FRONTEND_ORIGIN,
  parseTestDatabaseUrl,
  TEST_LANES,
} from "./support/test-environment";

// E2E hết hạn gói của US8 ở biên trình duyệt với PHIÊN/COOKIE CŨ (issue #90): các phiên
// của owner/teacher/student/parent được mở và giữ nguyên từ lúc gói còn hiệu lực; khi
// đồng hồ thật qua mốc hết hạn thì bằng đúng các cookie đó: đọc vẫn được, bài đã giao
// vẫn hoàn tất trọn vòng nộp/chấm/công bố, còn tăng thêm (tạo lớp, giao bài mới, gia hạn
// hạn nộp, mở thêm lượt) bị chặn; operator gia hạn thì quyền mở lại cho người còn phân
// công. Kỳ gói được operator xác nhận qua API với cửa sổ ngắn theo đồng hồ thật — không
// mock thời gian, không chờ cron. Spec cần origin ứng dụng đang chạy (backend cùng
// stack TEST) qua NATIVE_GATE_APP_ORIGIN, cùng cơ chế personal-entitlements.spec.ts.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? DEFAULT_FRONTEND_ORIGIN;

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

const HAN_DUNG_HET_HAN = 900_000;
const HAN_DUNG_CA_NHAN = 600_000;

type NguoiDungTest = Awaited<ReturnType<TestEnvironment["createUser"]>> & { appId: string };
type Csrf = { headerName: string; token: string };
type SessionView = {
  authenticated?: boolean;
  csrf?: Csrf;
  user?: { id?: string; username?: string };
};
type OperationView = {
  id: string;
  state: string;
  completed: boolean;
  targetType?: string;
  targetId?: string;
  errorCode?: string;
};
type MutationTraLoi = {
  operationId?: string;
  id?: string;
  state?: string;
  completed?: boolean;
  statusUrl?: string;
  operation?: OperationView;
  invitationToken?: string;
};
type SampleActionTraLoi = { targetId?: string; state?: string };
type TrangDanhSach<T> = { items: T[]; nextCursor?: string };
type CenterView = { id: string; workspaceId: string; name: string };
type MembershipView = {
  id: string;
  user: { id: string; username: string; displayName?: string };
  roles: string[];
  state: string;
  version: number;
};
type WorkspaceView = {
  id: string;
  kind: string;
  name: string;
  subscription?: { effective?: unknown; expiresAt?: string };
  roles?: string[];
};
type ClassView = { id: string; name: string };
type MonTrongKhoa = { id: string; subjectId: string; name?: string };
type CourseView = { id: string; classId: string; name: string; subjects: MonTrongKhoa[] };
type SubjectView = { id: string; code?: string; name?: string };
type RosterEntry = {
  id: string;
  studentUserId?: string;
  user?: { id?: string; username?: string };
};
type KetQuaView = { id: string };

function gateOrigin(): string {
  if (!appOrigin) throw new Error("Cần NATIVE_GATE_APP_ORIGIN để chạy E2E US8 thật.");
  return appOrigin;
}

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
  await luuPhienHienTai(page, user.username);
}

async function laySessionView(page: Page, origin: string): Promise<SessionView> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  return (await response.json()) as SessionView;
}

// Đăng nhập lại bằng tài khoản khác trên cùng trang: đổi phiên rồi lấy CSRF mới.
// Nếu actor đã có phiên lưu từ lần đăng nhập form thật trong cùng lượt chạy thì khôi
// phục storageState thay vì qua form Keycloak lần nữa (mẫu personal-entitlements).
async function dangNhapLai(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<Csrf> {
  if (await khoiPhucPhienDaLuu(page, user.username)) {
    const session = await laySessionView(page, origin);
    if (session.authenticated && session.csrf?.token) return session.csrf;
  }
  await page.context().clearCookies();
  await dangNhapQuaUngDung(page, origin, user);
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  return session.csrf!;
}

// Mọi mutation đi qua helper chuẩn appRequestWhenReady (support/test-environment.ts):
// chỉ thử lại 503 AUTHORIZATION_NOT_READY tối đa 60 giây và giữ nguyên Idempotency-Key.
async function guiMutation(
  page: Page,
  origin: string,
  csrf: Csrf,
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: Record<string, unknown>,
  tuyChon?: { idempotencyKey?: string; headers?: Record<string, string> },
): Promise<APIResponse> {
  return appRequestWhenReady(page, `${origin}${path}`, {
    method,
    data: body,
    headers: {
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": tuyChon?.idempotencyKey ?? randomUUID(),
      ...(tuyChon?.headers ?? {}),
    },
  });
}

async function guiGet(page: Page, origin: string, path: string): Promise<APIResponse> {
  return appRequestWhenReady(page, `${origin}${path}`);
}

function layOperationId(traLoi: MutationTraLoi): string {
  const operationId = traLoi.operationId ?? traLoi.operation?.id ?? traLoi.id;
  expect(typeof operationId).toBe("string");
  expect(operationId!.length).toBeGreaterThan(0);
  return operationId!;
}

async function choOperationThanhCong(
  page: Page,
  origin: string,
  operationId: string,
  statusUrl?: string,
): Promise<OperationView> {
  const url = statusUrl
    ? new URL(statusUrl, origin).toString()
    : `${origin}/api/me/operations/${operationId}`;
  let cuoi: OperationView | undefined;
  await expect.poll(async () => {
    const response = await page.request.get(url);
    if (response.status() !== 200) return `HTTP_${response.status()}`;
    cuoi = (await response.json()) as OperationView;
    return cuoi.state;
  }, { timeout: 60_000, message: `operation ${operationId} chưa SUCCEEDED` }).toBe("SUCCEEDED");
  expect(cuoi!.completed).toBe(true);
  expect(cuoi!.id).toBe(operationId);
  return cuoi!;
}

async function taoNguoiDung(
  page: Page, origin: string, env: TestEnvironment,
  nhom: string | null,
  daTao: NguoiDungTest[],
): Promise<NguoiDungTest> {
  const user = { ...await env.createUser(), appId: "" };
  daTao.push(user);
  if (nhom) await env.addToRealmGroup(user, nhom);
  // ID directory của ứng dụng khác sub Keycloak; bootstrap bằng phiên thật trước khi làm target.
  const context = await page.context().browser()!.newContext();
  try {
    const temporaryPage = await context.newPage();
    await dangNhapQuaUngDung(temporaryPage, origin, user);
    const session = await laySessionView(temporaryPage, origin);
    expect(session.user?.id).toBeTruthy();
    user.appId = session.user!.id!;
  } finally { await context.close(); }
  return user;
}

async function xoaNguoiDung(env: TestEnvironment, daTao: NguoiDungTest[]): Promise<void> {
  for (const user of daTao) {
    try {
      await env.deleteUser(user);
    } catch {
      // Dọn dẹp best-effort, không che lỗi của test chính.
    }
  }
}

async function layDanhSachCenter(page: Page, origin: string): Promise<CenterView[]> {
  const centers: CenterView[] = [];
  let cursor: string | undefined;
  const seen = new Set<string>();
  do {
    const path = `/api/platform/centers${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`;
    const response = await guiGet(page, origin, path);
    expect(response.status()).toBe(200);
    const body = (await response.json()) as TrangDanhSach<CenterView>;
    expect(Array.isArray(body.items)).toBe(true);
    centers.push(...body.items);
    cursor = body.nextCursor;
    if (cursor) {
      expect(seen.has(cursor)).toBe(false);
      seen.add(cursor);
    }
  } while (cursor);
  return centers;
}

async function taoCenter(
  page: Page,
  origin: string,
  csrf: Csrf,
  ownerId: string,
): Promise<{ workspaceId: string; tenCenter: string }> {
  const tenCenter = `center-het-han-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST", "/api/platform/centers", {
    name: tenCenter,
    ownerUserId: ownerId,
  });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const centers = await layDanhSachCenter(page, origin);
  const center = centers.find((item) => item.name === tenCenter);
  expect(center).toBeTruthy();
  return { workspaceId: center!.workspaceId, tenCenter };
}

async function layDanhSachThanhVien(
  page: Page,
  origin: string,
  workspaceId: string,
): Promise<MembershipView[]> {
  const response = await guiGet(page, origin, `/api/workspaces/${workspaceId}/memberships`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<MembershipView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

async function moiVaChapNhan(
  page: Page,
  origin: string,
  csrfNguoiMoi: Csrf,
  workspaceId: string,
  nguoiDuocMoi: NguoiDungTest,
  roles: string[],
): Promise<{ membershipId: string }> {
  const moi = await guiMutation(page, origin, csrfNguoiMoi, "POST",
    `/api/workspaces/${workspaceId}/invitations`, { email: nguoiDuocMoi.email, roles });
  expect(moi.status()).toBe(202);
  const traLoiMoi = (await moi.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoiMoi), traLoiMoi.statusUrl);

  const token = traLoiMoi.invitationToken;
  expect(token).toBeTruthy();
  const context = await page.context().browser()!.newContext();
  try {
    const acceptPage = await context.newPage();
    const csrfNguoiDuocMoi = await dangNhapLai(acceptPage, origin, nguoiDuocMoi);
    const chapNhan = await guiMutation(acceptPage, origin, csrfNguoiDuocMoi, "POST",
      `/api/invitations/${token!}/accept`);
    expect(chapNhan.status()).toBe(202);
    const traLoiChapNhan = (await chapNhan.json()) as MutationTraLoi;
    await choOperationThanhCong(
      acceptPage, origin, layOperationId(traLoiChapNhan), traLoiChapNhan.statusUrl);

    const thanhVien = await layDanhSachThanhVien(acceptPage, origin, workspaceId);
    const membership = thanhVien.find((item) => item.user.id === nguoiDuocMoi.appId);
    expect(membership).toBeTruthy();
    return { membershipId: membership!.id };
  } finally { await context.close(); }
}

async function layDanhSachWorkspaceCuaToi(page: Page, origin: string): Promise<WorkspaceView[]> {
  const response = await guiGet(page, origin, "/api/me/workspaces");
  expect(response.status()).toBe(200);
  const workspaces = (await response.json()) as WorkspaceView[];
  expect(Array.isArray(workspaces)).toBe(true);
  return workspaces;
}

async function layWorkspaceCaNhan(page: Page, origin: string): Promise<WorkspaceView> {
  const workspaces = await layDanhSachWorkspaceCuaToi(page, origin);
  const personal = workspaces.find((item) => item.kind === "PERSONAL");
  expect(personal).toBeTruthy();
  return personal!;
}

// Operator xác nhận gói với cửa sổ [validFrom, expiresAt) cho trước: bản tổng quát của
// xacNhanGoi trong personal-entitlements (vốn cố định cửa sổ tương lai 90 ngày) để test
// hết hạn dùng cửa sổ ngắn theo đồng hồ thật và test gia hạn dùng kỳ mới sau mốc.
async function xacNhanGoiCuaSo(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  validFrom: Date,
  expiresAt: Date,
): Promise<void> {
  const lamTronGiay = (d: Date) => new Date(Math.floor(d.getTime() / 1000) * 1000).toISOString();
  const xacNhan = await guiMutation(page, origin, csrf, "POST", "/api/platform/subscriptions", {
    workspaceId,
    validFrom: lamTronGiay(validFrom),
    expiresAt: lamTronGiay(expiresAt),
    confirmationRef: `xac-nhan-het-han-${randomUUID()}`,
  });
  expect(xacNhan.status()).toBe(202);
  const traLoi = (await xacNhan.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function layDanhSachMon(page: Page, origin: string): Promise<SubjectView[]> {
  const response = await guiGet(page, origin, "/api/subjects");
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<SubjectView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

async function taoLop(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
): Promise<{ lopId: string; tenLop: string }> {
  const tenLop = `lop-het-han-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/classes`, { name: tenLop });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const danhSach = await guiGet(page, origin, `/api/workspaces/${workspaceId}/classes`);
  expect(danhSach.status()).toBe(200);
  const lop = ((await danhSach.json()) as TrangDanhSach<ClassView>).items
    .find((item) => item.name === tenLop);
  expect(lop).toBeTruthy();
  return { lopId: lop!.id, tenLop };
}

async function taoKhoa(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  lopId: string,
  monHoc: SubjectView[],
): Promise<{ khoaId: string; monKhoa: MonTrongKhoa[] }> {
  const tenKhoa = `khoa-het-han-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/classes/${lopId}/courses`,
    { name: tenKhoa, subjectIds: monHoc.map((mon) => mon.id) });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const danhSach = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/classes/${lopId}/courses`);
  expect(danhSach.status()).toBe(200);
  const khoaTomTat = ((await danhSach.json()) as TrangDanhSach<CourseView>).items
    .find((item) => item.name === tenKhoa);
  expect(khoaTomTat).toBeTruthy();
  const chiTiet = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/courses/${khoaTomTat!.id}`);
  expect(chiTiet.status()).toBe(200);
  const khoa = (await chiTiet.json()) as CourseView;
  expect(khoa.subjects).toHaveLength(monHoc.length);
  return { khoaId: khoa.id, monKhoa: khoa.subjects };
}

async function themNguoiHocVaoLop(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  lopId: string,
  studentUserId: string,
): Promise<void> {
  const them = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/classes/${lopId}/students`,
    { studentUserIds: [studentUserId] });
  expect(them.status()).toBe(202);
  const traLoi = (await them.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function layRoster(
  page: Page,
  origin: string,
  workspaceId: string,
  lopId: string,
): Promise<RosterEntry[]> {
  const response = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/classes/${lopId}/students`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<RosterEntry>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

function timDongRoster(
  roster: RosterEntry[],
  studentUserId: string,
  username: string,
): RosterEntry {
  const dong = roster.find((item) =>
    item.studentUserId === studentUserId
    || item.user?.id === studentUserId
    || item.user?.username === username);
  expect(dong).toBeTruthy();
  return dong!;
}

async function ghiDanh(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  classStudentIds: string[],
  courseSubjectIds: string[],
): Promise<void> {
  const batch = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/enrollments`,
    { classStudentIds, courseSubjectIds });
  expect(batch.status()).toBe(202);
  const traLoi = (await batch.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function phanCongGiaoVien(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  courseSubjectId: string,
  teacherUserIds: string[],
): Promise<void> {
  const phanCong = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/course-subjects/${courseSubjectId}/teachers`,
    { teacherUserIds });
  expect(phanCong.status()).toBe(202);
  const traLoi = (await phanCong.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

// Gửi một sample action đồng bộ và trả response thô để test khẳng định trạng thái:
// action thành công trả 201 kèm targetId/state, bị chặn trả 403.
async function guiSampleAction(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  body: Record<string, unknown>,
): Promise<APIResponse> {
  return guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/samples/actions`, body);
}

// Chờ đồng hồ thật qua mốc hết hạn của kỳ gói (cộng đệm 3 giây cho độ chính xác giây
// của native time policy): mốc do chính test đặt qua API nên chờ theo mốc là đủ, các
// khẳng định sau mốc mới là bằng chứng hành vi.
async function choQuaMocHetHan(mocHetHanMs: number): Promise<void> {
  await expect.poll(() => Date.now(), {
    timeout: 300_000,
    intervals: [1_000],
    message: "chưa qua mốc hết hạn của kỳ gói",
  }).toBeGreaterThanOrEqual(mocHetHanMs + 3_000);
}

// ---------- Seed fixture bằng psql (mẫu support/guardian-results-fixture.ts) ----------
// Chỉ seed primitive trong schema riêng của runner TEST; quyền vẫn do PAP/PDP thật
// dựng và xét. Dùng cho các tiền đề mà API không phơi bày: resource/version mẫu để
// giao bài, liên kết phụ huynh đã duyệt và bản nháp kết quả cho lượt nộp thật.

const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const literalSql = (value: string): string => `'${value.replaceAll("'", "''")}'`;

function chaySqlSeed(sql: string): void {
  const pairedTarget = TEST_LANES.some(([database, keycloak]) =>
    process.env.TEST_DATABASE_URL === database
    && process.env.TEST_KEYCLOAK_BASE_URL === keycloak);
  const schema = process.env.TEST_E2E_SCHEMA ?? "";
  if (!pairedTarget || process.env.TEST_KEYCLOAK_REALM !== "giaokhoa-it"
      || !/^e2e_[a-f0-9]{32}$/.test(schema)) {
    throw new Error("Seed E2E hết hạn chỉ chạy trên cặp DB/Keycloak TEST và schema riêng của Playwright.");
  }
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  const parsed = parseTestDatabaseUrl(process.env.TEST_DATABASE_URL ?? "");
  if (!username || !password || !parsed) {
    throw new Error("Seed E2E hết hạn cần TEST_DATABASE_URL/USERNAME/PASSWORD của làn TEST.");
  }
  const ketQua = spawnSync("psql",
    ["-X", "-v", "ON_ERROR_STOP=1", "-qAt", "-f", "-"],
    {
      input: `BEGIN;\nSET LOCAL search_path TO ${schema},public;\n${sql}\nCOMMIT;`,
      env: {
        ...process.env,
        PGHOST: parsed.host,
        PGPORT: parsed.port,
        PGDATABASE: parsed.database,
        PGUSER: username,
        PGPASSWORD: password,
      },
      timeout: 60_000,
      encoding: "utf8",
    });
  if (ketQua.error) {
    throw new Error(`Không chạy được psql để seed E2E hết hạn: ${ketQua.error.message}`);
  }
  if (ketQua.status !== 0) {
    throw new Error(`Seed E2E hết hạn thất bại (psql ${ketQua.status}): ${(ketQua.stderr ?? "").trim()}`);
  }
}

function seedTaiNguyen(
  workspaceId: string,
  createdByUserId: string,
  subjectId: string,
): { resourceId: string; versionId: string } {
  for (const value of [workspaceId, createdByUserId, subjectId]) {
    if (!uuidPattern.test(value)) throw new Error("Seed tài nguyên cần UUID hợp lệ.");
  }
  const resourceId = randomUUID();
  const versionId = randomUUID();
  chaySqlSeed(`
INSERT INTO resource (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)
VALUES ('${resourceId}', ${literalSql(workspaceId)}, ${literalSql(createdByUserId)}, 'EXAM',
  ${literalSql(subjectId)}, 'ACTIVE');
INSERT INTO resource_version (id, resource_id, version_no, label, body_stub, answer_stub, review_state)
VALUES ('${versionId}', '${resourceId}', 1, 'Fixture hết hạn', 'body', 'answer', 'APPROVED');`);
  return { resourceId, versionId };
}

function seedLienKetPhuHuynh(
  workspaceId: string,
  parentUserId: string,
  studentUserId: string,
  decidedByUserId: string,
): void {
  for (const value of [workspaceId, parentUserId, studentUserId, decidedByUserId]) {
    if (!uuidPattern.test(value)) throw new Error("Seed liên kết phụ huynh cần UUID hợp lệ.");
  }
  chaySqlSeed(`
INSERT INTO guardian_link (id, workspace_id, parent_user_id, student_user_id, state,
  requested_by, approved_by, approved_at, revoked_at)
VALUES ('${randomUUID()}', ${literalSql(workspaceId)}, ${literalSql(parentUserId)},
  ${literalSql(studentUserId)}, 'APPROVED', ${literalSql(parentUserId)},
  ${literalSql(decidedByUserId)}, now(), NULL);`);
}

function seedKetQuaDraft(
  workspaceId: string,
  submissionId: string,
  studentUserId: string,
  courseSubjectId: string,
): string {
  for (const value of [workspaceId, submissionId, studentUserId, courseSubjectId]) {
    if (!uuidPattern.test(value)) throw new Error("Seed kết quả cần UUID hợp lệ.");
  }
  const resultId = randomUUID();
  chaySqlSeed(`
INSERT INTO sample_result (id, workspace_id, submission_id, student_user_id, course_subject_id,
  score_stub, comment_stub, state, published_at, graded_by)
VALUES ('${resultId}', ${literalSql(workspaceId)}, ${literalSql(submissionId)},
  ${literalSql(studentUserId)}, ${literalSql(courseSubjectId)}, NULL, NULL, 'DRAFT', NULL, NULL);`);
  return resultId;
}

test.describe("US8 hết hạn gói ở biên trình duyệt", () => {
  test("Center hết hạn khi phiên còn mở: cookie cũ vẫn nộp/chấm/công bố được bài đã giao, tăng thêm bị chặn, gia hạn mở lại", async ({ page }) => {
    test.setTimeout(HAN_DUNG_HET_HAN);
    const origin = gateOrigin();
    void frontendOrigin;
    const env = new TestEnvironment();
    const daTao: NguoiDungTest[] = [];
    try {
      const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
      const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
      const teacher = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
      const student = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
      const parent = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);

      // Dựng center và xác nhận kỳ gói cửa sổ ngắn: còn hiệu lực trong lúc dựng chuỗi
      // dạy học và giao bài, hết hạn ngay trong lúc các phiên vẫn mở.
      let csrf = await dangNhapLai(page, origin, operator);
      const { workspaceId } = await taoCenter(page, origin, csrf, owner.appId);
      // Ngoại lệ đã chốt tại contracts/authorization.md: center CHƯA TỪNG có kỳ gói
      // vẫn tạo lớp được. Đừng nhầm với center có kỳ gói đã hết hạn (403 phía dưới).
      const csrfChuaKichHoat = await dangNhapLai(page, origin, owner);
      const taoTruocKichHoat = await guiMutation(
        page, origin, csrfChuaKichHoat, "POST",
        `/api/workspaces/${workspaceId}/classes`, { name: `chua-kich-hoat-${randomUUID().slice(0, 8)}` });
      expect(taoTruocKichHoat.status()).toBe(202);
      const chuaKichHoatReceipt = (await taoTruocKichHoat.json()) as MutationTraLoi;
      await choOperationThanhCong(
        page, origin, layOperationId(chuaKichHoatReceipt), chuaKichHoatReceipt.statusUrl);
      csrf = await dangNhapLai(page, origin, operator);
      const batDau = Date.now();
      const mocHetHan = Math.floor(batDau / 1000) * 1000 + 240_000;
      await xacNhanGoiCuaSo(
        page, origin, csrf, workspaceId,
        new Date(batDau - 120_000), new Date(mocHetHan));

      csrf = await dangNhapLai(page, origin, owner);
      await moiVaChapNhan(page, origin, csrf, workspaceId, teacher, ["TEACHER"]);
      csrf = await dangNhapLai(page, origin, owner);
      await moiVaChapNhan(page, origin, csrf, workspaceId, student, ["STUDENT"]);
      csrf = await dangNhapLai(page, origin, owner);
      await moiVaChapNhan(page, origin, csrf, workspaceId, parent, ["PARENT"]);

      csrf = await dangNhapLai(page, origin, owner);
      const { lopId } = await taoLop(page, origin, csrf, workspaceId);
      const monHoc = await layDanhSachMon(page, origin);
      expect(monHoc.length).toBeGreaterThan(0);
      const { monKhoa } = await taoKhoa(page, origin, csrf, workspaceId, lopId, [monHoc[0]]);
      const courseSubjectId = monKhoa[0].id;
      await themNguoiHocVaoLop(page, origin, csrf, workspaceId, lopId, student.appId);
      const roster = await layRoster(page, origin, workspaceId, lopId);
      const dongRoster = timDongRoster(roster, student.appId, student.username);
      await ghiDanh(page, origin, csrf, workspaceId, [dongRoster.id], [courseSubjectId]);
      await phanCongGiaoVien(page, origin, csrf, workspaceId, courseSubjectId, [teacher.appId]);
      seedLienKetPhuHuynh(workspaceId, parent.appId, student.appId, owner.appId);
      const taiNguyen = seedTaiNguyen(workspaceId, teacher.appId, monKhoa[0].subjectId);

      // Giáo viên giao bài trong lúc gói còn hiệu lực.
      const csrfTeacher = await dangNhapLai(page, origin, teacher);
      const giaoBai = await guiSampleAction(page, origin, csrfTeacher, workspaceId, {
        action: "ASSIGN_ACTIVITY",
        courseSubjectId,
        resourceId: taiNguyen.resourceId,
        versionId: taiNguyen.versionId,
        dueAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
        attemptLimit: 1,
      });
      expect(giaoBai.status()).toBe(201);
      const giaoBaiJson = (await giaoBai.json()) as SampleActionTraLoi;
      expect(giaoBaiJson.state).toBe("SUCCEEDED");
      expect(giaoBaiJson.targetId).toBeTruthy();
      const activityId = giaoBaiJson.targetId!;

      // Mở các context độc lập bằng cookie đã lưu từ trước mốc hết hạn và GIỮ NGUYÊN
      // qua mốc: đây là các "cookie cũ" mà kịch bản hết hạn phải tôn trọng cho phần
      // hoàn tất, đồng thời phải chặn ở phần tăng thêm.
      const browser = page.context().browser()!;
      const boiCanhStudent = await moBoiCanhTuPhienDaLuu(browser, origin, student);
      const boiCanhTeacher = await moBoiCanhTuPhienDaLuu(browser, origin, teacher);
      const boiCanhParent = await moBoiCanhTuPhienDaLuu(browser, origin, parent);
      try {
        await choQuaMocHetHan(mocHetHan);

        // Đọc bằng cookie cũ vẫn được cho cả học sinh lẫn owner. Học sinh không
        // liệt kê được lớp (chỉ quản trị/chủ nhiệm được đọc danh sách lớp) nên
        // đường đọc còn hiệu lực của học sinh là kết quả đã công bố của chính mình.
        const ketQuaHocSinh = await guiGet(
          boiCanhStudent.page, origin,
          `/api/workspaces/${workspaceId}/students/${student.appId}/results`);
        expect(ketQuaHocSinh.status()).toBe(200);
        csrf = await dangNhapLai(page, origin, owner);
        const docThanhVien = await guiGet(page, origin, `/api/workspaces/${workspaceId}/memberships`);
        expect(docThanhVien.status()).toBe(200);

        // Hoàn tất bài đã giao bằng cookie cũ: nộp -> chấm -> công bố đều thành công.
        const nopBai = await guiSampleAction(
          boiCanhStudent.page, origin, boiCanhStudent.csrf, workspaceId, {
            action: "SUBMIT_ACTIVITY",
            activityId,
            contentStub: "Bài nộp sau khi gói hết hạn",
          });
        expect(nopBai.status()).toBe(201);
        const nopBaiJson = (await nopBai.json()) as SampleActionTraLoi;
        expect(nopBaiJson.state).toBe("SUCCEEDED");
        expect(nopBaiJson.targetId).toBeTruthy();
        const resultId = seedKetQuaDraft(
          workspaceId, nopBaiJson.targetId!, student.appId, courseSubjectId);

        const cham = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "GRADE_RESULT",
            resultId,
            scoreStub: "9.0",
            commentStub: "Đạt",
          });
        expect(cham.status()).toBe(201);
        expect(((await cham.json()) as SampleActionTraLoi).state).toBe("SUCCEEDED");
        const congBo = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "PUBLISH_RESULT",
            resultId,
          });
        expect(congBo.status()).toBe(201);
        expect(((await congBo.json()) as SampleActionTraLoi).state).toBe("SUCCEEDED");

        // Phụ huynh đọc được kết quả đã công bố của con bằng cookie cũ sau hết hạn.
        const ketQua = await guiGet(
          boiCanhParent.page, origin,
          `/api/workspaces/${workspaceId}/students/${student.appId}/results`);
        expect(ketQua.status()).toBe(200);
        const ketQuaJson = (await ketQua.json()) as TrangDanhSach<KetQuaView>;
        expect(ketQuaJson.items.some((item) => item.id === resultId)).toBe(true);

        // Học sinh tự đọc được kết quả đã công bố của mình bằng cookie cũ sau hết hạn.
        const ketQuaCuaMinh = await guiGet(
          boiCanhStudent.page, origin,
          `/api/workspaces/${workspaceId}/students/${student.appId}/results`);
        expect(ketQuaCuaMinh.status()).toBe(200);
        const ketQuaCuaMinhJson = (await ketQuaCuaMinh.json()) as TrangDanhSach<KetQuaView>;
        expect(ketQuaCuaMinhJson.items.some((item) => item.id === resultId)).toBe(true);

        // Tăng thêm bằng cookie cũ bị chặn: gia hạn hạn nộp, mở thêm lượt, giao bài
        // mới và tạo lớp đều bị từ chối sau mốc hết hạn.
        const giaHan = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "EXTEND_DEADLINE",
            activityId,
            dueAt: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString(),
          });
        expect(giaHan.status()).toBe(403);
        const moLuot = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "OPEN_ATTEMPT",
            activityId,
            studentUserId: student.appId,
          });
        expect(moLuot.status()).toBe(403);
        const giaoMoi = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "ASSIGN_ACTIVITY",
            courseSubjectId,
            resourceId: taiNguyen.resourceId,
            versionId: taiNguyen.versionId,
            dueAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
            attemptLimit: 1,
          });
        expect(giaoMoi.status()).toBe(403);
        const taoLopSauHan = await guiMutation(page, origin, csrf, "POST",
          `/api/workspaces/${workspaceId}/classes`, { name: `lop-sau-han-${randomUUID().slice(0, 8)}` });
        expect(taoLopSauHan.status()).toBe(403);

        // Cùng phiên owner, giao diện trung tâm cũng phải báo chỉ đọc và vô hiệu
        // hóa nút tạo lớp, không chỉ dựa vào API 403.
        await page.goto(`${origin}/centers/${workspaceId}/classes`);
        await expect(page.getByRole("alert").filter({ hasText: /gói của trung tâm đã hết hạn/i }))
          .toBeVisible();
        await expect(page.getByRole("button", { name: "Tạo lớp", exact: true })).toBeDisabled();

        // Gia hạn: operator xác nhận kỳ mới thì quyền tăng thêm mở lại cho người còn
        // phân công hiệu lực — owner tạo lớp được, giáo viên giao bài mới được.
        const csrfOperator = await dangNhapLai(page, origin, operator);
        await xacNhanGoiCuaSo(
          page, origin, csrfOperator, workspaceId,
          new Date(), new Date(Date.now() + 90 * 24 * 60 * 60 * 1000));
        csrf = await dangNhapLai(page, origin, owner);
        await taoLop(page, origin, csrf, workspaceId);
        const giaoSauGiaHan = await guiSampleAction(
          boiCanhTeacher.page, origin, boiCanhTeacher.csrf, workspaceId, {
            action: "ASSIGN_ACTIVITY",
            courseSubjectId,
            resourceId: taiNguyen.resourceId,
            versionId: taiNguyen.versionId,
            dueAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
            attemptLimit: 1,
          });
        expect(giaoSauGiaHan.status()).toBe(201);
      } finally {
        await boiCanhStudent.page.context().close();
        await boiCanhTeacher.page.context().close();
        await boiCanhParent.page.context().close();
      }
    } finally {
      await xoaNguoiDung(env, daTao);
    }
  });

  test("Gói cá nhân hết hạn: chủ lớp giữ phiên cũ vẫn đọc được, tạo mới bị chặn tới khi operator gia hạn", async ({ page }) => {
    test.setTimeout(HAN_DUNG_CA_NHAN);
    const origin = gateOrigin();
    const env = new TestEnvironment();
    const daTao: NguoiDungTest[] = [];
    try {
      const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
      const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);

      let csrf = await dangNhapLai(page, origin, owner);
      const personal = await layWorkspaceCaNhan(page, origin);
      // Personal workspace chưa được xác nhận gói cũng phải chặn tạo lớp,
      // khác với trường hợp gói đã được kích hoạt rồi mới hết hạn ở dưới.
      expect(personal.subscription?.effective).toBeFalsy();
      const taoTruocKichHoat = await guiMutation(
        page, origin, csrf, "POST", `/api/workspaces/${personal.id}/classes`,
        { name: `personal-chua-kich-hoat-${randomUUID().slice(0, 8)}` });
      expect(taoTruocKichHoat.status()).toBe(403);
      const batDau = Date.now();
      const mocHetHan = Math.floor(batDau / 1000) * 1000 + 180_000;
      const csrfOperator = await dangNhapLai(page, origin, operator);
      await xacNhanGoiCuaSo(
        page, origin, csrfOperator, personal.id,
        new Date(batDau - 120_000), new Date(mocHetHan));

      // Chủ lớp tạo lớp trong lúc gói còn hiệu lực rồi giữ nguyên phiên qua mốc.
      csrf = await dangNhapLai(page, origin, owner);
      const { lopId } = await taoLop(page, origin, csrf, personal.id);
      expect(lopId).toBeTruthy();
      const browser = page.context().browser()!;
      const boiCanhOwner = await moBoiCanhTuPhienDaLuu(browser, origin, owner);
      try {
        await choQuaMocHetHan(mocHetHan);

        const docLop = await guiGet(
          boiCanhOwner.page, origin, `/api/workspaces/${personal.id}/classes`);
        expect(docLop.status()).toBe(200);
        const taoLopSauHan = await guiMutation(
          boiCanhOwner.page, origin, boiCanhOwner.csrf, "POST",
          `/api/workspaces/${personal.id}/classes`,
          { name: `lop-ca-nhan-sau-han-${randomUUID().slice(0, 8)}` });
        expect(taoLopSauHan.status()).toBe(403);
        const monHoc = await layDanhSachMon(boiCanhOwner.page, origin);
        expect(monHoc.length).toBeGreaterThan(0);
        const taoHocLieu = await guiMutation(
          boiCanhOwner.page, origin, boiCanhOwner.csrf, "POST",
          `/api/workspaces/${personal.id}/samples/actions`,
          {
            action: "CREATE_RESOURCE",
            subjectId: monHoc[0].id,
            kind: "QUESTION",
            label: `hoc-lieu-sau-han-${randomUUID().slice(0, 8)}`,
          });
        expect(taoHocLieu.status()).toBe(403);

        // Operator gia hạn thì chủ lớp tạo lớp lại được bằng đúng phiên cũ.
        const csrfOperatorMoi = await dangNhapLai(page, origin, operator);
        await xacNhanGoiCuaSo(
          page, origin, csrfOperatorMoi, personal.id,
          new Date(), new Date(Date.now() + 90 * 24 * 60 * 60 * 1000));
        const taoLai = await guiMutation(
          boiCanhOwner.page, origin, boiCanhOwner.csrf, "POST",
          `/api/workspaces/${personal.id}/classes`,
          { name: `lop-ca-nhan-sau-gia-han-${randomUUID().slice(0, 8)}` });
        expect(taoLai.status()).toBe(202);
        const traLoiTaoLai = (await taoLai.json()) as MutationTraLoi;
        await choOperationThanhCong(
          boiCanhOwner.page, origin, layOperationId(traLoiTaoLai), traLoiTaoLai.statusUrl);
      } finally {
        await boiCanhOwner.page.context().close();
      }
    } finally {
      await xoaNguoiDung(env, daTao);
    }
  });
});
