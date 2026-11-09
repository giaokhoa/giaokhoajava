import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse, type Browser, type Page } from "@playwright/test";
import { TestEnvironment, loginViaKeycloakForm } from "./support/test-environment";

// E2E phụ huynh và kết quả ở biên trình duyệt: parent gửi request liên kết, manager
// duyệt, parent chỉ xem được kết quả ĐÃ CÔNG BỐ của đúng con trong đúng center.
// Nguồn contract: FR-022–FR-024 và các acceptance scenario của US4 (spec.md), hợp đồng
// HTTP (mục "Phụ huynh và kết quả": guardian-links + students/{sid}/results, DTO
// PublishedResultView không chứa submission/body/answer) và hợp đồng quyết định quyền
// (scope workspace guardian.request/guardian.approve/guardian.revoke, result
// read.published; liên kết REQUESTED chưa cho read, chủ nhiệm đứng riêng không duyệt).
// Spec cần origin ứng dụng đang chạy (backend cùng stack TEST) qua
// NATIVE_GATE_APP_ORIGIN, cùng cơ chế với teaching-administration.spec.ts; khi biến
// chưa được đặt, spec tự bỏ qua để CI không đỏ giả. Các endpoint/UI thuộc hợp đồng
// nhưng chưa được cài đặt (guardian-links, students/{sid}/results, trang
// /centers/{wid}/guardian-links và /centers/{wid}/children/{sid}/results) sẽ đỏ có ý
// nghĩa cho tới khi có code; tuyệt đối không trích test bị skip làm bằng chứng chạy thật.
//
// Các helper dựng center/owner/manager/lớp/khóa/học sinh ở đây sao chép tối giản từ
// teaching-administration.spec.ts để spec tự chứa, không sửa spec đang xanh.
//
// Lưu ý kiểm chứng:
// - Kết quả mẫu được seed thẳng vào DB bằng psql theo đúng mẫu PublishedResultContractIT
//   (resource/version/activity/submission/result, đúng FK và trigger ghi danh): đường
//   sample action dừng ở ASSIGN_ACTIVITY vì backend chỉ cài CREATE_RESOURCE, các action
//   assign/submit/grade/publish là T083/T084 của US7 (B60). Bundle kết quả được ghi kèm
//   trong operation duyệt/gỡ link (B58) nên seed trước khi tạo liên kết là đủ, không cần
//   ghi projection riêng.
// - Trạng thái liên kết theo vòng đời của hợp đồng: REQUESTED → APPROVED/REJECTED,
//   APPROVED → REVOKED; quyết định duyệt mang expectedVersion lấy từ bản đọc hiện tại.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? "http://localhost:3001";

// Nhóm nguồn cố định theo NativePolicyTemplates của backend.
const NHOM_ELIGIBLE = "/gk/identity/eligible";
const NHOM_OPERATOR = "/gk/platform/operators";

// Chuỗi seed kết quả mẫu dài hơn spec quản trị giảng dạy (thêm 3 học sinh/phụ huynh
// và 15 sample action), nên hạn dùng của spec này lớn hơn.
const HAN_DUNG_TEST = 600_000;

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

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec phụ huynh.",
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
}

async function laySessionView(page: Page, origin: string): Promise<SessionView> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  return (await response.json()) as SessionView;
}

// Lần mutation được bảo vệ đầu tiên của actor mới trả 503 AUTHORIZATION_NOT_READY:
// partition ACCOUNT mới bootstrap ở PENDING, ProtectedActionExecutor đồng bộ eligibility
// rồi fence chặn cho tới khi worker chiếu xong. Chỉ thử lại đúng mã này, tối đa 60 giây;
// tuyệt đối không thử lại 400/403/409 hay 503 với mã khác (vd: PDP lỗi).
const THOI_GIAN_CHO_FENCE_MS = 60_000;
const KHOANG_CHO_FENCE_MS = 1_000;

// Lời gọi có fence này đồng bộ eligibility của actor (mẫu d7ade21 bên Java):
// không có nó, lời gọi platform đầu tiên của operator mới bị PDP chặn 403 vì
// leaf eligible chưa đồng bộ. Phiên thật luôn nạp workspace trước khi làm việc.
async function dongBoEligibility(page: Page, origin: string): Promise<void> {
  const batDau = Date.now();
  for (;;) {
    const traLoi = await page.request.get(`${origin}/api/me/workspaces`);
    if (traLoi.status() !== 503) {
      expect(traLoi.status()).toBe(200);
      return;
    }
    let ma: unknown;
    try {
      ma = ((await traLoi.json()) as { code?: unknown }).code;
    } catch {
      throw new Error("workspaces trả 503 không rõ mã");
    }
    if (ma !== "AUTHORIZATION_NOT_READY" || Date.now() - batDau >= THOI_GIAN_CHO_FENCE_MS) {
      throw new Error(`workspaces chưa sẵn sàng: 503 ${String(ma)}`);
    }
    await page.waitForTimeout(KHOANG_CHO_FENCE_MS);
  }
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
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  // user.id là id NỘI BỘ của user_account (UUID random), KHÔNG phải Keycloak sub:
  // mọi endpoint nhận user id (vd: studentUserId khi gửi guardian request) đều dùng
  // id nội bộ này.
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
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: Record<string, unknown>,
  tuyChon?: { idempotencyKey?: string; headers?: Record<string, string> },
): Promise<APIResponse> {
  const batDau = Date.now();
  const khoaIdempotency = tuyChon?.idempotencyKey ?? randomUUID();
  for (;;) {
    const traLoi = await page.request.fetch(`${origin}${path}`, {
      method,
      headers: {
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": khoaIdempotency,
        ...(tuyChon?.headers ?? {}),
      },
      data: body,
    });
    if (traLoi.status() !== 503) return traLoi;
    let ma: unknown;
    try {
      ma = ((await traLoi.json()) as { code?: unknown }).code;
    } catch {
      return traLoi;
    }
    if (ma !== "AUTHORIZATION_NOT_READY" || Date.now() - batDau >= THOI_GIAN_CHO_FENCE_MS) {
      return traLoi;
    }
    await page.waitForTimeout(KHOANG_CHO_FENCE_MS);
  }
}

// GET được bảo vệ cũng có thể gặp 503 AUTHORIZATION_NOT_READY ở lần gọi đầu của actor
// mới (partition ACCOUNT còn PENDING); thử lại đúng mã này như mutation.
async function guiGet(page: Page, origin: string, path: string): Promise<APIResponse> {
  const batDau = Date.now();
  for (;;) {
    const traLoi = await page.request.get(`${origin}${path}`);
    if (traLoi.status() !== 503) return traLoi;
    let ma: unknown;
    try {
      ma = ((await traLoi.json()) as { code?: unknown }).code;
    } catch {
      return traLoi;
    }
    if (ma !== "AUTHORIZATION_NOT_READY" || Date.now() - batDau >= THOI_GIAN_CHO_FENCE_MS) {
      return traLoi;
    }
    await page.waitForTimeout(KHOANG_CHO_FENCE_MS);
  }
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

// Mở context trình duyệt mới và đăng nhập bằng tài khoản cho trước; dùng khi một
// phiên phải sống độc lập với trang chính (vd: giữ nguyên phiên parent trong lúc
// manager thao tác ở trang chính).
async function moBoiCanhDangNhap(
  browser: Browser,
  origin: string,
  user: { username: string; password: string },
): Promise<{ page: Page; csrf: Csrf }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await dangNhapQuaUngDung(page, origin, user);
  const session = await laySessionView(page, origin);
  expect(session.authenticated).toBe(true);
  expect(session.csrf?.token).toBeTruthy();
  await dongBoEligibility(page, origin);
  return { page, csrf: session.csrf! };
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
// không công bố ở lại trạng thái DRAFT. Cần E2E_SCHEMA (schema riêng của lượt chạy,
// run-e2e.sh xuất từ schema.txt) và TEST_DATABASE_* của làn TEST; psql lấy từ PATH
// mà run-e2e.sh chuẩn bị. Seed chạy trước khi có liên kết: bundle kết quả được ghi
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
  const schema = process.env.E2E_SCHEMA;
  const databaseUrl = process.env.TEST_DATABASE_URL;
  const username = process.env.TEST_DATABASE_USERNAME;
  const password = process.env.TEST_DATABASE_PASSWORD;
  if (!schema || !databaseUrl || !username || !password) {
    throw new Error("Seed kết quả mẫu cần E2E_SCHEMA và TEST_DATABASE_URL/USERNAME/PASSWORD");
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

async function docKetQuaCon(response: APIResponse): Promise<PublishedResultView[]> {
  expect(response.status()).toBe(200);
  const body = (await response.json()) as TrangDanhSach<PublishedResultView>;
  expect(Array.isArray(body.items)).toBe(true);
  return body.items;
}

// Mở trang kết quả con ở frontend và chờ trang gọi API results thật (không dựa vào
// text tĩnh của trang lỗi): trang chưa cài đặt hoặc không nạp dữ liệu thì chờ này đỏ.
async function moTrangKetQua(page: Page, url: string): Promise<void> {
  const choTaiDuLieu = page.waitForResponse(
    (resp) => resp.url().includes("/students/") && resp.url().includes("/results")
      && resp.request().method() === "GET",
    { timeout: 30_000 },
  );
  await page.goto(url);
  await choTaiDuLieu;
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

test("parent gửi request, manager duyệt, parent chỉ xem kết quả đã công bố của đúng con", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungSanSangPhuHuynh(page, origin, env, daTao);

    // Vai trò PARENT trong membership chưa tự cho xem con: parent gửi request cho
    // chính mình và liên kết ở trạng thái REQUESTED.
    const csrfParent = await dangNhapLai(page, origin, setup.phuHuynh);
    await guiRequestLienKet(
      page, origin, csrfParent, setup.workspaceId, setup.hocSinhX.userId);
    const lienKetCuaParent = timLienKet(
      await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
    expect(lienKetCuaParent.state).toBe("REQUESTED");
    expect(lienKetCuaParent.parentUserId).toBe(setup.parentAppId);

    // REQUESTED chưa cho read: dù đã gửi request, parent vẫn bị từ chối xem kết quả.
    const chuaDuyet = await layKetQuaCon(
      page, origin, setup.workspaceId, setup.hocSinhX.userId);
    expect(chuaDuyet.status()).toBe(403);

    // Manager thấy đúng request đang chờ và duyệt bằng expectedVersion hiện tại.
    const csrfManager = await dangNhapLai(page, origin, setup.manager);
    const lienKetChoDuyet = timLienKet(
      await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
    expect(lienKetChoDuyet.state).toBe("REQUESTED");
    await duyetLienKet(page, origin, csrfManager, setup.workspaceId, lienKetChoDuyet);
    const lienKetSauDuyet = timLienKet(
      await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
    expect(lienKetSauDuyet.state).toBe("APPROVED");

    // Sau duyệt, parent xem được đúng kết quả đã công bố của con: khớp lớp/khóa/môn,
    // có điểm và thời điểm công bố; kết quả DRAFT của cùng con không xuất hiện và DTO
    // không chứa trường bài làm/đáp án.
    await dangNhapLai(page, origin, setup.phuHuynh);
    const ketQua = await docKetQuaCon(
      await layKetQuaCon(page, origin, setup.workspaceId, setup.hocSinhX.userId));
    const congBo = ketQua.find((item) => item.comment === setup.nhanXetCongBo);
    expect(congBo).toBeTruthy();
    expect(congBo!.student.id).toBe(setup.hocSinhX.userId);
    expect(congBo!.className).toBe(setup.tenLop);
    expect(congBo!.courseName).toBe(setup.tenKhoa);
    expect(congBo!.subjectName).toBe(setup.tenMon1);
    expect(String(congBo!.score)).toContain("9.5");
    expect(congBo!.publishedAt).toBeTruthy();
    expect(ketQua.some((item) => item.comment === setup.nhanXetNhap)).toBe(false);
    for (const item of ketQua) {
      const truong = Object.keys(item);
      for (const cam of ["submission", "submissionId", "body", "answer", "contentStub"]) {
        expect(truong).not.toContain(cam);
      }
    }

    // Trên giao diện, trang kết quả của con hiện nhận xét đã công bố và không hiện
    // nhận xét còn nháp.
    await moTrangKetQua(
      page, `${frontendOrigin}/centers/${setup.workspaceId}/children/${setup.hocSinhX.userId}/results`);
    await expect(page.getByText(setup.nhanXetCongBo)).toBeVisible();
    await expect(page.getByText(setup.nhanXetNhap)).toHaveCount(0);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("đổi sid hoặc wid: parent không lấy được kết quả của con khác hay ở center khác", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungSanSangPhuHuynh(page, origin, env, daTao);
    await lienKetDaDuyet(page, origin, setup);
    await dangNhapLai(page, origin, setup.phuHuynh);

    // Truy vấn API trực tiếp với sid của học sinh khác cùng center: bị từ chối dù
    // học sinh đó có kết quả đã công bố.
    const conKhac = await layKetQuaCon(
      page, origin, setup.workspaceId, setup.hocSinhY.userId);
    expect(conKhac.status()).toBe(403);

    // Đổi wid sang center B: liên kết đã duyệt ở A không cấp quyền ở B.
    const ownerB = await taoNguoiDung(env, NHOM_ELIGIBLE, daTao);
    const { userId: ownerBAppId } = await dangNhapLayId(page, origin, ownerB);
    const csrf = await dangNhapLai(page, origin, setup.operator);
    const centerB = await taoCenter(page, origin, csrf, ownerBAppId);
    await xacNhanGoi(page, origin, csrf, centerB.workspaceId);
    await dangNhapLai(page, origin, setup.phuHuynh);
    const centerKhac = await layKetQuaCon(
      page, origin, centerB.workspaceId, setup.hocSinhX.userId);
    expect(centerKhac.status()).toBe(403);

    // Đổi URL sid trên giao diện: trang của học sinh Y không hiện kết quả đã công bố
    // của Y, cũng không hiện dữ liệu của X còn sót từ trang trước.
    await moTrangKetQua(
      page, `${frontendOrigin}/centers/${setup.workspaceId}/children/${setup.hocSinhX.userId}/results`);
    await expect(page.getByText(setup.nhanXetCongBo)).toBeVisible();
    await moTrangKetQua(
      page, `${frontendOrigin}/centers/${setup.workspaceId}/children/${setup.hocSinhY.userId}/results`);
    await expect(page.getByText(setup.nhanXetCongBoY)).toHaveCount(0);
    await expect(page.getByText(setup.nhanXetCongBo)).toHaveCount(0);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("chủ nhiệm không có vai trò quản lý thì bị từ chối khi duyệt liên kết", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const daTao: NguoiDungTest[] = [];
  try {
    const setup = await dungSanSangPhuHuynh(page, origin, env, daTao);

    const csrfParent = await dangNhapLai(page, origin, setup.phuHuynh);
    await guiRequestLienKet(
      page, origin, csrfParent, setup.workspaceId, setup.hocSinhX.userId);

    // Lấy id liên kết qua danh sách của manager: hợp đồng chỉ hứa danh sách cho
    // parent và owner/manager, không hứa cho giáo viên/chủ nhiệm.
    await dangNhapLai(page, origin, setup.manager);
    const lienKet = timLienKet(
      await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);

    // Chủ nhiệm chỉ là giáo viên của lớp, không có vai trò quản lý/chủ sở hữu:
    // gọi decision bị từ chối và liên kết vẫn ở REQUESTED.
    const csrfChuNhiem = await dangNhapLai(page, origin, setup.chuNhiem);
    const quyetDinh = await guiMutation(page, origin, csrfChuNhiem, "POST",
      `/api/workspaces/${setup.workspaceId}/guardian-links/${lienKet.id}/decision`,
      { decision: "APPROVE", expectedVersion: lienKet.version });
    expect(quyetDinh.status()).toBe(403);

    await dangNhapLai(page, origin, setup.manager);
    const lienKetSau = timLienKet(
      await layDanhSachLienKet(page, origin, setup.workspaceId), setup.hocSinhX.userId);
    expect(lienKetSau.state).toBe("REQUESTED");

    await dangNhapLai(page, origin, setup.phuHuynh);
    const ketQua = await layKetQuaCon(
      page, origin, setup.workspaceId, setup.hocSinhX.userId);
    expect(ketQua.status()).toBe(403);
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});

test("gỡ liên kết: phiên parent cũ mất quyền xem và back navigation không hiện dữ liệu cũ", async ({
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

    // Phiên parent riêng, giữ nguyên không đăng nhập lại trong suốt phần còn lại:
    // thu hồi phải có hiệu lực trên chính phiên cũ này, không nhờ phiên mới.
    const { page: trangParent } = await moBoiCanhDangNhap(
      browser, origin, setup.phuHuynh);
    try {
      const truocGo = await docKetQuaCon(await layKetQuaCon(
        trangParent, origin, setup.workspaceId, setup.hocSinhX.userId));
      expect(truocGo.some((item) => item.comment === setup.nhanXetCongBo)).toBe(true);
      const urlKetQua =
        `${frontendOrigin}/centers/${setup.workspaceId}/children/${setup.hocSinhX.userId}/results`;
      await moTrangKetQua(trangParent, urlKetQua);
      await expect(trangParent.getByText(setup.nhanXetCongBo)).toBeVisible();

      // Manager gỡ liên kết ở trang chính: trạng thái chuyển REVOKED. Danh sách của
      // manager chỉ gồm link còn tác động được (REQUESTED + APPROVED, B55(iii)) nên
      // trạng thái REVOKED đọc qua danh sách của chính parent — danh sách này gồm cả
      // lịch sử đã đóng — trên phiên parent cũ.
      const csrfManager = await dangNhapLai(page, origin, setup.manager);
      const go = await guiMutation(page, origin, csrfManager, "DELETE",
        `/api/workspaces/${setup.workspaceId}/guardian-links/${lienKet.id}`);
      expect(go.status()).toBe(202);
      const traLoiGo = (await go.json()) as MutationTraLoi;
      await choOperationThanhCong(page, origin, layOperationId(traLoiGo), traLoiGo.statusUrl);
      const lienKetSauGo = timLienKet(
        await layDanhSachLienKet(trangParent, origin, setup.workspaceId), setup.hocSinhX.userId);
      expect(lienKetSauGo.state).toBe("REVOKED");

      // Cùng phiên parent cũ: API bị từ chối ngay dù chưa hề đăng nhập lại.
      const sauGo = await layKetQuaCon(
        trangParent, origin, setup.workspaceId, setup.hocSinhX.userId);
      expect(sauGo.status()).toBe(403);

      // Tải lại trang kết quả đang mở: dữ liệu cũ không còn; rời trang rồi back quay
      // lại cũng không được hiện dữ liệu từ lịch sử điều hướng.
      const choTaiLai = trangParent.waitForResponse(
        (resp) => resp.url().includes("/students/") && resp.url().includes("/results")
          && resp.request().method() === "GET",
        { timeout: 30_000 },
      );
      await trangParent.reload();
      await choTaiLai;
      await expect(trangParent.getByText(setup.nhanXetCongBo)).toHaveCount(0);
      await trangParent.goto(`${frontendOrigin}/centers/${setup.workspaceId}/guardian-links`);
      await trangParent.goBack();
      await trangParent.waitForURL(urlKetQua);
      await expect.poll(
        async () => trangParent.getByText(setup.nhanXetCongBo).count(),
        { timeout: 10_000, message: "back navigation vẫn hiện kết quả sau khi gỡ liên kết" },
      ).toBe(0);
    } finally {
      await trangParent.context().close();
    }
  } finally {
    await xoaNguoiDung(env, daTao);
  }
});
