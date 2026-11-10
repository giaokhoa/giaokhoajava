import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { seedGuardianResult } from "../e2e/support/guardian-results-fixture";

vi.mock("node:child_process", () => ({ spawnSync: vi.fn() }));
const id = randomUUID();
const seed = {
  workspaceId: id, teacherUserId: id, studentUserId: id, courseSubjectId: id,
  subjectId: id, score: "9.5", comment: "nhận xét 'riêng'", published: true,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(spawnSync).mockReturnValue({ status: 0, stderr: "" } as never);
  vi.stubEnv("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:15433/giaokhoa_test");
  vi.stubEnv("TEST_DATABASE_USERNAME", "giaokhoa_test");
  vi.stubEnv("TEST_DATABASE_PASSWORD", "test-only-password");
  vi.stubEnv("TEST_KEYCLOAK_BASE_URL", "http://127.0.0.1:18082");
  vi.stubEnv("TEST_KEYCLOAK_REALM", "giaokhoa-it");
  vi.stubEnv("TEST_E2E_SCHEMA", `e2e_${"a".repeat(32)}`);
});
afterEach(() => vi.unstubAllEnvs());

test("fixture từ chối DEV, cặp TEST chéo và schema không thuộc runner", () => {
  vi.stubEnv("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/giaokhoa");
  expect(() => seedGuardianResult(seed)).toThrow(/TEST/);
  vi.stubEnv("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:15432/giaokhoa_test");
  expect(() => seedGuardianResult(seed)).toThrow(/TEST/);
  vi.stubEnv("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:15433/giaokhoa_test");
  vi.stubEnv("TEST_E2E_SCHEMA", "public");
  expect(() => seedGuardianResult(seed)).toThrow(/TEST/);
  expect(spawnSync).not.toHaveBeenCalled();
});

test("primitive giữ resource/version riêng, escape dữ liệu và không ghi policy giả", () => {
  expect(seedGuardianResult(seed)).toMatch(/^[a-f0-9-]{36}$/);
  const [command, args, options] = vi.mocked(spawnSync).mock.calls[0];
  expect(command).toBe("psql");
  expect(args).toContain("ON_ERROR_STOP=1");
  const env = (options as { env: Record<string, string> }).env;
  expect(env.PGHOST).toBe("127.0.0.1");
  expect(env.PGPORT).toBe("15433");
  expect(env.PGDATABASE).toBe("giaokhoa_test");
  expect(env.PGUSER).toBe("giaokhoa_test");
  const sql = (options as { input: string }).input;
  expect(sql).toContain("nhận xét ''riêng''");
  expect(sql).toContain("BEGIN;");
  expect(sql).toContain("COMMIT;");
  expect(sql).toContain("resource_version");
  expect(sql).toContain("'PUBLISHED'");
  expect(sql).not.toContain("authz_projection");
  const resourceId = sql.match(/SELECT '([a-f0-9-]+)'/)?.[1];
  const versionId = sql.match(/VALUES \('([a-f0-9-]+)', '[a-f0-9-]+', 1, 'Fixture/)?.[1];
  expect(resourceId).toBeTruthy();
  expect(versionId).toBeTruthy();
  expect(resourceId).not.toBe(versionId);
});

test("lỗi constraint của fixture phải làm test thất bại", () => {
  vi.mocked(spawnSync).mockReturnValueOnce({ status: 1, stderr: "constraint violation" } as never);
  expect(() => seedGuardianResult({ ...seed, published: false })).toThrow("constraint violation");
});
