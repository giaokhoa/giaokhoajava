import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse, type Page } from "@playwright/test";
import {
  TestEnvironment,
  appRequestWhenReady,
  loginViaKeycloakForm,
  moBoiCanhDangNhap,
} from "./support/test-environment";

// E2E ma trận capability personal/center của US6 ở biên trình duyệt (FR-025–FR-028,
// acceptance US6.1–US6.5 của spec.md): gói cá nhân mở lớp/kho cá nhân theo owner+gói;
// membership/phân công center không mở quyền cá nhân và gói cá nhân không mở quyền
// center; operator xác nhận gói thủ công, không qua payment gateway. Spec viết trước
// implementation US6 (PersonalEntitlementProjector, PersonalClassService, trang
// /personal/classes). Trạng thái đã kiểm chứng trên main ae5c762 (lượt chạy đại diện
// trên stack TEST native): nhánh dương của US6.2 đã xanh — owner có gói tạo được
// lớp/khóa/roster/ghi danh và CREATE_RESOURCE trong personal workspace. Phần còn
// thiếu đúng phạm vi implementation US6: cửa gói trên đường giảng dạy personal chưa
// có (US6.1 đỏ có bằng chứng: chưa có gói vẫn nhận 202 khi tạo lớp cá nhân và lớp
// được ghi thật vào personal workspace, thay vì 403 như FR-025 yêu cầu) và giao diện
// /personal/classes + trạng thái gói ở /personal chưa có. Đỏ ở các điểm còn lại
// (luồng center, xác nhận gói, CREATE_RESOURCE) là lỗi thật, không phải test-first.
// Spec cần origin ứng dụng đang chạy (backend cùng stack TEST) qua
// NATIVE_GATE_APP_ORIGIN, cùng cơ chế với teaching-administration.spec.ts.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? "http://localhost:3001";

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

const HAN_DUNG_TEST = 300_000;
const HAN_DUNG_MA_TRAN = 600_000;

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
type EnrollmentEntry = { id: string; classStudentId?: string; studentUserId?: string };
type PracticeEntry = {
  id: string;
  attemptedAt: string;
  systemResourceId: string;
  versionId: string;
};

function gateOrigin(): string {
  if (!appOrigin) throw new Error("Cần NATIVE_GATE_APP_ORIGIN để chạy E2E US6 thật.");
  return appOrigin;
}

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
}

async function laySessionView(page: Page, origin: string): Promise<SessionView> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  return (await response.json()) as SessionView;
}

// Đăng nhập lại bằng tài khoản khác trên cùng trang: đổi phiên rồi lấy CSRF mới.
// GET /api/session đồng thời bootstrap tài khoản/personal workspace của actor.
async function dangNhapLai(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<Csrf> {
  await page.context().clearCookies();
  await dangNhapQuaUngDung(page, origin, user);
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  return session.csrf!;
}

// Mọi mutation đi qua helper chuẩn appRequestWhenReady (support/test-environment.ts):
// chỉ thử lại 503 AUTHORIZATION_NOT_READY tối đa 60 giây và giữ nguyên Idempotency-Key;
// 400/403/409 hay 503 mã khác trả về ngay cho caller khẳng định.
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

// GET được bảo vệ cũng có thể gặp 503 AUTHORIZATION_NOT_READY ở lần gọi đầu của actor
// mới (partition ACCOUNT còn PENDING); dùng cùng helper chuẩn như mutation.
async function guiGet(page: Page, origin: string, path: string): Promise<APIResponse> {
  return appRequestWhenReady(page, `${origin}${path}`);
}

function layOperationId(traLoi: MutationTraLoi): string {
  const operationId = traLoi.operationId ?? traLoi.operation?.id ?? traLoi.id;
  expect(typeof operationId).toBe("string");
  expect(operationId!.length).toBeGreaterThan(0);
  return operationId!;
}

// Chỉ SUCCEEDED/completed:true mới là hoàn tất nghiệp vụ; PENDING/FAILED không được tính.
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

async function layDanhSachWorkspaceCuaToi(page: Page, origin: string): Promise<WorkspaceView[]> {
  const response = await guiGet(page, origin, "/api/me/workspaces");
  expect(response.status()).toBe(200);
  const workspaces = (await response.json()) as WorkspaceView[];
  expect(Array.isArray(workspaces)).toBe(true);
  return workspaces;
}

// Personal workspace của chính actor đang đăng nhập: bootstrap tạo sẵn khi đăng nhập,
// không cần gói hay membership nào để tồn tại.
async function layWorkspaceCaNhan(page: Page, origin: string): Promise<WorkspaceView> {
  const workspaces = await layDanhSachWorkspaceCuaToi(page, origin);
  const personal = workspaces.find((item) => item.kind === "PERSONAL");
  expect(personal).toBeTruthy();
  return personal!;
}

async function taoCenter(
  page: Page,
  origin: string,
  csrf: Csrf,
  ownerId: string,
): Promise<{ workspaceId: string; tenCenter: string }> {
  const tenCenter = `center-e2e-${randomUUID().slice(0, 8)}`;
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

// Operator xác nhận gói thủ công cho một workspace bất kỳ (CENTER hoặc PERSONAL của
// người khác): đây là đường kích hoạt gói duy nhất theo hợp đồng, không có payment
// gateway và người dùng không tự kích hoạt được cho mình.
async function xacNhanGoi(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
): Promise<void> {
  const validFrom = new Date().toISOString();
  // Native time policy và SubscriptionService lưu mốc thời gian tới giây.
  const expiresAt = new Date(Math.floor(Date.now() / 1000) * 1000 + 90 * 24 * 60 * 60 * 1000).toISOString();
  const xacNhan = await guiMutation(page, origin, csrf, "POST", "/api/platform/subscriptions", {
    workspaceId,
    validFrom,
    expiresAt,
    confirmationRef: `xac-nhan-${randomUUID()}`,
  });
  expect(xacNhan.status()).toBe(202);
  const traLoi = (await xacNhan.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function kichHoatGoiCaNhan(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
  chuSoHuu: NguoiDungTest,
): Promise<{ personalId: string }> {
  const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
  await dangNhapLai(page, origin, chuSoHuu);
  const personal = await layWorkspaceCaNhan(page, origin);
  const csrfOperator = await dangNhapLai(page, origin, operator);
  await xacNhanGoi(page, origin, csrfOperator, personal.id);
  await dangNhapLai(page, origin, chuSoHuu);
  const sau = await layWorkspaceCaNhan(page, origin);
  expect(sau.subscription?.effective).toBeTruthy();
  expect(sau.subscription?.expiresAt).toBeTruthy();
  return { personalId: personal.id };
}

// Nhận token một lần từ response CREATE; không đọc token từ DB hoặc dựng token giả.
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
  // Người chấp nhận dùng context riêng, giữ nguyên phiên và CSRF của người mời.
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

// Dựng đủ chuỗi center đã xác nhận gói + manager (TEACHER+MANAGER) đã bổ nhiệm;
// trả phiên manager đang đăng nhập. Cùng mẫu center-administration.spec.ts.
async function dungCenterVoiManager(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
): Promise<{
  workspaceId: string;
  tenCenter: string;
  operator: NguoiDungTest;
  owner: NguoiDungTest;
  manager: NguoiDungTest;
  csrfManager: Csrf;
}> {
  const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
  const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
  const ungVien = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
  let csrf = await dangNhapLai(page, origin, operator);
  const { workspaceId, tenCenter } = await taoCenter(page, origin, csrf, owner.appId);
  await xacNhanGoi(page, origin, csrf, workspaceId);
  csrf = await dangNhapLai(page, origin, owner);
  const { membershipId } = await moiVaChapNhan(
    page, origin, csrf, workspaceId, ungVien, ["TEACHER"]);
  csrf = await dangNhapLai(page, origin, owner);
  const thanhVien = await layDanhSachThanhVien(page, origin, workspaceId);
  const membership = thanhVien.find((item) => item.id === membershipId);
  expect(membership).toBeTruthy();
  // Cập nhật quan hệ hiện có phải kèm If-Match là strong ETag của row version.
  const boNhiem = await guiMutation(page, origin, csrf, "PUT",
    `/api/workspaces/${workspaceId}/memberships/${membershipId}/roles`,
    { roles: ["TEACHER", "MANAGER"] },
    { headers: { "If-Match": `"${membership!.version}"` } });
  expect(boNhiem.status()).toBe(202);
  const traLoi = (await boNhiem.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const csrfManager = await dangNhapLai(page, origin, ungVien);
  return { workspaceId, tenCenter, operator, owner, manager: ungVien, csrfManager };
}

async function layDanhSachMon(page: Page, origin: string): Promise<SubjectView[]> {
  const response = await guiGet(page, origin, "/api/subjects");
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<SubjectView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

// Tạo lớp và chờ SUCCEEDED trong một workspace bất kỳ (CENTER hoặc PERSONAL):
// dùng cho nhánh được phép; nhánh bị từ chối gọi thẳng guiMutation ở test.
async function taoLop(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
): Promise<{ lopId: string; tenLop: string }> {
  const tenLop = `lop-e2e-${randomUUID().slice(0, 8)}`;
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
): Promise<{ khoaId: string; tenKhoa: string; monKhoa: MonTrongKhoa[] }> {
  const tenKhoa = `khoa-e2e-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/classes/${lopId}/courses`,
    { name: tenKhoa, subjectIds: monHoc.map((mon) => mon.id) });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const khoa = await layKhoaTheoLop(page, origin, workspaceId, lopId, tenKhoa);
  expect(khoa.classId).toBe(lopId);
  expect(khoa.subjects).toHaveLength(monHoc.length);
  return { khoaId: khoa.id, tenKhoa, monKhoa: khoa.subjects };
}

async function layKhoaTheoLop(
  page: Page,
  origin: string,
  workspaceId: string,
  lopId: string,
  tenKhoa: string,
): Promise<CourseView> {
  const danhSach = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/classes/${lopId}/courses`);
  expect(danhSach.status()).toBe(200);
  const khoaTomTat = ((await danhSach.json()) as TrangDanhSach<CourseView>).items
    .find((item) => item.name === tenKhoa);
  expect(khoaTomTat).toBeTruthy();
  const chiTiet = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/courses/${khoaTomTat!.id}`);
  expect(chiTiet.status()).toBe(200);
  return (await chiTiet.json()) as CourseView;
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

// Tìm dòng roster của người học theo user id (hoặc username khi DTO không mang user id).
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

async function layDanhSachGhiDanh(
  page: Page,
  origin: string,
  workspaceId: string,
  courseSubjectId: string,
): Promise<EnrollmentEntry[]> {
  const response = await guiGet(page, origin,
    `/api/workspaces/${workspaceId}/course-subjects/${courseSubjectId}/enrollments`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<EnrollmentEntry>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
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

// Tạo học liệu cá nhân qua sample action CREATE_RESOURCE không gắn môn-khóa:
// nhánh owner+gói của hợp đồng verification. Trả response thô để test khẳng định.
async function taoHocLieuCaNhan(
  page: Page,
  origin: string,
  csrf: Csrf,
  personalId: string,
  subjectId: string,
): Promise<APIResponse> {
  return guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${personalId}/samples/actions`,
    {
      action: "CREATE_RESOURCE",
      subjectId,
      kind: "QUESTION",
      label: `hoc-lieu-${randomUUID().slice(0, 8)}`,
    });
}

// Gửi một DELETE thu hồi; khẳng định 202 với completed:false (202 không phải thành
// công) và trả biên nhận để caller chờ SUCCEEDED rồi mới quan sát từ chối.
async function guiThuHoi(
  page: Page,
  origin: string,
  csrf: Csrf,
  path: string,
): Promise<MutationTraLoi> {
  const traLoi = await guiMutation(page, origin, csrf, "DELETE", path);
  expect(traLoi.status()).toBe(202);
  const receipt = (await traLoi.json()) as MutationTraLoi;
  expect(receipt.completed).toBe(false);
  expect(["PENDING", "APPLYING"]).toContain(receipt.state);
  expect(layOperationId(receipt)).toBeTruthy();
  return receipt;
}

async function docLichSuLuyenTap(page: Page, origin: string): Promise<PracticeEntry[]> {
  const response = await guiGet(page, origin, "/api/me/practice-history");
  expect(response.status()).toBe(200);
  const body = (await response.json()) as PracticeEntry[] | TrangDanhSach<PracticeEntry>;
  const items = Array.isArray(body) ? body : body.items;
  expect(Array.isArray(items)).toBe(true);
  return items;
}

// Seed một lần luyện tập trên resource HỆ THỐNG thẳng vào DB bằng psql, theo đúng mẫu
// SystemPracticeHistoryIT: tự tạo SYSTEM workspace + resource + version LOCKED của
// chính nó rồi ghi attempt cho actor (trigger DB khóa đúng các ràng buộc này). Cần
// E2E_SCHEMA và TEST_DATABASE_* của làn TEST; psql lấy từ PATH của harness.
function seedLichSuLuyenTap(
  personalId: string,
  userAppId: string,
  subjectId: string,
): { resourceId: string; versionId: string } {
  const schema = process.env.E2E_SCHEMA;
  const databaseUrl = process.env.TEST_DATABASE_URL;
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  if (!schema || !databaseUrl || !username || !password) {
    throw new Error("Seed lịch sử luyện tập cần E2E_SCHEMA và TEST_DATABASE_URL/USERNAME/PASSWORD");
  }
  const match = databaseUrl.match(/^jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/);
  const host = match?.[1];
  const database = match?.[3];
  if (!host || !database) {
    throw new Error(`TEST_DATABASE_URL không đúng dạng jdbc:postgresql://host:port/db: ${databaseUrl}`);
  }
  const port = match[2] ?? "5432";
  const resourceId = randomUUID();
  const versionId = randomUUID();
  const sql = `
SET search_path TO :schema;
BEGIN;
INSERT INTO workspace(id, kind, state) VALUES (:'system_ws', 'SYSTEM', 'ACTIVE');
INSERT INTO resource(id, owner_workspace_id, created_by_user_id, kind, subject_id, state)
VALUES (:'resource_id', :'system_ws', :'user_id', 'EXAM', :'subject_id', 'ACTIVE');
INSERT INTO resource_version(id, resource_id, version_no, label, body_stub, answer_stub, review_state, locked_at)
VALUES (:'version_id', :'resource_id', 1, 'Bản hệ thống', 'Nội dung', 'Đáp án', 'LOCKED', now());
INSERT INTO system_practice_attempt_sample(id, personal_workspace_id, user_id, system_resource_id, version_id, attempted_at, content_stub)
VALUES (:'attempt_id', :'personal_id', :'user_id', :'resource_id', :'version_id', now(), 'Bài làm mẫu');
COMMIT;
`;
  const ketQua = spawnSync("psql",
    [
      "-v", "ON_ERROR_STOP=1", "-X", "-q", "-f", "-",
      "-v", `schema=${schema}`,
      "-v", `system_ws=${randomUUID()}`,
      "-v", `resource_id=${resourceId}`,
      "-v", `version_id=${versionId}`,
      "-v", `attempt_id=${randomUUID()}`,
      "-v", `personal_id=${personalId}`,
      "-v", `user_id=${userAppId}`,
      "-v", `subject_id=${subjectId}`,
    ],
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
      encoding: "utf-8",
    });
  if (ketQua.error) {
    throw new Error(`Không chạy được psql để seed lịch sử luyện tập: ${ketQua.error.message}`);
  }
  if (ketQua.status !== 0) {
    throw new Error(`Seed lịch sử luyện tập thất bại (psql ${ketQua.status}): ${(ketQua.stderr ?? "").trim()}`);
  }
  return { resourceId, versionId };
}

test("US6.1 chưa có gói cá nhân: tạo lớp và học liệu cá nhân bị từ chối, lịch sử luyện tập hệ thống vẫn còn", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguoiDung = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const csrf = await dangNhapLai(page, origin, nguoiDung);
    const personal = await layWorkspaceCaNhan(page, origin);
    // Tiền đề của scenario: chưa có kỳ gói nào hiệu lực cho personal workspace.
    expect(personal.subscription?.effective).toBeFalsy();

    // Lịch sử làm đề hệ thống không cần gói: seed một attempt rồi đọc lại qua API.
    const monHoc = await layDanhSachMon(page, origin);
    expect(monHoc.length).toBeGreaterThanOrEqual(1);
    const seed = seedLichSuLuyenTap(personal.id, nguoiDung.appId, monHoc[0].id);
    const lichSu = await docLichSuLuyenTap(page, origin);
    expect(lichSu.some((item) => item.systemResourceId === seed.resourceId
      && item.versionId === seed.versionId)).toBe(true);

    // Chưa có gói: tạo lớp cá nhân bị từ chối (deny 403, không phải 202 chờ xử lý).
    const taoLop = await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${personal.id}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` });
    expect(taoLop.status()).toBe(403);

    // Chưa có gói: tạo học liệu cá nhân bị từ chối đích danh vì gói chưa hiệu lực.
    const taoHocLieu = await taoHocLieuCaNhan(page, origin, csrf, personal.id, monHoc[0].id);
    expect(taoHocLieu.status()).toBe(403);
    expect(((await taoHocLieu.json()) as { code?: string }).code).toBe("SUBSCRIPTION_INACTIVE");

    // Sau hai lần bị từ chối, lịch sử luyện tập vẫn đọc được nguyên vẹn.
    const lichSuSau = await docLichSuLuyenTap(page, origin);
    expect(lichSuSau.some((item) => item.systemResourceId === seed.resourceId)).toBe(true);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US6.2 operator kích hoạt gói cá nhân: owner tổ chức lớp/khóa/roster/ghi danh và tạo học liệu theo gói", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const chuSoHuu = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const nguoiHoc = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const { personalId } = await kichHoatGoiCaNhan(page, origin, env, daTao, chuSoHuu);

    const csrf = await dangNhapLai(page, origin, chuSoHuu);
    const monHoc = (await layDanhSachMon(page, origin)).slice(0, 2);
    expect(monHoc.length).toBeGreaterThanOrEqual(1);

    // Có gói hiệu lực: tạo học liệu cá nhân được phép, không cần xác minh nghề nghiệp.
    const taoHocLieu = await taoHocLieuCaNhan(page, origin, csrf, personalId, monHoc[0].id);
    expect(taoHocLieu.status()).toBe(201);

    // Owner tự tổ chức lớp/khóa trong personal workspace của mình.
    const { lopId } = await taoLop(page, origin, csrf, personalId);
    const { monKhoa } = await taoKhoa(page, origin, csrf, personalId, lopId, monHoc);

    // Người học personal chỉ cần danh tính hợp lệ: đưa vào roster bằng owner, không
    // qua membership center và không cần tài khoản do center cấp.
    await themNguoiHocVaoLop(page, origin, csrf, personalId, lopId, nguoiHoc.appId);
    const roster = await layRoster(page, origin, personalId, lopId);
    const dongRoster = timDongRoster(roster, nguoiHoc.appId, nguoiHoc.username);
    await ghiDanh(page, origin, csrf, personalId,
      [dongRoster.id], monKhoa.map((mon) => mon.id));
    const ghiDanhMon = await layDanhSachGhiDanh(page, origin, personalId, monKhoa[0].id);
    expect(ghiDanhMon.some((item) => item.classStudentId === dongRoster.id
      || item.studentUserId === nguoiHoc.appId)).toBe(true);

    // Lớp personal không sinh center_membership: route thành viên chỉ dành cho
    // workspace CENTER nên không liệt kê được người học personal như thành viên.
    const thanhVien = await guiGet(page, origin, `/api/workspaces/${personalId}/memberships`);
    expect([403, 404]).toContain(thanhVien.status());
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US6.3 teacher không gói cá nhân: làm việc theo phân công ở center, cá nhân vẫn bị từ chối", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVien = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    await moiVaChapNhan(page, origin, setup.csrfManager, setup.workspaceId, giaoVien, ["TEACHER"]);

    // Manager dựng lớp/khóa và phân công giáo viên vào một môn của khóa.
    const monHoc = (await layDanhSachMon(page, origin)).slice(0, 1);
    expect(monHoc.length).toBeGreaterThanOrEqual(1);
    const { lopId } = await taoLop(page, origin, setup.csrfManager, setup.workspaceId);
    const { monKhoa } = await taoKhoa(
      page, origin, setup.csrfManager, setup.workspaceId, lopId, monHoc);
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      monKhoa[0].id, [giaoVien.appId]);

    const csrfGiaoVien = await dangNhapLai(page, origin, giaoVien);
    const personal = await layWorkspaceCaNhan(page, origin);
    expect(personal.subscription?.effective).toBeFalsy();

    // Theo phân công hợp lệ tại center: đọc được ghi danh của môn được phân công.
    const ghiDanhMon = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${monKhoa[0].id}/enrollments`);
    expect(ghiDanhMon.status()).toBe(200);

    // Biên soạn theo phân công tại center được phép và tài nguyên thuộc center:
    // CREATE_RESOURCE gắn đúng môn-khóa được phân công trả 201.
    const soanTaiCenter = await guiMutation(page, origin, csrfGiaoVien, "POST",
      `/api/workspaces/${setup.workspaceId}/samples/actions`,
      {
        action: "CREATE_RESOURCE",
        courseSubjectId: monKhoa[0].id,
        subjectId: monKhoa[0].subjectId,
        kind: "QUESTION",
        label: `hoc-lieu-${randomUUID().slice(0, 8)}`,
      });
    expect(soanTaiCenter.status()).toBe(201);

    // Không gói cá nhân: lớp cá nhân và kho soạn cá nhân vẫn bị từ chối.
    const taoLopCaNhan = await guiMutation(page, origin, csrfGiaoVien, "POST",
      `/api/workspaces/${personal.id}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` });
    expect(taoLopCaNhan.status()).toBe(403);
    const hocLieuBiTuChoi = await taoHocLieuCaNhan(
      page, origin, csrfGiaoVien, personal.id, monHoc[0].id);
    expect(hocLieuBiTuChoi.status()).toBe(403);
    expect(((await hocLieuBiTuChoi.json()) as { code?: string }).code)
      .toBe("SUBSCRIPTION_INACTIVE");
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US6.4 có gói cá nhân nhưng không quản lý center: tạo lớp ở center bị từ chối, lớp cá nhân được phép", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVien = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    // Chỉ là TEACHER của center — hiện diện thật ở center nhưng không phải quản lý.
    await moiVaChapNhan(page, origin, setup.csrfManager, setup.workspaceId, giaoVien, ["TEACHER"]);
    const { personalId } = await kichHoatGoiCaNhan(page, origin, env, daTao, giaoVien);

    const csrf = await dangNhapLai(page, origin, giaoVien);
    // Gói cá nhân không thay quyền center: tạo lớp tại center vẫn bị từ chối.
    const taoLopCenter = await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${setup.workspaceId}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` });
    expect(taoLopCenter.status()).toBe(403);

    // Cùng actor, cùng gói: tạo lớp trong personal workspace của mình được phép.
    await taoLop(page, origin, csrf, personalId);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US6.5 rời center: quyền center chấm dứt, gói và lớp cá nhân còn hiệu lực", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_MA_TRAN);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const nguoiDung = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const { membershipId } = await moiVaChapNhan(
      page, origin, setup.csrfManager, setup.workspaceId, nguoiDung, ["TEACHER"]);
    const { personalId } = await kichHoatGoiCaNhan(page, origin, env, daTao, nguoiDung);

    // Phiên độc lập của người dùng: đang là thành viên center và có gói cá nhân.
    const { page: trangNguoiDung, csrf: csrfNguoiDung } =
      await moBoiCanhDangNhap(browser, origin, nguoiDung);
    expect((await guiGet(trangNguoiDung, origin,
      `/api/workspaces/${setup.workspaceId}/classes`)).status()).toBe(200);

    // Manager gỡ membership: rời center.
    const csrfManager = await dangNhapLai(page, origin, setup.manager);
    const thuHoi = await guiThuHoi(page, origin, csrfManager,
      `/api/workspaces/${setup.workspaceId}/memberships/${membershipId}`);
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);

    // Cùng phiên cũ, không đăng nhập lại: quyền theo center chấm dứt ngay.
    expect((await guiGet(trangNguoiDung, origin,
      `/api/workspaces/${setup.workspaceId}/classes`)).status()).toBe(403);
    expect((await guiGet(trangNguoiDung, origin,
      `/api/workspaces/${setup.workspaceId}/memberships`)).status()).toBe(403);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    expect(thanhVien.some((item) => item.user.id === nguoiDung.appId)).toBe(false);

    // Quyền cá nhân không bị chạm: gói vẫn hiệu lực, tạo học liệu và lớp cá nhân
    // vẫn được phép theo gói.
    const workspaces = await layDanhSachWorkspaceCuaToi(trangNguoiDung, origin);
    const personal = workspaces.find((item) => item.id === personalId);
    expect(personal?.subscription?.effective).toBeTruthy();
    const monHoc = (await layDanhSachMon(trangNguoiDung, origin)).slice(0, 1);
    expect(monHoc.length).toBeGreaterThanOrEqual(1);
    const taoHocLieu = await taoHocLieuCaNhan(
      trangNguoiDung, origin, csrfNguoiDung, personalId, monHoc[0].id);
    expect(taoHocLieu.status()).toBe(201);
    await taoLop(trangNguoiDung, origin, csrfNguoiDung, personalId);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("ma trận ba tài khoản: chỉ personal, chỉ center, có cả hai — quyền không rò rỉ giữa hai không gian", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_MA_TRAN);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const chiPersonal = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const chiCenter = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const caHai = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);

    // Center có sẵn lớp/khóa một môn để hai tài khoản center làm việc theo phân công.
    const monHoc = (await layDanhSachMon(page, origin)).slice(0, 1);
    expect(monHoc.length).toBeGreaterThanOrEqual(1);
    const { lopId } = await taoLop(page, origin, setup.csrfManager, setup.workspaceId);
    const { monKhoa } = await taoKhoa(
      page, origin, setup.csrfManager, setup.workspaceId, lopId, monHoc);
    await moiVaChapNhan(page, origin, setup.csrfManager, setup.workspaceId, chiCenter, ["TEACHER"]);
    await moiVaChapNhan(page, origin, setup.csrfManager, setup.workspaceId, caHai, ["TEACHER"]);
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      monKhoa[0].id, [chiCenter.appId, caHai.appId]);

    const { personalId: personalChiPersonal } =
      await kichHoatGoiCaNhan(page, origin, env, daTao, chiPersonal);
    const { personalId: personalCaHai } =
      await kichHoatGoiCaNhan(page, origin, env, daTao, caHai);

    // Tài khoản chỉ personal: lớp cá nhân được, chạm vào center thì bị từ chối
    // (người ngoài hoàn toàn: 403 deny hoặc 404 không tiết lộ tồn tại đều đúng hợp đồng).
    let csrf = await dangNhapLai(page, origin, chiPersonal);
    await taoLop(page, origin, csrf, personalChiPersonal);
    expect([403, 404]).toContain((await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${setup.workspaceId}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` })).status());
    expect([403, 404]).toContain((await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/classes`)).status());

    // Tài khoản chỉ center: làm việc theo phân công được, cá nhân bị từ chối cả hai.
    csrf = await dangNhapLai(page, origin, chiCenter);
    const personalChiCenter = await layWorkspaceCaNhan(page, origin);
    expect((await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${monKhoa[0].id}/enrollments`))
      .status()).toBe(200);
    expect((await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${personalChiCenter.id}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` })).status()).toBe(403);
    expect((await taoHocLieuCaNhan(page, origin, csrf, personalChiCenter.id, monHoc[0].id))
      .status()).toBe(403);

    // Tài khoản có cả hai: mỗi không gian dùng đúng nguồn quyền của nó.
    csrf = await dangNhapLai(page, origin, caHai);
    await taoLop(page, origin, csrf, personalCaHai);
    expect((await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${monKhoa[0].id}/enrollments`))
      .status()).toBe(200);
    // Có cả hai vẫn không thành quản lý center: tạo lớp ở center bị từ chối.
    expect((await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${setup.workspaceId}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` })).status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("giao diện cá nhân: trang lớp cá nhân hiện lớp theo gói; trang cá nhân hiện trạng thái gói và không hiện editor học liệu", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const chuSoHuu = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const { personalId } = await kichHoatGoiCaNhan(page, origin, env, daTao, chuSoHuu);
    const csrf = await dangNhapLai(page, origin, chuSoHuu);
    const { tenLop } = await taoLop(page, origin, csrf, personalId);

    // Trang lớp cá nhân (route theo hợp đồng UI) liệt kê lớp của owner theo gói.
    await page.goto(`${frontendOrigin}/personal/classes`);
    await expect(page.getByRole("heading", { name: /lớp/i }).first()).toBeVisible();
    await expect(page.getByText(tenLop, { exact: true }).first()).toBeVisible();

    // Trang cá nhân hiện trạng thái gói của workspace; tuyệt đối không giả đã có
    // trình soạn học liệu: không nút/đường dẫn nào mở editor soạn học liệu.
    await page.goto(`${frontendOrigin}/personal`);
    await expect(page.getByRole("heading", { name: /gói/i }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: /soạn|editor/i })).toHaveCount(0);
    await expect(page.getByRole("link", { name: /soạn|editor/i })).toHaveCount(0);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
