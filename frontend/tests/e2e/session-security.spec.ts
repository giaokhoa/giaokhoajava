import { randomUUID } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";
import { TestEnvironment, loginViaKeycloakForm } from "./support/test-environment";

// Kiểm chứng bảo mật phiên ở biên trình duyệt: không token trong DOM/network/SSR,
// Cache-Control no-store trên phản hồi bảo vệ, CSRF cho mọi mutation (back-channel là
// ngoại lệ duy nhất), chưa eligible thì không tạo được action mới, và tính bền của phiên
// qua khởi động lại trình duyệt. Cùng cơ chế cổng môi trường với native-gate.spec.ts:
// cần NATIVE_GATE_APP_ORIGIN của ứng dụng đang chạy trên stack TEST, chưa đặt thì tự bỏ qua.
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec bảo mật phiên.",
  );
  return appOrigin as string;
}

const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/;
const TOKEN_MARKERS = ["access_token", "id_token", "refresh_token"];

async function dangNhapQuaUngDung(
  page: Page,
  origin: string,
  user: { username: string; password: string },
): Promise<void> {
  await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
  await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
}

async function goiOperationDauDo(page: Page, origin: string) {
  return page.request.get(`${origin}/api/me/operations/${randomUUID()}`, { maxRedirects: 0 });
}

async function layCsrf(page: Page, origin: string): Promise<{ headerName: string; token: string }> {
  const response = await page.request.get(`${origin}/api/session`);
  expect(response.status()).toBe(200);
  const session = (await response.json()) as {
    csrf?: { headerName: string; token: string };
  };
  expect(session.csrf?.token).toBeTruthy();
  return session.csrf!;
}

async function dangXuatBangForm(page: Page, token: string): Promise<void> {
  await page.evaluate((csrfToken) => {
    const form = document.createElement("form");
    form.method = "POST";
    form.action = "/api/logout";
    const csrf = document.createElement("input");
    csrf.type = "hidden";
    csrf.name = "_csrf";
    csrf.value = csrfToken;
    form.appendChild(csrf);
    document.body.appendChild(form);
    form.submit();
  }, token);
}

test("không token trong DOM/network/SSR: trình duyệt không gửi Authorization và storage không giữ token", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  const requestHeaders: Record<string, string>[] = [];
  const requestUrls: string[] = [];
  const apiBodies: string[] = [];
  page.on("request", (request) => {
    requestUrls.push(request.url());
    requestHeaders.push(request.headers());
  });
  page.on("response", async (response) => {
    if (!response.url().includes("/api/")) return;
    try {
      apiBodies.push(await response.text());
    } catch {
      // Response điều hướng/đã bị hủy không có body đọc được; bỏ qua đúng các response đó.
    }
  });
  try {
    await dangNhapQuaUngDung(page, origin, user);
    await page.request.get(`${origin}/api/session`);
    await goiOperationDauDo(page, origin);

    for (const headers of requestHeaders) {
      expect(headers["authorization"]).toBeUndefined();
    }
    for (const url of requestUrls) {
      for (const marker of TOKEN_MARKERS) {
        expect(url).not.toContain(marker);
      }
    }
    const bodies = apiBodies.join("\n");
    for (const marker of TOKEN_MARKERS) {
      expect(bodies).not.toContain(marker);
    }
    expect(bodies).not.toMatch(JWT_PATTERN);
    expect(await page.content()).not.toMatch(JWT_PATTERN);

    const storage = await page.evaluate(() => JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }));
    expect(storage.toLowerCase()).not.toContain("token");
    expect(storage).not.toMatch(JWT_PATTERN);
  } finally {
    await env.deleteUser(user);
  }
});

test("no-store: session view mang Cache-Control private, no-store cả khi ẩn danh và đã đăng nhập", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    const anonymous = await page.request.get(`${origin}/api/session`);
    expect(anonymous.status()).toBe(200);
    expect(anonymous.headers()["cache-control"]).toContain("private");
    expect(anonymous.headers()["cache-control"]).toContain("no-store");

    await dangNhapQuaUngDung(page, origin, user);
    const authenticated = await page.request.get(`${origin}/api/session`);
    expect(authenticated.status()).toBe(200);
    expect(authenticated.headers()["cache-control"]).toContain("private");
    expect(authenticated.headers()["cache-control"]).toContain("no-store");
  } finally {
    await env.deleteUser(user);
  }
});

test("CSRF: mutation thiếu token bị 403; back-channel là ngoại lệ duy nhất và token sai hình dạng vẫn bị 400", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    // CSRF bảo vệ phiên đã đăng nhập: các POST dưới đây mang cookie phiên thật nhưng
    // không có token, nên phải bị 403 (chưa đăng nhập thì ứng dụng chuyển 302 tới login).
    await dangNhapQuaUngDung(page, origin, user);

    const logout = await page.request.post(`${origin}/api/logout`);
    expect(logout.status()).toBe(403);

    const action = await page.request.post(
      `${origin}/api/workspaces/${randomUUID()}/samples/actions`,
      {
        headers: { "Idempotency-Key": randomUUID() },
        data: {
          action: "CREATE_RESOURCE",
          subjectId: randomUUID(),
          kind: "QUESTION",
          label: "Bài mẫu",
        },
      },
    );
    expect(action.status()).toBe(403);

    // Route back-channel được miễn CSRF/session theo hợp đồng, nhưng lớp chặn hình dạng
    // của Spring vẫn từ chối logout_token không phải JWT ký đúng hình dạng bằng 400.
    const forged = await page.request.post(
      `${origin}/logout/connect/back-channel/keycloak`,
      { form: { logout_token: "bad" } },
    );
    expect(forged.status()).toBe(400);
    const missing = await page.request.post(`${origin}/logout/connect/back-channel/keycloak`);
    expect(missing.status()).toBe(400);
  } finally {
    await env.deleteUser(user);
  }
});

test("chưa eligible thì không tạo được action mới: POST action kèm CSRF hợp lệ vẫn bị 503 fail-closed", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    const csrf = await layCsrf(page, origin);

    const response = await page.request.post(
      `${origin}/api/workspaces/${randomUUID()}/samples/actions`,
      {
        headers: { [csrf.headerName]: csrf.token, "Idempotency-Key": randomUUID() },
        data: {
          action: "CREATE_RESOURCE",
          subjectId: randomUUID(),
          kind: "QUESTION",
          label: "Bài mẫu",
        },
      },
    );
    expect(response.status()).toBe(503);
    expect((await response.json() as { code?: string }).code).toBe("AUTHORIZATION_NOT_READY");
  } finally {
    await env.deleteUser(user);
  }
});

// Playwright lưu/trả trạng thái trình duyệt bằng storageState (cookie + storage); mở
// context mới từ state đã lưu mô phỏng khởi động lại trình duyệt. Tính bền qua khởi động
// lại ỨNG DỤNG (session JDBC phía server) do OidcSessionPersistenceIT và
// infra/keycloak/verify-oidc-logout.mjs kiểm chứng ở tầng của chúng.
test("khởi động lại trình duyệt: context mới từ storage state đã lưu vẫn giữ phiên", async ({
  page,
  browser,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    const state = await page.context().storageState();

    const restarted = await browser.newContext({ storageState: state });
    try {
      const page2 = await restarted.newPage();
      expect((await goiOperationDauDo(page2, origin)).status()).toBe(503);
    } finally {
      await restarted.close();
    }
  } finally {
    await env.deleteUser(user);
  }
});

test("sau logout, storage state cũ không hồi sinh được phiên", async ({ page, browser }) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await dangNhapQuaUngDung(page, origin, user);
    const state = await page.context().storageState();

    const csrf = await layCsrf(page, origin);
    await dangXuatBangForm(page, csrf.token);
    await page.waitForURL((url) => url.origin === new URL(origin).origin && url.pathname === "/");
    expect((await goiOperationDauDo(page, origin)).status()).toBe(302);

    // Cookie trong state cũ trỏ tới session đã bị hủy phía server: context mới vẫn bị 302.
    const revived = await browser.newContext({ storageState: state });
    try {
      const page2 = await revived.newPage();
      const probe = await goiOperationDauDo(page2, origin);
      expect(probe.status()).toBe(302);
      expect(probe.headers()["location"]).toContain("/oauth2/authorization/keycloak");
    } finally {
      await revived.close();
    }
  } finally {
    await env.deleteUser(user);
  }
});
