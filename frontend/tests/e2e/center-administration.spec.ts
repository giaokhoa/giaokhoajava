import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse, type Browser, type Page } from "@playwright/test";
import {
  TestEnvironment, loginViaKeycloakForm, appRequestWhenReady,
  luuPhienHienTai, khoiPhucPhienDaLuu, DEFAULT_FRONTEND_ORIGIN,
} from "./support/test-environment";

// E2E quản trị center ở biên trình duyệt: operator tạo center/xác nhận gói, owner bổ nhiệm
// manager, manager mời/cấp student, mutation 202 và bí mật handoff chỉ ở response CREATE
// đầu tiên. Spec cần origin ứng dụng đang chạy (backend cùng stack TEST) qua
// NATIVE_GATE_APP_ORIGIN, cùng cơ chế với identity.spec.ts; khi biến chưa được đặt, spec tự
// bỏ qua để CI không đỏ giả. Các endpoint/UI thuộc hợp đồng nhưng chưa được cài đặt
// (platform centers/subscriptions, memberships, invitations, students, trang /platform và
// /centers/{wid}/members) sẽ đỏ có ý nghĩa cho tới khi có code; tuyệt đối không trích test
// bị skip làm bằng chứng chạy thật.
//
// Lưu ý kiểm chứng:
// - Token lời mời chỉ có trong response CREATE theo hợp đồng API; không có thư mời SMTP.
// - UI và API dùng cùng origin TEST_FRONTEND_ORIGIN qua gateway do Playwright khởi động.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? DEFAULT_FRONTEND_ORIGIN;

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

const HAN_DUNG_TEST = 300_000;

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
  invitationToken?: string;
  operation?: OperationView;
  handoff?: { username?: string; temporaryPassword?: string };
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

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec quản trị center.",
  );
  return appOrigin as string;
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
// GET /api/session đồng thời bootstrap tài khoản/personal workspace của actor.
// Nếu actor đã có phiên lưu từ lần đăng nhập form thật trong cùng lượt chạy thì
// khôi phục storageState thay vì qua form Keycloak lần nữa; phiên lưu không còn
// hiệu lực (hết hạn/bị hủy phía server) thì rơi xuống đăng nhập form thật.
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

// Lần mutation được bảo vệ đầu tiên của actor mới trả 503 AUTHORIZATION_NOT_READY:
// partition ACCOUNT mới bootstrap ở PENDING, ProtectedActionExecutor đồng bộ eligibility
// rồi fence chặn cho tới khi worker chiếu xong. Chỉ thử lại đúng mã này, tối đa 60 giây;
// 503 xảy ra trước khi endpoint ghi operation của nó nên key chưa từng được ghi — giữ
// nguyên key qua mọi lần thử để lần retry tường minh sau đó vẫn tra đúng operation;
// tuyệt đối không thử lại 400/403/409 hay 503 với mã khác (vd: PDP lỗi).

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
    const response = await appRequestWhenReady(page, `${origin}${path}`);
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
  const response = await appRequestWhenReady(page, `${origin}/api/workspaces/${workspaceId}/memberships`);
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<MembershipView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

async function layDanhSachWorkspaceCuaToi(page: Page, origin: string): Promise<WorkspaceView[]> {
  const response = await appRequestWhenReady(page, `${origin}/api/me/workspaces`);
  expect(response.status()).toBe(200);
  const workspaces = (await response.json()) as WorkspaceView[];
  expect(Array.isArray(workspaces)).toBe(true);
  return workspaces;
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
  expect(["PENDING", "APPLYING"]).toContain(traLoi.state);
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

// Mật khẩu tạm thật phải đưa tới bước đổi mật khẩu bắt buộc của Keycloak, không đăng nhập thẳng.
async function kiemTraMatKhauTam(
  browser: Browser,
  origin: string,
  username: string,
  matKhauTam: string,
): Promise<void> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, {
      username,
      password: matKhauTam,
    });
    await expect(page.locator("#password-new")).toBeVisible();
  } finally {
    await context.close();
  }
}

test("operator tạo center qua giao diện platform: thao tác hoàn tất trên UI, center có trong danh sách platform", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
    const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    await dangNhapLai(page, origin, operator);

    // Thao tác được kiểm thử đi qua UI như operator thật: điền form "Tạo trung tâm"
    // trên trang platform rồi chờ UI báo thao tác hoàn tất (UI tự theo dõi operation,
    // trạng thái chờ không được tính là xong). Hợp đồng 202/poll của chính endpoint
    // tạo center do CenterAdministrationIT khóa ở tầng backend.
    const tenCenter = `center-e2e-${randomUUID().slice(0, 8)}`;
    await page.goto(`${frontendOrigin}/platform`);
    await page.locator("#center-name").fill(tenCenter);
    await page.locator("#center-owner").fill(owner.appId);
    await page.getByRole("button", { name: "Tạo trung tâm" }).click();
    await expect(page.getByText("Thao tác đã hoàn tất.")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole("definition").filter({ hasText: tenCenter })).toBeVisible();

    // Khẳng định phụ về dữ liệu qua API: center có trong danh sách platform.
    const centers = await layDanhSachCenter(page, origin);
    const center = centers.find((item) => item.name === tenCenter);
    expect(center).toBeTruthy();

    // Tạo center đồng thời tạo owner membership; owner tự thấy membership của mình.
    await dangNhapLai(page, origin, owner);
    const thanhVien = await layDanhSachThanhVien(page, origin, center!.workspaceId);
    const ownerMembership = thanhVien.find((item) => item.user.id === owner.appId);
    expect(ownerMembership).toBeTruthy();
    expect(ownerMembership!.roles).toContain("OWNER");
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("operator xác nhận kỳ/gói: subscription 202 rồi poll tới SUCCEEDED, workspace có gói hiệu lực", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
    const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const csrf = await dangNhapLai(page, origin, operator);
    const { workspaceId } = await taoCenter(page, origin, csrf, owner.appId);
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

    await dangNhapLai(page, origin, owner);
    const workspaces = await layDanhSachWorkspaceCuaToi(page, origin);
    const center = workspaces.find((item) => item.id === workspaceId);
    expect(center?.subscription?.effective).toBeTruthy();
    expect(center?.subscription?.expiresAt).toBeTruthy();
    expect(Date.parse(center!.subscription!.expiresAt!)).toBe(Date.parse(expiresAt));
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("owner bổ nhiệm manager: PUT roles 202 rồi poll, danh sách thành viên ghi MANAGER", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
    const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const ungVien = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    let csrf = await dangNhapLai(page, origin, operator);
    const { workspaceId } = await taoCenter(page, origin, csrf, owner.appId);
    await xacNhanGoi(page, origin, csrf, workspaceId);

    csrf = await dangNhapLai(page, origin, owner);
    const { membershipId } = await moiVaChapNhan(
      page, origin, csrf, workspaceId, ungVien, ["TEACHER"]);
    csrf = await dangNhapLai(page, origin, owner);
    const thanhVien = await layDanhSachThanhVien(page, origin, workspaceId);
    const membership = thanhVien.find((item) => item.id === membershipId);
    expect(membership).toBeTruthy();
    expect(membership!.roles).toContain("TEACHER");

    const boNhiem = await guiMutation(page, origin, csrf, "PUT",
      `/api/workspaces/${workspaceId}/memberships/${membershipId}/roles`,
      { roles: ["TEACHER", "MANAGER"] },
      { headers: { "If-Match": `"${membership!.version}"` } });
    expect(boNhiem.status()).toBe(202);
    const traLoi = (await boNhiem.json()) as MutationTraLoi;
    await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);

    const sauBoNhiem = await layDanhSachThanhVien(page, origin, workspaceId);
    expect(sauBoNhiem.find((item) => item.id === membershipId)?.roles).toContain("MANAGER");
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("manager mời thành viên: invite 202; cấm mời OWNER; manager không cấp được MANAGER", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const nguoiMoi = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const nguoiMoi2 = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const nguoiMoi3 = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);

    // Manager mời thành viên mới bằng userId: 202 rồi SUCCEEDED, nhưng chưa phải thành viên
    // khi chưa chấp nhận lời mời.
    const moi = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/invitations`,
      { userId: nguoiMoi.appId, roles: ["TEACHER"] });
    expect(moi.status()).toBe(202);
    const traLoi = (await moi.json()) as MutationTraLoi;
    await choOperationThanhCong(page, origin, layOperationId(traLoi), traLoi.statusUrl);
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    expect(thanhVien.some((item) => item.user.id === nguoiMoi.appId)).toBe(false);

    // OWNER không nằm trong danh mục invite (lỗi field) và manager không được cấp MANAGER.
    const moiOwner = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/invitations`,
      { userId: nguoiMoi2.appId, roles: ["OWNER"] });
    expect(moiOwner.status()).toBe(400);
    const moiManager = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/invitations`,
      { userId: nguoiMoi3.appId, roles: ["MANAGER"] });
    expect(moiManager.status()).toBe(403);

    // Đối chứng: owner mời MANAGER được (403 ở trên là do actor, không phải payload).
    const csrfOwner = await dangNhapLai(page, origin, setup.owner);
    const moiCuaOwner = await guiMutation(page, origin, csrfOwner, "POST",
      `/api/workspaces/${setup.workspaceId}/invitations`,
      { userId: nguoiMoi3.appId, roles: ["MANAGER"] });
    expect(moiCuaOwner.status()).toBe(202);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("manager cấp student: handoff chỉ ở response CREATE; poll/list/retry không lộ mật khẩu tạm", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const khoa = randomUUID();
    const { username, temporaryPassword, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId, khoa);

    await choOperationThanhCong(page, origin, operationId);
    const poll = await page.request.get(`${origin}/api/me/operations/${operationId}`);
    expect(poll.status()).toBe(200);
    expect(await poll.text()).not.toContain(temporaryPassword);

    const danhSach = await page.request.get(
      `${origin}/api/workspaces/${setup.workspaceId}/memberships`);
    expect(danhSach.status()).toBe(200);
    const danhSachTho = await danhSach.text();
    expect(danhSachTho).not.toContain(temporaryPassword);
    const thanhVien = (JSON.parse(danhSachTho) as TrangDanhSach<MembershipView>).items;
    expect(thanhVien.some(
      (item) => item.user.username === username && item.roles.includes("STUDENT"),
    )).toBe(true);

    // Retry cùng Idempotency-Key không cấp lại handoff và không tạo thêm membership.
    const retry = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/students`,
      { username, displayName: "Học sinh E2E" },
      { idempotencyKey: khoa });
    expect([200, 202]).toContain(retry.status());
    expect(await retry.text()).not.toContain(temporaryPassword);
    const sauRetry = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    expect(sauRetry.filter((item) => item.user.username === username)).toHaveLength(1);

    // Mật khẩu tạm trong handoff là thật: đăng nhập lần đầu bị ép đổi mật khẩu.
    await kiemTraMatKhauTam(browser, origin, username, temporaryPassword);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("username trùng không chiếm user đã có: cấp lại trả 409, mật khẩu tạm cũ còn dùng được", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const { username, temporaryPassword, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);

    const trung = await guiMutation(page, origin, setup.csrfManager, "POST",
      `/api/workspaces/${setup.workspaceId}/students`,
      { username, displayName: "Học sinh khác" });
    expect(trung.status()).toBe(409);
    expect(((await trung.json()) as { code?: string }).code).toBe("USERNAME_TAKEN");
    const thanhVien = await layDanhSachThanhVien(page, origin, setup.workspaceId);
    expect(thanhVien.filter((item) => item.user.username === username)).toHaveLength(1);

    // Cấp trùng không reset credential: mật khẩu tạm cũ vẫn đưa tới bước đổi mật khẩu.
    await kiemTraMatKhauTam(browser, origin, username, temporaryPassword);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("teacher không tạo được lớp center dù đã có gói personal", async ({ page }) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const operator = await taoNguoiDung(page, origin, env, NHOM_OPERATOR, daTao);
    const owner = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    const teacher = await taoNguoiDung(page, origin, env, NHOM_ELIGIBLE, daTao);
    let csrf = await dangNhapLai(page, origin, operator);
    const { workspaceId } = await taoCenter(page, origin, csrf, owner.appId);
    await xacNhanGoi(page, origin, csrf, workspaceId);

    // Teacher có gói personal thật: xác nhận gói cho personal workspace của chính teacher.
    await dangNhapLai(page, origin, teacher);
    const workspaces = await layDanhSachWorkspaceCuaToi(page, origin);
    const personal = workspaces.find((item) => item.kind === "PERSONAL");
    expect(personal).toBeTruthy();
    csrf = await dangNhapLai(page, origin, operator);
    await xacNhanGoi(page, origin, csrf, personal!.id);

    // Dù có gói personal, teacher vẫn không có quyền tạo lớp trong center.
    // (Endpoint lớp thuộc US3: khi chưa có endpoint, test đỏ ở 404 thay vì 403.)
    csrf = await dangNhapLai(page, origin, teacher);
    const taoLop = await guiMutation(page, origin, csrf, "POST",
      `/api/workspaces/${workspaceId}/classes`,
      { name: `lop-e2e-${randomUUID().slice(0, 8)}` });
    expect(taoLop.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("giao diện quản trị: trang members hiển thị thành viên và không lộ mật khẩu tạm; trang platform liệt kê center", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungCenterVoiManager(page, origin, env, daTao);
    const { username, temporaryPassword, operationId } = await capStudent(
      page, origin, setup.csrfManager, setup.workspaceId);
    await choOperationThanhCong(page, origin, operationId);

    const phanHoiTrang: string[] = [];
    page.on("response", async (response) => {
      if (!response.url().startsWith(frontendOrigin)) return;
      try {
        phanHoiTrang.push(await response.text());
      } catch {
        // Response điều hướng/không đọc được; bỏ qua đúng các response đó.
      }
    });
    await page.goto(`${frontendOrigin}/centers/${setup.workspaceId}/members`);
    await expect(page.getByText(username, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(setup.manager.username, { exact: true }).first()).toBeVisible();
    expect(await page.content()).not.toContain(temporaryPassword);
    for (const body of phanHoiTrang) expect(body).not.toContain(temporaryPassword);

    await dangNhapLai(page, origin, setup.operator);
    await page.goto(`${frontendOrigin}/platform`);
    await expect(page.getByRole("definition").filter({ hasText: setup.tenCenter })).toBeVisible();
    expect(await page.content()).not.toContain(temporaryPassword);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
