// @vitest-environment jsdom
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, expect, it, vi } from "vitest";
import { PersonalClassesContent } from "@/app/(session)/personal/classes/personal-classes-content";
import { requestAllPages, requestWhenReady, requestWorkspaces } from "@/lib/api-client";

vi.mock("@/lib/api-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api-client")>();
  return { ...actual, requestAllPages: vi.fn(), requestWhenReady: vi.fn(), requestWorkspaces: vi.fn() };
});
vi.mock("@/features/identity/session-provider", () => ({
  useSession: () => ({
    session: {
      status: "authenticated",
      user: { id: "owner", username: "owner", emailVerified: true, identityState: "ACTIVE" },
    },
    refreshSession: async () => {},
  }),
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let node: HTMLDivElement | null = null;
let root: Root | null = null;
afterEach(async () => {
  await act(async () => root?.unmount());
  node?.remove();
  node = null;
  root = null;
  vi.resetAllMocks();
});

function button(label: string): HTMLButtonElement {
  const found = [...(node?.querySelectorAll("button") ?? [])]
    .find((element) => element.textContent?.trim() === label);
  if (!found) throw new Error(`Không tìm thấy nút ${label}`);
  return found;
}

it("personal hết gói: tạo lớp và thêm học sinh bị khóa, vẫn xem và thu hồi được lớp/roster cũ", async () => {
  vi.mocked(requestWorkspaces).mockResolvedValue({
    kind: "data", status: 200,
    data: [{
      id: "w-personal", kind: "PERSONAL", name: "Cá nhân",
      subscription: { effective: false }, roles: [], syncState: "SYNCED",
    }],
  });
  vi.mocked(requestAllPages).mockImplementation(async (path: string) => {
    if (path === "/api/workspaces/w-personal/classes") {
      return [{ id: "c1", workspaceId: "w-personal", name: "Lớp cũ", state: "ACTIVE", version: 1 }];
    }
    if (path.endsWith("/classes/c1/students")) {
      return [{ id: "r1", studentUserId: "student", username: "hocsinh", state: "ACTIVE", version: 1 }];
    }
    return [];
  });
  vi.mocked(requestWhenReady).mockImplementation(async (path: string) => ({
    kind: "data", status: 200,
    data: path.endsWith("/classes/c1/courses")
      ? { items: [{ id: "k1", classId: "c1", name: "Khóa cũ", subjects: [], version: 1 }] }
      : { items: [] },
  }));

  node = document.createElement("div");
  document.body.appendChild(node);
  root = createRoot(node);
  await act(async () => root?.render(createElement(PersonalClassesContent)));
  expect(node.textContent).toContain("Chưa có gói cá nhân hiệu lực");
  expect(button("Tạo lớp").disabled).toBe(true);
  expect(button("Thu hồi lớp").disabled).toBe(false);

  await act(async () => button("Lớp cũ").click());
  expect(button("Thêm học sinh").disabled).toBe(true);
  expect(button("Thu hồi khỏi lớp").disabled).toBe(false);
  expect(node.querySelector<HTMLInputElement>("#rename-course-k1")?.disabled).toBe(true);
  expect(button("Đổi tên khóa").disabled).toBe(true);
  expect(button("Thu hồi khóa").disabled).toBe(false);
});
