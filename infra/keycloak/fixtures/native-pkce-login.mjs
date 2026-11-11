// Acceptance authorization native dùng một lần: đăng nhập Chromium thật + Authorization Code/PKCE S256.
// Actor token nằm trong một file tạm mode-0600 chỉ được các native Java test tiêu thụ.
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { writeFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(new URL("../../../frontend/package.json", import.meta.url));
const { chromium } = require("@playwright/test");

const base = process.env.NATIVE_GATE_KEYCLOAK_BASE_URL;
const password = process.env.NATIVE_GATE_NATIVE_PASSWORD;
const output = process.env.NATIVE_GATE_NATIVE_TOKENS_FILE;
assert.equal(base, "http://127.0.0.1:18091", "Native login must target only disposable Keycloak");
assert.ok(password && output, "Native PKCE fixture parameters are required");

const callback = "http://127.0.0.1:18895/callback";
const issuer = base + "/realms/giaokhoa";
const server = createServer((_request, response) => {
    response.writeHead(200, { "content-type": "text/plain", "cache-control": "no-store" });
    response.end("Native PKCE callback received");
});
await new Promise((done, reject) => {
    server.once("error", reject);
    server.listen(Number(new URL(callback).port), "127.0.0.1", done);
});

let browser;
try {
    const endpoint = process.env.PW_TEST_CONNECT_WS_ENDPOINT;
    browser = endpoint
        ? await chromium.connect(endpoint)
        : await chromium.launch({ headless: true });

    async function login(username, clientId) {
        const context = await browser.newContext();
        try {
            const page = await context.newPage();
            const verifier = randomBytes(32).toString("base64url");
            const state = randomBytes(24).toString("base64url");
            const authorize = new URL(issuer + "/protocol/openid-connect/auth");
            for (const [key, value] of Object.entries({
                client_id: clientId,
                redirect_uri: callback,
                response_type: "code",
                scope: "openid profile",
                state,
                nonce: randomBytes(20).toString("base64url"),
                code_challenge: createHash("sha256").update(verifier).digest("base64url"),
                code_challenge_method: "S256",
            })) authorize.searchParams.set(key, value);

            await page.goto(authorize.href);
            await page.locator("#username").fill(username);
            // Keycloak 26 có thể tách username và password thành hai form trình duyệt.
            if (await page.locator('input[name="password"]').count() === 0) {
                await page.locator('#kc-login, button[type="submit"], input[type="submit"]').first().click();
            }
            await page.locator('input[name="password"]').fill(password);
            await page.locator('#kc-login, button[type="submit"], input[type="submit"]').first().click();
            try {
                await page.waitForURL((url) => url.origin === new URL(callback).origin, { timeout: 12000 });
            } catch {
                const error = await page.locator("#input-error").textContent({ timeout: 500 }).catch(() => null);
                throw new Error("Real browser Code login did not reach callback: " + (error?.trim() || page.url().split("?")[0]));
            }

            const returned = new URL(page.url());
            assert.equal(returned.origin + returned.pathname, callback);
            assert.equal(returned.searchParams.get("state"), state);
            assert.equal(returned.searchParams.has("access_token"), false);
            const code = returned.searchParams.get("code");
            assert.ok(code, "Missing browser authorization code");

            const redeemed = await fetch(issuer + "/protocol/openid-connect/token", {
                method: "POST",
                headers: { "content-type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams({
                    grant_type: "authorization_code", client_id: clientId, redirect_uri: callback,
                    code, code_verifier: verifier,
                }),
                signal: AbortSignal.timeout(15000),
            });
            assert.equal(redeemed.status, 200, "Real Code+PKCE exchange must succeed");
            const tokens = await redeemed.json();
            assert.equal(tokens.token_type.toLowerCase(), "bearer");
            const claims = JSON.parse(Buffer.from(tokens.access_token.split(".")[1], "base64url").toString());
            assert.equal(claims.preferred_username, username);
            assert.equal(claims.azp, clientId);
            return tokens.access_token;
        } finally {
            await context.close();
        }
    }

    const assigned = await login("native-gate-assigned", "native-gate-test");
    const forged = await login("native-gate-claim-only", "native-gate-forged");
    writeFileSync(output, assigned + "\n" + forged + "\n", { mode: 0o600, flag: "wx" });
    console.log("PASS: two Chromium Authorization Code + PKCE S256 logins, no password grant");
} finally {
    if (browser) await browser.close();
    await new Promise((done) => server.close(done));
}
