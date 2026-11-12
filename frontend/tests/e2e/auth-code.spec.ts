import { expect, test } from "@playwright/test";
import { TestEnvironment } from "./support/test-environment";

// PHÂN TẦNG: đây là test GIAO THỨC OIDC (Authorization Code + PKCE với Keycloak
// thật, đổi code ở phía Node) và hạ tầng thư (Mailpit) — không phải hành trình
// người dùng qua UI của ứng dụng, nên về bản chất thuộc tầng giao thức/tích hợp
// phía backend (cùng nhóm kiểm chứng với NativeGateAcceptanceIT), không phải
// App E2E. File vẫn nằm trong frontend/tests/e2e vì CI hiện chạy nó qua project
// Playwright chromium-core với bộ biến TEST_* của job integration; chuyển file
// ra khỏi tests/e2e lúc này sẽ khiến CI ngừng chạy nó. Đề xuất đặt lại tầng:
// chuyển hai test này thành class phía backend trong profile integration (đổi
// code/PKCE bằng HTTP client của test; Mailpit qua fixture tương đương) và gỡ
// file khỏi tests/e2e trong cùng một thay đổi.

test("Keycloak thật cấp Authorization Code + PKCE và đổi code lấy token", { tag: ["@layer-protocol", "@flow-TK-01", "@role-user"] }, async ({ page }) => {
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await env.exchangeAuthorizationCode(page, user);
  } finally {
    await env.deleteUser(user);
  }
});

test("Mailpit nhận email xác minh từ realm TEST", { tag: ["@layer-protocol", "@flow-TK-01", "@role-user"] }, async () => {
  const env = new TestEnvironment();
  const user = await env.createUser(false);
  let message: Awaited<ReturnType<TestEnvironment["waitForEmail"]>> | undefined;
  try {
    const requestedAt = Date.now();
    await env.requestVerificationEmail(user);
    message = await env.waitForEmail(user.email, requestedAt);
    expect(message.Subject).toBeTruthy();
  } finally {
    try {
      if (message) await env.deleteEmail(message);
    } finally {
      await env.deleteUser(user);
    }
  }
});
