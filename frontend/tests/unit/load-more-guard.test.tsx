// @vitest-environment jsdom
import { act, createElement, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  requestAllPages,
  requestWhenReady,
  requestWorkspaces,
  type ApiResult,
} from "@/lib/api-client";
import { CenterMembersContent } from "@/features/tenancy/center-members";
import { CenterClassesContent } from "@/features/teaching/center-classes";
import { CourseSubjectEditor } from "@/features/teaching/course-subject-editor";
import type { TeachingMutate } from "@/features/teaching/teaching-helpers";

// Test hành vi chặn tải thêm trùng theo cursor: api-client bị mock để lượt tải
// trang kế treo ở một promise do test điều khiển, trong lúc đó bấm thêm lần nữa
// phải bị bỏ qua hoàn toàn (không request thứ hai, không append trùng trang).
vi.mock("@/lib/api-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api-client")>();
  return {
    ...actual,
    requestWhenReady: vi.fn(),
    requestAllPages: vi.fn(),
    requestWorkspaces: vi.fn(),
  };
});

vi.mock("@/features/identity/session-provider", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/identity/session-provider")
    >();
  return {
    ...actual,
    useSession: () => ({
      session: {
        status: "authenticated" as const,
        user: {
          id: "u-1",
          username: "owner",
          emailVerified: true,
          identityState: "ACTIVE",
        },
      },
      refreshSession: async () => {},
      activeWorkspaceId: null,
      selectWorkspace: () => {},
    }),
  };
});

(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

const whenReady = vi.mocked(requestWhenReady);
const allPages = vi.mocked(requestAllPages);
const workspacesRequest = vi.mocked(requestWorkspaces);

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function dataResult(data: unknown): ApiResult<unknown> {
  return { kind: "data", status: 200, data };
}

let container: HTMLDivElement | null = null;
let root: Root | null = null;

async function render(element: ReactElement) {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root?.render(element);
  });
}

function findButton(text: string): HTMLButtonElement {
  const button = [...(container?.querySelectorAll("button") ?? [])].find(
    (item) => item.textContent?.trim() === text,
  );
  if (!button) throw new Error(`Không thấy nút "${text}".`);
  return button;
}

async function click(button: HTMLButtonElement) {
  await act(async () => {
    button.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  });
}

function occurrences(text: string): number {
  return (container?.textContent ?? "").split(text).length - 1;
}

function cursorCalls(): number {
  return whenReady.mock.calls.filter(([path]) =>
    String(path).includes("cursor="),
  ).length;
}

beforeEach(() => {
  vi.resetAllMocks();
  workspacesRequest.mockResolvedValue(dataResult([]));
  allPages.mockResolvedValue([]);
});

afterEach(async () => {
  await act(async () => {
    root?.unmount();
  });
  container?.remove();
  container = null;
  root = null;
});

describe("tải thêm theo cursor chống bấm đúp", () => {
  it("thành viên: lượt thứ hai trong lúc đang tải bị bỏ qua, danh sách không trùng mục", async () => {
    const member = (id: string, userId: string, username: string) => ({
      id,
      user: { id: userId, username },
      roles: ["TEACHER"],
      state: "ACTIVE",
      version: 0,
    });
    const gate = deferred<ApiResult<unknown>>();
    whenReady.mockImplementation((path: string) => {
      if (path.includes("cursor=")) return gate.promise;
      return Promise.resolve(
        dataResult({
          items: [member("m-1", "u-2", "member1")],
          nextCursor: "cur-1",
        }),
      );
    });

    await render(createElement(CenterMembersContent, { workspaceId: "ws-1" }));
    expect(occurrences("member1")).toBe(1);

    const button = findButton("Tải thêm thành viên");
    await click(button);
    expect(button.disabled).toBe(true);
    await click(button);
    expect(cursorCalls()).toBe(1);

    await act(async () => {
      gate.resolve(
        dataResult({
          items: [member("m-2", "u-3", "member2")],
          nextCursor: null,
        }),
      );
    });
    expect(occurrences("member1")).toBe(1);
    expect(occurrences("member2")).toBe(1);
  });

  it("lớp: lượt thứ hai trong lúc đang tải bị bỏ qua, danh sách không trùng mục", async () => {
    const clazz = (id: string, name: string) => ({
      id,
      workspaceId: "ws-1",
      name,
      state: "ACTIVE",
      version: 0,
    });
    const gate = deferred<ApiResult<unknown>>();
    whenReady.mockImplementation((path: string) => {
      if (path.includes("cursor=")) return gate.promise;
      return Promise.resolve(
        dataResult({ items: [clazz("cl-1", "Lớp 1")], nextCursor: "cur-1" }),
      );
    });

    await render(createElement(CenterClassesContent, { workspaceId: "ws-1" }));
    expect(occurrences("Lớp 1")).toBe(1);

    const button = findButton("Tải thêm lớp");
    await click(button);
    expect(button.disabled).toBe(true);
    await click(button);
    expect(cursorCalls()).toBe(1);

    await act(async () => {
      gate.resolve(
        dataResult({ items: [clazz("cl-2", "Lớp 2")], nextCursor: null }),
      );
    });
    expect(occurrences("Lớp 1")).toBe(1);
    expect(occurrences("Lớp 2")).toBe(1);
  });

  it("khóa: lượt thứ hai trong lúc đang tải bị bỏ qua, danh sách không trùng mục", async () => {
    const course = (id: string, name: string) => ({
      id,
      classId: "cl-1",
      name,
      subjects: [],
      version: 0,
    });
    const gate = deferred<ApiResult<unknown>>();
    whenReady.mockImplementation((path: string) => {
      if (path.includes("cursor=")) return gate.promise;
      return Promise.resolve(
        dataResult({ items: [course("c-1", "Khóa 1")], nextCursor: "cur-1" }),
      );
    });
    const mutate: TeachingMutate = async () => null;

    await render(
      createElement(CourseSubjectEditor, {
        workspaceId: "ws-1",
        classId: "cl-1",
        subjects: [],
        teachers: [],
        roster: [],
        userId: "u-1",
        canManage: false,
        subscriptionExpired: false,
        busy: false,
        refreshTick: 0,
        mutate,
        showTeacherAssignment: false,
      }),
    );
    expect(occurrences("Khóa: Khóa 1")).toBe(1);

    const button = findButton("Tải thêm khóa");
    await click(button);
    expect(button.disabled).toBe(true);
    await click(button);
    expect(cursorCalls()).toBe(1);

    await act(async () => {
      gate.resolve(
        dataResult({ items: [course("c-2", "Khóa 2")], nextCursor: null }),
      );
    });
    expect(occurrences("Khóa: Khóa 1")).toBe(1);
    expect(occurrences("Khóa: Khóa 2")).toBe(1);
  });
});
