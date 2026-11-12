// E2E review resource actions của US7 ở biên trình duyệt: actor thật đăng nhập qua
// Keycloak, thao tác action tài nguyên trên trang review (/review) như người dùng,
// theo hợp đồng kiểm chứng quyền (spec.md US7.1–US7.9 và bảng SampleAction trong
// issue của task): lưu giữ nguyên định danh/nguồn, fork ra định danh mới giữ truy
// vết, chia sẻ không rộng hơn nguồn, body và answer là hai control tách nhau với
// hai scope riêng, thu hồi xong thì nội dung không còn truy cập được kể cả qua bản
// đã lưu (không cache content sau revoke).
//
// Phần seed nền (user/center/gói/membership/lớp/khóa/phân công) đi theo đúng mẫu
// harness hiện có: phiên trình duyệt thật + API của ứng dụng, và resource/version
// mẫu seed thẳng bằng psql như revocation.spec.ts — CREATE_RESOURCE hiện tại chỉ
// trả stub, chưa lưu resource thật. Mọi hành vi thuộc phạm vi test (save, fork,
// share, accept, revoke, assign, mở body/answer) đều bấm trên giao diện review,
// không gọi tắt API thay cho UI.
//
// Lịch sử: spec viết test-first trên main d7adfb4f khi implementation US7 chưa
// có (đỏ đúng lý do thiếu hành vi). Implementation đã land sau đó (panel /review,
// SampleActionController đủ action, endpoint đọc body/answer tách riêng); spec
// được rebase lên main 74ab09c0, neo tương tác của panel (discriminator cố định,
// tên trường của hợp đồng, vùng kết quả) giữ theo mục B110 của Sổ điểm chưa chắc
// chắn/lệch spec.
import { randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { expect, test, type APIResponse, type Locator, type Page } from "@playwright/test";
import {
  TestEnvironment,
  appRequestWhenReady,
  dongBoEligibility,
  layPhienHienTai,
  loginViaKeycloakForm,
  moBoiCanhDangNhap,
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
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec resource.",
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
  statusUrl?: string;
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
};
type SubjectView = { id: string; code?: string; name?: string };
type MonTrongKhoa = { id: string; subjectId: string; name?: string };
type CourseView = { id: string; classId: string; name: string; subjects: MonTrongKhoa[] };
type ClassView = { id: string; name: string };

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
}

// Đăng nhập lại bằng tài khoản khác trên cùng trang: đổi phiên rồi lấy CSRF mới và
// id nội bộ của user (id trong user_account, không phải Keycloak sub).
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

// Mọi mutation/get đi qua helper chuẩn appRequestWhenReady (support): chỉ thử lại
// 503 AUTHORIZATION_NOT_READY tối đa 60 giây, giữ nguyên Idempotency-Key.
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
    headers: {
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": tuyChon?.idempotencyKey ?? randomUUID(),
      ...(tuyChon?.headers ?? {}),
    },
    data: body,
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

// Chỉ SUCCEEDED/completed:true mới là hoàn tất nghiệp vụ; 202 PENDING không tính.
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
  await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
  const centers = await layDanhSachCenter(page, origin);
  const center = centers.find((item) => item.name === tenCenter);
  expect(center).toBeTruthy();
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

// Token chấp nhận chỉ có trong response CREATE của lời mời; người được mời chấp
// nhận bằng phiên của chính mình rồi membership được đọc lại theo username.
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
  const membership = thanhVien.find((item) => item.user.username === nguoiDuocMoi.username);
  expect(membership).toBeTruthy();
  return { membershipId: membership!.id, userAppId: membership!.user.id };
}

// Dựng center đã xác nhận gói + manager (TEACHER+MANAGER) đã bổ nhiệm; trả phiên
// manager đang đăng nhập. Cùng mẫu revocation.spec.ts/personal-entitlements.spec.ts.
async function dungCenterVoiManager(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
): Promise<{
  workspaceId: string;
  operator: NguoiDungTest;
  owner: NguoiDungTest;
  ownerAppId: string;
  manager: NguoiDungTest;
  csrfManager: Csrf;
}> {
  const operator = await taoNguoiDung(env, NHOM_OPERATOR, daTao);
  const owner = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const ungVien = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const { userId: ownerAppId } = await dangNhapLayId(page, origin, owner);
  let csrf = await dangNhapLai(page, origin, operator);
  const { workspaceId } = await taoCenter(page, origin, csrf, ownerAppId);
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
  return { workspaceId, operator, owner, ownerAppId, manager: ungVien, csrfManager };
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
): Promise<{ lopId: string }> {
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
  return { lopId: lop!.id };
}

async function taoKhoa(
  page: Page,
  origin: string,
  csrf: Csrf,
  workspaceId: string,
  lopId: string,
  monHoc: SubjectView[],
): Promise<{ monKhoa: MonTrongKhoa[] }> {
  const tenKhoa = `khoa-e2e-${randomUUID().slice(0, 8)}`;
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
  return { monKhoa: khoa.subjects };
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

async function layWorkspaceCaNhan(page: Page, origin: string): Promise<WorkspaceView> {
  const response = await guiGet(page, origin, "/api/me/workspaces");
  expect(response.status()).toBe(200);
  const workspaces = (await response.json()) as WorkspaceView[];
  expect(Array.isArray(workspaces)).toBe(true);
  const personal = workspaces.find((item) => item.kind === "PERSONAL");
  expect(personal).toBeTruthy();
  return personal!;
}

// Seed một resource + version mẫu thẳng vào DB bằng psql, theo đúng mẫu
// revocation.spec.ts: resource thuộc workspace nhà, created_by là author, version
// APPROVED với body/answer stub là marker riêng của test để khẳng định trên UI.
// Cần TEST_E2E_SCHEMA (schema ngẫu nhiên dùng chung backend/Playwright ở gate;
// E2E_SCHEMA là đường lui local) và TEST_DATABASE_* của làn TEST.
function seedTaiNguyen(
  workspaceId: string,
  authorAppId: string,
  subjectId: string,
  marker: string,
): { resourceId: string; versionId: string; noiDung: string; dapAn: string } {
  const schema = process.env.TEST_E2E_SCHEMA ?? process.env.E2E_SCHEMA;
  const databaseUrl = process.env.TEST_DATABASE_URL;
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  if (!schema || !databaseUrl || !username || !password) {
    throw new Error("Seed tài nguyên cần TEST_E2E_SCHEMA (hoặc E2E_SCHEMA) và TEST_DATABASE_URL/USERNAME/PASSWORD");
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
  const noiDung = `noi-dung-${marker}`;
  const dapAn = `dap-an-${marker}`;
  const sql = `
SET search_path TO :schema;
SET app.workspace_id = :'wid';
BEGIN;
INSERT INTO resource(id, owner_workspace_id, created_by_user_id, kind, subject_id, state)
VALUES (:'resource_id', :'wid', :'author_id', 'EXAM', :'subject_id', 'ACTIVE');
INSERT INTO resource_version(id, resource_id, version_no, label, body_stub, answer_stub, review_state)
VALUES (:'version_id', :'resource_id', 1, 'V1', :'noi_dung', :'dap_an', 'APPROVED');
COMMIT;
`;
  const ketQua = spawnSync("psql",
    [
      "-v", "ON_ERROR_STOP=1", "-X", "-q", "-f", "-",
      "-v", `schema=${schema}`,
      "-v", `wid=${workspaceId}`,
      "-v", `author_id=${authorAppId}`,
      "-v", `subject_id=${subjectId}`,
      "-v", `resource_id=${resourceId}`,
      "-v", `version_id=${versionId}`,
      "-v", `noi_dung=${noiDung}`,
      "-v", `dap_an=${dapAn}`,
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
    throw new Error(`Không chạy được psql để seed tài nguyên: ${ketQua.error.message}`);
  }
  if (ketQua.status !== 0) {
    throw new Error(`Seed tài nguyên thất bại (psql ${ketQua.status}): ${(ketQua.stderr ?? "").trim()}`);
  }
  return { resourceId, versionId, noiDung, dapAn };
}

// --- Panel review: mọi hành vi thuộc phạm vi test đều đi qua giao diện này. ---
// Neo tương tác theo hợp đồng: route /review, discriminator action là chuỗi cố
// định của hợp đồng SampleAction, tên trường là tên field của hợp đồng, kết quả
// hiện trạng thái SUCCEEDED hoặc mã lỗi nghiệp vụ (ghi ở B110 của Sổ).

async function moPanelReview(page: Page): Promise<void> {
  await page.goto(`${frontendOrigin}/review`);
  // Panel đã dựng xong khi select action và trường workspaceId hiện. Option của
  // một select đang đóng không có hộp hiển thị nên Playwright không tính là
  // visible — neo vào chính select thay vì text của option.
  await expect(page.locator('select[name="action"]'))
    .toBeVisible({ timeout: 30_000 });
  await expect(page.locator('[name="workspaceId"]')).toBeVisible();
}

// Tìm control của một trường hợp đồng: ưu tiên label trùng tên trường, sau đó
// thuộc tính name của control (input/select/textarea đều dùng được).
async function timTruong(page: Page, tenTruong: string): Promise<Locator> {
  const theoNhan = page.getByLabel(new RegExp(`^${tenTruong}$`, "i"));
  if (await theoNhan.count()) return theoNhan.first();
  const theoTen = page.locator(`[name="${tenTruong}"]`);
  if (await theoTen.count()) return theoTen.first();
  return page.getByLabel(new RegExp(tenTruong, "i")).first();
}

async function dienTruong(page: Page, tenTruong: string, giaTri: string): Promise<void> {
  const truong = await timTruong(page, tenTruong);
  const tag = await truong.evaluate((el) => el.tagName).catch(() => "");
  if (tag === "SELECT") {
    // Option của select theo hợp đồng mang discriminator/giá trị cố định làm value
    // hoặc nhãn; thử value trước rồi tới nhãn hiển thị.
    await truong.selectOption(giaTri).catch(async () => {
      await truong.selectOption({ label: giaTri });
    });
    return;
  }
  await truong.fill(giaTri);
}

async function chonHanhDong(page: Page, action: string): Promise<void> {
  const select = page.locator("select").filter({
    has: page.locator("option", { hasText: action }),
  });
  if (await select.count()) {
    await select.first().selectOption(action).catch(async () => {
      await select.first().selectOption({ label: action });
    });
    return;
  }
  await page.getByRole("button", { name: action, exact: true }).click();
}

async function guiFormPanel(page: Page): Promise<void> {
  const nutChay = page.getByRole("button", { name: /thực hiện|chạy/i });
  if (await nutChay.count()) {
    await nutChay.first().click();
    return;
  }
  await page.locator('button[type="submit"]').first().click();
}

// Chạy một sample action trên panel từ trạng thái sạch: mở lại trang review, chọn
// action, điền đúng các trường của hợp đồng rồi gửi form.
async function chayHanhDong(
  page: Page,
  action: string,
  truong: Record<string, string>,
): Promise<void> {
  await moPanelReview(page);
  await chonHanhDong(page, action);
  for (const [ten, giaTri] of Object.entries(truong)) {
    await dienTruong(page, ten, giaTri);
  }
  await guiFormPanel(page);
}

// Thành công của action hiện trạng thái SUCCEEDED trên panel (đồng bộ hoặc sau
// khi operation hoàn tất); assertion web-first tự chờ tới hạn 60 giây.
async function choHanhDongThanhCong(page: Page): Promise<void> {
  await expect(page.getByText(/SUCCEEDED/).first()).toBeVisible({ timeout: 60_000 });
}

// Từ chối hiện mã lỗi nghiệp vụ hoặc câu giải thích ngắn trên panel; ẩn nút
// không tính là từ chối — panel phải báo rõ thao tác không được phép.
async function choHanhDongBiTuChoi(page: Page): Promise<void> {
  await expect(page.getByText(
    /PERMISSION_DENIED|SUBSCRIPTION_INACTIVE|không có quyền|không được phép|bị từ chối/i,
  ).first()).toBeVisible({ timeout: 60_000 });
}

// Từ chối dạng validation của hợp đồng cho ca ASSIGN sai môn: PDP cho qua theo
// phân công của actor nhưng invariant môn của version chặn ở service bằng
// 400 INVALID_SAMPLE_ACTION kèm câu giải thích — khác hẳn từ chối quyền.
async function choHanhDongBiTuChoiValidation(page: Page): Promise<void> {
  await expect(page.getByText(
    /INVALID_SAMPLE_ACTION|Version không thuộc/i,
  ).first()).toBeVisible({ timeout: 60_000 });
}

// Đọc một UUID mới xuất hiện trên panel sau action (grant id của share, id của
// bản fork): UUID đầu tiên không thuộc tập id đã biết của test.
async function trichIdMoi(page: Page, cacIdDaBiet: string[]): Promise<string> {
  const text = await page.locator("body").innerText();
  const uuids = text.match(
    /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi) ?? [];
  const moi = uuids.find((u) => !cacIdDaBiet.includes(u));
  expect(moi).toBeTruthy();
  return moi!;
}

// Mở stub body của một version trên panel: điền ngữ cảnh workspace/resource/
// version rồi bấm control nội dung — control tách riêng với control đáp án.
async function moNoiDung(
  page: Page,
  workspaceId: string,
  resourceId: string,
  versionId: string,
): Promise<void> {
  await moPanelReview(page);
  await dienTruong(page, "workspaceId", workspaceId);
  await dienTruong(page, "resourceId", resourceId);
  await dienTruong(page, "versionId", versionId);
  await page.getByRole("button", { name: /nội dung|body/i }).first().click();
}

async function moDapAn(
  page: Page,
  workspaceId: string,
  resourceId: string,
  versionId: string,
): Promise<void> {
  await moPanelReview(page);
  await dienTruong(page, "workspaceId", workspaceId);
  await dienTruong(page, "resourceId", resourceId);
  await dienTruong(page, "versionId", versionId);
  await page.getByRole("button", { name: /đáp án|answer/i }).first().click();
}

type BoiCanhTaiNguyen = {
  workspaceId: string;
  manager: NguoiDungTest;
  csrfManager: Csrf;
  tacGia: NguoiDungTest;
  tacGiaAppId: string;
  monKhoa: MonTrongKhoa[];
  taiNguyen: ReturnType<typeof seedTaiNguyen>;
};

// Dựng center A đầy đủ cho US7: gói hiệu lực, lớp/khóa hai môn, một giáo viên
// được phân công cả hai môn làm author, và một resource/version mẫu của author.
async function dungCenterCoTaiNguyen(
  page: Page,
  origin: string,
  env: TestEnvironment,
  daTao: NguoiDungTest[],
): Promise<BoiCanhTaiNguyen> {
  const setup = await dungCenterVoiManager(page, origin, env, daTao);
  const monHoc = (await layDanhSachMon(page, origin)).slice(0, 2);
  expect(monHoc.length).toBeGreaterThanOrEqual(2);
  const { lopId } = await taoLop(page, origin, setup.csrfManager, setup.workspaceId);
  const { monKhoa } = await taoKhoa(
    page, origin, setup.csrfManager, setup.workspaceId, lopId, monHoc);

  const tacGia = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
  const { userAppId: tacGiaAppId } = await moiVaChapNhan(
    page, origin, setup.csrfManager, setup.workspaceId, tacGia, ["TEACHER"]);
  const csrfManager = await dangNhapLai(page, origin, setup.manager);
  for (const mon of monKhoa) {
    await phanCongGiaoVien(page, origin, csrfManager, setup.workspaceId,
      mon.id, [tacGiaAppId]);
  }
  const taiNguyen = seedTaiNguyen(
    setup.workspaceId, tacGiaAppId, monKhoa[0].subjectId, randomUUID().slice(0, 8));
  return {
    workspaceId: setup.workspaceId,
    manager: setup.manager,
    csrfManager,
    tacGia,
    tacGiaAppId,
    monKhoa,
    taiNguyen,
  };
}

// Author chia sẻ version cho workspace nhận bằng panel; trả grant id đọc được
// từ kết quả hiển thị trên panel của author.
async function chiaSeToiWorkspace(
  page: Page,
  nguon: BoiCanhTaiNguyen,
  workspaceNhan: string,
  actions: string,
): Promise<string> {
  await dangNhapLai(page, gateOrigin(), nguon.tacGia);
  await chayHanhDong(page, "SHARE_VERSION", {
    workspaceId: nguon.workspaceId,
    resourceId: nguon.taiNguyen.resourceId,
    versionId: nguon.taiNguyen.versionId,
    recipientWorkspaceId: workspaceNhan,
    mode: "REVOCABLE",
    actions,
  });
  await choHanhDongThanhCong(page);
  return trichIdMoi(page, [
    nguon.workspaceId, workspaceNhan,
    nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId,
  ]);
}

// Người nhận chấp nhận share bằng phiên của chính mình trên panel.
async function chapNhanChiaSe(
  page: Page,
  workspaceNhan: string,
  grantId: string,
): Promise<void> {
  await chayHanhDong(page, "ACCEPT_SHARE", { workspaceId: workspaceNhan, grantId });
  await choHanhDongThanhCong(page);
}

test("lưu giữ nguyên định danh nguồn và không mở kho soạn cá nhân cho account không có gói", { tag: ["@layer-e2e", "@flow-GV-01", "@flow-CNH-01", "@role-subject-teacher"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const { resourceId, versionId } = nguon.taiNguyen;

    // Author lưu version vào kho của center nhà: thao tác thành công và mục đã
    // lưu vẫn mang đúng resource id + version id của nguồn, không sinh định danh
    // nghiệp vụ mới, không đổi chủ sở hữu. Manager không lưu được ở đây vì nhánh
    // tác giả của projector chỉ cấp library.save cho chính author (Sổ B86(ii)).
    await dangNhapLai(page, origin, nguon.tacGia);
    await chayHanhDong(page, "SAVE_VERSION", {
      workspaceId: nguon.workspaceId, resourceId, versionId,
    });
    await choHanhDongThanhCong(page);
    await expect(page.getByText(resourceId).first()).toBeVisible();
    await expect(page.getByText(versionId).first()).toBeVisible();

    // Cùng account đó không có gói cá nhân: thao tác lưu ở center không mở kho
    // soạn cá nhân — tạo học liệu trong personal workspace vẫn bị từ chối vì gói.
    const personal = await layWorkspaceCaNhan(page, origin);
    expect(personal.subscription?.effective).toBeFalsy();
    const monHoc = await layDanhSachMon(page, origin);
    await chayHanhDong(page, "CREATE_RESOURCE", {
      workspaceId: personal.id,
      subjectId: monHoc[0].id,
      kind: "QUESTION",
      label: `hoc-lieu-${randomUUID().slice(0, 8)}`,
    });
    await choHanhDongBiTuChoi(page);

    // Trang cá nhân vẫn chỉ hiện trạng thái gói, không xuất hiện kho soạn nào.
    await page.goto(`${frontendOrigin}/personal`);
    await expect(page.getByRole("heading", { name: /gói/i }).first()).toBeVisible();
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("fork tạo định danh mới giữ truy vết nguồn; người chỉ có quyền dùng không fork được", { tag: ["@layer-e2e", "@flow-GV-01", "@role-subject-teacher", "@role-center-manager"] }, async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const benB = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVienB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    await moiVaChapNhan(page, origin, benB.csrfManager, benB.workspaceId,
      giaoVienB, ["TEACHER"]);

    const grantId = await chiaSeToiWorkspace(
      page, nguon, benB.workspaceId, "use,content.read");
    // Accept do owner của workspace nhận thực hiện: nguồn accept của grant chỉ
    // có owner/manager nơi nhận (Sổ B86(ii)), giáo viên thường bị PDP từ chối.
    await dangNhapLai(page, origin, benB.owner);
    await chapNhanChiaSe(page, benB.workspaceId, grantId);
    const { page: trangNhan } = await moBoiCanhDangNhap(browser, origin, giaoVienB);

    // Người nhận chỉ có quyền dùng theo điều kiện share: fork bị từ chối.
    await chayHanhDong(trangNhan, "FORK_VERSION", {
      workspaceId: benB.workspaceId,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
      label: `fork-${randomUUID().slice(0, 8)}`,
    });
    await choHanhDongBiTuChoi(trangNhan);

    // Author fork tại workspace nhà: thành công với định danh tài nguyên MỚI
    // khác nguồn, và truy vết nguồn (resource id gốc) vẫn hiện trên kết quả.
    await dangNhapLai(page, origin, nguon.tacGia);
    await chayHanhDong(page, "FORK_VERSION", {
      workspaceId: nguon.workspaceId,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
      label: `fork-${randomUUID().slice(0, 8)}`,
    });
    await choHanhDongThanhCong(page);
    const idFork = await trichIdMoi(page, [
      nguon.workspaceId, nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId,
    ]);
    expect(idFork).not.toBe(nguon.taiNguyen.resourceId);
    await expect(page.getByText(nguon.taiNguyen.resourceId).first()).toBeVisible();
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("quyền dùng tại workspace nhận không rò sang nơi khác: chia sẻ tiếp và mở ở kho cá nhân đều bị chặn", { tag: ["@layer-e2e", "@flow-GV-01", "@role-subject-teacher", "@role-center-manager"] }, async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const benB = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVienB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    await moiVaChapNhan(page, origin, benB.csrfManager, benB.workspaceId,
      giaoVienB, ["TEACHER"]);

    const grantId = await chiaSeToiWorkspace(
      page, nguon, benB.workspaceId, "use,content.read");
    // Accept do owner của workspace nhận thực hiện: nguồn accept của grant chỉ
    // có owner/manager nơi nhận (Sổ B86(ii)), giáo viên thường bị PDP từ chối.
    await dangNhapLai(page, origin, benB.owner);
    await chapNhanChiaSe(page, benB.workspaceId, grantId);
    const { page: trangNhan } = await moBoiCanhDangNhap(browser, origin, giaoVienB);

    // Trong đúng ngữ cảnh được chia sẻ, người nhận mở được nội dung.
    await moNoiDung(trangNhan, benB.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await expect(trangNhan.getByText(nguon.taiNguyen.noiDung)).toBeVisible();

    // Chia sẻ tiếp ra workspace khác (ở đây là personal của chính người nhận)
    // vượt quá điều kiện nguồn: bị từ chối, share không rộng hơn nguồn.
    const personalNhan = await layWorkspaceCaNhan(trangNhan, origin);
    await chayHanhDong(trangNhan, "SHARE_VERSION", {
      workspaceId: benB.workspaceId,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
      recipientWorkspaceId: personalNhan.id,
      mode: "REVOCABLE",
      actions: "use",
    });
    await choHanhDongBiTuChoi(trangNhan);

    // Mở cùng version ở ngữ cảnh personal (wrong workspace) cũng bị chặn:
    // quyền dùng chỉ có tại workspace đã nhận share.
    await moNoiDung(trangNhan, personalNhan.id,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await choHanhDongBiTuChoi(trangNhan);
    await expect(trangNhan.getByText(nguon.taiNguyen.noiDung)).toHaveCount(0);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("giao hoạt động bằng tài nguyên sai môn bị chặn, đúng môn được phép", { tag: ["@layer-e2e", "@flow-GV-02", "@role-subject-teacher"] }, async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const [monDung, monSai] = nguon.monKhoa;
    // Tài nguyên thuộc môn của monDung (seed theo subject của môn-khóa đầu).
    expect(monDung.subjectId).not.toBe(monSai.subjectId);
    const hanNop = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

    await dangNhapLai(page, origin, nguon.tacGia);
    // Đối chứng: giao vào môn-khóa đúng môn của tài nguyên thì được phép.
    await chayHanhDong(page, "ASSIGN_ACTIVITY", {
      workspaceId: nguon.workspaceId,
      courseSubjectId: monDung.id,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
      dueAt: hanNop,
      attemptLimit: "1",
    });
    await choHanhDongThanhCong(page);

    // Cùng actor, cùng tài nguyên, đổi sang môn-khóa của môn khác: invariant
    // môn của tài nguyên phải khớp môn trong khóa — bị chặn ở service bằng
    // lỗi validation của hợp đồng, không phải từ chối quyền.
    await chayHanhDong(page, "ASSIGN_ACTIVITY", {
      workspaceId: nguon.workspaceId,
      courseSubjectId: monSai.id,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
      dueAt: hanNop,
      attemptLimit: "1",
    });
    await choHanhDongBiTuChoiValidation(page);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("đáp án tách khỏi nội dung: quyền dùng không mở được đáp án, author mở được bằng control riêng", { tag: ["@layer-e2e", "@flow-GV-01", "@role-subject-teacher"] }, async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const benB = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVienB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    await moiVaChapNhan(page, origin, benB.csrfManager, benB.workspaceId,
      giaoVienB, ["TEACHER"]);

    // Share chỉ gồm quyền dùng và đọc nội dung — không có scope đáp án.
    const grantId = await chiaSeToiWorkspace(
      page, nguon, benB.workspaceId, "use,content.read");
    // Accept do owner của workspace nhận thực hiện: nguồn accept của grant chỉ
    // có owner/manager nơi nhận (Sổ B86(ii)), giáo viên thường bị PDP từ chối.
    await dangNhapLai(page, origin, benB.owner);
    await chapNhanChiaSe(page, benB.workspaceId, grantId);
    const { page: trangNhan } = await moBoiCanhDangNhap(browser, origin, giaoVienB);

    // Mở nội dung: body hiện, đáp án không xuất hiện ở bất cứ đâu trên trang —
    // đáp án không đi kèm DTO nội dung và không mở từ quyền dùng/soạn.
    await moNoiDung(trangNhan, benB.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await expect(trangNhan.getByText(nguon.taiNguyen.noiDung)).toBeVisible();
    await expect(trangNhan.getByText(nguon.taiNguyen.dapAn)).toHaveCount(0);

    // Bấm control đáp án riêng: bị từ chối và đáp án vẫn không xuất hiện.
    await moDapAn(trangNhan, benB.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await choHanhDongBiTuChoi(trangNhan);
    await expect(trangNhan.getByText(nguon.taiNguyen.dapAn)).toHaveCount(0);

    // Author tại workspace nhà mở đáp án bằng control riêng thì thấy đáp án.
    await dangNhapLai(page, origin, nguon.tacGia);
    await moDapAn(page, nguon.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await expect(page.getByText(nguon.taiNguyen.dapAn)).toBeVisible();
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("thu hồi chia sẻ: nội dung đã mở không còn truy cập được từ cùng phiên, kể cả qua bản đã lưu", { tag: ["@layer-e2e", "@flow-GV-01", "@flow-QL-05", "@role-subject-teacher", "@role-center-manager"] }, async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const nguon = await dungCenterCoTaiNguyen(page, origin, env, daTao);
    const benB = await dungCenterVoiManager(page, origin, env, daTao);
    const giaoVienB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    await moiVaChapNhan(page, origin, benB.csrfManager, benB.workspaceId,
      giaoVienB, ["TEACHER"]);

    const grantId = await chiaSeToiWorkspace(
      page, nguon, benB.workspaceId, "use,content.read,library.save");
    // Người nhận giữ nguyên một phiên/context trong suốt kịch bản thu hồi.
    // Accept do owner của workspace nhận thực hiện: nguồn accept của grant chỉ
    // có owner/manager nơi nhận (Sổ B86(ii)), giáo viên thường bị PDP từ chối.
    await dangNhapLai(page, origin, benB.owner);
    await chapNhanChiaSe(page, benB.workspaceId, grantId);
    const { page: trangNhan } = await moBoiCanhDangNhap(browser, origin, giaoVienB);

    // Trước thu hồi: mở được nội dung và lưu được vào kho của workspace nhận.
    await moNoiDung(trangNhan, benB.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await expect(trangNhan.getByText(nguon.taiNguyen.noiDung)).toBeVisible();
    await chayHanhDong(trangNhan, "SAVE_VERSION", {
      workspaceId: benB.workspaceId,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
    });
    await choHanhDongThanhCong(trangNhan);

    // Author thu hồi share dạng REVOCABLE trên panel của mình.
    await dangNhapLai(page, origin, nguon.tacGia);
    await chayHanhDong(page, "REVOKE_SHARE", {
      workspaceId: nguon.workspaceId, grantId,
    });
    await choHanhDongThanhCong(page);

    // Cùng phiên cũ của người nhận, nạp lại trang (không đăng nhập lại): mở
    // nội dung bị từ chối và marker nội dung không còn trên trang — bản đã
    // mở trước đó không được giữ bằng cache phía client.
    await moNoiDung(trangNhan, benB.workspaceId,
      nguon.taiNguyen.resourceId, nguon.taiNguyen.versionId);
    await choHanhDongBiTuChoi(trangNhan);
    await expect(trangNhan.getByText(nguon.taiNguyen.noiDung)).toHaveCount(0);

    // Đường qua bản đã lưu cũng bị chặn: lưu lại cùng version bị từ chối vì
    // quyền nguồn đã thu hồi (derived copy không sống lâu hơn grant).
    await chayHanhDong(trangNhan, "SAVE_VERSION", {
      workspaceId: benB.workspaceId,
      resourceId: nguon.taiNguyen.resourceId,
      versionId: nguon.taiNguyen.versionId,
    });
    await choHanhDongBiTuChoi(trangNhan);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
