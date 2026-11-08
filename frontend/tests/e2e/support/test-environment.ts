import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { expect, type Page, type APIResponse } from "@playwright/test";

type TestUser = { id: string; username: string; email: string; password: string };
type Mail = { ID: string; Subject: string; Created: string; To: { Address: string }[] };
type MailSearch = { messages: Mail[] };

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Thiếu cấu hình TEST: ${name}`);
  return value;
}

// Đăng nhập form Keycloak, linh hoạt một/hai bước: Keycloak 26 chuyển sang
// identity-first (bước 1 chỉ hỏi username, bước 2 mới hỏi password) ngay khi realm có
// ≥1 organization — ứng dụng tạo 1 organization cho mỗi center nên form hai bước là
// hành vi production thật, không phải realm hỏng. Điền username rồi kiểm tra #password:
// chưa có thì sang bước 2 bằng nút #kc-login rồi mới điền password.
export async function loginViaKeycloakForm(
  page: Page,
  loginUrl: string,
  user: { username: string; password: string },
): Promise<void> {
  await page.goto(loginUrl);
  await page.locator("#username").fill(user.username);
  if ((await page.locator("#password").count()) === 0) {
    await page.locator("#kc-login").click();
    await page.locator("#password").waitFor({ state: "visible" });
  }
  await page.locator("#password").fill(user.password);
  await page.locator("#kc-login").click();
}

/** Chỉ các lời gọi từ Node test runner; không token hay credential nào tới Next/trình duyệt. */
export class TestEnvironment {
  readonly keycloak = new URL(required("TEST_KEYCLOAK_BASE_URL"));
  readonly realm = required("TEST_KEYCLOAK_REALM");
  readonly clientId = required("TEST_OIDC_CLIENT_ID");
  readonly mailpit = new URL(required("TEST_MAILPIT_URL"));
  private readonly createdUsers = new Set<string>();
  private readonly receivedMail = new Set<string>();

  constructor() {
    if (this.realm !== "giaokhoa-it") {
      throw new Error("Chỉ cho phép realm giaokhoa-it để kiểm thử");
    }
    // shortcut: Chỉ Compose TEST cục bộ; chỉ thêm target bên ngoài sau khi TEST độc lập được kiểm chứng.
    // Hai làn TEST local đều được phép: làn 1 Keycloak 18081, làn 2 Keycloak 18082.
    if ((this.keycloak.href !== "http://127.0.0.1:18081/"
        && this.keycloak.href !== "http://127.0.0.1:18082/")
        || (this.mailpit.href !== "http://127.0.0.1:18025/"
          && this.mailpit.href !== "http://127.0.0.1:18026/")) {
      throw new Error("Chỉ dùng Keycloak/Mailpit TEST; không được kết nối DEV");
    }
  }

  private url(path: string): string {
    return new URL(path, this.keycloak).toString();
  }

  private async adminToken(): Promise<string> {
    const response = await fetch(this.url(`/realms/${this.realm}/protocol/openid-connect/token`), {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: required("TEST_KEYCLOAK_ADMIN_CLIENT_ID"),
        client_secret: required("TEST_KEYCLOAK_ADMIN_CLIENT_SECRET"),
      }),
    });
    if (!response.ok) throw new Error(`Keycloak test admin token: HTTP ${response.status}`);
    return (await response.json() as { access_token: string }).access_token;
  }

  private async admin(method: string, path: string, body?: object): Promise<Response> {
    const response = await fetch(this.url(`/admin/realms/${this.realm}${path}`), {
      method,
      headers: {
        authorization: `Bearer ${await this.adminToken()}`,
        ...(body ? { "content-type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) throw new Error(`Keycloak test admin ${method}: HTTP ${response.status}`);
    return response;
  }

  async createUser(emailVerified = true, temporaryPassword = false): Promise<TestUser> {
    const unique = randomUUID();
    const username = `e2e-${unique}`;
    const email = `${username}@test.giaokhoa.localhost`;
    const password = randomBytes(24).toString("base64url") + "Aa1!";
    const response = await this.admin("POST", "/users", {
      username, email, firstName: "Test", lastName: "Fixture", enabled: true, emailVerified,
      credentials: [{ type: "password", value: password, temporary: temporaryPassword }],
    });
    const location = response.headers.get("location");
    const id = location?.split("/").at(-1);
    if (!id || !/^[a-f0-9-]{36}$/.test(id)) {
      throw new Error("Keycloak không trả ID của user test; kiểm tra fixture thủ công");
    }
    this.createdUsers.add(id);
    return { id, username, email, password };
  }

  async deleteUser(user: TestUser): Promise<void> {
    if (!this.createdUsers.has(user.id)) throw new Error("Không xóa tài khoản không do test tạo");
    await this.admin("DELETE", `/users/${encodeURIComponent(user.id)}`);
    this.createdUsers.delete(user.id);
  }

  async setEmailVerified(user: TestUser, verified: boolean): Promise<void> {
    if (!this.createdUsers.has(user.id)) throw new Error("Chỉ sửa tài khoản do test tạo");
    await this.admin("PUT", `/users/${encodeURIComponent(user.id)}`, { emailVerified: verified });
  }

  async requestVerificationEmail(user: TestUser): Promise<void> {
    if (!this.createdUsers.has(user.id)) throw new Error("Chỉ gửi mail cho tài khoản test");
    await this.admin("PUT", `/users/${encodeURIComponent(user.id)}/send-verify-email`);
  }

  async waitForEmail(email: string, since: number): Promise<Mail> {
    if (!email.endsWith("@test.giaokhoa.localhost")) throw new Error("Email phải thuộc TEST");
    const url = new URL("/api/v1/search", this.mailpit);
    url.searchParams.set("query", `to:${email}`);
    for (let i = 0; i < 30; i++) {
      const response = await fetch(url);
      if (!response.ok) throw new Error(`Mailpit test: HTTP ${response.status}`);
      const result = await response.json() as MailSearch;
      const message = result.messages.find((mail) =>
        Date.parse(mail.Created) >= since - 1_000 &&
        mail.To.some((recipient) => recipient.Address === email));
      if (message) {
        this.receivedMail.add(message.ID);
        return message;
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error("Mailpit không nhận được email xác minh của user test");
  }

  // Credential tạm của Keycloak tự kéo theo required action UPDATE_PASSWORD ở lần đăng nhập đầu.
  async createUserWithTemporaryPassword(): Promise<TestUser> {
    return this.createUser(true, true);
  }

  async readVerificationLink(message: Mail): Promise<string> {
    const link = (await this.readEmailContent(message))
      .match(/https?:\/\/[^\s"'<>]*action-token[^\s"'<>]*/)?.[0];
    if (!link) throw new Error("Email xác minh không chứa link action-token của Keycloak");
    return link.replace(/&amp;/g, "&");
  }

  /** Đọc toàn bộ nội dung text/HTML của một email test đã nhận. */
  async readEmailContent(message: Mail): Promise<string> {
    if (!this.receivedMail.has(message.ID)) throw new Error("Không đọc email không do test nhận");
    const response = await fetch(new URL(`/api/v1/message/${encodeURIComponent(message.ID)}`, this.mailpit));
    if (!response.ok) throw new Error(`Mailpit message: HTTP ${response.status}`);
    const detail = await response.json() as { Text?: string; HTML?: string };
    return `${detail.Text ?? ""}\n${detail.HTML ?? ""}`;
  }

  /**
   * Đưa user test vào một realm group nguồn (/gk/...): tạo từng đoạn group nếu chưa có rồi
   * gắn user vào group cuối. Dùng để dựng operator nền tảng và eligible cho actor E2E.
   */
  async addToRealmGroup(user: TestUser, groupPath: string): Promise<void> {
    if (!this.createdUsers.has(user.id)) throw new Error("Chỉ thêm user do test tạo vào realm group");
    if (!/^\/gk(\/[A-Za-z0-9_-]+)+$/.test(groupPath)) {
      throw new Error("Chỉ thêm vào group nguồn /gk/*");
    }
    let parentId: string | undefined;
    let groupId = "";
    for (const segment of groupPath.split("/").filter(Boolean)) {
      const siblings = parentId
        ? await this.adminJson<{ id: string; name: string }[]>(
          "GET", `/groups/${encodeURIComponent(parentId)}/children`)
        : await this.adminJson<{ id: string; name: string }[]>("GET", "/groups");
      const found = siblings.find((group) => group.name === segment);
      if (found) {
        groupId = found.id;
      } else {
        const response = await this.admin(
          "POST",
          parentId ? `/groups/${encodeURIComponent(parentId)}/children` : "/groups",
          { name: segment },
        );
        const location = response.headers.get("location");
        const id = location?.split("/").at(-1);
        if (!id || !/^[a-f0-9-]{36}$/.test(id)) {
          throw new Error("Keycloak không trả ID của group test; kiểm tra fixture thủ công");
        }
        groupId = id;
      }
      parentId = groupId;
    }
    await this.admin("PUT", `/users/${encodeURIComponent(user.id)}/groups/${encodeURIComponent(groupId)}`);
  }

  private async adminJson<T>(method: string, path: string): Promise<T> {
    const response = await this.admin(method, path);
    return (await response.json()) as T;
  }

  // Admin kết thúc mọi phiên SSO của user; Keycloak gửi back-channel logout tới các client đã đăng ký.
  async terminateUserSessions(user: TestUser): Promise<void> {
    if (!this.createdUsers.has(user.id)) throw new Error("Chỉ kết thúc phiên của tài khoản test");
    await this.admin("POST", `/users/${encodeURIComponent(user.id)}/logout`);
  }

  async deleteEmail(message: Mail): Promise<void> {
    if (!this.receivedMail.has(message.ID)) throw new Error("Không xóa email không do test nhận");
    const response = await fetch(new URL("/api/v1/messages", this.mailpit), {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ IDs: [message.ID] }),
    });
    if (!response.ok) throw new Error(`Xóa mail test: HTTP ${response.status}`);
    this.receivedMail.delete(message.ID);
  }

  async exchangeAuthorizationCode(page: Page, user: TestUser): Promise<void> {
    // Trình duyệt dùng đăng nhập Keycloak thật; chỉ Node runner nhận và đổi code.
    const redirectUri = "http://127.0.0.1:18888/callback";
    const state = randomBytes(20).toString("base64url");
    const nonce = randomBytes(20).toString("base64url");
    const verifier = randomBytes(32).toString("base64url");
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const authorize = new URL(`/realms/${this.realm}/protocol/openid-connect/auth`, this.keycloak);
    for (const [key, value] of Object.entries({
      client_id: this.clientId, redirect_uri: redirectUri, response_type: "code",
      scope: "openid profile", state, nonce, code_challenge: challenge,
      code_challenge_method: "S256",
    })) authorize.searchParams.set(key, value);

    const callbackServer = createServer((_request, response) => {
      response.writeHead(200, { "content-type": "text/plain" });
      response.end("Authorization Code callback received");
    });
    await new Promise<void>((resolve, reject) => {
      callbackServer.once("error", reject);
      callbackServer.listen(18888, "127.0.0.1", resolve);
    });
    try {
      await loginViaKeycloakForm(page, authorize.toString(), user);
      await page.waitForURL((url) => url.origin === "http://127.0.0.1:18888");
    const callback = new URL(page.url());
    expect(callback.searchParams.get("state")).toBe(state);
    const code = callback.searchParams.get("code");
    expect(code).toBeTruthy();
    expect(callback.searchParams.has("access_token")).toBe(false);

    const response = await fetch(new URL(`/realms/${this.realm}/protocol/openid-connect/token`, this.keycloak), {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code", code: code!, redirect_uri: redirectUri,
        client_id: this.clientId, client_secret: required("TEST_OIDC_CLIENT_SECRET"),
        code_verifier: verifier,
      }),
    });
    expect(response.status).toBe(200);
    const tokens = await response.json() as { token_type: string; id_token: string };
    expect(tokens.token_type.toLowerCase()).toBe("bearer");
    const claims = JSON.parse(Buffer.from(tokens.id_token.split(".")[1], "base64url").toString()) as {
      iss: string; aud: string; nonce: string;
    };
    expect(claims.iss).toBe(`${this.keycloak.origin}/realms/${this.realm}`);
    expect(claims.aud).toBe(this.clientId);
    expect(claims.nonce).toBe(nonce);
    } finally {
      callbackServer.close();
    }
  }
}


// Chỉ thử lại ACCOUNT/target fence đang đồng bộ; caller giữ nguyên key qua mọi lần thử.
export async function appRequestWhenReady(
  page: Page,
  url: string,
  options: { method?: string; headers?: Record<string, string>; data?: Record<string, unknown> } = {},
): Promise<APIResponse> {
  const startedAt = Date.now();
  for (;;) {
    const response = await page.request.fetch(url, options);
    if (response.status() !== 503) return response;
    let code: unknown;
    try { code = ((await response.json()) as { code?: unknown }).code; }
    catch { return response; }
    if (code !== "AUTHORIZATION_NOT_READY" || Date.now() - startedAt >= 60_000) return response;
    await page.waitForTimeout(1_000);
  }
}

// Chờ một navigation mới về trang chủ, kể cả khi URL trước logout cũng là trang chủ.
export async function logoutViaApplicationForm(page: Page, origin: string, token: string): Promise<void> {
  await Promise.all([
    page.waitForEvent("framenavigated", {
      predicate: (frame) => frame === page.mainFrame() && frame.url() === `${origin}/`,
    }),
    page.evaluate((csrfToken) => {
      const form = document.createElement("form");
      form.method = "POST";
      form.action = "/api/logout";
      const csrf = document.createElement("input");
      csrf.type = "hidden";
      csrf.name = "_csrf";
      csrf.value = csrfToken;
      form.appendChild(csrf);
      document.body.appendChild(form);
      form.submit();
    }, token),
  ]);
}
