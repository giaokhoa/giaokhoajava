#!/usr/bin/env bash
# Chạy từ host có curl và jq. Chỉ cấp quyền Admin ở thời điểm bootstrap, không dùng trên web.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASELINE="$ROOT/baseline/realm.json"
: "${KEYCLOAK_ADMIN_BASE_URL:?Set Keycloak Admin URL}"
: "${KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME:?Set bootstrap admin username}"
: "${KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD:?Set bootstrap admin password}"
: "${OIDC_CLIENT_SECRET:?Set confidential web client secret}"
: "${KEYCLOAK_PROVISIONER_CLIENT_SECRET:?Set provisioner client secret}"
: "${APP_ORIGIN:?Set public application origin}"

BASE="${KEYCLOAK_ADMIN_BASE_URL%/}"
ORIGIN="${APP_ORIGIN%/}"
REALM="$(jq -er '.realm' "$BASELINE")"
[[ "$REALM" == "giaokhoa" ]] || { echo "Unexpected baseline realm" >&2; exit 1; }
[[ "$ORIGIN" =~ ^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] ||
  { echo "APP_ORIGIN must be an exact origin without credentials or wildcard" >&2; exit 1; }
for secret in "$OIDC_CLIENT_SECRET" "$KEYCLOAK_PROVISIONER_CLIENT_SECRET"; do
  [[ "$secret" != replace-with-* && "${#secret}" -ge 16 ]] ||
    { echo "Use distinct, non-placeholder client secrets (at least 16 characters)" >&2; exit 1; }
done
[[ "$OIDC_CLIENT_SECRET" != "$KEYCLOAK_PROVISIONER_CLIENT_SECRET" ]] ||
  { echo "Web and provisioner must use different secrets" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"

# Password grant của admin-cli CHỈ dành cho bootstrap; không client hướng-người-dùng nào có password grant.
TOKEN="$(curl -fsS --request POST "$BASE/realms/master/protocol/openid-connect/token" \
  --data-urlencode 'grant_type=password' --data-urlencode 'client_id=admin-cli' \
  --data-urlencode "username=$KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME" \
  --data-urlencode "password=$KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD" | jq -er '.access_token')"

admin_get() {
  curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/admin/realms/$REALM$1" || {
    echo "Keycloak request failed: GET /admin/realms/$REALM$1" >&2
    return 1
  }
}
admin_send() {
  curl -fsS -o /dev/null -X "$1" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' --data-binary "@$3" \
    "$BASE/admin/realms/$REALM$2" || {
      echo "Keycloak request failed: $1 /admin/realms/$REALM$2" >&2
      return 1
    }
}
admin_delete() {
  curl -fsS -o /dev/null -X DELETE -H "Authorization: Bearer $TOKEN" \
    "$BASE/admin/realms/$REALM$1" || {
      echo "Keycloak request failed: DELETE /admin/realms/$REALM$1" >&2
      return 1
    }
}

code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" "$BASE/admin/realms/$REALM")"
if [[ "$code" == 404 ]]; then
  # Vô hiệu hóa realm mới cho tới khi default wildcard permission đã bị gỡ.
  jq '.enabled = false | .attributes["gk.bootstrap.pending"] = "true"' \
    "$BASELINE" > "$TMP/realm-new.json"
  curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' --data-binary "@$TMP/realm-new.json" "$BASE/admin/realms"
elif [[ "$code" != 200 ]]; then
  echo "Cannot inspect realm (HTTP $code)" >&2
  exit 1
fi

# Chỉ các realm được bootstrap dở dang của ta đánh dấu mới được bật khi retry.
# Các realm bị tắt khác giữ nguyên trạng thái có chủ ý của administrator.
admin_get '' > "$TMP/realm-current.json"
PENDING_BOOTSTRAP="$(jq -r '.attributes["gk.bootstrap.pending"] // "false"' "$TMP/realm-current.json")"
jq -s '.[0] * (.[1] | {
  organizationsEnabled, registrationAllowed, registrationEmailAsUsername,
  loginWithEmailAllowed, verifyEmail, duplicateEmailsAllowed,
  sslRequired, bruteForceProtected, passwordPolicy, resetPasswordAllowed,
  revokeRefreshToken, refreshTokenMaxReuse, accessTokenLifespan,
  ssoSessionIdleTimeout, ssoSessionMaxLifespan
})' "$TMP/realm-current.json" "$BASELINE" > "$TMP/realm-update.json"
admin_send PUT '' "$TMP/realm-update.json"

# Tài khoản được cấp có displayName ở firstName, không bắt buộc email hay họ tách riêng.
admin_get '/users/profile' | jq '(.attributes[] | select(.name == "email" or .name == "lastName")) |= del(.required)' \
  > "$TMP/profile.json"
admin_send PUT '/users/profile' "$TMP/profile.json"

# Không tự động gắn các action này cho mọi identity (đặc biệt là học viên không có email).
admin_get '/authentication/required-actions' > "$TMP/actions.json"
for action in VERIFY_EMAIL UPDATE_PROFILE VERIFY_PROFILE; do
  if jq -e --arg a "$action" '.[] | select(.alias == $a)' "$TMP/actions.json" > "$TMP/action.json"; then
    jq '.defaultAction = false' "$TMP/action.json" > "$TMP/action-update.json"
    admin_send PUT "/authentication/required-actions/$action" "$TMP/action-update.json"
  fi
done

client_id() {
  admin_get "/clients?clientId=$1" | jq -er --arg id "$1" \
    '[.[] | select(.clientId == $id)] | if length == 1 then .[0].id else error("Missing/ambiguous client") end'
}

for id in giaokhoa-web giaokhoa-api giaokhoa-provisioner; do
  jq --arg id "$id" --arg origin "$ORIGIN" \
    --arg webSecret "$OIDC_CLIENT_SECRET" \
    --arg provisionerSecret "$KEYCLOAK_PROVISIONER_CLIENT_SECRET" '
    .clients[] | select(.clientId == $id) |
    if $id == "giaokhoa-web" then
      .secret = $webSecret |
      .baseUrl = $origin |
      .redirectUris = [$origin + "/login/oauth2/code/keycloak"] |
      # URL back-channel và post-logout lấy từ APP_ORIGIN tại đây, không hardcode
      # trong baseline (xem Sổ A4): origin/TLS production chưa chốt.
      .attributes += {
        "post.logout.redirect.uris": ($origin + "/"),
        "backchannel.logout.url": ($origin + "/logout/connect/back-channel/keycloak")
      }
    elif $id == "giaokhoa-provisioner" then .secret = $provisionerSecret
    else . end
  ' "$BASELINE" > "$TMP/client-desired.json"

  current="$(admin_get "/clients?clientId=$id")"
  if [[ "$(jq --arg id "$id" '[.[] | select(.clientId == $id)] | length' <<< "$current")" == 0 ]]; then
    admin_send POST '/clients' "$TMP/client-desired.json"
  else
    cid="$(jq --arg id "$id" -er '[.[] | select(.clientId == $id)] | .[0].id' <<< "$current")"
    admin_get "/clients/$cid" > "$TMP/client-existing.json"
    jq 'del(.protocolMappers)' "$TMP/client-desired.json" > "$TMP/client-fields.json"
    jq -s '.[0] * .[1]' "$TMP/client-existing.json" "$TMP/client-fields.json" \
      > "$TMP/client-update.json"
    cid="$(jq -er '.id' "$TMP/client-existing.json")"
    admin_send PUT "/clients/$cid" "$TMP/client-update.json"
  fi
done

WEB_ID="$(client_id giaokhoa-web)"
API_ID="$(client_id giaokhoa-api)"
PROV_ID="$(client_id giaokhoa-provisioner)"

# Đảm bảo access token mang audience của resource-server, không kèm group claim.
admin_get "/clients/$WEB_ID/protocol-mappers/models" > "$TMP/mappers.json"
if ! jq -e '[.[] | select(.protocolMapper == "oidc-group-membership-mapper")] | length == 0' \
  "$TMP/mappers.json" > /dev/null; then
  echo "Unexpected group claim mapper on web client" >&2
  exit 1
fi
jq '.clients[] | select(.clientId == "giaokhoa-web") | .protocolMappers[0]' \
  "$BASELINE" > "$TMP/audience.json"
mapper="$(jq -r '[.[] | select(.name == "giaokhoa-api-audience")] | .[0].id // empty' "$TMP/mappers.json")"
if [[ -z "$mapper" ]]; then
  admin_send POST "/clients/$WEB_ID/protocol-mappers/models" "$TMP/audience.json"
else
  jq --arg mapper "$mapper" '.id = $mapper' "$TMP/audience.json" > "$TMP/audience-update.json"
  admin_send PUT "/clients/$WEB_ID/protocol-mappers/models/$mapper" "$TMP/audience-update.json"
fi

# Ép chế độ enforcing của PDP native; gỡ wildcard allow mặc định của Keycloak.
admin_get "/clients/$API_ID/authz/resource-server/settings" |
  jq '.policyEnforcementMode = "ENFORCING" | .decisionStrategy = "UNANIMOUS" |
      .allowRemoteResourceManagement = true' > "$TMP/authz.json"
admin_send PUT "/clients/$API_ID/authz/resource-server" "$TMP/authz.json"

AUTHZ="/clients/$API_ID/authz/resource-server"
admin_get "$AUTHZ/permission/resource" > "$TMP/permissions.json"
jq -r '.[] | select(.name == "Default Permission") | .id' "$TMP/permissions.json" |
  while IFS= read -r permission; do admin_delete "$AUTHZ/permission/resource/$permission"; done
admin_get "$AUTHZ/resource" > "$TMP/resources.json"
jq -r '.[] | select(.name == "Default Resource") | ._id // .id' "$TMP/resources.json" |
  while IFS= read -r resource; do admin_delete "$AUTHZ/resource/$resource"; done
admin_get "$AUTHZ/policy" > "$TMP/policies.json"
jq -r '.[] | select(.name == "Default Policy") | .id' "$TMP/policies.json" |
  while IFS= read -r policy; do admin_delete "$AUTHZ/policy/$policy"; done

# Một wildcard thêm thủ công là không an toàn; từ chối tuyên bố bootstrap thành công.
admin_get "$AUTHZ/resource" | jq -e 'all(.[]; all(.uris // []; . != "/*"))' > /dev/null ||
  { echo "Wildcard resource still present; inspect manually" >&2; exit 1; }

# Đây là các realm group cố định (không bao giờ là Organization group, không bao giờ là policy kế thừa).
group_id() {
  local path="$1" status
  status="$(curl -sS -o "$TMP/group-lookup.json" -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE/admin/realms/$REALM/group-by-path$path")" || return 1
  if [[ "$status" == 404 ]]; then return 0; fi
  if [[ "$status" != 200 ]]; then
    echo "Keycloak request failed: GET /group-by-path$path (HTTP $status)" >&2
    return 1
  fi
  jq -er '.id' "$TMP/group-lookup.json"
}
ensure_group() {
  local path="$1" parent="${1%/*}" name="${1##*/}" target
  target="$(group_id "$path")"
  [[ -z "$target" ]] || return 0
  jq -n --arg name "$name" '{name: $name}' > "$TMP/group.json"
  if [[ -z "$parent" ]]; then
    admin_send POST '/groups' "$TMP/group.json"
  else
    target="$(group_id "$parent")"
    [[ -n "$target" ]] || { echo "Missing group parent: $parent" >&2; exit 1; }
    admin_send POST "/groups/$target/children" "$TMP/group.json"
  fi
  [[ -n "$(group_id "$path")" ]] || { echo "Group not visible: $path" >&2; exit 1; }
}
for path in /gk /gk/identity /gk/identity/eligible /gk/platform \
  /gk/platform/operators /gk/workspaces; do
  ensure_group "$path"
done

# Chỉ quản lý directory + realm group, cộng Protection API của resource-server.
# Không realm-admin, không manage-clients chung chung và không service account trên web client.
service_user="$(admin_get "/clients/$PROV_ID/service-account-user" | jq -er '.id')"
RM_ID="$(client_id realm-management)"
for role in manage-users view-users query-users query-groups view-clients \
  manage-organizations view-organizations query-organizations \
  manage-authorization view-authorization; do
  admin_get "/clients/$RM_ID/roles/$role" | jq -s '.[0:1]' > "$TMP/role.json"
  admin_send POST "/users/$service_user/role-mappings/clients/$RM_ID" "$TMP/role.json"
done
admin_get "/clients/$API_ID/roles/uma_protection" | jq -s '.[0:1]' > "$TMP/role.json"
admin_send POST "/users/$service_user/role-mappings/clients/$API_ID" "$TMP/role.json"

if [[ "$PENDING_BOOTSTRAP" == true ]]; then
  admin_get '' | jq '.enabled = true | del(.attributes["gk.bootstrap.pending"])' \
    > "$TMP/realm-ready.json"
  admin_send PUT '' "$TMP/realm-ready.json"
fi
echo "Keycloak realm '$REALM' baseline reconciled; existing directory and policy graph preserved."
