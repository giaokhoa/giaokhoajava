import { expect, test, type Page } from "@playwright/test";
import {
  TestEnvironment,
  loginViaKeycloakForm,
  appRequestWhenReady,
  dongBoEligibility,
} from "./support/test-environment";

// Kiểm chứng biên bảo mật ở tầng trình duyệt mà session-security.spec.ts chưa giữ:
// cờ cookie phiên, CSRF token gắn với đúng phiên, IDOR chéo workspace, eligibility
// chặn cả đọc (không chỉ mutation), và route fixture không tồn tại trên stack chạy
// profile mặc định như production. Cùng cơ chế cổng môi trường với
// session-security.spec.ts: cần NATIVE_GATE_APP_ORIGIN của ứng dụng đang chạy trên
// stack TEST, chưa đặt thì tự bỏ qua.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec biên bảo mật.",
  );
  return appOrigin as string;
}

const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// dongBoEligibility poll tới 60s nên ca nào gọi nó cần hạn test dài hơn mặc định 30s.
const HAN_DUNG_TEST = 300_000;

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(
    page,
    `${origin}/oauth2/authorization/keycloak`,
    user,
  );
  await page.waitForURL(
    (url) => url.origin === new URL(origin).origin && url.pathname === "/",
  );
}

async function layCsrf(
  page: Page,
  origin: string,
): Promise<{ headerName: string; token: string }> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  const session = (await response.json()) as {
    csrf?: { headerName: string; token: string };
  };
  expect(session.csrf?.token).toBeTruthy();
  return session.csrf!;
}

async function layPersonalWorkspaceId(
  page: Page,
  origin: string,
): Promise<string> {
  const workspaces = await appRequestWhenReady(
    page,
    `${origin}/api/me/workspaces`,
  );
  expect(workspaces.status()).toBe(200);
  const personal = (
    (await workspaces.json()) as { id: string; kind: string }[]
  ).find((workspace) => workspace.kind === "PERSONAL");
  expect(personal).toBeTruthy();
  return personal!.id;
}

test("cookie phiên: SESSION HttpOnly + SameSite=Lax, giá trị là session id không phải JWT", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    const sessionCookie = (await page.context().cookies()).find(
      (cookie) => cookie.name === "SESSION",
    );
    expect(sessionCookie).toBeTruthy();
    expect(sessionCookie!.httpOnly).toBe(true);
    expect(sessionCookie!.sameSite).toBe("Lax");
    expect(sessionCookie!.value).toMatch(UUID_PATTERN);
    expect(sessionCookie!.value).not.toMatch(JWT_PATTERN);
  } finally {
    await env.deleteUser(user);
  }
});

test("CSRF token gắn với phiên: token của phiên khác bị 403", async ({
  page,
  browser,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const userA = await env.createUser();
  const userB = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, userA);
    const csrfA = await layCsrf(page, origin);

    const contextB = await browser.newContext();
    try {
      const pageB = await contextB.newPage();
      await dangNhapQuaUngDung(pageB, origin, userB);
      const logout = await pageB.request.post(`${origin}/api/logout`, {
        headers: { [csrfA.headerName]: csrfA.token },
      });
      expect(logout.status()).toBe(403);
    } finally {
      await contextB.close();
    }
  } finally {
    await env.deleteUser(userA);
    await env.deleteUser(userB);
  }
});

test("IDOR: thành viên không xem được danh sách membership của workspace khác", async ({
  page,
  browser,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const userA = await env.createUser();
  const userB = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, userA);
    await dongBoEligibility(page, origin);

    const contextB = await browser.newContext();
    try {
      const pageB = await contextB.newPage();
      await dangNhapQuaUngDung(pageB, origin, userB);
      await dongBoEligibility(pageB, origin);
      const personalB = await layPersonalWorkspaceId(pageB, origin);

      // Đối chứng dương: chủ workspace đọc được danh sách của chính mình.
      const own = await appRequestWhenReady(
        pageB,
        `${origin}/api/workspaces/${personalB}/memberships`,
      );
      expect(own.status()).toBe(200);

      // Người ngoài không có membership hiện hành trong workspace đó thì bị 403.
      const foreign = await appRequestWhenReady(
        page,
        `${origin}/api/workspaces/${personalB}/memberships`,
      );
      expect(foreign.status()).toBe(403);
      expect(((await foreign.json()) as { code?: string }).code).toBe(
        "PERMISSION_DENIED",
      );
    } finally {
      await contextB.close();
    }
  } finally {
    await env.deleteUser(userA);
    await env.deleteUser(userB);
  }
});

test("người mất eligibility không đọc được danh sách workspace", async ({
  page,
}) => {
  test.setTimeout(HAN_DUNG_TEST);
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    await dongBoEligibility(page, origin);
    const ready = await appRequestWhenReady(
      page,
      `${origin}/api/me/workspaces`,
    );
    expect(ready.status()).toBe(200);

    await env.setEmailVerified(user, false);
    const denied = await page.request.get(`${origin}/api/me/workspaces`);
    expect([403, 503]).toContain(denied.status());
    expect(["PERMISSION_DENIED", "AUTHORIZATION_NOT_READY"]).toContain(
      ((await denied.json()) as { code?: string }).code,
    );
  } finally {
    await env.deleteUser(user);
  }
});

test("production không fixture: ẩn danh bị chuyển về đăng nhập, đã đăng nhập trả 404", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    // Route chỉ đăng ký khi profile test/review bật; stack e2e chạy profile mặc định
    // như production nên route không tồn tại. Ẩn danh không tới được khâu tìm handler:
    // security chain chuyển về đăng nhập trước, nên chỉ khóa được 302; 404 chỉ quan
    // sát được khi đã đăng nhập. Phải kèm CSRF token hợp lệ để CsrfFilter không chặn
    // 403 trước — 403 sẽ che mất đáp án cần khóa. Playwright mặc định theo redirect
    // nên phải tắt theo redirect để thấy 302 gốc thay vì 200 của trang đăng nhập.
    const anonymousCsrf = await layCsrf(page, origin);
    const anonymous = await page.request.post(
      `${origin}/api/verification/fixtures`,
      {
        headers: { [anonymousCsrf.headerName]: anonymousCsrf.token },
        data: { scenarioSet: "resource-access" },
        maxRedirects: 0,
      },
    );
    expect(anonymous.status()).toBe(302);
    expect(anonymous.headers().location ?? "").toContain(
      "/oauth2/authorization/keycloak",
    );

    await dangNhapQuaUngDung(page, origin, user);
    const csrf = await layCsrf(page, origin);
    const authenticated = await page.request.post(
      `${origin}/api/verification/fixtures`,
      {
        headers: { [csrf.headerName]: csrf.token },
        data: { scenarioSet: "resource-access" },
      },
    );
    expect(authenticated.status()).toBe(404);
  } finally {
    await env.deleteUser(user);
  }
});
