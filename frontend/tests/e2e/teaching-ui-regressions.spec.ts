import { expect, test, type Page } from "@playwright/test";

// Component thật với HTTP fixture để bắt lỗi UI; E2E backend/Keycloak thật ở teaching-administration.
async function teachingFixture(page: Page, paginated = false) {
  const keys: string[] = [];
  const membershipQueries: string[] = [];
  const receipts = new Map<string, object>();
  const operations = new Map<string, object>();
  let assignments: object[] = [];
  let sequence = 0;
  const member = (id: string, roles: string[]) => ({
    id: `membership-${id}`, user: { id, username: id, displayName: id === "teacher" ? "Teacher A" : id },
    roles, state: "ACTIVE", version: 0,
  });
  const roster = (index: number) => ({
    id: `r-${index}`, studentUserId: `student-${index}`, username: `student-${index}`,
    state: "ACTIVE", version: 0,
  });
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    let data: unknown;
    let status = 200;
    const complete = () => ({ id: `op-${++sequence}`, state: "SUCCEEDED", completed: true });
    const pending = (done: ReturnType<typeof complete>) => {
      operations.set(done.id, done);
      status = 202;
      return { operationId: done.id, state: "PENDING", completed: false, statusUrl: `/api/me/operations/${done.id}` };
    };
    if (path === "/api/session") {
      data = { authenticated: true, user: { id: "actor", username: "actor", identityState: "ACTIVE", emailVerified: true }, csrf: { headerName: "X-CSRF-TOKEN", token: "fixture" } };
    } else if (path.startsWith("/api/me/operations/")) {
      data = operations.get(path.split("/").at(-1)!);
    } else if (path === "/api/me/workspaces") {
      data = [{ id: "w", kind: "CENTER", name: "Center", subscription: { effective: true }, roles: [paginated ? "MANAGER" : "OWNER"], syncState: "READY" }];
    } else if (path === "/api/subjects") {
      data = { items: [{ id: "s", code: "M1", name: "Math" }] };
    } else if (path === "/api/workspaces/w/memberships") {
      membershipQueries.push(url.search);
      data = paginated && !url.searchParams.has("cursor")
        ? { items: Array.from({ length: 20 }, (_, i) => member(`student-${i}`, ["STUDENT"])), nextCursor: "members-2" }
        : { items: [member("actor", [paginated ? "MANAGER" : "OWNER"]), member("teacher", ["TEACHER"]), member("student-20", ["STUDENT"])] };
    } else if (path === "/api/workspaces/w/classes") {
      data = { items: [{ id: "c", workspaceId: "w", name: "Review Class", state: "ACTIVE", version: 0 }] };
    } else if (path === "/api/workspaces/w/classes/c/students") {
      data = !paginated ? { items: [] } : !url.searchParams.has("cursor")
        ? { items: Array.from({ length: 20 }, (_, i) => roster(i)), nextCursor: "roster-2" }
        : { items: [roster(20)] };
    } else if (path === "/api/workspaces/w/classes/c/homeroom-assignments") {
      data = { items: [] };
    } else if (path === "/api/workspaces/w/classes/c/courses") {
      data = { items: [{ id: "k", classId: "c", name: "Review Course", version: 0, subjects: [{ id: "cs", subjectId: "s", name: "Math" }] }] };
    } else if (path === "/api/workspaces/w/course-subjects/cs/teachers" && request.method() === "POST") {
      const key = request.headers()["idempotency-key"];
      keys.push(key);
      if (!receipts.has(key)) {
        assignments = [{ id: "assignment", courseSubjectId: "cs", teacherUserId: "teacher", state: "ACTIVE", version: 0 }];
        const done = complete();
        receipts.set(key, done);
        data = pending(done);
      } else data = receipts.get(key);
    } else if (path === "/api/workspaces/w/course-subjects/cs/teachers/assignment" && request.method() === "DELETE") {
      assignments = [];
      data = pending(complete());
    } else if (path === "/api/workspaces/w/course-subjects/cs/teachers") {
      data = { items: assignments };
    } else if (path === "/api/workspaces/w/course-subjects/cs/enrollments") {
      data = { items: [] };
    } else {
      throw new Error(`Fixture chưa định nghĩa ${request.method()} ${path}`);
    }
    await route.fulfill({ status, json: data });
  });
  await page.goto("/centers/w/classes");
  return { keys, membershipQueries };
}

test("phân công lại sau thu hồi tạo attempt mới, giữ pending tới khi operation thành công", async ({ page }) => {
  const fixture = await teachingFixture(page);
  await page.getByRole("button", { name: "Review Class", exact: true }).click();
  await page.getByRole("button", { name: "Ghi danh và phân công", exact: true }).click();
  const assign = page.getByRole("button", { name: "Phân công giáo viên đã chọn", exact: true });
  await page.getByRole("checkbox", { name: "Teacher A", exact: true }).check();
  await assign.click();
  await page.getByText("op-1", { exact: true }).waitFor();
  await expect(assign).toBeDisabled();
  await page.getByRole("button", { name: "Kiểm tra trạng thái", exact: true }).click();
  await page.getByRole("button", { name: "Thu hồi phân công", exact: true }).click();
  await page.getByText("op-2", { exact: true }).waitFor();
  await page.getByRole("button", { name: "Kiểm tra trạng thái", exact: true }).click();
  await page.getByText("Chưa có giáo viên nào đang phân công môn này.").waitFor();
  await page.getByRole("checkbox", { name: "Teacher A", exact: true }).check();
  await assign.click();
  await page.getByText("op-3", { exact: true }).waitFor();
  await page.getByRole("button", { name: "Kiểm tra trạng thái", exact: true }).click();
  await expect(page.getByRole("button", { name: "Thu hồi phân công", exact: true })).toBeVisible();
  expect(fixture.keys).toHaveLength(2);
  expect(fixture.keys[1]).not.toBe(fixture.keys[0]);
});

test("quản lý ở trang hai vẫn quản trị và thấy đủ roster vượt 20 học sinh", async ({ page }) => {
  const fixture = await teachingFixture(page, true);
  await expect(page.getByRole("button", { name: "Tạo lớp", exact: true })).toBeVisible();
  expect(fixture.membershipQueries).toEqual(["", "?cursor=members-2"]);
  await page.getByRole("button", { name: "Review Class", exact: true }).click();
  await expect(page.getByText("student-20", { exact: true })).toBeVisible();
});
