// E2E thu hồi quyền — [T064][US5] US5.1–US5.6, SC-005, FR-048–FR-053.
// Mỗi kịch bản giữ nguyên browser context của actor (cùng phiên/cookie cũ), một
// manager KHÁC thực hiện thu hồi, rồi actor gọi lại và bị từ chối từ yêu cầu tiếp theo.
// Không dùng UI cache hay hidden button làm authorization; phản hồi 202 (operation
// PENDING) KHÔNG được hiển thị thành thành công.
//
// Nguồn quyền quan sát được, theo contracts/authorization.md §11 và http-api.md:
// - Phân công giảng dạy: quyết quyền bằng SQL trên assignment hiện hành — giáo viên
//   đang phân công mới được đọc roster của môn–khóa (GET .../course-subjects/{id}/enrollments).
// - Membership: GET .../memberships chỉ thành viên hiện hành mới được xem.
// - Ghi danh: học sinh đọc kết quả của chính mình cần enrollment (B56: chính học
//   sinh nhận tập được phép, có thể rỗng, HTTP 200; actor khác mà tập rỗng thì 403).
// - Kết quả công bố: quyết bằng PDP read.published trên đồ thị Keycloak — chỉ đổi
//   khi operation SUCCEEDED, nên trong lúc PENDING quyền cũ vẫn sử dụng được.
// - Header X-Tenant-Id giả không được tôn trọng: route quyết định theo path.
// - 202 chỉ là "đã nhận, chưa hoàn tất": completed:false; chỉ SUCCEEDED/completed:true
//   mới là hoàn tất (FR-052). UI map PENDING thành "Đang cập nhật quyền. Thao tác
//   chưa hoàn tất." (operation-status.ts), không thành thành công.
//
// Lưu ý khóa flow thật (B68): action "chấm" thật là sample action của US7 (B60),
// chưa có ở US5; các kịch bản dưới khóa cùng nguồn phân công qua roster read mà
// chính phân công đó cấp (authorization.md §11). Gate US7 chạy lại với lời gọi
// chấm thật.
//
// Các helper dựng center/owner/manager/lớp/khóa/học sinh ở đây sao chép tối giản từ
// guardian-results.spec.ts để spec tự chứa, không sửa spec đang xanh.
import { randomBytes, randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { expect, test, type APIResponse, type Browser, type Page } from "@playwright/test";
import {
  TestEnvironment,
  loginViaKeycloakForm,
  layPhienHienTai,
  dongBoEligibility,
  moBoiCanhDangNhap,
  appRequestWhenReady,
} from "./support/test-environment";

const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? "http://localhost:3001";

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

const HAN_DUNG_TEST = 600_000;

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec thu hồi.",
  );
  return appOrigin as string;
}

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
type GuardianLinkView = {
  id: string;
  parentUserId?: string;
  studentUserId?: string;
  state: string;
  version: number;
};
type PublishedResultView = {
  id: string;
  student: { id: string; displayName?: string };
  className: string;
  courseName: string;
  subjectName: string;
  score?: unknown;
  comment?: string;
  publishedAt?: string;
};
type TeachingAssignmentView = {
  id: string;
  courseSubjectId: string;
  teacherUserId: string;
  state: string;
  version: number;
};
type EnrollmentView = {
  id: string;
  classStudentId: string;
  courseSubjectId: string;
  studentUserId: string;
  state: string;
  version: number;
};

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
}

// Đăng nhập lại bằng tài khoản khác trên cùng trang: đổi phiên rồi lấy CSRF mới.
// GET /api/session đồng thời bootstrap tài khoản/personal workspace của actor.
async function dangNhapLayId(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<{ csrf: Csrf; userId: string }> {
  await page.context().clearCookies();
  await dangNhapQuaUngDung(page, origin, user);
  const session = (await layPhienHienTai(page, origin)) as SessionView;
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  // user.id là id NỘI BỘ của user_account (UUID random), KHÔNG phải Keycloak sub:
  // mọi endpoint nhận user id đều dùng id nội bộ này.
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

// Lần mutation được bảo vệ đầu tiên của actor mới trả 503 AUTHORIZATION_NOT_READY:
// partition ACCOUNT mới bootstrap ở PENDING, ProtectedActionExecutor đồng bộ eligibility
// rồi fence chặn cho tới khi worker chiếu xong. Vòng chờ dùng helper CHUẨN DUY NHẤT
// appRequestWhenReady trong support/test-environment.ts (expect.poll, chỉ thử lại
// đúng mã này, tối đa 60 giây, giữ nguyên Idempotency-Key); tuyệt đối không thử lại
// 400/403/409 hay 503 với mã khác (vd: PDP lỗi).
async function guiMutation(
  page: Page,
  origin: string,
  csrf: Csrf,
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: Record<string, unknown>,
  tuyChon?: { idempotencyKey?: string; headers?: Record<string, string> },
): Promise<APIResponse> {
  const khoaIdempotency = tuyChon?.idempotencyKey ?? randomUUID();
  return appRequestWhenReady(page, `${origin}${path}`, {
    method,
    headers: {
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": khoaIdempotency,
      ...(tuyChon?.headers ?? {}),
    },
    data: body,
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

  const csrfNguoiDuocMoi = await dangNhapLai(page, origin, nguoiDuocMoi);
  const chapNhan = await guiMutation(page, origin, csrfNguoiDuocMoi, "POST",
    `/api/invitations/${token}/accept`);
  expect(chapNhan.status()).toBe(202);
  const traLoiChapNhan = (await chapNhan.json()) as MutationTraLoi;
  await choOperationThanhCong(
    page, origin, layOperationId(traLoiChapNhan), traLoiChapNhan.statusUrl);

  const thanhVien = await layDanhSachThanhVien(page, origin, workspaceId);
  // Khớp theo username: membership user.id là id NỘI BỘ của user_account,
  // không phải Keycloak sub của người được mời.
  const membership = thanhVien.find((item) => item.user.username === nguoiDuocMoi.username);
  expect(membership).toBeTruthy();
  return { membershipId: membership!.id, userAppId: membership!.user.id };
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
  ownerAppId: string;
  manager: NguoiDungTest;
  managerMembershipId: string;
  csrfManager: Csrf;
}> {
  const operator = await taoNguoiDung(env, NHOM_OPERATOR, daTao);
  const owner = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const ungVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  // Owner đăng nhập trước để bootstrap user_account; taoCenter nhận ownerUserId là
  // id nội bộ lấy từ /api/session, không phải Keycloak sub.
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
    workspaceId, tenCenter, operator, owner, ownerAppId, manager: ungVien,
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
  // StudentIssueView bọc biên nhận trong operation (http-api.md), khác các mutation phẳng.
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

// Dựng center + lớp L1 + khóa K hai môn; trả đủ id để test đi thẳng vào hành vi giảng dạy.
async function dungCenterLopKhoa(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
  soMon = 2,
): Promise<{
  workspaceId: string;
  tenCenter: string;
  operator: NguoiDungTest;
  owner: NguoiDungTest;
  ownerAppId: string;
  manager: NguoiDungTest;
  managerMembershipId: string;
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

// Cấp một học sinh, đưa vào lớp và ghi danh mọi môn của khóa; trả định danh dùng tiếp.
async function capHocSinhDayDu(
  page: Page,
  origin: string,
  csrfManager: Csrf,
  workspaceId: string,
  lopId: string,
  monKhoa: MonTrongKhoa[],
): Promise<{ username: string; temporaryPassword: string; userId: string }> {
  const { username, temporaryPassword, operationId } = await capStudent(
    page, origin, csrfManager, workspaceId);
  await choOperationThanhCong(page, origin, operationId);
  const thanhVien = await layDanhSachThanhVien(page, origin, workspaceId);
  const hocSinh = thanhVien.find((item) => item.user.username === username);
  expect(hocSinh).toBeTruthy();
  expect(hocSinh!.roles).toContain("STUDENT");
  await themHocSinhVaoLop(page, origin, csrfManager, workspaceId, lopId, hocSinh!.user.id);
  const roster = await layRoster(page, origin, workspaceId, lopId);
  const dongRoster = timDongRoster(roster, hocSinh!.user.id, username);
  await ghiDanh(page, origin, csrfManager, workspaceId,
    [dongRoster.id], monKhoa.map((mon) => mon.id));
  return { username, temporaryPassword, userId: hocSinh!.user.id };
}

// Seed một kết quả mẫu thẳng vào DB bằng psql, theo đúng mẫu PublishedResultContractIT:
// resource + resource_version + sample_activity + sample_submission + sample_result
// (đúng FK; trigger kiểm tra ghi danh của submission và nguồn của result). Kết quả
// không công bố ở lại trạng thái DRAFT. Cần TEST_E2E_SCHEMA (schema ngẫu nhiên của lượt
// chạy do playwright.config.ts đặt; run-backend.sh dùng cùng tên cho backend nên seed
// mới trùng schema đã migrate) và TEST_DATABASE_* của làn TEST; psql lấy từ PATH.
// kèm trong operation duyệt/gỡ link (B58) nên không cần ghi projection riêng ở đây.
function seedKetQuaMau(
  workspaceId: string,
  mon: MonTrongKhoa,
  studentUserId: string,
  ownerAppId: string,
  teacherAppId: string,
  diem: string,
  nhanXet: string,
  congBo: boolean,
): void {
  const schema = process.env.TEST_E2E_SCHEMA;
  const databaseUrl = process.env.TEST_DATABASE_URL;
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  if (!schema || !databaseUrl || !username || !password) {
    throw new Error("Seed kết quả mẫu cần TEST_E2E_SCHEMA và TEST_DATABASE_URL/USERNAME/PASSWORD");
  }
  const match = databaseUrl.match(/^jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/);
  const host = match?.[1];
  const database = match?.[3];
  if (!host || !database) {
    throw new Error(`TEST_DATABASE_URL không đúng dạng jdbc:postgresql://host:port/db: ${databaseUrl}`);
  }
  const port = match[2] ?? "5432";
  const sql = `
SET search_path TO :schema;
SET app.workspace_id = :'wid';
BEGIN;
INSERT INTO resource(id, owner_workspace_id, created_by_user_id, kind, subject_id, state)
VALUES (:'resource_id', :'wid', :'owner_id', 'EXAM', :'subject_id', 'ACTIVE');
INSERT INTO resource_version(id, resource_id, version_no, label, body_stub, answer_stub, review_state)
VALUES (:'version_id', :'resource_id', 1, 'Bản mẫu', 'Nội dung', 'Đáp án', 'APPROVED');
INSERT INTO sample_activity(id, workspace_id, course_subject_id, resource_id, version_id, assigned_by, assigned_at, due_at, attempt_limit, subscription_period_id, state)
VALUES (:'activity_id', :'wid', :'cs_id', :'resource_id', :'version_id', :'owner_id', now(), now() + interval '7 days', 1, (SELECT id FROM subscription_period WHERE workspace_id = :'wid'), 'ASSIGNED');
INSERT INTO sample_submission(id, workspace_id, activity_id, student_user_id, attempt_no, submitted_at, content_stub, state)
VALUES (:'submission_id', :'wid', :'activity_id', :'student_id', 1, now(), 'Bài làm mẫu', 'SUBMITTED');
INSERT INTO sample_result(id, workspace_id, submission_id, student_user_id, course_subject_id, score_stub, comment_stub, state, published_at, graded_by)
VALUES (:'result_id', :'wid', :'submission_id', :'student_id', :'cs_id', :'score', :'comment', :'state', CASE WHEN :'state' = 'PUBLISHED' THEN now() ELSE NULL END, :'teacher_id');
COMMIT;
`;
  const ketQua = spawnSync("psql",
    [
      "-v", "ON_ERROR_STOP=1", "-X", "-q", "-f", "-",
      "-v", `schema=${schema}`,
      "-v", `wid=${workspaceId}`,
      "-v", `cs_id=${mon.id}`,
      "-v", `subject_id=${mon.subjectId}`,
      "-v", `student_id=${studentUserId}`,
      "-v", `owner_id=${ownerAppId}`,
      "-v", `teacher_id=${teacherAppId}`,
      "-v", `score=${diem}`,
      "-v", `comment=${nhanXet}`,
      "-v", `state=${congBo ? "PUBLISHED" : "DRAFT"}`,
      "-v", `resource_id=${randomUUID()}`,
      "-v", `version_id=${randomUUID()}`,
      "-v", `activity_id=${randomUUID()}`,
      "-v", `submission_id=${randomUUID()}`,
      "-v", `result_id=${randomUUID()}`,
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
    throw new Error(`Không chạy được psql để seed kết quả mẫu: ${ketQua.error.message}`);
  }
  if (ketQua.status !== 0) {
    throw new Error(`Seed kết quả mẫu thất bại (psql ${ketQua.status}): ${(ketQua.stderr ?? "").trim()}`);
  }
}

async function layDanhSachLienKet(
  page: Page,
  origin: string,
  workspaceId: string,
): Promise<GuardianLinkView[]> {
  const response = await guiGet(page, origin, `/api/workspaces/${workspaceId}/guardian-links`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<GuardianLinkView> | GuardianLinkView[];
  const items = Array.isArray(body) ? body : body.items;
  expect(Array.isArray(items)).toBe(true);
  return items;
}

function timLienKet(
  danhSach: GuardianLinkView[],
  studentUserId: string,
): GuardianLinkView {
  const lienKet = danhSach.find((item) => item.studentUserId === studentUserId);
  expect(lienKet).toBeTruthy();
  return lienKet!;
}

// Parent gửi request cho chính mình: GuardianRequest chỉ mang studentUserId,
// server tự lấy requester là actor hiện hành, không nhận parentId từ body.
async function guiRequestLienKet(
  page: Page,
  origin: string,
  csrfParent: Csrf,
  workspaceId: string,
  studentUserId: string,
): Promise<void> {
  const gui = await guiMutation(page, origin, csrfParent, "POST",
    `/api/workspaces/${workspaceId}/guardian-links`, { studentUserId });
  expect(gui.status()).toBe(202);
  const traLoi = (await gui.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function duyetLienKet(
  page: Page,
  origin: string,
  csrfManager: Csrf,
  workspaceId: string,
  lienKet: GuardianLinkView,
): Promise<void> {
  const quyetDinh = await guiMutation(page, origin, csrfManager, "POST",
    `/api/workspaces/${workspaceId}/guardian-links/${lienKet.id}/decision`,
    { decision: "APPROVE", expectedVersion: lienKet.version });
  expect(quyetDinh.status()).toBe(202);
  const traLoi = (await quyetDinh.json()) as MutationTraLoi;
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
}

async function layKetQuaCon(
  page: Page,
  origin: string,
  workspaceId: string,
  studentUserId: string,
): Promise<APIResponse> {
  return guiGet(page, origin, `/api/workspaces/${workspaceId}/students/${studentUserId}/results`);
}

// Một GET thô duy nhất, KHÔNG qua vòng retry của fence: dùng để quan sát
// trạng thái thật tại một thời điểm (vd ngay sau 202, lúc operation còn
// PENDING) — helper có retry sẽ bắc cầu qua cả cửa sổ đồng bộ và chỉ để lộ
// trạng thái cuối.
async function layKetQuaConTho(
  page: Page,
  origin: string,
  workspaceId: string,
  studentUserId: string,
): Promise<APIResponse> {
  return page.request.get(
    `${origin}/api/workspaces/${workspaceId}/students/${studentUserId}/results`);
}

async function docKetQuaCon(response: APIResponse): Promise<PublishedResultView[]> {
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<PublishedResultView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

type SanSangPhuHuynh = Awaited<ReturnType<typeof dungCenterLopKhoa>> & {
  giaoVien: NguoiDungTest;
  chuNhiem: NguoiDungTest;
  phuHuynh: NguoiDungTest;
  parentAppId: string;
  hocSinhX: { username: string; temporaryPassword: string; userId: string };
  hocSinhY: { username: string; temporaryPassword: string; userId: string };
  tenMon1: string;
  nhanXetCongBo: string;
  nhanXetNhap: string;
  nhanXetCongBoY: string;
};

// Dựng center đầy đủ cho luồng phụ huynh: giáo viên phụ trách cả hai môn, một chủ
// nhiệm, hai học sinh cùng lớp/khóa; học sinh X có một kết quả đã công bố (môn 1) và
// một kết quả còn DRAFT (môn 2), học sinh Y có một kết quả đã công bố (môn 1); một
// parent đã là thành viên với vai trò PARENT nhưng chưa có liên kết nào được duyệt.
async function dungSanSangPhuHuynh(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
): Promise<SanSangPhuHuynh> {
  const setup = await dungCenterLopKhoa(page, origin, env, daTao, 2);
  const [mon1, mon2] = setup.monKhoa;

  // moiVaChapNhan kết thúc ở phiên người được mời: trước mutation tiếp theo bằng
  // vai manager phải đăng nhập lại manager để lấy CSRF của phiên hiện tại.
  let csrfManager = setup.csrfManager;

  const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const { userAppId: idGiaoVien } = await moiVaChapNhan(page, origin,
    csrfManager, setup.workspaceId, giaoVien, ["TEACHER"]);
  csrfManager = await dangNhapLai(page, origin, setup.manager);
  await phanCongGiaoVien(page, origin, csrfManager, setup.workspaceId,
    mon1.id, [idGiaoVien]);
  await phanCongGiaoVien(page, origin, csrfManager, setup.workspaceId,
    mon2.id, [idGiaoVien]);

  const chuNhiem = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const { userAppId: idChuNhiem } = await moiVaChapNhan(page, origin,
    csrfManager, setup.workspaceId, chuNhiem, ["TEACHER"]);
  csrfManager = await dangNhapLai(page, origin, setup.manager);
  await phanCongChuNhiem(page, origin, csrfManager, setup.workspaceId,
    setup.lopId, idChuNhiem);

  const phuHuynh = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const { userAppId: parentAppId } = await moiVaChapNhan(page, origin,
    csrfManager, setup.workspaceId, phuHuynh, ["PARENT"]);
  csrfManager = await dangNhapLai(page, origin, setup.manager);

  const hocSinhX = await capHocSinhDayDu(page, origin, csrfManager,
    setup.workspaceId, setup.lopId, setup.monKhoa);
  const hocSinhY = await capHocSinhDayDu(page, origin, csrfManager,
    setup.workspaceId, setup.lopId, setup.monKhoa);

  const nhanXetCongBo = `nhan-xet-cong-bo-${randomUUID().slice(0, 8)}`;
  const nhanXetNhap = `nhan-xet-nhap-${randomUUID().slice(0, 8)}`;
  const nhanXetCongBoY = `nhan-xet-cong-bo-y-${randomUUID().slice(0, 8)}`;
  // Seed bằng fixture trước khi có liên kết: bundle kết quả được ghi kèm trong
  // operation duyệt/gỡ link (B58), nên không cần ghi projection riêng ở đây.
  seedKetQuaMau(setup.workspaceId, mon1, hocSinhX.userId, setup.ownerAppId,
    idGiaoVien, "9.5", nhanXetCongBo, true);
  seedKetQuaMau(setup.workspaceId, mon2, hocSinhX.userId, setup.ownerAppId,
    idGiaoVien, "7.0", nhanXetNhap, false);
  seedKetQuaMau(setup.workspaceId, mon1, hocSinhY.userId, setup.ownerAppId,
    idGiaoVien, "8.0", nhanXetCongBoY, true);

  await dangNhapLai(page, origin, setup.manager);
  const monHoc = await layDanhSachMon(page, origin);
  const tenMon1 = monHoc.find((mon) => mon.id === mon1.subjectId)?.name;
  expect(tenMon1).toBeTruthy();

  return {
    ...setup, giaoVien, chuNhiem, phuHuynh, parentAppId, hocSinhX, hocSinhY,
    tenMon1: tenMon1!, nhanXetCongBo, nhanXetNhap, nhanXetCongBoY,
  };
}

// Parent gửi request và manager duyệt ngay; dùng cho các test cần liên kết APPROVED.
async function lienKetDaDuyet(
  page: Page,
  origin: string,
  setup: SanSangPhuHuynh,
): Promise<GuardianLinkView> {
  const csrfParent = await dangNhapLai(page, origin, setup.phuHuynh);
  await guiRequestLienKet(
    page, origin, csrfParent, setup.workspaceId, setup.hocSinhX.userId);
  const csrfManager = await dangNhapLai(page, origin, setup.manager);
  const lienKet = timLienKet(
    await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
  await duyetLienKet(page, origin, csrfManager, setup.workspaceId, lienKet);
  return timLienKet(
    await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
}

// --- Thu hồi: các DELETE trả 202 PENDING/APPLYING, chỉ SUCCEEDED mới là thu hồi xong. ---

// Gửi một DELETE thu hồi; khẳng định 202 với completed:false (FR-052: 202 không
// phải thành công) và trả biên nhận để caller chờ SUCCEEDED rồi mới quan sát từ chối.
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

async function layPhanCongGiaoVien(
  page: Page,
  origin: string,
  workspaceId: string,
  courseSubjectId: string,
): Promise<TeachingAssignmentView[]> {
  const response = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/course-subjects/${courseSubjectId}/teachers`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<TeachingAssignmentView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

async function layGhiDanhMon(
  page: Page,
  origin: string,
  workspaceId: string,
  courseSubjectId: string,
): Promise<EnrollmentView[]> {
  const response = await guiGet(
    page, origin, `/api/workspaces/${workspaceId}/course-subjects/${courseSubjectId}/enrollments`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<EnrollmentView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

async function layDanhSachLop(
  page: Page,
  origin: string,
  workspaceId: string,
): Promise<ClassView[]> {
  const response = await guiGet(page, origin, `/api/workspaces/${workspaceId}/classes`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<ClassView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

// Học sinh đăng nhập lần đầu bằng mật khẩu tạm: Keycloak bắt đổi mật khẩu
// (trang update-password, #password-new/#password-confirm) rồi mới vào ứng dụng.
async function dangNhapHocSinh(
  browser: Browser,
  origin: string,
  username: string,
  matKhauTam: string,
): Promise<{ page: Page; csrf: Csrf }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, {
    username,
    password: matKhauTam,
  });
  await expect(page.locator("#password-new")).toBeVisible();
  const matKhauMoi = `${randomBytes(18).toString("base64url")}Aa1!`;
  await page.locator("#password-new").fill(matKhauMoi);
  await page.locator("#password-confirm").fill(matKhauMoi);
  // Trang required-action UPDATE_PASSWORD (theme keycloak.v2) dùng nút
  // submit #kc-submit, khác #kc-login của form đăng nhập.
  await page.locator("#kc-submit").click();
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
  const session = (await layPhienHienTai(page, origin)) as SessionView;
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  await dongBoEligibility(page, origin);
  return { page, csrf: session.csrf! };
}

// Mở trang thành viên ở frontend và chờ danh sách thành viên hiện ra.
async function moTrangThanhVien(page: Page, workspaceId: string): Promise<void> {
  await page.goto(`${frontendOrigin}/centers/${workspaceId}/members`);
  await expect(page.getByText("Thành viên trung tâm")).toBeVisible();
}

test("US5.1 gỡ phân công giảng dạy: phiên giáo viên cũ bị từ chối từ yêu cầu tiếp theo", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 2);
    const [mon1] = setup.monKhoa;

    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId: idGiaoVien } = await moiVaChapNhan(page, origin,
      setup.csrfManager, setup.workspaceId, giaoVien, ["TEACHER"]);
    let csrfManager = await dangNhapLai(page, origin, setup.manager);
    await phanCongGiaoVien(page, origin, csrfManager, setup.workspaceId,
      mon1.id, [idGiaoVien]);

    // Giáo viên giữ nguyên phiên của mình (context riêng, cookie cũ) và đọc
    // được roster của môn–khóa khi còn phân công.
    const { page: trangGiaoVien } = await moBoiCanhDangNhap(browser, origin, giaoVien);
    const truoc = await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/enrollments`);
    expect(truoc.status()).toBe(200);

    // Một manager KHÁC gỡ phân công; chờ SUCCEEDED mới quan sát từ chối.
    csrfManager = await dangNhapLai(page, origin, setup.manager);
    const phanCong = await layPhanCongGiaoVien(page, origin, setup.workspaceId, mon1.id);
    const dong = phanCong.find((item) => item.teacherUserId === idGiaoVien);
    expect(dong).toBeTruthy();
    const thuHoi = await guiThuHoi(page, origin, csrfManager,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/teachers/${dong!.id}`);
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);

    // Cùng phiên cũ, không đăng nhập lại: yêu cầu tiếp theo bị từ chối 403.
    const sau = await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/enrollments`);
    expect(sau.status()).toBe(403);
    const danhSach = await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/teachers`);
    expect(danhSach.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US5.2 gỡ membership: phiên cũ mất quyền ở A, quyền ở B còn nguyên", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setupA = await dungCenterLopKhoa(page, origin, env, daTao, 2);
    const setupB = await dungCenterVoiManager(page, origin, env, daTao);

    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    let csrfA = await dangNhapLai(page, origin, setupA.manager);
    const { membershipId: membershipA } = await moiVaChapNhan(page, origin,
      csrfA, setupA.workspaceId, giaoVien, ["TEACHER"]);
    const csrfB = await dangNhapLai(page, origin, setupB.manager);
    await moiVaChapNhan(page, origin,
      csrfB, setupB.workspaceId, giaoVien, ["TEACHER"]);

    // Giáo viên giữ nguyên phiên: là thành viên ở cả hai center.
    const { page: trangGiaoVien } = await moBoiCanhDangNhap(browser, origin, giaoVien);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupA.workspaceId}/memberships`)).status()).toBe(200);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupB.workspaceId}/memberships`)).status()).toBe(200);

    // Manager của A gỡ membership của giáo viên ở A.
    csrfA = await dangNhapLai(page, origin, setupA.manager);
    const thuHoi = await guiThuHoi(page, origin, csrfA,
      `/api/workspaces/${setupA.workspaceId}/memberships/${membershipA}`);
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);

    // Cùng phiên cũ: ở A bị từ chối, ở B vẫn đọc được.
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupA.workspaceId}/memberships`)).status()).toBe(403);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupA.workspaceId}/classes`)).status()).toBe(403);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupB.workspaceId}/memberships`)).status()).toBe(200);

    // Danh sách thành viên của A không còn giáo viên.
    const thanhVienA = await layDanhSachThanhVien(page, origin, setupA.workspaceId);
    expect(thanhVienA.some((item) => item.user.username === giaoVien.username)).toBe(false);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US5.3 gỡ ghi danh: học sinh mất quyền đọc kết quả của mình, lịch sử còn nguyên", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungSanSangPhuHuynh(page, origin, env, daTao);
    const [mon1] = setup.monKhoa;
    const lienKet = await lienKetDaDuyet(page, origin, setup);

    // Học sinh đăng nhập lần đầu bằng mật khẩu tạm (đổi mật khẩu bắt buộc) và
    // đọc được kết quả đã công bố của chính mình.
    const { page: trangHocSinh } = await dangNhapHocSinh(
      browser, origin, setup.hocSinhX.username, setup.hocSinhX.temporaryPassword);
    const truoc = await docKetQuaCon(
      await layKetQuaCon(trangHocSinh, origin, setup.workspaceId, setup.hocSinhX.userId));
    expect(truoc.some((item) => item.comment === setup.nhanXetCongBo)).toBe(true);

    // Manager gỡ ghi danh môn 1 của học sinh.
    const csrfManager = await dangNhapLai(page, origin, setup.manager);
    const ghiDanhMon1 = await layGhiDanhMon(page, origin, setup.workspaceId, mon1.id);
    const dong = ghiDanhMon1.find((item) => item.studentUserId === setup.hocSinhX.userId);
    expect(dong).toBeTruthy();
    const thuHoi = await guiThuHoi(page, origin, csrfManager,
      `/api/workspaces/${setup.workspaceId}/enrollments/${dong!.id}`);
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);

    // Cùng phiên cũ: học sinh không còn thấy kết quả của mình (B56: chính học
    // sinh nhận tập được phép, có thể rỗng, HTTP 200 — không phải 403).
    const sau = await layKetQuaCon(
      trangHocSinh, origin, setup.workspaceId, setup.hocSinhX.userId);
    expect(sau.status()).toBe(200);
    const ketQuaSau = await docKetQuaCon(sau);
    expect(ketQuaSau.some((item) => item.comment === setup.nhanXetCongBo)).toBe(false);
    expect(ketQuaSau).toHaveLength(0);

    // Lịch sử giữ nguyên cho người còn thẩm quyền: parent (liên kết đã duyệt)
    // vẫn đọc được kết quả đã công bố của con.
    expect(lienKet.state).toBe("APPROVED");
    const { page: trangPhuHuynh } = await moBoiCanhDangNhap(browser, origin, setup.phuHuynh);
    const cuaPhuHuynh = await docKetQuaCon(
      await layKetQuaCon(trangPhuHuynh, origin, setup.workspaceId, setup.hocSinhX.userId));
    expect(cuaPhuHuynh.some((item) => item.comment === setup.nhanXetCongBo)).toBe(true);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US5.4 thông tin giả không mở quyền: đổi tenant, header giả, role giả đều bị chặn", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setupA = await dungCenterLopKhoa(page, origin, env, daTao, 2);
    const setupB = await dungCenterVoiManager(page, origin, env, daTao);

    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const csrfA = await dangNhapLai(page, origin, setupA.manager);
    await moiVaChapNhan(page, origin,
      csrfA, setupA.workspaceId, giaoVien, ["TEACHER"]);

    const { page: trangGiaoVien, csrf: csrfGiaoVien } =
      await moBoiCanhDangNhap(browser, origin, giaoVien);

    // (a) Đổi wid trên path sang center B mà giáo viên không phải thành viên.
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setupB.workspaceId}/memberships`)).status()).toBe(403);

    // (b) Tạo lớp ở A kèm header giả X-Tenant-Id trỏ sang B: giáo viên thường
    // không có class.create ở A nên vẫn 403.
    const taoLopGia = await guiMutation(trangGiaoVien, origin, csrfGiaoVien, "POST",
      `/api/workspaces/${setupA.workspaceId}/classes`, { name: "Lớp giả mạo" },
      { headers: { "X-Tenant-Id": setupB.workspaceId } });
    expect(taoLopGia.status()).toBe(403);

    // (d) Mời người khác với role MANAGER trong body: giáo viên thường không
    // có quyền cấp quản lý nên 403.
    const moiGia = await guiMutation(trangGiaoVien, origin, csrfGiaoVien, "POST",
      `/api/workspaces/${setupA.workspaceId}/invitations`,
      { email: `moi-${randomUUID().slice(0, 8)}@example.invalid`, roles: ["MANAGER"] });
    expect(moiGia.status()).toBe(403);

    // (c) Manager tạo lớp ở A kèm header giả X-Tenant-Id trỏ sang B: route
    // quyết định theo path, header bị bỏ qua — lớp xuất hiện ở A, không ở B.
    const tenLop = `lop-gia-${randomUUID().slice(0, 8)}`;
    const csrfManagerA = await dangNhapLai(page, origin, setupA.manager);
    const tao = await guiMutation(page, origin, csrfManagerA, "POST",
      `/api/workspaces/${setupA.workspaceId}/classes`, { name: tenLop },
      { headers: { "X-Tenant-Id": setupB.workspaceId } });
    expect(tao.status()).toBe(202);
    const traLoi = (await tao.json()) as MutationTraLoi;
    await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
    const lopA = await layDanhSachLop(page, origin, setupA.workspaceId);
    expect(lopA.some((item) => item.name === tenLop)).toBe(true);
    await dangNhapLai(page, origin, setupB.manager);
    const lopB = await layDanhSachLop(page, origin, setupB.workspaceId);
    expect(lopB.some((item) => item.name === tenLop)).toBe(false);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US5.5 nguồn độc lập: gỡ phân công giảng dạy, nguồn chủ nhiệm còn nguyên", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterLopKhoa(page, origin, env, daTao, 2);
    const [mon1] = setup.monKhoa;

    // Cùng một actor vừa là chủ nhiệm lớp vừa được phân công dạy môn 1.
    const giaoVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userAppId: idGiaoVien } = await moiVaChapNhan(page, origin,
      setup.csrfManager, setup.workspaceId, giaoVien, ["TEACHER"]);
    let csrfManager = await dangNhapLai(page, origin, setup.manager);
    await phanCongGiaoVien(page, origin, csrfManager, setup.workspaceId,
      mon1.id, [idGiaoVien]);
    await phanCongChuNhiem(page, origin, csrfManager, setup.workspaceId,
      setup.lopId, idGiaoVien);

    const { page: trangGiaoVien } = await moBoiCanhDangNhap(browser, origin, giaoVien);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/enrollments`)).status())
      .toBe(200);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/classes/${setup.lopId}/students`)).status())
      .toBe(200);

    // Manager gỡ phân công dạy môn 1 (không đụng tới phân công chủ nhiệm).
    csrfManager = await dangNhapLai(page, origin, setup.manager);
    const phanCong = await layPhanCongGiaoVien(page, origin, setup.workspaceId, mon1.id);
    const dong = phanCong.find((item) => item.teacherUserId === idGiaoVien);
    expect(dong).toBeTruthy();
    const thuHoi = await guiThuHoi(page, origin, csrfManager,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/teachers/${dong!.id}`);
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);

    // Cùng phiên cũ: roster môn–khóa bị từ chối, roster lớp nhờ chủ nhiệm vẫn đọc được.
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/course-subjects/${mon1.id}/enrollments`)).status())
      .toBe(403);
    expect((await guiGet(trangGiaoVien, origin,
      `/api/workspaces/${setup.workspaceId}/classes/${setup.lopId}/students`)).status())
      .toBe(200);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("US5.6 202 không phải thành công: chưa bị từ chối lúc PENDING, UI không báo xong sớm", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungSanSangPhuHuynh(page, origin, env, daTao);
    const lienKet = await lienKetDaDuyet(page, origin, setup);

    // Phần API (FR-052): parent giữ nguyên phiên và đọc được kết quả của con.
    const { page: trangPhuHuynh } = await moBoiCanhDangNhap(browser, origin, setup.phuHuynh);
    const truoc = await docKetQuaCon(
      await layKetQuaCon(trangPhuHuynh, origin, setup.workspaceId, setup.hocSinhX.userId));
    expect(truoc.some((item) => item.comment === setup.nhanXetCongBo)).toBe(true);

    // Manager gỡ liên kết: 202 với completed:false, KHÔNG phải thành công.
    const csrfManager = await dangNhapLai(page, origin, setup.manager);
    const thuHoi = await guiThuHoi(page, origin, csrfManager,
      `/api/workspaces/${setup.workspaceId}/guardian-links/${lienKet.id}`);

    // Ngay sau 202, lúc thay đổi chưa có hiệu lực, parent CHƯA bị từ chối và
    // operation vẫn báo chưa hoàn tất (FR-052, scenario 6 của US5). Điểm quan
    // sát này phải là một GET thô: trong lúc đồng bộ, fence trả 503
    // AUTHORIZATION_NOT_READY theo hợp đồng, còn helper có retry sẽ bắc cầu
    // qua cả cửa sổ PENDING rồi chỉ để lộ trạng thái cuối sau thu hồi.
    const tho = await layKetQuaConTho(
      trangPhuHuynh, origin, setup.workspaceId, setup.hocSinhX.userId);
    const trangThaiUrl = new URL(thuHoi.statusUrl!, origin).toString();
    const phanHoiTrangThai = await page.request.get(trangThaiUrl);
    expect(phanHoiTrangThai.status()).toBe(200);
    const trangThai = (await phanHoiTrangThai.json()) as OperationView;
    if (trangThai.state !== "SUCCEEDED") {
      expect(trangThai.completed).toBe(false);
      // Chưa bị từ chối: còn đọc được theo quyền cũ (200), hoặc đang bị fence
      // chặn trong lúc đồng bộ (503) — cả hai đều là "chưa hoàn tất".
      expect([200, 503]).toContain(tho.status());
      if (tho.status() === 200) {
        const items = await docKetQuaCon(tho);
        expect(items.some((item) => item.comment === setup.nhanXetCongBo)).toBe(true);
      } else {
        expect(((await tho.json()) as { code?: string }).code)
          .toBe("AUTHORIZATION_NOT_READY");
      }
    }

    // Chỉ sau SUCCEEDED, yêu cầu tiếp theo của parent mới bị từ chối.
    await choOperationThanhCong(page, origin, layOperationId(thuHoi), thuHoi.statusUrl);
    expect((await layKetQuaCon(
      trangPhuHuynh, origin, setup.workspaceId, setup.hocSinhX.userId)).status()).toBe(403);

    // Phần UI: manager gỡ thành viên qua trang thành viên — lúc PENDING chỉ
    // hiện "Đang cập nhật quyền. Thao tác chưa hoàn tất.", không hiện thành công.
    const csrfManagerUi = await dangNhapLai(page, origin, setup.manager);
    expect(csrfManagerUi.token).toBeTruthy();
    await moTrangThanhVien(page, setup.workspaceId);
    const hangGiaoVien = page.locator("li", { hasText: setup.giaoVien.username });
    await expect(hangGiaoVien).toHaveCount(1);
    await hangGiaoVien.getByRole("button", { name: "Gỡ thành viên" }).click();
    await expect(page.getByText("Đang cập nhật quyền. Thao tác chưa hoàn tất.")).toBeVisible();
    await expect(page.getByText("Thao tác đã hoàn tất.")).toHaveCount(0);
    // Bấm "Kiểm tra trạng thái" (poll bằng expect.poll) tới khi operation
    // SUCCEEDED thì mới hiện xong.
    await expect
      .poll(
        async () => {
          if ((await page.getByText("Thao tác đã hoàn tất.").count()) > 0) return true;
          await page.getByRole("button", { name: "Kiểm tra trạng thái" }).click();
          return false;
        },
        { timeout: 60_000, intervals: [1_000], message: "operation chưa SUCCEEDED trên UI sau 60s" },
      )
      .toBe(true);
    await expect(page.getByText("Thao tác đã hoàn tất.")).toBeVisible();
    await expect(page.locator("li", { hasText: setup.giaoVien.username })).toHaveCount(0);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
