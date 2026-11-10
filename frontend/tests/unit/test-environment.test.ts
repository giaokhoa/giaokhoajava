import { randomUUID } from "node:crypto";
import { afterEach, expect, test, vi } from "vitest";
import { TestEnvironment } from "../e2e/support/test-environment";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

const USER_ID = randomUUID();

function stubEnvTest() {
  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18081");
  vi.stubEnv("TEST_KEYCLOAK_REALM", "giaokhoa-it");
  vi.stubEnv("TEST_OIDC_CLIENT_ID", "giaokhoa-it-web");
  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:18025");
  vi.stubEnv("TEST_KEYCLOAK_ADMIN_CLIENT_ID", "giaokhoa-it-fixtures");
  vi.stubEnv("TEST_KEYCLOAK_ADMIN_CLIENT_SECRET", "test-only");
}

/** fetch giả theo kịch bản: token + tạo user luôn thành công, DELETE theo danh sách mã cho trước. */
function stubFetch(deleteStatuses: number[]) {
  const calls: { method: string; url: string }[] = [];
  const deletes = [...deleteStatuses];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: { method?: string }) => {
      const method = init?.method ?? "GET";
      calls.push({ method, url });
      if (url.includes("/protocol/openid-connect/token")) {
        return {
          ok: true,
          status: 200,
          json: async () => ({ access_token: "token-test" }),
          text: async (): Promise<string> => "",
        };
      }
      if (method === "POST" && url.includes("/admin/realms/giaokhoa-it/users")) {
        return {
          ok: true,
          status: 201,
          headers: { get: () => `http://127.0.0.1:18081/admin/realms/giaokhoa-it/users/${USER_ID}` },
          text: async (): Promise<string> => "",
        };
      }
      if (method === "DELETE") {
        const status = deletes.shift() ?? 204;
        return {
          ok: status >= 200 && status < 300,
          status,
          headers: { get: () => null },
          text: async (): Promise<string> => (status === 409 ? '{"error":"conflict"}' : ""),
        };
      }
      throw new Error(`fetch giả không ngờ tới: ${method} ${url}`);
    }),
  );
  return calls;
}

test("deleteUser thử lại khi Keycloak trả 409 rồi xóa được", async () => {
  stubEnvTest();
  const calls = stubFetch([409, 204]);
  const env = new TestEnvironment();
  const user = await env.createUser();
  await env.deleteUser(user);
  expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(2);
}, 10_000);

test("deleteUser coi 404 là đã dọn xong", async () => {
  stubEnvTest();
  const calls = stubFetch([404]);
  const env = new TestEnvironment();
  const user = await env.createUser();
  await env.deleteUser(user);
  expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(1);
});

test("deleteUser 409 dai dẳng thì ném lỗi kèm nội dung Keycloak", async () => {
  stubEnvTest();
  const calls = stubFetch([409, 409, 409]);
  const env = new TestEnvironment();
  const user = await env.createUser();
  await expect(env.deleteUser(user)).rejects.toThrow(/409.*conflict/);
  expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(3);
}, 10_000);

test("deleteUser không thử lại lỗi khác 409", async () => {
  stubEnvTest();
  const calls = stubFetch([500]);
  const env = new TestEnvironment();
  const user = await env.createUser();
  await expect(env.deleteUser(user)).rejects.toThrow(/500/);
  expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(1);
});


test("chỉ cho phép Keycloak và Mailpit của local Compose TEST", () => {
  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18081");
  vi.stubEnv("TEST_KEYCLOAK_REALM", "giaokhoa-it");
  vi.stubEnv("TEST_OIDC_CLIENT_ID", "giaokhoa-it-web");
  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:18025");
  expect(new TestEnvironment().realm).toBe("giaokhoa-it");

  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18082");
  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:18026");
  expect(new TestEnvironment().realm).toBe("giaokhoa-it");
  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:18025");

  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:8081");
  expect(() => new TestEnvironment()).toThrow(/TEST/);
  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18081");

  vi.stubEnv("TEST_KEYCLOAK_REALM", "giaokhoa");
  expect(() => new TestEnvironment()).toThrow(/realm/);
  vi.stubEnv("TEST_KEYCLOAK_REALM", "giaokhoa-it");

  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:8025");
  expect(() => new TestEnvironment()).toThrow(/TEST/);
  vi.stubEnv("TEST_MAILPIT_URL", "http://127.0.0.1:18025");

  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://auth.giaokhoa.localhost:8081");
  expect(() => new TestEnvironment()).toThrow(/TEST/);
  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18081");

  vi.stubEnv("TEST_MAILPIT_URL", "http://mailpit:8025");
  expect(() => new TestEnvironment()).toThrow(/TEST/);
});
