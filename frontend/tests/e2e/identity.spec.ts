import { randomUUID } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";
import { TestEnvironment, loginViaKeycloakForm, appRequestWhenReady, logoutViaApplicationForm } from "./support/test-environment";

// E2E danh tính US1 ở biên trình duyệt: Code login, verify mail, đổi mật khẩu tạm,
// workspace switch, logout và back-channel logout qua ứng dụng thật trên stack TEST.
// Spec cần origin ứng dụng đang chạy (backend cùng stack TEST) qua NATIVE_GATE_APP_ORIGIN,
// cùng cơ chế với native-gate.spec.ts. CI bắt buộc khởi động ứng dụng và đặt origin;
// local có thể chạy riêng nhóm không cần ứng dụng khi chưa đặt biến.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec danh tính.",
  );
  return appOrigin as string;
}

type SessionView = {
  authenticated?: boolean;
  csrf?: { headerName: string; token: string };
  user?: { username?: string; emailVerified?: boolean };
};

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
}

// Đầu dò operation không tồn tại: 503 trước bootstrap, 404 sau bootstrap, 302 khi mất phiên.
async function goiOperationDauDo(page: Page, origin: string) {
  return page.request.get(`${origin}/api/me/operations/${randomUUID()}`, { maxRedirects: 0 });
}

async function laySessionView(page: Page, origin: string): Promise<SessionView> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  return (await response.json()) as SessionView;
}

test("đăng nhập Code qua ứng dụng: phiên được lập, session view khớp danh tính và không mang token", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);

    const sessionCookie = (await page.context().cookies(origin))
      .find((cookie) => cookie.name === "SESSION");
    expect(sessionCookie?.httpOnly).toBe(true);

    const session = await laySessionView(page, origin);
    expect(session.authenticated).toBe(true);
    expect(session.user?.username).toBe(user.username);
    expect(session.user?.emailVerified).toBe(true);
    expect(session.csrf?.headerName).toMatch(/^X-/);
    expect(session.csrf?.token).toBeTruthy();

    // /api/session đã bootstrap directory; operation không tồn tại phải trả 404.
    const probe = await goiOperationDauDo(page, origin);
    expect(probe.status()).toBe(404);
  } finally {
    await env.deleteUser(user);
  }
});

test("email chưa xác minh: đăng nhập bị chặn ở bước verify email của Keycloak và không lập phiên ứng dụng", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser(false);
  try {
    await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);

    // Required action VERIFY_EMAIL giữ người dùng ở phía Keycloak, không quay về ứng dụng.
    await page.waitForURL((url) => url.origin === env.keycloak.origin);
    await expect(page.locator("body")).toContainText(/verif/i);

    const probe = await goiOperationDauDo(page, origin);
    expect(probe.status()).toBe(302);
    expect(probe.headers()["location"]).toContain("/oauth2/authorization/keycloak");
  } finally {
    await env.deleteUser(user);
  }
});

test("bấm link xác minh trong email (Mailpit) thì đăng nhập được và session báo email đã xác minh", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser(false);
  let message: Awaited<ReturnType<TestEnvironment["waitForEmail"]>> | undefined;
  try {
    const requestedAt = Date.now();
    await env.requestVerificationEmail(user);
    message = await env.waitForEmail(user.email, requestedAt);

    await page.goto(await env.readVerificationLink(message));
    await page.getByRole("link", { name: /Click here to proceed/i }).click();
    await expect(page.getByRole("heading", { name: "Account updated", exact: true })).toBeVisible();

    await dangNhapQuaUngDung(page, origin, user);
    const session = await laySessionView(page, origin);
    expect(session.authenticated).toBe(true);
    expect(session.user?.emailVerified).toBe(true);
  } finally {
    try {
      if (message) await env.deleteEmail(message);
    } finally {
      await env.deleteUser(user);
    }
  }
});

test("mật khẩu tạm: lần đăng nhập đầu bị ép đổi mật khẩu, mật khẩu cũ không dùng lại được", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUserWithTemporaryPassword();
  const newPassword = `${user.password}Moi1!`;
  try {
    await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);

    // Credential tạm kéo theo required action UPDATE_PASSWORD trước khi vào ứng dụng.
    await page.locator("#password-new").fill(newPassword);
    await page.locator("#password-confirm").fill(newPassword);
    await page.locator("#kc-submit").click();
    await page.waitForURL(
      (url) => url.origin === new URL(origin).origin && url.pathname === "/",
    );
    expect((await goiOperationDauDo(page, origin)).status()).toBe(503);

    // Mật khẩu tạm cũ bị từ chối; mật khẩu mới vào thẳng, không bị ép đổi lần hai.
    await page.context().clearCookies();
    await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
    // Sau lần đăng nhập bị từ chối, trang vẫn ở bước 2 (chỉ có #password, kèm lỗi),
    // không quay về bước 1 nên #username không còn trong DOM.
    await expect(page.locator("#input-error-password")).toBeVisible();

    // Đang ở bước 2 sau lần đăng nhập bị từ chối: chỉ cần điền mật khẩu mới.
    await page.locator("#password").fill(newPassword);
    await page.locator("#kc-login").click();
    await page.waitForURL(
      (url) => url.origin === new URL(origin).origin && url.pathname === "/",
    );
    await expect(page.locator("#password-new")).toHaveCount(0);
  } finally {
    await env.deleteUser(user);
  }
});

test("workspace switch: danh sách theo hợp đồng, workspace lạ bị từ chối và cookie active-workspace giả không đổi được danh sách", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);

    await laySessionView(page, origin);
    const response = await appRequestWhenReady(page, `${origin}/api/me/workspaces`);
    expect(response.status()).toBe(200);
    const workspaces = (await response.json()) as {
      id: string; kind: string; name: string; roles: string[]; syncState: string;
    }[];
    expect(Array.isArray(workspaces)).toBe(true);
    for (const workspace of workspaces) {
      expect(typeof workspace.id).toBe("string");
      expect(["PERSONAL", "CENTER", "SYSTEM"]).toContain(workspace.kind);
      expect(typeof workspace.name).toBe("string");
      expect(Array.isArray(workspace.roles)).toBe(true);
      expect(typeof workspace.syncState).toBe("string");
    }
    expect(workspaces.some((workspace) => workspace.kind === "PERSONAL")).toBe(true);

    // Route workspaceId không phải bằng chứng quyền: workspace không tồn tại/không thuộc
    // về actor trả 404, không lộ sự tồn tại.
    const stranger = await page.request.get(
      `${origin}/api/workspaces/${randomUUID()}/classes`,
    );
    expect(stranger.status()).toBe(403);
    const detail = await page.request.get(`${origin}/api/workspaces/${randomUUID()}/classes/${randomUUID()}`);
    expect(detail.status()).toBe(404);

    // Backend không tin active-workspace cookie: cookie giả không làm danh sách đổi khác.
    await page.context().addCookies([
      { name: "active-workspace", value: randomUUID(), url: origin },
    ]);
    const after = await appRequestWhenReady(page, `${origin}/api/me/workspaces`);
    const afterIds = ((await after.json()) as { id: string }[]).map((w) => w.id).sort();
    expect(afterIds).toEqual(workspaces.map((w) => w.id).sort());
  } finally {
    await env.deleteUser(user);
  }
});

test("logout: hủy phiên ứng dụng và cả phiên SSO Keycloak", async ({ page }) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    const session = await laySessionView(page, origin);
    expect(session.csrf?.token).toBeTruthy();

    // Gửi form POST /api/logout kèm _csrf đúng cách frontend vẫn làm (api-client submitLogout).
    await logoutViaApplicationForm(page, origin, session.csrf!.token);

    expect((await laySessionView(page, origin)).authenticated).toBe(false);
    const probe = await goiOperationDauDo(page, origin);
    expect(probe.status()).toBe(302);

    // RP-Initiated Logout đã kết thúc phiên SSO: mở lại đường đăng nhập phải gặp form
    // Keycloak, không được lặng lẽ đăng nhập lại bằng phiên SSO cũ.
    await page.goto(`${origin}/oauth2/authorization/keycloak`);
    await expect(page.locator("#username")).toBeVisible();
  } finally {
    await env.deleteUser(user);
  }
});

// Điều kiện môi trường: client giaokhoa-it-web phải có backchannel.logout.url trỏ về
// {origin}/logout/connect/back-channel/keycloak và backchannel.logout.session.required=true
// (theo tài liệu Keycloak về Back-Channel Logout) thì Keycloak mới gửi logout_token khi
// phiên SSO bị kết thúc từ bên ngoài; verify-oidc-logout.mjs cấu hình tương tự cho client
// tạm của nó. Môi trường gate/review cấu hình client trước khi chạy spec này.
test("back-channel: admin kết thúc phiên SSO thì phiên ứng dụng cũng bị hủy", async ({ page }) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    expect((await goiOperationDauDo(page, origin)).status()).toBe(503);

    await env.terminateUserSessions(user);

    await expect.poll(
      async () => (await goiOperationDauDo(page, origin)).status(),
      { timeout: 15_000 },
    ).toBe(302);
  } finally {
    await env.deleteUser(user);
  }
});
