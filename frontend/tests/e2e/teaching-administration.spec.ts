import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse, type Browser, type Page } from "@playwright/test";
import {
  TestEnvironment, loginViaKeycloakForm, appRequestWhenReady,
  luuPhienHienTai, khoiPhucPhienDaLuu, DEFAULT_FRONTEND_ORIGIN,
} from "./support/test-environment";

// E2E quản trị giảng dạy ở biên trình duyệt: tạo lớp/khóa nhiều môn, chọn môn ghi danh,
// nhiều giáo viên cùng môn, chủ nhiệm, và actor chuyển lớp/môn không lộ dữ liệu.
// Nguồn contract: FR-013–FR-021 (spec.md), hợp đồng HTTP (Lớp, khóa, môn và ghi danh)
// và hợp đồng quyết định quyền (mục 4, bảng nguồn theo môn–khóa/chủ nhiệm).
// Spec cần origin ứng dụng đang chạy (backend cùng stack TEST) qua
// NATIVE_GATE_APP_ORIGIN; thiếu biến là lỗi cấu hình, không được dùng skip thay cho nghiệm thu.
//
// Các helper dựng center/owner/manager dùng phiên thật và ID directory của ứng dụng.
//
// Lưu ý kiểm chứng:
// - Ghi danh/phân công chỉ dùng môn HIỆN CÓ lúc thao tác: thêm môn vào khóa sau không tự
//   tạo ghi danh hay phân công (FR-015); test 3 khẳng định trực tiếp điều này.
// - Kiểm tra giao/chấm/xem đáp án của chủ nhiệm thuộc bề mặt sample action
//   (ASSIGN_ACTIVITY, GRADE_RESULT), không thuộc quản trị lớp, nên spec này chưa chạm tới.
// - Mã lỗi vi phạm invariant chéo lớp/workspace ở test 7 lấy theo quy tắc lỗi chung của
//   hợp đồng HTTP (field violations → 400); xem Sổ điểm chưa chắc chắn.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? DEFAULT_FRONTEND_ORIGIN;

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

const HAN_DUNG_TEST = 300_000;

type NguoiDungTest = Awaited<ReturnType<TestEnvironment["createUser"]>>;
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
  handoff?: { username?: string; temporaryPassword?: string };
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
type ClassView = { id: string; name: string };
type MonTrongKhoa = { id: string; subjectId: string; name?: string };
type CourseView = { id: string; classId: string; name: string; subjects: MonTrongKhoa[] };
type SubjectView = { id: string; code?: string; name?: string };
type RosterEntry = {
  id: string;
  studentUserId?: string;
  user?: { id?: string; username?: string };
};
type EnrollmentEntry = { id: string; classStudentId?: string };

function gateOrigin(): string {
  if (!appOrigin) throw new Error("Cần NATIVE_GATE_APP_ORIGIN để chạy E2E giảng dạy thật.");
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

// Lần mutation được bảo vệ đầu tiên của actor mới trả 503 AUTHORIZATION_NOT_READY:
// partition ACCOUNT mới bootstrap ở PENDING, ProtectedActionExecutor đồng bộ eligibility
// rồi fence chặn cho tới khi worker chiếu xong. Chỉ thử lại đúng mã này, tối đa 60 giây;
// 503 xảy ra trước khi endpoint ghi operation của nó nên key chưa từng được ghi — giữ
// nguyên key qua mọi lần thử để lần retry tường minh sau đó vẫn tra đúng operation;
// tuyệt đối không thử lại 400/403/409 hay 503 với mã khác (vd: PDP lỗi).

// Lời gọi có fence này đồng bộ eligibility của actor (mẫu d7ade21 bên Java):
// không có nó, lời gọi platform đầu tiên của operator mới bị PDP chặn 403 vì
// leaf eligible chưa đồng bộ. Phiên thật luôn nạp workspace trước khi làm việc.
async function dongBoEligibility(page: Page, origin: string): Promise<void> {
  const response = await appRequestWhenReady(page, `${origin}/api/me/workspaces`);
  expect(response.status()).toBe(200);
}

// Đăng nhập lại bằng tài khoản khác trên cùng trang: đổi phiên rồi lấy CSRF mới.
// GET /api/session đồng thời bootstrap tài khoản/personal workspace của actor.
// Nếu actor đã có phiên lưu từ lần đăng nhập form thật trong cùng lượt chạy thì
// khôi phục storageState thay vì qua form Keycloak lần nữa; phiên lưu không còn
// hiệu lực thì rơi xuống đăng nhập form thật.
async function dangNhapLayId(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<{ csrf: Csrf; userId: string }> {
  if (await khoiPhucPhienDaLuu(page, user.username)) {
    const session = await laySessionView(page, origin);
    if (session.authenticated && session.csrf?.token && session.user?.id) {
      await dongBoEligibility(page, origin);
      return { csrf: session.csrf, userId: session.user.id };
    }
  }
  await page.context().clearCookies();
  await dangNhapQuaUngDung(page, origin, user);
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  // user.id là id NỘI BỘ của user_account (UUID random), KHÔNG phải Keycloak sub:
  // IdentityService.bootstrap tạo id mới, chỉ lưu sub ở cột keycloak_sub; mọi
  // endpoint nhận user id (vd: ownerUserId khi tạo center) đều dùng id nội bộ này.
  expect(session.user?.id).toBeTruthy();
  await dongBoEligibility(page, origin);
  return { csrf: session.csrf!, userId: session.user!.id! };
}

async function dangNhapLai(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<Csrf> {
  return (await dangNhapLayId(page, origin, user)).csrf;
}

async function guiMutation(
  page: Page,
  origin: string,
  csrf: Csrf,
  method: "POST" | "PUT",
  path: string,
  body?: Record<string, unknown>,
  tuyChon?: { idempotencyKey?: string; headers?: Record<string, string> },
): Promise<APIResponse> {
  return appRequestWhenReady(page, `${origin}${path}`, {
    method, data: body,
    headers: {
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": tuyChon?.idempotencyKey ?? randomUUID(),
      ...(tuyChon?.headers ?? {}),
    },
  });
}
// GET được bảo vệ cũng có thể gặp 503 AUTHORIZATION_NOT_READY ở lần gọi đầu của actor
// mới (partition ACCOUNT còn PENDING); thử lại đúng mã này như mutation.
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
  env: TestEnvironment,
  nhom: string | null,
  daTao: NguoiDungTest[],
): Promise<NguoiDungTest> {
  const user = await env.createUser();
  daTao.push(user);
  if (nhom) await env.addToRealmGroup(user, nhom);
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
    const body = (await response.json()) as { items: CenterView[]; nextCursor?: string };
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

async function taoCenter(
  page: Page,
  origin: string,
  csrf: Csrf,
  ownerAppId: string,
): Promise<{ workspaceId: string; tenCenter: string }> {
  const tenCenter = `center-e2e-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST", "/api/platform/centers", {
    name: tenCenter,
    ownerUserId: ownerAppId,
  });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.completed).toBe(false);
  expect(["PENDING", "APPLYING"]).toContain(traLoi.state);
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const centers = await layDanhSachCenter(page, origin);
  const center = centers.find((item) => item.name === tenCenter);
  expect(center).toBeTruthy();
  // Center có id riêng và workspaceId riêng: xác nhận gói dùng workspaceId.
  return { workspaceId: center!.workspaceId, tenCenter };
}

async function xacNhanGoi(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
): Promise<void> {
  const validFrom = new Date().toISOString();
  const expiresAt = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString();
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

// Token chấp nhận một lần chỉ có trong response CREATE của lời mời
// (InvitationCreatedView.invitationToken); hệ thống không gửi mail mời.
async function moiVaChapNhan(
  page: Page,
  origin: string,
  csrfNguoiMoi: Csrf,
  workspaceId: string,
  nguoiDuocMoi: NguoiDungTest,
  roles: string[],
): Promise<{ membershipId: string; userAppId: string }> {
  const moi = await guiMutation(page, origin, csrfNguoiMoi, "POST",
    `/api/workspaces/${workspaceId}/invitations`, { email: nguoiDuocMoi.email, roles });
  expect(moi.status()).toBe(202);
  const traLoiMoi = (await moi.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoiMoi), traLoiMoi.statusUrl);
  const token = traLoiMoi.invitationToken ?? "";
  expect(token).toBeTruthy();

  // Người chấp nhận dùng context riêng, giữ nguyên phiên và CSRF của người mời.
  const context = await page.context().browser()!.newContext();
  try {
    const acceptPage = await context.newPage();
    const csrfNguoiDuocMoi = await dangNhapLai(acceptPage, origin, nguoiDuocMoi);
    const chapNhan = await guiMutation(acceptPage, origin, csrfNguoiDuocMoi, "POST",
      `/api/invitations/${token}/accept`);
    expect(chapNhan.status()).toBe(202);
    const traLoiChapNhan = (await chapNhan.json()) as MutationTraLoi;
    await choOperationThanhCong(
      acceptPage, origin, layOperationId(traLoiChapNhan), traLoiChapNhan.statusUrl);

    const thanhVien = await layDanhSachThanhVien(acceptPage, origin, workspaceId);
    // Khớp theo username: membership user.id là id NỘI BỘ của user_account,
    // không phải Keycloak sub của người được mời.
    const membership = thanhVien.find((item) => item.user.username === nguoiDuocMoi.username);
    expect(membership).toBeTruthy();
    return { membershipId: membership!.id, userAppId: membership!.user.id };
  } finally { await context.close(); }
}

// Dựng đủ chuỗi center đã xác nhận gói + manager đã bổ nhiệm; trả phiên manager đang đăng nhập.
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
  managerMembershipId: string;
  csrfManager: Csrf;
}> {
  const operator = await taoNguoiDung(env, NHOM_OPERATOR, daTao);
  const owner = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const ungVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  // Owner đăng nhập trước để bootstrap user_account; taoCenter nhận ownerUserId là
  // id nội bộ lấy từ /api/session, không phải Keycloak sub (nếu không sẽ 404
  // ACCOUNT_NOT_FOUND). dangNhapLai của operator đồng bộ eligibility qua fence
  // GET /api/me/workspaces trước lời gọi platform đầu tiên.
  const { userId: ownerAppId } = await dangNhapLayId(page, origin, owner);
  let csrf = await dangNhapLai(page, origin, operator);
  const { workspaceId, tenCenter } = await taoCenter(page, origin, csrf, ownerAppId);
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
  return {
    workspaceId, tenCenter, operator, owner, manager: ungVien,
    managerMembershipId: membershipId, csrfManager,
  };
}

async function capStudent(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  khoaIdempotency = randomUUID(),
): Promise<{ username: string; temporaryPassword: string; operationId: string }> {
  const username = `hs-${randomUUID().slice(0, 8)}`;
  const tao = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/students`,
    { username, displayName: "Học sinh E2E" },
    { idempotencyKey: khoaIdempotency });
  expect(tao.status()).toBe(202);
  const traLoi = (await tao.json()) as MutationTraLoi;
  expect(traLoi.operation?.completed).toBe(false);
  expect(traLoi.handoff?.username).toBe(username);
  const matKhauTam = traLoi.handoff?.temporaryPassword ?? "";
  expect(matKhauTam.length).toBeGreaterThan(0);
  expect(matKhauTam).not.toBe(username);
  return { username, temporaryPassword: matKhauTam, operationId: layOperationId(traLoi) };
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

async function themHocSinhVaoLop(
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

// Tìm dòng roster của học sinh theo user id (hoặc username khi DTO không mang user id).
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

async function phanCongChuNhiem(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  lopId: string,
  teacherUserId: string,
): Promise<void> {
  const phanCong = await guiMutation(page, origin, csrf, "POST",
    `/api/workspaces/${workspaceId}/classes/${lopId}/homeroom-assignments`,
    { teacherUserId });
  expect(phanCong.status()).toBe(202);
  const traLoi = (await phanCong.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

// Học sinh cấp bằng capStudent chỉ có mật khẩu tạm: đổi mật khẩu ở lần đăng nhập đầu
// (required action UPDATE_PASSWORD) rồi mới có phiên dùng được cho API.
async function dangNhapHocSinhMoi(
  browser: Browser,
  origin: string,
  username: string,
  matKhauTam: string,
): Promise<{ page: Page; csrf: Csrf }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const matKhauMoi = `${matKhauTam}Moi1!`;
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, {
    username,
    password: matKhauTam,
  });
  await page.locator("#password-new").fill(matKhauMoi);
  await page.locator("#password-confirm").fill(matKhauMoi);
  await page.locator("#kc-submit").click();
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  await dongBoEligibility(page, origin);
  return { page, csrf: session.csrf! };
}

// Dựng center + lớp L1 + khóa K ba môn; trả đủ id để test đi thẳng vào hành vi giảng dạy.
async function dungCenterLopKhoa(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
  soMon = 3,
): Promise<{
  workspaceId: string;
  tenCenter: string;
  operator: NguoiDungTest;
  owner: NguoiDungTest;
  manager: NguoiDungTest;
  csrfManager: Csrf;
  lopId: string;
  tenLop: string;
  khoaId: string;
  tenKhoa: string;
  monKhoa: MonTrongKhoa[];
}> {
  const setup = await dungCenterVoiManager(page, origin, env, daTao);
  const monHoc = (await layDanhSachMon(page, origin)).slice(0, soMon);
  expect(monHoc.length).toBeGreaterThanOrEqual(soMon);
  const { lopId, tenLop } = await taoLop(
    page, origin, setup.csrfManager, setup.workspaceId);
  const { khoaId, tenKhoa, monKhoa } = await taoKhoa(
    page, origin, setup.csrfManager, setup.workspaceId, lopId, monHoc);
  return { ...setup, lopId, tenLop, khoaId, tenKhoa, monKhoa };
}

test("owner tạo lớp L1 và khóa K ba môn qua giao diện lớp học: thao tác hoàn tất trên UI, danh sách hiện đủ", { tag: ["@layer-e2e", "@flow-QL-02", "@role-center-owner"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const monHoc = (await layDanhSachMon(page, origin)).slice(0, 3);
    expect(monHoc).toHaveLength(3);

    // Thao tác được kiểm thử đi qua UI như owner thật: tạo lớp bằng form "Tạo lớp
    // mới", mở lớp vừa tạo rồi tạo khóa bằng form "Tạo khóa mới" với ba môn được
    // chọn; thao tác chỉ tính là xong khi UI báo hoàn tất và khóa hiện trên trang.
    // Hợp đồng 202/poll của các endpoint lớp/khóa do TeachingStructureIT khóa ở
    // tầng backend.
    await dangNhapLai(page, origin, setup.owner);
    const tenLop = `lop-e2e-${randomUUID().slice(0, 8)}`;
    const tenKhoa = `khoa-e2e-${randomUUID().slice(0, 8)}`;
    await page.goto(`${frontendOrigin}/centers/${setup.workspaceId}/classes`);
    await page.locator("#class-name").fill(tenLop);
    await page.getByRole("button", { name: "Tạo lớp" }).click();
    await expect(page.getByText("Thao tác đã hoàn tất.")).toBeVisible({ timeout: 60_000 });
    await page.getByRole("button", { name: tenLop, exact: true }).click();
    await page.locator("#course-name").fill(tenKhoa);
    for (const mon of monHoc) {
      expect(mon.name).toBeTruthy();
      expect(mon.code).toBeTruthy();
      await page.getByLabel(`${mon.name} (${mon.code})`).check();
    }
    await page.getByRole("button", { name: "Tạo khóa" }).click();
    await expect(page.getByRole("heading", { name: `Khóa: ${tenKhoa}`, exact: true }))
      .toBeVisible({ timeout: 60_000 });

    // Khẳng định phụ về dữ liệu qua API: danh sách lớp hiện L1; khóa thuộc đúng
    // lớp đó và mang đúng ba môn đã chọn trên UI.
    const danhSachLop = await guiGet(page, origin, `/api/workspaces/${setup.workspaceId}/classes`);
    expect(danhSachLop.status()).toBe(200);
    const lop = ((await danhSachLop.json()) as TrangDanhSach<ClassView>).items
      .find((item) => item.name === tenLop);
    expect(lop).toBeTruthy();

    const khoa = await layKhoaTheoLop(page, origin, setup.workspaceId, lop!.id, tenKhoa);
    expect(khoa.classId).toBe(lop!.id);
    expect(khoa.subjects).toHaveLength(3);
    expect(new Set(khoa.subjects.map((mon) => mon.subjectId)))
      .toEqual(new Set(monHoc.map((mon) => mon.id)));
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("manager ghi danh đủ ba môn rồi hoàn thành nguyên khóa qua UI, không hồi quyền học tập", { tag: ["@layer-e2e", "@flow-QL-03", "@flow-QL-06", "@flow-HS-03", "@role-center-manager"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    const { lopId: lop2Id } = await taoLop(
      page, origin, setup.csrfManager, setup.workspaceId);
    expect(lop2Id).not.toBe(setup.lopId);

    const { username, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    const hocSinh = thanhVien.find((item) => item.user.username === username);
    expect(hocSinh).toBeTruthy();
    expect(hocSinh!.roles).toContain("STUDENT");

    await themHocSinhVaoLop(
      page, origin, setup.csrfManager, setup.workspaceId, setup.lopId, hocSinh!.user.id);
    const roster = await layRoster(page, origin, setup.workspaceId, setup.lopId);
    const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);

    // Chỉ ghi danh môn đầu: hai môn còn lại không có học sinh này.
    const [mon1, mon2, mon3] = setup.monKhoa;
    await ghiDanh(page, origin, setup.csrfManager, setup.workspaceId,
      [dongRoster.id], [mon1.id]);
    const ghiDanhMon1 = await layDanhSachGhiDanh(
      page, origin, setup.workspaceId, mon1.id);
    expect(ghiDanhMon1.some((item) => item.classStudentId === dongRoster.id)).toBe(true);
    expect(await layDanhSachGhiDanh(page, origin, setup.workspaceId, mon2.id)).toHaveLength(0);
    expect(await layDanhSachGhiDanh(page, origin, setup.workspaceId, mon3.id)).toHaveLength(0);

    // Ghi danh thêm hai môn còn lại: cả ba roster môn đều có học sinh.
    await ghiDanh(page, origin, setup.csrfManager, setup.workspaceId,
      [dongRoster.id], [mon2.id, mon3.id]);
    for (const mon of [mon2, mon3]) {
      const danhSach = await layDanhSachGhiDanh(page, origin, setup.workspaceId, mon.id);
      expect(danhSach.some((item) => item.classStudentId === dongRoster.id)).toBe(true);
    }

    // QL-06: quản lý dùng nút trên frontend thật, frontend tải snapshot cả ba môn.
    await page.goto(`${frontendOrigin}/centers/${setup.workspaceId}/classes`);
    await page.getByRole("button", { name: setup.tenLop, exact: true }).click();
    await expect(page.getByRole("heading", { name: `Khóa: ${setup.tenKhoa}`, exact: true }))
      .toBeVisible();
    await page.getByRole("button", { name: "Ghi danh và phân công", exact: true }).first().click();
    const completion = page.getByRole("button", { name: new RegExp(`Hoàn thành khóa cho ${username}`) });
    await expect(completion).toBeVisible();
    await completion.click();
    await expect(page.getByText("Thao tác đã hoàn tất.")).toBeVisible({ timeout: 60_000 });
    // Từ cùng phiên manager: ba ghi danh không còn là nguồn nộp/roster hiện hành.
    for (const mon of [mon1, mon2, mon3]) {
      expect(await layDanhSachGhiDanh(page, origin, setup.workspaceId, mon.id)).toHaveLength(0);
    }
    // Không coi COMPLETED là REVOKED: backend từ chối tái ghi danh vào chính khóa đó.
    const tryReenroll = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/enrollments`,
      { classStudentIds: [dongRoster.id], courseSubjectIds: [mon1.id] });
    expect(tryReenroll.status()).toBe(409);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("thêm môn vào khóa sau không tự ghi danh/phân công", { tag: ["@layer-e2e", "@flow-QL-02", "@flow-QL-03", "@role-center-manager", "@role-subject-teacher"] }, async ({ page }) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 2);
    const [mon1, mon2] = setup.monKhoa;
    const monThuBa = (await layDanhSachMon(page, origin))
      .find((mon) => !setup.monKhoa.some((daCo) => daCo.subjectId === mon.id));
    expect(monThuBa).toBeTruthy();

    const { username, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    const hocSinh = thanhVien.find((item) => item.user.username === username);
    expect(hocSinh).toBeTruthy();
    await themHocSinhVaoLop(
      page, origin, setup.csrfManager, setup.workspaceId, setup.lopId, hocSinh!.user.id);
    const roster = await layRoster(page, origin, setup.workspaceId, setup.lopId);
    const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);
    await ghiDanh(page, origin, setup.csrfManager, setup.workspaceId,
      [dongRoster.id], [mon1.id, mon2.id]);

    // Thêm môn thứ ba vào khóa đã có ghi danh: không sinh ghi danh mới.
    const themMon = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/courses/${setup.khoaId}/subjects`,
      { subjectIds: [monThuBa!.id] });
    expect(themMon.status()).toBe(202);
    const traLoi = (await themMon.json()) as MutationTraLoi;
    await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
    const khoaSau = await layKhoaTheoLop(
      page, origin, setup.workspaceId, setup.lopId, setup.tenKhoa);
    expect(khoaSau.subjects).toHaveLength(3);
    const monMoi = khoaSau.subjects.find((mon) => mon.subjectId === monThuBa!.id);
    expect(monMoi).toBeTruthy();
    expect(await layDanhSachGhiDanh(page, origin, setup.workspaceId, monMoi!.id))
      .toHaveLength(0);

    // Cũng không tự phân công giáo viên môn cũ sang môn mới: giáo viên của môn 1
    // gọi roster môn mới bị từ chối.
    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId } = await moiVaChapNhan(page, origin, setup.csrfManager,
      setup.workspaceId, giaoVien, ["TEACHER"]);
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      mon1.id, [userAppId]);
    await dangNhapLai(page, origin, giaoVien);
    const rosterMonMoi = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${monMoi!.id}/enrollments`);
    expect(rosterMonMoi.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("nhiều giáo viên cùng môn: cả hai đều thấy roster môn, giáo viên môn khác thì 403", { tag: ["@layer-e2e", "@flow-QL-03", "@role-center-manager", "@role-subject-teacher"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    const [mon1, mon2] = setup.monKhoa;
    const giaoVienA = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const giaoVienB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const giaoVienC = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId: idGiaoVienA } = await moiVaChapNhan(page, origin,
      setup.csrfManager, setup.workspaceId, giaoVienA, ["TEACHER"]);
    const { userAppId: idGiaoVienB } = await moiVaChapNhan(page, origin,
      setup.csrfManager, setup.workspaceId, giaoVienB, ["TEACHER"]);
    const { userAppId: idGiaoVienC } = await moiVaChapNhan(page, origin,
      setup.csrfManager, setup.workspaceId, giaoVienC, ["TEACHER"]);

    const { username, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    const hocSinh = thanhVien.find((item) => item.user.username === username);
    expect(hocSinh).toBeTruthy();
    await themHocSinhVaoLop(
      page, origin, setup.csrfManager, setup.workspaceId, setup.lopId, hocSinh!.user.id);
    const roster = await layRoster(page, origin, setup.workspaceId, setup.lopId);
    const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);
    await ghiDanh(page, origin, setup.csrfManager, setup.workspaceId,
      [dongRoster.id], [mon1.id]);

    // Một lần phân công nhận nhiều giáo viên cho cùng môn–khóa.
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      mon1.id, [idGiaoVienA, idGiaoVienB]);
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      mon2.id, [idGiaoVienC]);

    for (const giaoVien of [giaoVienA, giaoVienB]) {
      await dangNhapLai(page, origin, giaoVien);
      const danhSach = await layDanhSachGhiDanh(page, origin, setup.workspaceId, mon1.id);
      expect(danhSach.some((item) => item.classStudentId === dongRoster.id)).toBe(true);
    }
    await dangNhapLai(page, origin, giaoVienC);
    const biCam = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/enrollments`);
    expect(biCam.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("chủ nhiệm: thấy roster lớp, không được phân công giáo viên hay tạo lớp", { tag: ["@layer-e2e", "@flow-CN-01", "@flow-QL-03", "@flow-QL-02", "@role-homeroom"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    const [mon1] = setup.monKhoa;
    const chuNhiem = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId } = await moiVaChapNhan(page, origin, setup.csrfManager,
      setup.workspaceId, chuNhiem, ["TEACHER"]);
    await phanCongChuNhiem(page, origin, setup.csrfManager, setup.workspaceId,
      setup.lopId, userAppId);

    const csrfChuNhiem = await dangNhapLai(page, origin, chuNhiem);
    const roster = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/classes/${setup.lopId}/students`);
    expect(roster.status()).toBe(200);

    const phanCong = await guiMutation(page, origin, csrfChuNhiem, "POST",
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/teachers`,
      { teacherUserIds: [userAppId] });
    expect(phanCong.status()).toBe(403);
    const taoLopMoi = await guiMutation(page, origin, csrfChuNhiem, "POST",
      `/api/workspaces/${setup.workspaceId}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` });
    expect(taoLopMoi.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("actor chuyển lớp/môn không lộ dữ liệu: giáo viên L1 và học sinh L1 không thấy L2", { tag: ["@layer-e2e", "@flow-QL-03", "@flow-HS-02", "@role-subject-teacher", "@role-student"] }, async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    const [mon1L1, mon2L1] = setup.monKhoa;
    const { lopId: lop2Id } = await taoLop(
      page, origin, setup.csrfManager, setup.workspaceId);
    const { monKhoa: monKhoa2 } = await taoKhoa(
      page, origin, setup.csrfManager, setup.workspaceId, lop2Id,
      (await layDanhSachMon(page, origin)).slice(0, 3));
    const [mon1L2] = monKhoa2;

    const { username, temporaryPassword, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    const hocSinh = thanhVien.find((item) => item.user.username === username);
    expect(hocSinh).toBeTruthy();
    await themHocSinhVaoLop(
      page, origin, setup.csrfManager, setup.workspaceId, setup.lopId, hocSinh!.user.id);
    const roster = await layRoster(page, origin, setup.workspaceId, setup.lopId);
    const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);
    await ghiDanh(page, origin, setup.csrfManager, setup.workspaceId,
      [dongRoster.id], [mon1L1.id]);

    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId } = await moiVaChapNhan(page, origin, setup.csrfManager,
      setup.workspaceId, giaoVien, ["TEACHER"]);
    await phanCongGiaoVien(page, origin, setup.csrfManager, setup.workspaceId,
      mon1L1.id, [userAppId]);

    // Giáo viên chỉ được phân công ở L1–K1–môn 1: mọi lối sang L2 hoặc môn khác đều chặn.
    await dangNhapLai(page, origin, giaoVien);
    const lop2 = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/classes/${lop2Id}`);
    expect(lop2.status()).toBe(404);
    const rosterL2 = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/classes/${lop2Id}/students`);
    expect(rosterL2.status()).toBe(403);
    const ghiDanhL2 = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1L2.id}/enrollments`);
    expect(ghiDanhL2.status()).toBe(403);
    const ghiDanhMonKhac = await guiGet(page, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon2L1.id}/enrollments`);
    expect(ghiDanhMonKhac.status()).toBe(403);

    // Học sinh L1 cũng vậy: không thấy roster môn chưa ghi danh, không thấy lớp L2.
    const { page: trangHocSinh } = await dangNhapHocSinhMoi(
      browser, origin, username, temporaryPassword);
    const ghiDanhMonChuaDangKy = await trangHocSinh.request.get(
      `${origin}/api/workspaces/${setup.workspaceId}/course-subjects/${mon2L1.id}/enrollments`);
    expect(ghiDanhMonChuaDangKy.status()).toBe(403);
    const lop2CuaHocSinh = await trangHocSinh.request.get(
      `${origin}/api/workspaces/${setup.workspaceId}/classes/${lop2Id}`);
    expect(lop2CuaHocSinh.status()).toBe(404);
    const danhSachLop = await trangHocSinh.request.get(
      `${origin}/api/workspaces/${setup.workspaceId}/classes`);
    expect([200, 403]).toContain(danhSachLop.status());
    if (danhSachLop.status() === 200) {
      const lop = ((await danhSachLop.json()) as TrangDanhSach<ClassView>).items;
      expect(lop.some((item) => item.id === lop2Id)).toBe(false);
    }
    await trangHocSinh.context().close();
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("ghi danh chéo lớp và chéo workspace bị từ chối 400", { tag: ["@layer-e2e", "@flow-QL-03", "@role-center-manager"] }, async ({ page }) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    const { lopId: lop2Id } = await taoLop(
      page, origin, setup.csrfManager, setup.workspaceId);
    const { monKhoa: monKhoa2 } = await taoKhoa(
      page, origin, setup.csrfManager, setup.workspaceId, lop2Id,
      (await layDanhSachMon(page, origin)).slice(0, 3));
    const [mon1L2] = monKhoa2;

    const { username, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    const hocSinh = thanhVien.find((item) => item.user.username === username);
    expect(hocSinh).toBeTruthy();
    await themHocSinhVaoLop(
      page, origin, setup.csrfManager, setup.workspaceId, setup.lopId, hocSinh!.user.id);
    const roster = await layRoster(page, origin, setup.workspaceId, setup.lopId);
    const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);

    // Học sinh lớp L1 ghi danh môn của khóa thuộc L2: vi phạm invariant cùng lớp.
    const cheoLop = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/enrollments`,
      { classStudentIds: [dongRoster.id], courseSubjectIds: [mon1L2.id] });
    expect(cheoLop.status()).toBe(400);

    // Học sinh của center khác thêm vào lớp center này: vi phạm FK chéo workspace.
    const ownerB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userId: ownerBAppId } = await dangNhapLayId(page, origin, ownerB);
    let csrf = await dangNhapLai(page, origin, setup.operator);
    const centerB = await taoCenter(page, origin, csrf, ownerBAppId);
    await xacNhanGoi(page, origin, csrf, centerB.workspaceId);
    csrf = await dangNhapLai(page, origin, ownerB);
    const hocSinhB = await capStudent(page, origin, csrf, centerB.workspaceId);
    await choOperationThanhCong(page, origin, hocSinhB.operationId);
    const thanhVienB = await layDanhSachThanhVien(page, origin, centerB.workspaceId);
    const userB = thanhVienB.find((item) => item.user.username === hocSinhB.username);
    expect(userB).toBeTruthy();
    const csrfManagerA = await dangNhapLai(page, origin, setup.manager);
    const cheoWorkspace = await guiMutation(page, origin, csrfManagerA, "POST",
      `/api/workspaces/${setup.workspaceId}/classes/${setup.lopId}/students`,
      { studentUserIds: [userB!.user.id] });
    expect(cheoWorkspace.status()).toBe(400);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("giao diện quản trị: trang classes hiển thị lớp và khóa", { tag: ["@layer-e2e", "@flow-QL-02", "@role-center-manager"] }, async ({ page }) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 3);
    await page.goto(`${frontendOrigin}/centers/${setup.workspaceId}/classes`);
    await page.getByRole("button", { name: setup.tenLop, exact: true }).click();
    await expect(page.getByRole("heading", { name: `Khóa: ${setup.tenKhoa}`, exact: true })).toBeVisible();
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
