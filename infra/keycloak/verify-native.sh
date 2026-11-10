#!/usr/bin/env bash
# Acceptance native tách biệt cho provisioning và authorization của Keycloak: Keycloak 26.8.1 + PostgreSQL dùng một lần.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
native_test_goal=test
if [[ $# -gt 0 ]]; then
  [[ $# -eq 1 && "$1" == --use-build-artifact ]] || { echo 'Usage: verify-native.sh [--use-build-artifact]' >&2; exit 2; }
  # CI đã compile và kiểm tra artifact; gọi đúng native test, không chạy lại vòng đời build.
  native_test_goal=surefire:test
  for test_class in authorization/keycloak/AuthorizationProvisioningClientNativeIT authorization/NativePermissionGateIT authorization/NativeTimePolicyIT authorization/policy/PolicyGraphWriterNativeIT authorization/NativeGateAcceptanceIT; do
    [[ -f "$REPO/backend/target/test-classes/com/giaokhoa/$test_class.class" ]] || { echo "Thiếu native test đã biên dịch: $test_class" >&2; exit 1; }
  done
fi
PORT=18091
BASE=http://127.0.0.1:$PORT
CONTAINER=gk-native-proof
DATABASE=gk-native-postgres
NETWORK=gk-native-network
TMP="$(mktemp -d)"
started=false
db_started=false
network_started=false
cleanup() {
  if [[ "$started" == true ]]; then docker stop "$CONTAINER" >/dev/null 2>&1 || true; fi
  if [[ "$db_started" == true ]]; then docker stop "$DATABASE" >/dev/null 2>&1 || true; fi
  if [[ "$network_started" == true ]]; then docker network rm "$NETWORK" >/dev/null 2>&1 || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
admin_get() {
  curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/admin/realms/giaokhoa$1"
}
admin_put() {
  curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' --data-binary "@$2" "$BASE/admin/realms/giaokhoa$1"
}
assert_admin() {
  admin_get "$1" | jq -e "$2" >/dev/null || fail "GET $1 did not satisfy expected invariant"
}
client_id() {
  admin_get "/clients?clientId=$1" | jq -er --arg id "$1" \
    '[.[] | select(.clientId == $id)] | if length==1 then .[0].id else error("expected one client") end'
}
snapshot() {
  admin_get "/clients" | jq -Sc '[.[] | select(.clientId | startswith("giaokhoa-")) | {clientId,id}] | sort_by(.clientId)'
  for path in /gk /gk/identity /gk/identity/eligible /gk/platform /gk/platform/operators /gk/workspaces; do
    admin_get "/group-by-path$path" | jq -r '.path + "=" + .id'
  done
}

docker network create "$NETWORK" >/dev/null
network_started=true
docker run --rm -d --name "$DATABASE" --network "$NETWORK" \
  -e POSTGRES_DB=keycloak_native -e POSTGRES_USER=keycloak_native \
  -e POSTGRES_PASSWORD=native-disposable-only-password \
  postgres:18.6-alpine >/dev/null
db_started=true
db_ready=false
for i in $(seq 1 45); do
  if docker exec "$DATABASE" pg_isready -q -U keycloak_native -d keycloak_native; then
    db_ready=true; break
  fi
  sleep 1
done
[[ "$db_ready" == true ]] || fail "Scratch PostgreSQL not ready"
docker run --rm -d --name "$CONTAINER" --network "$NETWORK" -p 127.0.0.1:$PORT:8080 \
  -e KC_DB=postgres -e KC_DB_URL="jdbc:postgresql://$DATABASE:5432/keycloak_native" \
  -e KC_DB_USERNAME=keycloak_native -e KC_DB_PASSWORD=native-disposable-only-password \
  -e TZ=UTC \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=native-test-admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=native-disposable-only-password \
  quay.io/keycloak/keycloak:26.8.1 start-dev >/dev/null
started=true
ready=false
for i in $(seq 1 60); do
  if curl -fsS --max-time 2 "$BASE/realms/master/.well-known/openid-configuration" >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || fail "Scratch Keycloak not ready"

export KEYCLOAK_ADMIN_BASE_URL="$BASE"
export KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=native-test-admin
export KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=native-disposable-only-password
export OIDC_CLIENT_SECRET=native-web-only-synthetic-secret
export KEYCLOAK_PROVISIONER_CLIENT_SECRET=native-provisioner-only-synthetic-secret
export APP_ORIGIN=http://127.0.0.1:3000

admin_token() {
  curl -fsS -X POST "$BASE/realms/master/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
    --data-urlencode "username=$KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME" \
    --data-urlencode "password=$KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD" | jq -er '.access_token'
}
TOKEN="$(admin_token)"
echo "CHECK: bootstrap on clean Keycloak"
bash "$ROOT/bootstrap.sh"
before="$(snapshot)"
echo "CHECK: bootstrap second pass, IDs unchanged"
bash "$ROOT/bootstrap.sh"
after="$(snapshot)"
[[ "$before" == "$after" ]] || fail "Second pass changed client or group IDs"
echo "PASS: bootstrap twice and stable IDs"

WEB_ID="$(client_id giaokhoa-web)"
API_ID="$(client_id giaokhoa-api)"
PROV_ID="$(client_id giaokhoa-provisioner)"
RM_ID="$(client_id realm-management)"

assert_admin '' '.enabled == true and .organizationsEnabled == true and .verifyEmail == true and .sslRequired == "external" and .bruteForceProtected == true and .passwordPolicy == "length(10) and digits(1) and lowerCase(1) and upperCase(1) and notUsername(undefined)" and .resetPasswordAllowed == false and ((.attributes["gk.bootstrap.pending"] // "false") == "false")'
assert_admin "/clients/$WEB_ID" '.publicClient == false and .standardFlowEnabled == true and .directAccessGrantsEnabled == false and .serviceAccountsEnabled == false and .attributes["pkce.code.challenge.method"] == "S256" and .redirectUris == ["http://127.0.0.1:3000/login/oauth2/code/keycloak"]'
assert_admin "/clients/$WEB_ID" '.attributes["post.logout.redirect.uris"] == "http://127.0.0.1:3000/" and .attributes["backchannel.logout.url"] == "http://127.0.0.1:3000/logout/connect/back-channel/keycloak"'
admin_get "/clients/$WEB_ID/client-secret" | jq -e --arg secret "$OIDC_CLIENT_SECRET" '.value == $secret' >/dev/null || fail "Web secret not reconciled"
admin_get "/clients/$PROV_ID/client-secret" | jq -e --arg secret "$KEYCLOAK_PROVISIONER_CLIENT_SECRET" '.value == $secret' >/dev/null || fail "Provisioner secret not reconciled"
assert_admin "/clients/$API_ID" '.authorizationServicesEnabled == true and .directAccessGrantsEnabled == false'
assert_admin "/clients/$PROV_ID" '.serviceAccountsEnabled == true and .directAccessGrantsEnabled == false and .standardFlowEnabled == false'
assert_admin "/clients/$API_ID/authz/resource-server/settings" '.policyEnforcementMode == "ENFORCING" and .decisionStrategy == "UNANIMOUS"'
assert_admin "/clients/$API_ID/authz/resource-server/permission/resource" 'all(.[]; .name != "Default Permission")'
assert_admin "/clients/$API_ID/authz/resource-server/resource" 'all(.[]; .name != "Default Resource" and all(.uris // []; . != "/*"))'
assert_admin "/clients/$API_ID/authz/resource-server/policy" 'all(.[]; .name != "Default Policy")'
assert_admin "/clients/$WEB_ID/protocol-mappers/models" 'any(.[]; .name == "giaokhoa-api-audience" and .protocolMapper == "oidc-audience-mapper") and all(.[]; .protocolMapper != "oidc-group-membership-mapper")'
assert_admin '/users/profile' 'any(.attributes[]; .name == "email" and (has("required") | not))'
assert_admin '/authentication/required-actions' 'all(.[]; (.alias != "VERIFY_EMAIL" and .alias != "UPDATE_PROFILE" and .alias != "VERIFY_PROFILE") or .defaultAction == false)'
echo "PASS: realm/client/authz defaults and email optional"

service_user="$(admin_get "/clients/$PROV_ID/service-account-user" | jq -er '.id')"
admin_get "/users/$service_user/role-mappings/clients/$RM_ID" > "$TMP/roles.json"
jq -e '[.[].name] as $roles | (["manage-users","view-users","query-users","query-groups","view-clients","manage-organizations","view-organizations","query-organizations","manage-authorization","view-authorization"] - $roles | length == 0) and ($roles | index("realm-admin") | not) and ($roles | index("manage-clients") | not)' "$TMP/roles.json" >/dev/null || fail "Provisioner realm-management role mapping differs"
assert_admin "/users/$service_user/role-mappings/clients/$API_ID" 'any(.[]; .name == "uma_protection")'
assert_admin "/users/$service_user/role-mappings/clients/$RM_ID/composite" 'all(.[]; .name != "realm-admin" and .name != "manage-clients" and .name != "manage-realm")'
echo "PASS: provisioner direct and effective grants exclude elevated roles"

PROV_TOKEN="$(curl -fsS -X POST "$BASE/realms/giaokhoa/protocol/openid-connect/token" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode client_id=giaokhoa-provisioner \
  --data-urlencode "client_secret=$KEYCLOAK_PROVISIONER_CLIENT_SECRET" | jq -er .access_token)"
provisioner_post() {
  local path="$1" body="$2" status
  status="$(curl -sS -o "$TMP/provisioner-response" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $PROV_TOKEN" -H 'Content-Type: application/json' \
    --data-binary "$body" "$BASE/admin/realms/giaokhoa$path")"
  [[ "$status" == 201 || "$status" == 204 ]] || fail "Provisioner POST $path returned HTTP $status"
}
provisioner_post '/users' '{"username":"student-without-email","enabled":true,"emailVerified":false}'
provisioner_post '/organizations' '{"name":"center-scratch","alias":"center-scratch","enabled":true}'
provisioner_post '/groups' '{"name":"scratch-group"}'
echo "PASS: provisioner created user without email, organization, and realm group"

# Kiểm chứng least privilege bằng cách thử một lần cập nhật cấu hình client bị cấm thật.
forbidden_status="$(curl -sS -o "$TMP/forbidden.json" -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer $PROV_TOKEN" -H 'Content-Type: application/json' \
  --data-binary '{"description":"unauthorized-change-probe"}' \
  "$BASE/admin/realms/giaokhoa/clients/$WEB_ID")"
[[ "$forbidden_status" == 403 ]] || fail "Provisioner client update must be denied (HTTP $forbidden_status)"
echo "PASS: provisioner cannot modify OIDC web client (HTTP 403)"

# Đoạn này gọi class Java production với xử lý token Spring OAuth2 thật.
export PROVISIONING_TEST_KEYCLOAK_BASE_URL="$BASE"
export PROVISIONING_TEST_KEYCLOAK_PROVISIONER_SECRET="$KEYCLOAK_PROVISIONER_CLIENT_SECRET"
PROVISIONING_TEST_KEYCLOAK_USER_ID="$(admin_get '/users?username=student-without-email&exact=true' |
  jq -er '[.[] | select(.username == "student-without-email")] | if length == 1 then .[0].id else error("test user missing") end')"
export PROVISIONING_TEST_KEYCLOAK_USER_ID
if ! "$REPO/backend/mvnw" -B -ntp -f "$REPO/backend/pom.xml" \
    -Dtest=AuthorizationProvisioningClientNativeIT "$native_test_goal" > "$TMP/java-native.log" 2>&1; then
  tail -55 "$TMP/java-native.log" >&2
  fail "Java native provisioning integration test"
fi
grep -E 'Tests run:|BUILD SUCCESS' "$TMP/java-native.log" | tail -4
echo "PASS: production Java client created Organization/group and added/revoked membership"

export NATIVE_GATE_API_CLIENT_ID="$API_ID"
# Chỉ dùng Authorization Services thật và fixture actor trên realm dùng một lần này.
source "$ROOT/fixtures/install-native-gate.sh"
export NATIVE_GATE_NATIVE_TOKENS_FILE="$TMP/native-pkce-actor-tokens"
if ! node "$ROOT/fixtures/native-pkce-login.mjs"; then
  docker logs "$CONTAINER" 2>&1 | tail -55 >&2 || true
  fail "Native actor login via real Authorization Code + PKCE"
fi
if ! "$REPO/backend/mvnw" -B -ntp -f "$REPO/backend/pom.xml" \
    -Dtest=NativePermissionGateIT,NativeTimePolicyIT,PolicyGraphWriterNativeIT,NativeGateAcceptanceIT "$native_test_goal" > "$TMP/native-gate-java.log" 2>&1; then
  tail -70 "$TMP/native-gate-java.log" >&2
  docker logs "$CONTAINER" 2>&1 | tail -90 >&2 || true
  fail "Native permission/time PDP integration tests"
fi
grep -E 'Tests run:|BUILD SUCCESS' "$TMP/native-gate-java.log" | tail -6
echo "PASS: native resource/scope, claim, batch and UTC time boundaries"

# Native time gate có thể vượt quá tuổi thọ ngắn của master admin token.
TOKEN="$(admin_token)"
admin_get '' | jq '.enabled = false | .attributes["gk.bootstrap.pending"] = "true"' > "$TMP/realm.json"
admin_put '' "$TMP/realm.json"
bash "$ROOT/bootstrap.sh"
assert_admin '' '.enabled == true and ((.attributes["gk.bootstrap.pending"] // "false") == "false")'
echo "PASS: interrupted bootstrap recovers"

admin_get '' | jq '.enabled = false | del(.attributes["gk.bootstrap.pending"])' > "$TMP/realm.json"
admin_put '' "$TMP/realm.json"
bash "$ROOT/bootstrap.sh"
assert_admin '' '.enabled == false'
echo "PASS: intentionally disabled realm stays disabled"

echo "NATIVE_AUTHORIZATION_ACCEPTANCE_PASSED"
