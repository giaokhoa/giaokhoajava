#!/usr/bin/env bash
# Backend dùng một schema TEST riêng cho mỗi lượt Playwright; không chạm DEV.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ "${TEST_DATABASE_URL:-}" == jdbc:postgresql://127.0.0.1:15432/giaokhoa_test && "${TEST_KEYCLOAK_BASE_URL:-}" == http://127.0.0.1:18081 || "${TEST_DATABASE_URL:-}" == jdbc:postgresql://127.0.0.1:15433/giaokhoa_test && "${TEST_KEYCLOAK_BASE_URL:-}" == http://127.0.0.1:18082 ]] || { echo 'Chỉ chạy trên cặp PostgreSQL/Keycloak TEST đã kiểm chứng.' >&2; exit 1; }
[[ "${TEST_KEYCLOAK_REALM:-}" == giaokhoa-it && "${NATIVE_GATE_APP_ORIGIN:-}" == http://127.0.0.1:8080 ]] || { echo 'Cần realm TEST và origin gateway TEST 127.0.0.1:8080.' >&2; exit 1; }
node "$ROOT/infra/testing/prepare-keycloak.mjs"
export SPRING_DATASOURCE_URL="$TEST_DATABASE_URL"
export SPRING_DATASOURCE_USERNAME="${TEST_DATABASE_USERNAME:?}"
export SPRING_DATASOURCE_PASSWORD="${TEST_DATABASE_PASSWORD:?}"
export SPRING_FLYWAY_USER="${TEST_DATABASE_MIGRATOR_USERNAME:?}"
export SPRING_FLYWAY_PASSWORD="${TEST_DATABASE_MIGRATOR_PASSWORD:?}"
export OIDC_CLIENT_ID="${TEST_OIDC_CLIENT_ID:?}"
export OIDC_CLIENT_SECRET="${TEST_OIDC_CLIENT_SECRET:?}"
export OIDC_ISSUER_URI="$TEST_KEYCLOAK_BASE_URL/realms/$TEST_KEYCLOAK_REALM"
export APP_ORIGIN="$NATIVE_GATE_APP_ORIGIN"
export KEYCLOAK_AUTHZ_AUDIENCE="${TEST_KEYCLOAK_AUTHZ_AUDIENCE:?}"
export KEYCLOAK_ADMIN_BASE_URL="$TEST_KEYCLOAK_BASE_URL"
export KEYCLOAK_PROVISIONER_CLIENT_ID="${TEST_KEYCLOAK_PROVISIONER_CLIENT_ID:?}"
export KEYCLOAK_PROVISIONER_CLIENT_SECRET="${TEST_KEYCLOAK_PROVISIONER_CLIENT_SECRET:?}"
SCHEMA="e2e_${RANDOM}_${RANDOM}"
# Heap có giới hạn vì backend, frontend và Keycloak chạy cùng runner TEST.
exec java -Xmx384m -jar "$ROOT/backend/target/giaokhoa-backend-0.0.1-SNAPSHOT.jar" \
  --server.port=18080 \
  --spring.flyway.schemas="$SCHEMA" --spring.flyway.default-schema="$SCHEMA" \
  --spring.datasource.hikari.maximum-pool-size=5 \
  "--spring.datasource.hikari.connection-init-sql=SET search_path TO $SCHEMA,public"
