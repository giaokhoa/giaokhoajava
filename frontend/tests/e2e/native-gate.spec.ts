import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { TestEnvironment, loginViaKeycloakForm } from "./support/test-environment";

// Gate native ở biên trình duyệt cho GET operation status. Spec cần origin ứng dụng của môi
// trường gate/review (backend chạy cùng stack TEST) qua NATIVE_GATE_APP_ORIGIN; khi biến chưa
// được đặt, spec tự bỏ qua — cùng mẫu với các native IT bị loại khỏi Failsafe và chỉ chạy qua
// runner riêng. Hành vi đầy đủ của endpoint (initiator đọc operation PENDING/SUCCEEDED của
// chính mình, operation của người khác thành 404) do OperationStatusIT kiểm chứng trong
// profile integration; spec này bổ sung bằng chứng phiên trình duyệt thật và fail-closed.
// PHÂN TẦNG: giữ ở tầng App E2E vì giá trị riêng của spec là bằng chứng PHIÊN
// TRÌNH DUYỆT thật (chưa đăng nhập thì bị chuyển tới đăng nhập; phiên Keycloak
// thật nhưng identity chưa có thư mục thì fail-closed, không lộ secret) — phần
// IT thuần không có phiên trình duyệt để khóa hai hành vi này. Khác
// auth-code.spec.ts (giao thức thuần, đã ghi đề xuất chuyển tầng ở đầu file đó).
const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;

function gateOrigin(): string {
  test.skip(
    !appOrigin,
    "Cần NATIVE_GATE_APP_ORIGIN của môi trường gate/review để chạy spec native gate.",
  );
  return appOrigin as string;
}

// Các marker secret/payload nội bộ không bao giờ được xuất hiện trong chẩn đoán của gate.
const SECRET_MARKERS = [
  "payloadHash",
  "idempotencyKey",
  "affectedPartitions",
  "access_token",
  "password",
];

test("chưa đăng nhập thì bị chuyển tới đăng nhập, không nhận trạng thái operation", async ({
  page,
}) => {
  const origin = gateOrigin();
  const response = await page.request.get(`${origin}/api/me/operations/${randomUUID()}`, {
    maxRedirects: 0,
  });

  expect(response.status()).toBe(302);
  expect(response.headers()["location"]).toContain("/oauth2/authorization/keycloak");
});

test("đăng nhập thật qua Keycloak: identity chưa có thư mục thì fail-closed, không lộ secret", async ({
  page,
}) => {
  const origin = gateOrigin();
  const env = new TestEnvironment();
  const user = await env.createUser();
  try {
    await loginViaKeycloakForm(page, `${origin}/oauth2/authorization/keycloak`, user);
    await page.waitForURL(
      (url) => url.origin === new URL(origin).origin && url.pathname === "/",
    );

    // User Keycloak thật nhưng chưa có user_account: resolve actor phải fail-closed 503,
    // không trả bất kỳ trạng thái operation nào và không lộ secret trong chẩn đoán lỗi.
    const response = await page.request.get(`${origin}/api/me/operations/${randomUUID()}`);
    expect(response.status()).toBe(503);
    const raw = await response.text();
    const body = JSON.parse(raw) as { code?: string };
    expect(body.code).toBe("AUTHORIZATION_NOT_READY");
    for (const marker of SECRET_MARKERS) {
      expect(raw).not.toContain(marker);
    }
  } finally {
    await env.deleteUser(user);
  }
});
