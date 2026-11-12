import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import {
  TestEnvironment,
  appRequestWhenReady,
  loginViaKeycloakForm,
} from "./support/test-environment";

// Kiểm tra hợp đồng UI bằng API giả; quyền thật được kiểm tra riêng trong IdentityFlowIT.
test("recovery giữ key khi retry, gửi If-Match và chỉ hoàn tất sau xác nhận bàn giao", { tag: ["@layer-ui", "@flow-QT-03", "@role-platform-operator"] }, async ({ page }) => {
  const requestId = randomUUID();
  let state = "REQUESTED";
  let version = 0;
  const mutations: { body: Record<string, string>; key: string; version: string }[] = [];

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/session") {
      await route.fulfill({ json: {
        authenticated: true,
        csrf: { headerName: "X-CSRF-TOKEN", token: "ui-test-csrf" },
        user: { id: "operator-ui", username: "operator", emailVerified: true, identityState: "ACTIVE" },
      } });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/resolve")) {
      const body = request.postDataJSON() as Record<string, string>;
      mutations.push({ body, key: request.headers()["idempotency-key"], version: request.headers()["if-match"] });
      if (mutations.length === 1) {
        await route.abort("failed");
        return;
      }
      if (body.decision === "APPROVE") {
        state = "VERIFIED";
        version = 1;
        await route.fulfill({ status: 202, json: {
          operationId: requestId, state: "PENDING", completed: false,
          statusUrl: `/api/me/operations/${requestId}`,
        } });
      } else {
        state = "RESOLVED";
        version = 2;
        await route.fulfill({ json: { id: requestId, state: "SUCCEEDED", completed: true } });
      }
      return;
    }
    await route.fulfill({ json: [{ id: requestId, targetUserId: "target-ui", state, version }] });
  });

  await page.goto("/platform/recovery-requests");
  const approve = page.getByRole("button", { name: "Đã xác minh danh tính", exact: true });
  const note = page.getByLabel("Ghi chú xác minh");
  await note.fill("Đã xác minh đúng chủ tài khoản");
  await approve.click();
  await expect(approve).toBeEnabled();
  await approve.click();
  const complete = page.getByRole("button", { name: "Xác nhận đã khôi phục và bàn giao", exact: true });
  await expect(complete).toBeEnabled();
  expect(mutations).toHaveLength(2);
  expect(mutations[0].key).toBeTruthy();
  expect(mutations[1].key).toBe(mutations[0].key);
  expect(mutations[1].version).toBe('"0"');
  await expect(page.getByText("Đã xác minh", { exact: true })).toBeVisible();

  await note.fill("Đã khôi phục và bàn giao qua kênh riêng");
  await complete.click();
  await expect(page.getByText("Đã giải quyết", { exact: true })).toBeVisible();
  expect(mutations[2].body.decision).toBe("COMPLETE");
  expect(mutations[2].version).toBe('"1"');
  expect(mutations[2].key).not.toBe(mutations[1].key);
});

// OperationId của recovery là ID hồ sơ kéo dài qua REQUESTED/VERIFIED/RESOLVED:
// trước khi bàn giao nó PHẢI PENDING; chỉ COMPLETE/REJECT mới kết thúc SUCCEEDED.
async function waitForLastRecoveryOperation(
  page: import("@playwright/test").Page,
  origin: string,
  expected: "PENDING" | "SUCCEEDED",
) {
  const id = (await page.locator('section[aria-labelledby="operation-title"] code').last()
    .textContent())?.trim();
  expect(id).toMatch(/^[0-9a-f-]{36}$/i);
  await expect.poll(async () => {
    const response = await page.request.get(`${origin}/api/me/operations/${id}`);
    if (response.status() !== 200) return `HTTP_${response.status()}`;
    const body = (await response.json()) as { state: string; completed: boolean };
    return body.completed ? body.state : `PENDING:${body.state}`;
  }, { timeout: 60_000, message: `Recovery hồ sơ ${id} chưa đạt ${expected}` })
    .toBe(expected === "SUCCEEDED" ? "SUCCEEDED" : "PENDING:PENDING");
}

// QT-03 REAL: không page.route, không fake actor, phiên Keycloak riêng requester/operator.
// Phần reset credential là bước vận hành ngoài ứng dụng qua Keycloak ADMIN TEST,
// còn yêu cầu/duyệt/bàn giao và quyền scope được kiểm ở các route/UI thật.
test("QT-03: requester thật gửi khôi phục, người thường không thấy queue, operator xác minh và bàn giao", {
  tag: ["@layer-e2e", "@flow-QT-03", "@role-platform-operator", "@role-user"],
}, async ({ page, browser }) => {
  test.setTimeout(600_000);
  const origin = process.env.NATIVE_GATE_APP_ORIGIN;
  test.skip(!origin, "Cần NATIVE_GATE_APP_ORIGIN thật trên stack TEST để chạy QT-03");
  const apiOrigin = origin!;
  const frontend = process.env.TEST_FRONTEND_ORIGIN ?? "http://localhost:3001";
  const env = new TestEnvironment();
  const requester = await env.createUser();
  const operator = await env.createUser();
  const reason = `QT03-browser-${randomUUID()}`;
  let operatorContext: Awaited<ReturnType<typeof browser.newContext>> | undefined;
  let checkContext: Awaited<ReturnType<typeof browser.newContext>> | undefined;
  try {
    await env.addToRealmGroup(requester, "/gk/identity/eligible");
    await env.addToRealmGroup(operator, "/gk/platform/operators");
    await loginViaKeycloakForm(page, `${apiOrigin}/oauth2/authorization/keycloak`, requester);
    await page.waitForURL((url) => url.origin === new URL(apiOrigin).origin
      && url.pathname === "/", { timeout: 60_000 });
    const requesterSession = await page.request.get(`${apiOrigin}/api/session`);
    expect(requesterSession.status()).toBe(200);
    const requesterId = (await requesterSession.json() as { user?: { id?: string } }).user?.id;
    expect(requesterId).toBeTruthy();
    await page.goto(`${frontend}/personal`);
    await page.getByLabel("Lý do cần hỗ trợ").fill(reason);
    await page.getByRole("button", { name: "Gửi yêu cầu khôi phục" }).click();
    await expect(page.getByRole("heading", { name: "Trạng thái thao tác" })).toBeVisible();
    await waitForLastRecoveryOperation(page, apiOrigin, "PENDING");
    // Cookie cùng requester không cấp quyền platform.account.recover.
    const denied = await appRequestWhenReady(page, `${apiOrigin}/api/platform/recovery-requests`);
    expect(denied.status()).toBe(403);

    operatorContext = await browser.newContext();
    const operatorPage = await operatorContext.newPage();
    await loginViaKeycloakForm(operatorPage,
      `${apiOrigin}/oauth2/authorization/keycloak`, operator);
    await operatorPage.waitForURL((url) => url.origin === new URL(apiOrigin).origin
      && url.pathname === "/", { timeout: 60_000 });
    await operatorPage.goto(`${frontend}/platform/recovery-requests`);
    const row = operatorPage.locator("li").filter({ hasText: reason });
    await expect(row).toBeVisible({ timeout: 60_000 });
    await expect(row).toContainText(requesterId!);
    await row.getByLabel("Ghi chú xác minh").fill("Đối chiếu đúng người yêu cầu ngoài hệ thống");
    await row.getByRole("button", { name: "Đã xác minh danh tính" }).click();
    await waitForLastRecoveryOperation(operatorPage, apiOrigin, "PENDING");
    await expect(row.getByText("Đã xác minh", { exact: true }))
      .toBeVisible({ timeout: 60_000 });

    // Bước reset/bàn giao do operator vận hành NGOÀI app, chỉ dùng Keycloak TEST fixture.
    const newPassword = `QT03-${randomUUID()}-Ab12!`;
    await env.resetTestUserPassword(requester, newPassword);
    await row.getByLabel("Ghi chú xác minh").fill("Đã đổi credential qua kênh riêng, bàn giao đúng người");
    await row.getByRole("button", { name: "Xác nhận đã khôi phục và bàn giao" }).click();
    await waitForLastRecoveryOperation(operatorPage, apiOrigin, "SUCCEEDED");
    await expect(row.getByText("Đã giải quyết", { exact: true }))
      .toBeVisible({ timeout: 60_000 });

    // Credential đã bàn giao phải đăng nhập được qua Keycloak thật, không chỉ nhãn UI.
    checkContext = await browser.newContext();
    const verifyPage = await checkContext.newPage();
    await loginViaKeycloakForm(verifyPage,
      `${apiOrigin}/oauth2/authorization/keycloak`,
      { username: requester.username, password: newPassword });
    await verifyPage.waitForURL((url) => url.origin === new URL(apiOrigin).origin
      && url.pathname === "/", { timeout: 60_000 });
    const sessionAfter = await verifyPage.request.get(`${apiOrigin}/api/session`);
    expect(sessionAfter.status()).toBe(200);
    expect((await sessionAfter.json() as { user?: { id?: string } }).user?.id)
      .toBe(requesterId);
  } finally {
    if (checkContext) await checkContext.close();
    if (operatorContext) await operatorContext.close();
    await Promise.all([requester, operator].map((user) =>
      env.deleteUser(user).catch(() => { /* Cleanup best effort, không che lỗi test chính. */ })));
  }
});
