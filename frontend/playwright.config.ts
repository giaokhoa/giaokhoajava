import { defineConfig, devices } from "@playwright/test";
import { randomUUID } from "node:crypto";

const appOrigin = process.env.NATIVE_GATE_APP_ORIGIN;
const frontendOrigin = process.env.TEST_FRONTEND_ORIGIN ?? appOrigin ?? "http://localhost:3001";
const teachingAndGuardians = /(?:guardian-results|teaching-administration)\.spec\.ts$/;
if (process.env.CI && !appOrigin) {
  throw new Error("CI phải đặt NATIVE_GATE_APP_ORIGIN để chạy E2E thật, không được bỏ qua gate.");
}
if (appOrigin && (appOrigin !== "http://127.0.0.1:8080" || frontendOrigin !== appOrigin)) {
  throw new Error("E2E dùng một origin TEST 127.0.0.1:8080 cho cả frontend/backend.");
}
if (appOrigin) {
  // Backend và fixture Node dùng cùng schema ngẫu nhiên, không đưa cấu hình DB vào browser.
  process.env.TEST_E2E_SCHEMA ??= `e2e_${randomUUID().replaceAll("-", "")}`;
}

export default defineConfig({
  testDir: "./tests/e2e",
  globalTimeout: process.env.CI ? 20 * 60_000 : 0,
  reporter: process.env.CI
    ? [["line"], ["json", { outputFile: "playwright-report/results.json" }]]
    : "list",
  // Các file dùng chung realm TEST; test race tự tạo request đồng thời trong từng ca.
  workers: 1,
  use: { baseURL: frontendOrigin, trace: "retain-on-failure" },
  projects: [
    { name: "chromium-core", testIgnore: teachingAndGuardians, use: { ...devices["Desktop Chrome"] } },
    { name: "chromium-teaching-guardians", testMatch: teachingAndGuardians, use: { ...devices["Desktop Chrome"] } },
  ],
  webServer: [
    ...(appOrigin ? [{
      command: "bash ../infra/testing/run-backend.sh",
      url: "http://127.0.0.1:18080/api/session",
      reuseExistingServer: false,
      timeout: 120_000,
      gracefulShutdown: { signal: "SIGTERM" as const, timeout: 10_000 },
    }] : []),
    {
      command: appOrigin ? "npm run build && npm run start" : "npm run dev",
      url: appOrigin ? "http://127.0.0.1:3001" : frontendOrigin,
      reuseExistingServer: !appOrigin && !process.env.CI,
      timeout: 120_000,
    },
    ...(appOrigin ? [{
      command: "docker run --rm --network host -v \"$PWD/../infra/gateway/Caddyfile.test:/etc/caddy/Caddyfile:ro\" caddy:2.10.2-alpine",
      url: frontendOrigin,
      // Không dùng lại gateway DEV dù cổng 8080 trùng nhau.
      reuseExistingServer: false,
      timeout: 120_000,
      gracefulShutdown: { signal: "SIGTERM" as const, timeout: 10_000 },
    }] : []),
  ],
});
