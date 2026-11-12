import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

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
