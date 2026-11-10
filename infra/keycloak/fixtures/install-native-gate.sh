#!/usr/bin/env bash
# Được verify-native.sh source vào trên realm dùng một lần của nó, không bao giờ trên DEV.
GATE_FIXTURE="$ROOT/fixtures/native-gate.json"
AUTHZ="/clients/$API_ID/authz/resource-server"
gate_value() { jq -er "$1" "$GATE_FIXTURE"; }
gate_post() {
  local status
  status="$(curl -sS -o "$TMP/native-gate-response" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary "$2" "$BASE/admin/realms/giaokhoa$1")"
  [[ "$status" == 201 || "$status" == 204 ]] ||
    fail "Native fixture POST $1 returned HTTP $status: $(cat "$TMP/native-gate-response")"
}
gate_id() {
  admin_get "$1" | jq -er --arg name "$2" \
    '[.[] | select(.name == $name)] | if length == 1 then .[0].id // .[0]._id else error("expected one native fixture item") end'
}
gate_group() {
  local path="$1" segment current="" parent="" body
  local -a segments
  IFS='/' read -r -a segments <<< "${path#/}"
  for segment in "${segments[@]}"; do
    current="$current/$segment"
    if ! curl -fsS -H "Authorization: Bearer $TOKEN" \
      "$BASE/admin/realms/giaokhoa/group-by-path$current" > "$TMP/native-group" 2>/dev/null; then
      body="$(jq -n --arg name "$segment" '{name:$name}')"
      if [[ -z "$parent" ]]; then
        gate_post /groups "$body"
      else
        gate_post "/groups/$parent/children" "$body"
      fi
      admin_get "/group-by-path$current" > "$TMP/native-group"
    fi
    parent="$(jq -er .id "$TMP/native-group")"
  done
  printf '%s' "$parent"
}

export NATIVE_GATE_KEYCLOAK_BASE_URL="$BASE"
# Hậu tố Aa1 bảo đảm đủ lớp ký tự theo passwordPolicy của realm baseline (hoa, thường, số).
export NATIVE_GATE_NATIVE_PASSWORD="$(openssl rand -hex 9)Aa1"
NATIVE_GATE_ASSIGNED_USER="$(gate_value .user)"
NATIVE_GATE_CLAIM_USER="$(gate_value .claimOnlyUser)"
NATIVE_GATE_TEACHER_GROUP="$(gate_value .teacherGroup)"
NATIVE_GATE_TEACHER_GROUP_ID="$(gate_group "$NATIVE_GATE_TEACHER_GROUP")"
NATIVE_GATE_TEACHER_B_GROUP_ID="$(gate_group "$(gate_value .teacherGroupB)")"
NATIVE_GATE_ELIGIBLE_GROUP_ID="$(gate_group "$(gate_value .eligibleGroup)")"

# Test client tách biệt phải thực thi Authorization Code + PKCE, không bao giờ password grant.
for test_client in native-gate-test native-gate-forged; do
  gate_post /clients "$(jq -n --arg name "$test_client" \
    '{clientId:$name,enabled:true,publicClient:true,standardFlowEnabled:true,
      directAccessGrantsEnabled:false,serviceAccountsEnabled:false,protocol:"openid-connect",
      redirectUris:["http://127.0.0.1:18895/callback"],
      attributes:{"pkce.code.challenge.method":"S256"}}')"
  admin_get "/clients/$(client_id "$test_client")" | jq -e \
    '.standardFlowEnabled == true and .directAccessGrantsEnabled == false and
      .attributes["pkce.code.challenge.method"] == "S256"' >/dev/null || \
    fail "Native actor clients must use Code+PKCE, never password grant"
done
NATIVE_FORGED_ID="$(client_id native-gate-forged)"
gate_post "/clients/$NATIVE_FORGED_ID/protocol-mappers/models" "$(jq -n --arg group "$NATIVE_GATE_TEACHER_GROUP" \
  '{name:"native-forged-groups",protocol:"openid-connect",protocolMapper:"oidc-hardcoded-claim-mapper",
    config:{"claim.name":"groups","claim.value":$group,"jsonType.label":"String","access.token.claim":"true"}}')"
gate_post /roles '{"name":"native-gate-teacher-role"}'
ROLE_JSON="$(admin_get '/roles/native-gate-teacher-role')"

for user in "$NATIVE_GATE_ASSIGNED_USER" "$NATIVE_GATE_CLAIM_USER"; do
  attrs='{}'
  if [[ "$user" == "$NATIVE_GATE_CLAIM_USER" ]]; then
    attrs="$(jq -n --arg group "$NATIVE_GATE_TEACHER_GROUP" '{gk_claimed_groups:[$group]}')"
  fi
  body="$(jq -n --arg user "$user" --arg email "$user@example.test" \
    --arg password "$NATIVE_GATE_NATIVE_PASSWORD" --argjson attrs "$attrs" \
    '{username:$user, email:$email, emailVerified:true, firstName:"Native", lastName:"Gate",
      enabled:true, requiredActions:[], attributes:$attrs,
      credentials:[{type:"password", value:$password, temporary:false}]}')"
  gate_post /users "$body"
  uid="$(admin_get "/users?username=$user&exact=true" |
    jq -er --arg user "$user" '[.[] | select(.username == $user)] | if length==1 then .[0].id else error("missing fixture actor") end')"
  curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
    "$BASE/admin/realms/giaokhoa/users/$uid/groups/$NATIVE_GATE_ELIGIBLE_GROUP_ID"
  if [[ "$user" == "$NATIVE_GATE_ASSIGNED_USER" ]]; then
    export NATIVE_GATE_ASSIGNED_USER_ID="$uid"
    curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
      "$BASE/admin/realms/giaokhoa/users/$uid/groups/$NATIVE_GATE_TEACHER_GROUP_ID"
  else
    # Actor này có một phân công B *thật* nhưng chỉ có A claim/role bịa ra.
    curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
      "$BASE/admin/realms/giaokhoa/users/$uid/groups/$NATIVE_GATE_TEACHER_B_GROUP_ID"
    curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "[$ROLE_JSON]" "$BASE/admin/realms/giaokhoa/users/$uid/role-mappings/realm"
  fi
done

for scope in $(jq -r '.scopes[]' "$GATE_FIXTURE"); do
  gate_post "$AUTHZ/scope" "$(jq -n --arg name "$scope" '{name:$name}')"
done
READ_ID="$(gate_id "$AUTHZ/scope" read)"
UPDATE_ID="$(gate_id "$AUTHZ/scope" update)"
OPEN_ID="$(gate_id "$AUTHZ/scope" attempt.open)"
gate_resource() {
  local key="$1" scope_ids="$2" name
  name="$(gate_value ".$key")"
  gate_post "$AUTHZ/resource" "$(jq -n --arg name "$name" --argjson scopes "$scope_ids" \
    '{name:$name,ownerManagedAccess:false,scopes:[$scopes[]|{id:.}]}')"
  gate_id "$AUTHZ/resource" "$name"
}
export NATIVE_GATE_RESOURCE_A_ID="$(gate_resource resourceA "$(jq -n --arg a "$READ_ID" --arg b "$UPDATE_ID" '[$a,$b]')")"
export NATIVE_GATE_RESOURCE_B_ID="$(gate_resource resourceB "$(jq -n --arg a "$READ_ID" '[$a]')")"
export NATIVE_GATE_ACTIVITY_ID="$(gate_resource activity "$(jq -n --arg a "$OPEN_ID" '[$a]')")"

gate_group_policy() {
  local name="$1" group_id="$2"
  gate_post "$AUTHZ/policy/group" "$(jq -n --arg name "$name" --arg id "$group_id" \
    '{name:$name,logic:"POSITIVE",groups:[{id:$id,extendChildren:false}]}')"
  gate_id "$AUTHZ/policy" "$name"
}
ELIGIBLE_POLICY_ID="$(gate_group_policy "$(gate_value .policy.eligibility)" "$NATIVE_GATE_ELIGIBLE_GROUP_ID")"
TEACHER_POLICY_ID="$(gate_group_policy "$(gate_value .policy.teacher)" "$NATIVE_GATE_TEACHER_GROUP_ID")"
TEACHER_B_POLICY_ID="$(gate_group_policy "$(gate_value .policy.teacherB)" "$NATIVE_GATE_TEACHER_B_GROUP_ID")"
gate_aggregate() {
  local name="$1" ids="$2"
  gate_post "$AUTHZ/policy/aggregate" "$(jq -n --arg name "$name" --argjson ids "$ids" \
    '{name:$name,logic:"POSITIVE",decisionStrategy:"UNANIMOUS",policies:$ids}')"
  gate_id "$AUTHZ/policy" "$name"
}
SOURCE_POLICY_ID="$(gate_aggregate "$(gate_value .policy.source)" \
  "$(jq -n --arg a "$ELIGIBLE_POLICY_ID" --arg b "$TEACHER_POLICY_ID" '[$a,$b]')")"
SOURCE_B_POLICY_ID="$(gate_aggregate "$(gate_value .policy.sourceB)" \
  "$(jq -n --arg a "$ELIGIBLE_POLICY_ID" --arg b "$TEACHER_B_POLICY_ID" '[$a,$b]')")"

gate_time_policy() {
  local name="$1" logic="$2"
  gate_post "$AUTHZ/policy/time" "$(jq -n --arg name "$name" --arg logic "$logic" \
    '{name:$name,logic:$logic,notBefore:"2099-01-01 00:00:00"}')"
  gate_id "$AUTHZ/policy" "$name"
}
export NATIVE_GATE_START_POLICY_ID="$(gate_time_policy "$(gate_value .policy.validFrom)" POSITIVE)"
export NATIVE_GATE_END_POLICY_ID="$(gate_time_policy "$(gate_value .policy.expiresAt)" NEGATIVE)"
TIME_POLICY_ID="$(gate_aggregate "$(gate_value .policy.timeGate)" \
  "$(jq -n --arg a "$SOURCE_POLICY_ID" --arg b "$NATIVE_GATE_START_POLICY_ID" \
    --arg c "$NATIVE_GATE_END_POLICY_ID" '[$a,$b,$c]')")"
gate_permission() {
  gate_post "$AUTHZ/permission/scope" "$(jq -n --arg name "$1" --arg resource "$2" \
    --arg scope "$3" --arg policy "$4" \
    '{name:$name,logic:"POSITIVE",decisionStrategy:"UNANIMOUS",
      resources:[$resource],scopes:[$scope],policies:[$policy]}')"
}
gate_permission native-gate-subject-a-read "$NATIVE_GATE_RESOURCE_A_ID" "$READ_ID" "$SOURCE_POLICY_ID"
gate_permission native-gate-subject-b-read "$NATIVE_GATE_RESOURCE_B_ID" "$READ_ID" "$SOURCE_B_POLICY_ID"
gate_permission native-gate-activity-open "$NATIVE_GATE_ACTIVITY_ID" "$OPEN_ID" "$TIME_POLICY_ID"
echo "PASS: isolated native fixture seeded with two actors and per-resource/scope policies"
