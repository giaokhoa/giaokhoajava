// Acceptance OIDC logout native: đăng nhập Keycloak Code+PKCE thật, back-channel logout có ký,
// khởi động lại session và vô hiệu hóa JDBC. Chỉ chạy trên Compose TEST stack chuyên dụng.
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from "node:crypto";
import { execFileSync, spawn } from "node:child_process";
import { createServer } from "node:http";
import { resolve } from "node:path";

const issuer = "http://127.0.0.1:18081/realms/giaokhoa-it";
const database = "jdbc:postgresql://127.0.0.1:15432/giaokhoa_test";
assert.equal(process.env.TEST_KEYCLOAK_BASE_URL, "http://127.0.0.1:18081");
assert.equal(process.env.TEST_KEYCLOAK_REALM, "giaokhoa-it");
assert.equal(process.env.TEST_DATABASE_URL, database);
const schema = "gk_oidc_logout_" + randomUUID().replaceAll("-", "").slice(0, 12);
const clientId = "oidc-logout-" + randomUUID();
const clientSecret = randomBytes(24).toString("base64url");
const password = randomBytes(24).toString("base64url") + "Aa1!";
const username = "oidc-logout-" + randomUUID();
const appJar = resolve("backend/target/giaokhoa-backend-0.0.1-SNAPSHOT.jar");
const keycloakBase = "http://127.0.0.1:18081";
const logoutEvent = "http://schemas.openid.net/event/backchannel-logout";
let app;
let clientUuid;
let userId;
let proxy;
let jwksServer;
let received;
let backendLogs = "";
let schemaCreated = false;
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

function dockerSql(query) {
    return execFileSync("docker", [
        "exec", "giaokhoa-test-test-app-db-1", "psql",
        "-v", "ON_ERROR_STOP=1", "-U", "giaokhoa_test", "-d", "giaokhoa_test",
        "-Atc", query,
    ], { encoding: "utf8" }).trim();
}

async function fetchAdminToken() {
    const response = await fetch(keycloakBase + "/realms/master/protocol/openid-connect/token", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            grant_type: "password", client_id: "admin-cli",
            username: "test-admin", password: "test-only-password",
        }),
    });
    assert.equal(response.status, 200, "TEST-only Keycloak admin login");
    return (await response.json()).access_token;
}

async function admin(method, path, body) {
    const response = await fetch(keycloakBase + "/admin/realms/giaokhoa-it" + path, {
        method,
        headers: {
            authorization: "Bearer " + await fetchAdminToken(),
            ...(body ? { "content-type": "application/json" } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
    });
    assert.ok(response.ok, "TEST Keycloak admin " + method + " " + path + ": " + response.status);
    return response;
}

function reservePort() {
    return new Promise((done, reject) => {
        const server = createServer();
        server.once("error", reject);
        server.listen(0, "0.0.0.0", () => {
            const port = server.address().port;
            server.close(() => done(port));
        });
    });
}

async function startBackend(port, testJwksUrl) {
    const appOrigin = "http://127.0.0.1:" + port;
    const environment = {
        ...process.env,
        SPRING_DATASOURCE_URL: database,
        SPRING_DATASOURCE_USERNAME: "giaokhoa_test",
        SPRING_DATASOURCE_PASSWORD: "test-only-password",
        SPRING_FLYWAY_USER: "giaokhoa_test",
        SPRING_FLYWAY_PASSWORD: "test-only-password",
        OIDC_ISSUER_URI: issuer,
        OIDC_CLIENT_ID: clientId,
        OIDC_CLIENT_SECRET: clientSecret,
        APP_ORIGIN: appOrigin,
        KEYCLOAK_AUTHZ_AUDIENCE: "giaokhoa-it-api",
        KEYCLOAK_ADMIN_BASE_URL: keycloakBase,
        KEYCLOAK_PROVISIONER_CLIENT_ID: "giaokhoa-it-fixtures",
        KEYCLOAK_PROVISIONER_CLIENT_SECRET: "ci-fixtures-only",
    };
    app = spawn("java", [
        "-jar", appJar,
        "--server.address=0.0.0.0",
        "--server.port=" + port,
        "--spring.flyway.schemas=" + schema,
        "--spring.flyway.default-schema=" + schema,
        "--spring.datasource.hikari.schema=" + schema,
        ...(testJwksUrl ? ["--spring.security.oauth2.client.provider.keycloak.jwk-set-uri=" + testJwksUrl] : []),
    ], { env: environment, stdio: ["ignore", "pipe", "pipe"] });
    const collect = (chunk) => { backendLogs = (backendLogs + chunk.toString()).slice(-12000); };
    app.stdout.on("data", collect);
    app.stderr.on("data", collect);
    for (let retry = 0; retry < 80; retry++) {
        if (app.exitCode !== null) {
            throw new Error("Backend exited during startup: " + backendLogs.slice(-3500));
        }
        try {
            const response = await fetch(appOrigin + "/api/me/workspaces", {
                redirect: "manual", signal: AbortSignal.timeout(1000),
            });
            if (response.status >= 200 && response.status < 500) return appOrigin;
        } catch {
            // Discovery của Spring/JDBC/Keycloak vẫn đang khởi động.
        }
        await sleep(250);
    }
    throw new Error("Backend did not start: " + backendLogs.slice(-3500));
}

async function stopBackend() {
    if (!app) return;
    const instance = app;
    app = undefined;
    if (instance.exitCode === null) {
        const ended = new Promise((done) => instance.once("exit", done));
        instance.kill("SIGTERM");
        await Promise.race([ended, sleep(8000)]);
        if (instance.exitCode === null) instance.kill("SIGKILL");
    }
}

const jars = new Map();
async function browserFetch(url, options = {}) {
    const origin = new URL(url).origin;
    const jar = jars.get(origin) ?? new Map();
    jars.set(origin, jar);
    const headers = new Headers(options.headers);
    if (jar.size) headers.set("cookie", [...jar].map(([k, v]) => k + "=" + v).join("; "));
    const response = await fetch(url, { ...options, headers, redirect: "manual" });
    for (const cookie of response.headers.getSetCookie()) {
        const first = cookie.split(";")[0];
        const index = first.indexOf("=");
        if (index > 0) {
            if (/max-age=0/i.test(cookie)) jar.delete(first.slice(0, index));
            else jar.set(first.slice(0, index), first.slice(index + 1));
        }
    }
    return response;
}

async function followRedirects(url, firstResponse) {
    let response = firstResponse;
    for (let step = 0; step < 12; step++) {
        if (!response) response = await browserFetch(url);
        if (response.status < 300 || response.status >= 400) return { url, response };
        const location = response.headers.get("location");
        assert.ok(location, "Redirect is missing Location");
        url = new URL(location, url).href;
        response = await browserFetch(url);
    }
    throw new Error("Unexpected redirect loop");
}

async function login(appOrigin) {
    const started = await followRedirects(appOrigin + "/oauth2/authorization/keycloak");
    assert.equal(started.response.status, 200, "Keycloak login page");
    assert.match(started.url, /\/realms\/giaokhoa-it\//);
    const html = await started.response.text();
    const form = html.match(/<form\b[^>]*id="kc-form-login"[^>]*>/);
    assert.ok(form, "Keycloak test login form was not found");
    const action = form[0].match(/action="([^"]+)"/);
    assert.ok(action, "Keycloak test login action was not found");
    let loginUrl = action[1].replaceAll("&amp;", "&");
    const postLogin = (url, params) => browserFetch(url, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(params).toString(),
    });
    // Keycloak 26 bật identity-first khi realm có ≥1 organization (app tạo một
    // organization cho mỗi center): bước 1 chỉ hỏi username, password ở bước sau.
    // Form một bước vẫn có sẵn field password nên giữ nguyên POST gộp.
    let loggedIn;
    if (html.includes('name="password"')) {
        loggedIn = await postLogin(loginUrl, { username, password });
    } else {
        const usernameStep = await postLogin(loginUrl, { username });
        assert.equal(usernameStep.status, 200, "Keycloak identity-first username step");
        const stepTwoHtml = await usernameStep.text();
        const stepTwoForm = stepTwoHtml.match(/<form\b[^>]*id="kc-form-login"[^>]*>/);
        assert.ok(stepTwoForm, "Keycloak test login form was not found");
        const stepTwoAction = stepTwoForm[0].match(/action="([^"]+)"/);
        assert.ok(stepTwoAction, "Keycloak test login action was not found");
        loginUrl = stepTwoAction[1].replaceAll("&amp;", "&");
        loggedIn = await postLogin(loginUrl, { password });
    }
    const returned = await followRedirects(loginUrl, loggedIn);
    assert.equal(new URL(returned.url).origin, appOrigin, "OIDC Code callback must return to Spring");
    const cookie = jars.get(appOrigin)?.get("SESSION");
    assert.match(cookie ?? "", /^[a-f0-9-]{36}$/, "raw SESSION id rather than Base64");
    return cookie;
}

async function signedIdTokenWithoutLogoutEvent(appOrigin) {
    const verifier = randomBytes(32).toString("base64url");
    const nonce = randomBytes(20).toString("base64url");
    const redirectUri = appOrigin + "/oidc-negative-callback";
    const url = new URL(issuer + "/protocol/openid-connect/auth");
    for (const [key, value] of Object.entries({
        response_type: "code", client_id: clientId, redirect_uri: redirectUri,
        scope: "openid", nonce, state: "oidc-logout-event-validation",
        code_challenge: createHash("sha256").update(verifier).digest("base64url"),
        code_challenge_method: "S256",
    })) url.searchParams.set(key, value);
    // Dùng lại SSO browser session thật do lần đăng nhập trước thiết lập.
    const authorized = await browserFetch(url.href);
    assert.equal(authorized.status, 302, "Keycloak SSO authorization code");
    const callback = new URL(authorized.headers.get("location"), url);
    assert.equal(callback.origin + callback.pathname, redirectUri);
    const code = callback.searchParams.get("code");
    assert.ok(code);
    const exchanged = await fetch(issuer + "/protocol/openid-connect/token", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            grant_type: "authorization_code", client_id: clientId,
            client_secret: clientSecret, redirect_uri: redirectUri,
            code, code_verifier: verifier,
        }),
    });
    assert.equal(exchanged.status, 200, "Keycloak must sign a test ID token");
    return (await exchanged.json()).id_token;
}

async function rejectedWithoutRevocation(appOrigin, signedToken, sessionCount, reason = "invalid claims") {
    const response = await submitLogout(appOrigin, signedToken);
    assert.equal(response.status, 400, reason + " must be rejected");
    assert.equal(sessionCount(), "1", reason + " cannot delete a JDBC session");
}

function signTestLogout(privateKey, claims) {
    const header = Buffer.from(JSON.stringify({
        alg: "RS256", typ: "logout+jwt", kid: "oidc-logout-ephemeral",
    })).toString("base64url");
    const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
    const content = header + "." + payload;
    return content + "." + sign("RSA-SHA256", Buffer.from(content), privateKey).toString("base64url");
}

function tamperSignature(token) {
    const parts = token.split(".");
    const signature = Buffer.from(parts[2], "base64url");
    signature[0] ^= 1;
    parts[2] = signature.toString("base64url");
    return parts.join(".");
}

async function verifyIsolatedSignatureIssuerAudience(appOrigin, port, sessionCount, correlation, recorded) {
    // Trust anchor tạm thời chỉ-cho-TEST: từ chối từng claim một với các logout token
    // vốn được ký đúng. Chữ ký thật của Keycloak được kiểm tra sau đó.
    const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const publicJwk = {
        ...publicKey.export({ format: "jwk" }),
        use: "sig", alg: "RS256", kid: "oidc-logout-ephemeral",
    };
    let jwksRequests = 0;
    jwksServer = createServer((request, response) => {
        if (request.url !== "/jwks") {
            response.writeHead(404);
            response.end();
            return;
        }
        jwksRequests++;
        response.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
        response.end(JSON.stringify({ keys: [publicJwk] }));
    });
    await new Promise((done, reject) => {
        jwksServer.once("error", reject);
        jwksServer.listen(0, "127.0.0.1", done);
    });
    const jwksUrl = "http://127.0.0.1:" + jwksServer.address().port + "/jwks";
    await stopBackend();
    backendLogs = "";
    await startBackend(port, jwksUrl);
    assert.equal(sessionCount(), "1", "Existing session survives test-only JWKS restart");

    const claims = {
        iss: issuer, aud: clientId, sub: userId, sid: recorded[1],
        iat: Math.floor(Date.now() / 1000), jti: randomUUID(),
        events: { [logoutEvent]: {} },
    };
    // Một control đã ký hợp lệ cho một subject vắng mặt phải được chấp nhận mà không thu hồi
    // session đang sống. Điều này chứng minh Spring tin JWKS tạm thời của ta.
    const control = signTestLogout(privateKey, {
        ...claims, sub: "nonexistent-" + randomUUID(), sid: "nonexistent-" + randomUUID(),
    });
    const accepted = await submitLogout(appOrigin, control);
    assert.equal(accepted.status, 200, "Valid test-signed logout token must reach the native handler");
    assert.ok(jwksRequests > 0, "Spring must obtain the ephemeral public signing key");
    assert.equal(sessionCount(), "1");

    const negatives = [
        ["tampered signature", tamperSignature(signTestLogout(privateKey, claims))],
        ["wrong issuer", signTestLogout(privateKey, { ...claims, iss: "https://wrong-issuer.example.test" })],
        ["wrong audience", signTestLogout(privateKey, { ...claims, aud: "other-client" })],
        ["wrong events", signTestLogout(privateKey, { ...claims, events: { "other-event": {} } })],
    ];
    for (const [reason, token] of negatives) {
        await rejectedWithoutRevocation(appOrigin, token, sessionCount, reason);
        assert.equal(correlation(), recorded.join("|"), reason + " must preserve correlation");
    }
    // Endpoint đã tồn tại từ US1 nên không còn 404: user chưa bootstrap (chưa gọi
    // /api/session) nên actor fail-closed 503 theo thiết kế; 503 chứ không phải 302
    // vẫn chứng minh cookie còn xác thực.
    const active = await browserFetch(appOrigin + "/api/me/workspaces");
    assert.equal(active.status, 503, "Original browser cookie stays authenticated");

    await stopBackend();
    await new Promise((done) => jwksServer.close(done));
    jwksServer = undefined;
    backendLogs = "";
    await startBackend(port);
    assert.equal(sessionCount(), "1", "Session persists when restoring genuine Keycloak JWKS");
    console.log("PASS: isolated signed-token validation for signature, issuer, audience and events");
}

async function submitLogout(appOrigin, token) {
    return fetch(appOrigin + "/logout/connect/back-channel/keycloak", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ logout_token: token }),
    });
}

async function waitUntil(check, message) {
    for (let attempt = 0; attempt < 80; attempt++) {
        if (await check()) return;
        await sleep(250);
    }
    throw new Error("Timed out: " + message);
}

async function run() {
    const port = await reservePort();
    const appOrigin = "http://127.0.0.1:" + port;
    const gateway = JSON.parse(execFileSync("docker", [
        "inspect", "giaokhoa-test-test-keycloak-1", "--format", "{{json .NetworkSettings.Networks}}",
    ], { encoding: "utf8" }))["giaokhoa-test_default"].Gateway;
    assert.match(gateway, /^172\.\d{1,3}\.\d{1,3}\.\d{1,3}$/);

    received = [];
    proxy = createServer(async (request, response) => {
        try {
            assert.equal(request.method, "POST");
            assert.equal(request.url, "/logout/connect/back-channel/keycloak");
            const chunks = [];
            for await (const chunk of request) chunks.push(chunk);
            const body = Buffer.concat(chunks).toString();
            const token = new URLSearchParams(body).get("logout_token");
            assert.ok(token);
            const relay = await fetch(appOrigin + request.url, {
                method: "POST",
                headers: { "content-type": "application/x-www-form-urlencoded" },
                body,
            });
            received.push({ token, status: relay.status });
            response.writeHead(relay.status);
            response.end();
        } catch {
            response.writeHead(500);
            response.end();
        }
    });
    await new Promise((done) => proxy.listen(0, "0.0.0.0", done));
    const proxyPort = proxy.address().port;
    dockerSql("CREATE SCHEMA " + schema);
    schemaCreated = true;

    const createdClient = await admin("POST", "/clients", {
        clientId, secret: clientSecret, name: "Ephemeral signed OIDC logout acceptance",
        enabled: true, protocol: "openid-connect",
        publicClient: false, standardFlowEnabled: true, directAccessGrantsEnabled: false,
        serviceAccountsEnabled: false,
        redirectUris: [appOrigin + "/login/oauth2/code/keycloak", appOrigin + "/oidc-negative-callback"],
        attributes: {
            "pkce.code.challenge.method": "S256",
            "backchannel.logout.url":
                "http://" + gateway + ":" + proxyPort + "/logout/connect/back-channel/keycloak",
            "backchannel.logout.session.required": "true",
        },
    });
    clientUuid = createdClient.headers.get("location")?.split("/").at(-1);
    assert.ok(clientUuid, "Ephemeral TEST client ID");

    const createdUser = await admin("POST", "/users", {
        username, email: username + "@test.giaokhoa.localhost",
        firstName: "OIDC logout", lastName: "Acceptance", enabled: true, emailVerified: true,
        credentials: [{ type: "password", value: password, temporary: false }],
    });
    userId = createdUser.headers.get("location")?.split("/").at(-1);
    assert.ok(userId, "Ephemeral TEST user ID");

    await startBackend(port);
    const cookie = await login(appOrigin);
    const sessionCount = () => dockerSql(
        "SELECT count(*) FROM " + schema + ".spring_session WHERE session_id='" + cookie + "'");
    const correlation = () => dockerSql(
        "SELECT sub || '|' || COALESCE(sid, '') FROM " + schema +
        ".oidc_session_correlation WHERE session_id='" + cookie + "'");
    assert.equal(sessionCount(), "1", "JDBC session persisted after Code+PKCE login");
    const recorded = correlation().split("|");
    assert.equal(recorded[0], userId, "OIDC sub must match session owner");
    assert.ok(recorded[1], "OIDC sid must be recorded");
    const sessionSetCookie = jars.get(appOrigin)?.get("SESSION");
    assert.equal(sessionSetCookie, cookie);

    await stopBackend();
    backendLogs = "";
    await startBackend(port);
    assert.equal(sessionCount(), "1", "JDBC session survives backend restart");
    assert.equal(correlation(), recorded.join("|"), "OIDC correlation survives restart");
    // Như trên: endpoint đã tồn tại từ US1, user chưa bootstrap nên fail-closed 503;
    // 503 chứ không phải 302 chứng minh SESSION cũ còn xác thực sau restart.
    const active = await browserFetch(appOrigin + "/api/me/workspaces");
    assert.equal(active.status, 503, "Old SESSION cookie authenticates after restart");

    const badToken = await fetch(appOrigin + "/logout/connect/back-channel/keycloak", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ logout_token: "not.a.signed.jwt" }),
    });
    assert.equal(badToken.status, 400, "Unsigned logout token rejected");
    assert.equal(sessionCount(), "1", "Invalid token cannot invalidate JDBC session");

    const validIdToken = await signedIdTokenWithoutLogoutEvent(appOrigin);
    const idClaims = JSON.parse(Buffer.from(validIdToken.split(".")[1], "base64url").toString());
    assert.equal(idClaims.iss, issuer);
    assert.equal(idClaims.aud, clientId);
    assert.equal(idClaims.events, undefined, "A signed ID token is not a logout event");
    await rejectedWithoutRevocation(appOrigin, validIdToken, sessionCount);

    // Ở phase tiếp theo một cặp RSA/JWKS chỉ-cho-TEST khiến mỗi thất bại tách biệt:
    // xác minh chữ ký thật, một claim không hợp lệ, và một JDBC session không đổi.
    await verifyIsolatedSignatureIssuerAudience(appOrigin, port, sessionCount, correlation, recorded);

    await admin("POST", "/users/" + userId + "/logout");
    await waitUntil(() => received.length > 0, "Keycloak signed back-channel callback");
    const event = received[0];
    assert.equal(event.status, 200, "Spring native signed-token back-channel handler");
    const parts = event.token.split(".");
    assert.equal(parts.length, 3, "Keycloak must sign a JWT logout_token");
    const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString());
    assert.equal(claims.iss, issuer);
    assert.equal(claims.aud, clientId);
    assert.equal(claims.sub, userId);
    assert.equal(claims.sid, recorded[1]);
    assert.deepEqual(claims.events, {
        "http://schemas.openid.net/event/backchannel-logout": {},
    });
    await waitUntil(() => sessionCount() === "0", "Keycloak logout must delete JDBC session");
    assert.equal(correlation(), "", "No stale OIDC correlation after logout");
    const expired = await browserFetch(appOrigin + "/api/me/workspaces");
    assert.equal(expired.status, 302, "Old browser cookie no longer authenticates");
    console.log("PASS: Keycloak-signed logout, issuer/aud/events/sid/sub, DB deletion, restart and old cookie rejection");
}

try {
    await run();
} catch (error) {
    console.error("Native OIDC back-channel acceptance failed:", error);
    if (backendLogs) console.error("Backend startup diagnostics:", backendLogs.slice(-3000));
    process.exitCode = 1;
} finally {
    await stopBackend();
    if (jwksServer) await new Promise((done) => jwksServer.close(done));
    if (proxy) await new Promise((done) => proxy.close(done));
    if (userId) await admin("DELETE", "/users/" + userId).catch((error) => console.error("TEST user cleanup:", error));
    if (clientUuid) await admin("DELETE", "/clients/" + clientUuid).catch((error) => console.error("TEST client cleanup:", error));
    // Chỉ schema mang tên ngẫu nhiên do lần chạy này tạo mới bị gỡ.
    if (schemaCreated) {
        try { dockerSql("SET client_min_messages=warning; DROP SCHEMA " + schema + " CASCADE"); }
        catch (error) { console.error("TEST schema cleanup:", error); process.exitCode = 1; }
    }
}
