import { afterEach, expect, test, vi } from "vitest";
import { TestEnvironment } from "../e2e/support/test-environment";

afterEach(() => vi.unstubAllEnvs());

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
