import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/server", () => ({ connection: vi.fn().mockResolvedValue(undefined) }));
vi.mock("next/navigation", () => ({ notFound: vi.fn(() => { throw new Error("NOT_FOUND"); }) }));

import ReviewPage from "@/app/(session)/review/page";

describe("review panel environment gate", () => {
  beforeEach(() => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("GIAOKHOA_REVIEW_PANEL_ENABLED", "");
  });
  afterEach(() => vi.unstubAllEnvs());

  it("returns 404 when a production session requests the review page", async () => {
    await expect(ReviewPage()).rejects.toThrow("NOT_FOUND");
  });

  it("allows the page only with a trusted server-side review flag", async () => {
    vi.stubEnv("GIAOKHOA_REVIEW_PANEL_ENABLED", "true");
    const panel = await ReviewPage();
    expect(panel.props).toEqual({});
    expect(typeof panel.type).toBe("function");
  });

  it("keeps the panel available for local development", async () => {
    vi.stubEnv("NODE_ENV", "development");
    expect(await ReviewPage()).toBeDefined();
  });
});
