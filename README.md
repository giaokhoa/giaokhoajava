# Giáo khoa — bộ khung Authentication & Authorization đa trung tâm

Backend Spring Boot giữ session và token ở server, frontend Next.js chỉ hiển thị và
điều hướng, Keycloak là nơi quyết định quyền (PDP native) với PostgreSQL làm nguồn dữ
kiện nghiệp vụ. Đặc tả đầy đủ nằm ở `specs/001-multitenant-access/` (spec, plan,
contracts); hướng dẫn chạy và nghiệm thu đầy đủ ở
[quickstart](specs/001-multitenant-access/quickstart.md).

## Yêu cầu

- JDK 25, Node 24 — DevContainer (`.devcontainer/devcontainer.json`) ghim sẵn hai
  phiên bản này; CI dùng đúng JDK 25 / Node 24.
- Docker và Docker Compose cho stack DEV/TEST.
- PostgreSQL 18.6, Keycloak 26.8.1 (image ghim trong các file compose).
- Mốc thời gian nghiệp vụ theo UTC; giữ đồng hồ host/container ở UTC. Baseline vận
  hành một instance Spring và một instance Next; đa instance chưa thuộc phạm vi.

## Chạy DEV

DevContainer mở sẵn project Compose hiện có (`app-db`, `keycloak-db`, `keycloak`,
container làm việc `app`). Từ thư mục gốc, trong DevContainer:

```bash
cp .env.example .env   # thay mọi giá trị replace-with-... bằng giá trị thật, không commit .env
set -a; . ./.env; set +a
docker compose -f .devcontainer/compose.yml -f infra/compose.dev.yaml up -d app-db keycloak-db keycloak mailpit gateway
bash infra/keycloak/bootstrap.sh
./backend/mvnw -f backend/pom.xml spring-boot:run
npm --prefix frontend ci
npm --prefix frontend run dev
```

Ứng dụng mở tại `http://giaokhoa.localhost:3000`, Keycloak tại
`http://auth.giaokhoa.localhost:8081` qua gateway Caddy. Trước lần chạy backend đầu
tiên phải cấp LOGIN cho runtime role `giaokhoa_runtime`, và bootstrap realm cần các
biến admin/secret — xem từng bước và lý do trong
[quickstart](specs/001-multitenant-access/quickstart.md) và
[infra/keycloak/README.md](infra/keycloak/README.md). Frontend không cần biến môi
trường và không nhận credential backend.

## Kiểm tra

```bash
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify   # cần stack TEST đang chạy
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
bash infra/keycloak/verify-native.sh
```

Stack TEST (`infra/compose.test.yaml`, project `giaokhoa-test`) độc lập hoàn toàn với
DEV; cách dựng stack, cấp role runtime và ý nghĩa từng cổng kiểm tra nằm ở
[backend/README.md](backend/README.md). CI (`.github/workflows/ci.yml`) chạy cùng các
lệnh này trên mọi pull request.

## Tài liệu

| Tài liệu | Nội dung |
| --- | --- |
| [specs/001-multitenant-access/quickstart.md](specs/001-multitenant-access/quickstart.md) | Chạy DEV/TEST, seed, vận hành (reconcile, emergency console, khôi phục không email, backup DEV/TEST), kịch bản nghiệm thu |
| [backend/README.md](backend/README.md) | Cổng chất lượng backend, runbook stack TEST, native Keycloak, khôi phục tài khoản không email |
| [infra/keycloak/README.md](infra/keycloak/README.md) | Bootstrap/reconcile realm, kiểm chứng native, giao thức emergency console |
| [specs/001-multitenant-access/spec.md](specs/001-multitenant-access/spec.md) | Đặc tả nghiệp vụ và acceptance scenarios |
| [AGENTS.md](AGENTS.md) | Quy tắc làm việc trong repo cho cả người và agent |
