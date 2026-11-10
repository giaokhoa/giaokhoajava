// Cấu hình realm TEST cho E2E: cùng quy tắc email với bootstrap production và callback về backend TEST.
import assert from "node:assert/strict";

const base = process.env.TEST_KEYCLOAK_BASE_URL;
// Allowlist Keycloak TEST: bản chép của cặp làn ở frontend/tests/e2e/support/test-environment.ts
// (TEST_LANES) — sửa cùng nhau.
assert.ok(["http://127.0.0.1:18081", "http://127.0.0.1:18082"].includes(base));
assert.equal(process.env.TEST_KEYCLOAK_REALM, "giaokhoa-it");
assert.equal(process.env.TEST_OIDC_CLIENT_ID, "giaokhoa-it-web");
const credentials = new URLSearchParams({
  grant_type: "password", client_id: "admin-cli",
  username: process.env.TEST_KEYCLOAK_MASTER_USERNAME ?? "",
  password: process.env.TEST_KEYCLOAK_MASTER_PASSWORD ?? "",
});
const login = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
  method: "POST", body: credentials, signal: AbortSignal.timeout(10_000),
});
assert.equal(login.status, 200, "Đăng nhập admin TEST");
const { access_token: token } = await login.json();
async function admin(path, body) {
  const response = await fetch(`${base}/admin/realms/giaokhoa-it${path}`, {
    method: body ? "PUT" : "GET", signal: AbortSignal.timeout(10_000),
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  assert.ok(response.ok, `Cấu hình TEST ${path}: HTTP ${response.status}`);
  return body ? undefined : response.json();
}
const profile = await admin("/users/profile");
for (const attribute of profile.attributes) {
  if (["email", "lastName"].includes(attribute.name)) delete attribute.required;
}
await admin("/users/profile", profile);
const clients = await admin("/clients?clientId=giaokhoa-it-web");
assert.equal(clients.length, 1);
const client = clients[0];
client.attributes = { ...client.attributes,
  "post.logout.redirect.uris": `${process.env.NATIVE_GATE_APP_ORIGIN}/`,
  "backchannel.logout.session.required": "true",
  "backchannel.logout.url": "http://host.docker.internal:18080/logout/connect/back-channel/keycloak",
};
await admin(`/clients/${client.id}`, client);
