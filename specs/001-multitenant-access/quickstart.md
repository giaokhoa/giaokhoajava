# Hướng dẫn chạy và nghiệm thu bộ khung

**Trạng thái**: Đã triển khai. Mọi lệnh và cấu hình dưới đây trỏ tới file, script và
workflow thật trong repository; chỗ nào repository chưa có cơ chế thì ghi rõ là chưa có,
không mô tả như đã tồn tại. Kết quả nghiệm thu nằm ở thư mục `validation/` và chỉ được
ghi từ lượt chạy thật, không tạo sẵn trạng thái đạt.

Tài liệu liên quan: [README.md](../../README.md) ở thư mục gốc (điểm vào),
[backend/README.md](../../backend/README.md) (cổng chất lượng backend, runbook stack
TEST, khôi phục tài khoản), [infra/keycloak/README.md](../../infra/keycloak/README.md)
(bootstrap/reconcile realm, kiểm chứng native, giao thức emergency console).

## 1. Điều kiện

- JDK 25 và Node 24. DevContainer (`.devcontainer/devcontainer.json`) ghim đúng hai
  phiên bản này qua features; CI cũng dùng JDK 25 / Node 24.
- Docker và Docker Compose. Không có Docker thì không chạy được stack DEV/TEST của repo.
- PostgreSQL 18.6 và Keycloak 26.8.1, image ghim trong `.devcontainer/compose.yml` và
  `infra/compose.test.yaml`. Test tích hợp chạy trên PostgreSQL/Keycloak thật của stack
  TEST; repository không dùng Testcontainers, H2 hay mock PDP cho nghiệm thu cốt lõi.
- Mốc thời gian theo UTC: dữ kiện thời gian nghiệp vụ là `OffsetDateTime`/`Instant` theo
  UTC và template policy native định dạng mốc hết hạn bằng UTC
  (`NativePolicyTemplates`), Keycloak đánh giá time policy theo giờ hệ thống của nó.
  Giữ đồng hồ host và container ở UTC; container Docker mặc định chạy UTC.
- Giới hạn hiện tại: baseline vận hành **một instance Spring và một instance Next**
  (xem `plan.md`). Projection worker claim operation bằng khoá cơ sở dữ liệu nên
  operation không bị xử lý trùng khi khởi động lại, nhưng đa instance chưa được
  nghiệm thu và không thuộc phạm vi bản này.

## 2. Cấu hình

Sao chép `.env.example` thành `.env` ở thư mục gốc và thay mọi giá trị
`replace-with-...` bằng giá trị thật của môi trường; không commit `.env` hay secret.
Nạp vào terminal chạy backend bằng:

```bash
set -a
. ./.env
set +a
```

Frontend không cần biến môi trường: trình duyệt chỉ gọi đường tương đối `/api` cùng
origin (xem `frontend/.env.local.example`), và không biến nào được truyền secret
Keycloak/DB sang Next.

| Biến | Nội dung |
| --- | --- |
| `APP_ORIGIN` | Origin public của ứng dụng, thống nhất gateway/callback |
| `OIDC_ISSUER_URI` | Issuer realm `giaokhoa` truy cập được từ browser và backend |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | Confidential web client `giaokhoa-web`; secret chỉ tại Spring |
| `KEYCLOAK_AUTHZ_AUDIENCE` | `giaokhoa-api` |
| `KEYCLOAK_ADMIN_BASE_URL` | Địa chỉ Admin/Protection API backend truy cập được (`http://keycloak:8080` trong mạng DEV) |
| `KEYCLOAK_PROVISIONER_CLIENT_ID` / `KEYCLOAK_PROVISIONER_CLIENT_SECRET` | Client `giaokhoa-provisioner` quyền tối thiểu; secret khác secret web client |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | App database bằng runtime role `giaokhoa_runtime`; không dùng role owner/BYPASSRLS |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | Migration role tách runtime: ở DEV là role owner `giaokhoa` của compose. Giá trị `giaokhoa_migrator` trong file mẫu chỉ là tên gợi ý — repo không tạo role đó, phải thay bằng role owner thật của môi trường |
| `MAIL_HOST` / `MAIL_PORT` | SMTP cho realm khi cần chạy luồng email ở DEV: `mailpit` / `1025` |
| `COMPOSE_PROJECT_NAME` | Đúng tên project Compose của DevContainer đang chạy; không tạo stack/volume DEV thứ hai |

Role runtime do baseline tạo ở dạng `NOLOGIN` khi chưa tồn tại. Trước lần chạy backend
đầu tiên, cấp LOGIN cho role bằng mật khẩu khớp `SPRING_DATASOURCE_PASSWORD` trong
`.env`, chạy bằng role owner qua container `app-db`:

```bash
docker compose -f .devcontainer/compose.yml exec -T app-db \
  psql -U giaokhoa -d giaokhoa \
  -c "ALTER ROLE giaokhoa_runtime WITH LOGIN PASSWORD '<khớp SPRING_DATASOURCE_PASSWORD>'"
```

Nếu role chưa tồn tại (chưa lần migrate nào chạy), tạo mới theo cùng mẫu lệnh
`CREATE ROLE` như stack TEST ở [backend/README.md](../../backend/README.md).

## 3. Khởi động DEV

DevContainer dùng project Compose đã có; `infra/compose.dev.yaml` là override bổ sung
gateway và Mailpit, bỏ mapping cổng trực tiếp của Keycloak — không chạy override như
một stack độc lập và không xoá volume DEV.

```bash
set -a
. ./.env
set +a
docker compose -f .devcontainer/compose.yml -f infra/compose.dev.yaml up -d app-db keycloak-db keycloak mailpit gateway
```

Gateway (Caddy, `infra/gateway/Caddyfile`) là lối vào duy nhất: `http://giaokhoa.localhost:3000`
cho ứng dụng và `http://auth.giaokhoa.localhost:8081` cho Keycloak; Spring nghe 8080 và
Next dev nghe 3001 bên trong container `app`.

Bootstrap realm `giaokhoa` (bắt buộc trước khi đăng nhập; script idempotent, chạy lại
được khi đổi `APP_ORIGIN`, secret hoặc baseline — chi tiết ở
[infra/keycloak/README.md](../../infra/keycloak/README.md)). Các biến secret và
`APP_ORIGIN` lấy từ `.env` đã nạp ở bước trên; chạy sau khi Keycloak đã khởi động xong
— script báo lỗi kết nối nếu Keycloak chưa sẵn sàng, chạy lại khi sẵn sàng là an toàn:

```bash
export KEYCLOAK_ADMIN_BASE_URL=http://keycloak:8080
export KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin   # mặc định DEV trong .devcontainer/compose.yml
export KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=admin   # chỉ DEV; môi trường khác dùng credential riêng
bash infra/keycloak/bootstrap.sh
```

Chạy backend và frontend (hai terminal trong DevContainer, đã nạp `.env` cho backend):

```bash
./backend/mvnw -f backend/pom.xml spring-boot:run
```

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

Kiểm tra trước khi chạy nghiệp vụ: gateway tải được UI; `GET /actuator/health` của
Spring trả trạng thái tổng (chỉ endpoint health được mở); browser đăng nhập qua
Keycloak và callback về ứng dụng; `/api/session` không chứa bearer token. Session,
authorized client và OIDC correlation lưu phía server (JDBC) nên sống qua restart
Spring; Next không giữ token hay session riêng.

## 4. Stack TEST và kiểm thử

Stack TEST độc lập hoàn toàn với DEV (project `giaokhoa-test`, cổng riêng, credential
`test-only-password` chỉ cho TEST):

```bash
docker compose -p giaokhoa-test -f infra/compose.test.yaml up -d
# Datasource của suite là runtime role nên role phải có LOGIN trước khi chạy:
docker compose -p giaokhoa-test -f infra/compose.test.yaml exec -T test-app-db \
  psql -U giaokhoa_test -d giaokhoa_test \
  -c "CREATE ROLE giaokhoa_runtime LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS"
set -a
. ./.env.test   # bản sao đã điền từ .env.test.example, để ngoài Git
set +a
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify
```

Với test chạy trên Docker host: `TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/giaokhoa_test`
và `TEST_KEYCLOAK_BASE_URL=http://127.0.0.1:18081` (đúng `.env.test.example`). Alias
`auth.test.giaokhoa.localhost` chỉ phân giải được bên trong mạng TEST. Không bao giờ
chạy `down -v` nhầm vào project DEV và không trỏ biến TEST vào database/realm DEV.

Cổng chất lượng đầy đủ (đúng lệnh CI trong `.github/workflows/ci.yml`):

```bash
./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
bash infra/keycloak/verify-native.sh
node infra/keycloak/verify-oidc-logout.mjs
```

Ghi chú: `clean verify` là cổng tĩnh + unit test (compiler/NullAway, Spotless, PMD,
SpotBugs); integration test cần profile `integration` và stack TEST như trên. E2E
Playwright tự chạy JAR backend ở cổng 18080, Next production ở 3001 và gateway TEST ở
8080 trên cùng stack TEST — tại một thời điểm chỉ chạy một lượt E2E giữ các cổng này.
`verify-native.sh` dựng stack Keycloak native riêng (cổng 18091) cho realm `giaokhoa`,
cần `npm --prefix frontend ci` và `npx playwright install chromium` trước; CI gọi
script này với `--use-build-artifact`. `verify-oidc-logout.mjs` kiểm tra back-channel
logout qua restart và cần các biến `TEST_*` đã nạp. Chi tiết từng cổng ở
[backend/README.md](../../backend/README.md).

## 5. Seed và bộ dữ kiện nghiệm thu

- **Seed hệ thống tự động**: khi ứng dụng sẵn sàng, `PlatformProjectionSeeder` ghi
  desired state của grant platform (một partition PLATFORM và một bundle platform)
  bằng đúng projector và hàm băm mà reconciliation dùng. Idempotent: khởi động lại
  khi desired đã khớp không sinh revision mới. Không có bước seed tay cho phần này.
- **Không có script seed dữ kiện nghiệp vụ** cho DEV. Dữ kiện vận hành được tạo qua
  chính API/UI của ứng dụng (platform operator tạo tenant/gói, owner bổ nhiệm
  manager, …) để đi qua đúng đường quyền như vận hành thật.
- **Fixture test/review**: thư mục `infra/keycloak/fixtures/` chứa fixture nghiệm thu
  theo story cho môi trường test/review, không chạy trên production và không nhập đè
  realm/dữ liệu DEV (xem `infra/keycloak/fixtures/README.md`). Realm TEST
  `giaokhoa-it` được import tự động từ `infra/keycloak/test-realm.json` khi stack
  TEST khởi động, kèm cấu hình SMTP tới Mailpit TEST (UI `http://127.0.0.1:18025`).

Bộ dữ kiện nghiệm thu tối thiểu (dựng qua API/UI thật, chỉ chứa nội dung giả):

| Nhóm | Dữ kiện |
| --- | --- |
| Workspace | Personal P; centers A, B; A có lớp L1/L2 |
| Role chéo | U quản lý A, học sinh B, chưa có gói personal |
| Khóa | L1 có K ba môn M1/M2/M3 |
| Giáo viên | T1/T2 cùng M1; T1 thêm M2; H chủ nhiệm L1 nhưng không chuyên môn M3 |
| Học sinh | S1 chỉ M1, S2 M1/M2, S3 toàn bộ; một học sinh CENTER_ISSUED không email |
| Phụ huynh | P1–S1 approved tại A; P2 request chưa duyệt; thêm dữ kiện con ở B không có link A dùng thay |
| Gói | Personal và A có các mốc active/expired độc lập; B vẫn active |
| Học liệu | Q/V1 hệ thống, V2 đính chính; tài nguyên personal, center-owned và licensed; fork giữ giới hạn A; đề tổng hợp hai nguồn |
| Hoạt động | Bài đã giao trước hết hạn có dueAt/attemptLimit cố định; result draft và published của các học sinh khác nhau |
| Chia sẻ | Một REVOCABLE, một LONG_TERM; bản nhận và fork phụ thuộc nguồn |

`fixture.manage` chỉ bật trong test/review; không có endpoint công khai nào cho phép
đổi actor hoặc tự sửa permissions.

## 6. Vận hành: reconcile, emergency console, khôi phục, backup

### Reconcile

- **Projection**: `ProjectionWorker` quét mỗi giây, claim operation tới hạn theo thứ
  tự tạo bằng khoá ghi `SKIP LOCKED` và đối chiếu desired state với Keycloak qua
  `ProjectionReconciler`. Operation ở trạng thái PENDING/FAILED là chưa hoàn tất —
  không báo quyền đã đồng bộ cho tới khi operation SUCCEEDED.
- **Realm baseline**: chạy lại `bash infra/keycloak/bootstrap.sh` để reconcile các
  trường realm, client, audience mapper, cây group cố định và role của service
  account provisioner theo `infra/keycloak/baseline/realm.json`. Script giữ nguyên
  directory và policy graph sẵn có; không import đè realm.

### Emergency console

Khi buộc phải sửa membership/policy trực tiếp trên Keycloak Admin Console ngoài luồng
ứng dụng, theo đúng giao thức ở `contracts/authorization.md`: **fence phạm vi trước,
sửa, reconcile, kiểm tra rồi mới mở lại**. Không có listener đồng bộ tức thời cho thao
tác ngoài luồng: phần desired state do ứng dụng sở hữu sẽ bị reconciler đưa về trạng
thái mong muốn ở chu kỳ quét kế tiếp, nên sửa console không qua fence/reconcile có
thể bị ghi đè hoặc làm lệch fence. Không sửa cây group `/gk` bằng tay ngoài giao
thức này.

### Credential ban đầu và khôi phục tài khoản không email

- Trung tâm cấp danh tính học sinh mới qua `InitialCredentialService`: hệ thống tạo
  user Keycloak kèm credential tạm và chỉ hiển thị credential **một lần** trong
  response tạo đầu tiên — không lưu xuống DB, không ghi log. Mất kết quả bàn giao thì
  đi qua khôi phục tập trung dưới đây, không cấp lại qua đường tạo.
- Người dùng không có email gửi yêu cầu khôi phục (`POST /api/me/recovery-requests`,
  form ở giao diện tài khoản). Platform operator xử lý tại `/platform/recovery-requests`:
  xác minh danh tính ngoài hệ thống và ghi chú trước khi chọn **Đã xác minh danh tính**
  (yêu cầu giữ `VERIFIED`, chưa báo hoàn tất); operator có thẩm quyền trên Keycloak đặt
  credential tạm và bàn giao qua kênh riêng cho đúng người dùng; kiểm tra việc bàn giao
  xong mới ghi chú và chọn **Xác nhận đã khôi phục và bàn giao** (`COMPLETE`). Không
  nhập credential vào ghi chú, không gửi credential qua trung tâm hay danh sách.
  `REJECT` không đổi credential. Mọi mutation dùng `Idempotency-Key`; resolve gửi
  `If-Match` theo `version` của yêu cầu đang xem. Chi tiết ở
  [backend/README.md](../../backend/README.md).

### Backup — chỉ DEV/TEST

Repository **không có công cụ backup tự động** và không có quy trình backup production.
Ở DEV/TEST, dữ kiện nằm trong named volume của Compose (`app_data`, `keycloak_data`
cho DEV; `test_app_data`, `test_keycloak_data` cho TEST). Sao lưu thủ công DEV/TEST
bằng `pg_dump` qua container đang chạy, ví dụ với app database DEV:

```bash
docker compose -f .devcontainer/compose.yml exec -T app-db \
  pg_dump -U giaokhoa -d giaokhoa > backup-dev-app.sql
```

Không dùng `docker compose down -v` trên DEV vì lệnh xoá cả volume dữ kiện.

### Email ở DEV

Baseline realm chưa khai SMTP. Stack DEV có sẵn Mailpit (`infra/compose.dev.yaml`,
UI tại `http://127.0.0.1:8025`, SMTP host `mailpit` cổng `1025` theo `MAIL_HOST`/
`MAIL_PORT`); muốn chạy luồng thư xác minh/khôi phục ở DEV thì đặt SMTP của realm
`giaokhoa` tới Mailpit đó trong Keycloak Admin Console (Realm settings → Email).
Realm TEST đã khai sẵn SMTP tới Mailpit TEST trong `test-realm.json`.

## 7. Kịch bản và kết quả mong đợi

| Mã | Thao tác | Kết quả mong đợi / spec |
| --- | --- | --- |
| T1 | Self-register chưa verified, thử vào nghiệp vụ; xác minh email rồi thử lại; thử tự khai origin issued | Trước bị chặn/onboarding, sau có quyền nền; không tự có author/student role; origin giả vô hiệu; US1 |
| T2 | Cấp username student không email, login/đổi temporary password; thêm cùng identity vào B | Không ép email, không xóa account khi rời A; không manager reset global password; US1/US2 |
| T3 | Operator tạo A/owner/gói, owner bổ nhiệm manager; teacher thử tạo lớp center | Owner/manager vận hành được; teacher bị từ chối dù có personal package; US2/US6 |
| T4 | Tạo khóa ba môn, nhiều giáo viên; ghi danh chọn môn, thêm môn mới sau batch | Đúng môn và đúng người nhận; không tự enroll/assign môn mới; US3 |
| T5 | H giao đề M3 approved rồi thử grade/answer; T1 giao đề sai môn | Giao hợp lệ được phép; grade/answer của H và đề sai môn bị từ chối nếu không có nguồn riêng; US3 |
| T6 | P1 xem S1 published/draft, đổi sid sang S2 và wid sang B; P2 chưa approved | Chỉ P1 đúng con/center/published được xem; không có answer/submission trong DTO; US4 |
| T7 | Giữ nguyên cookie/session, manager revoke assignment T1 tại A rồi T1 gọi lại; thử B | Sau operation SUCCEEDED request tiếp theo bị từ chối tại A; B và gói personal hợp lệ không bị mất; US5 |
| T8 | Quyền target assign true nhưng artifact use false; request nhiều permission chỉ một cấp | Ứng dụng vẫn từ chối; không suy true batch là ALL; FR-018/FR-041 |
| T9 | Lưu Q/V1, phát hành V2/correction, nhận cập nhật và fork | Giữ ID/V1 khi lưu, fork ID mới, nhận update không đổi bài cũ; thông báo đúng nơi nhận; US7 |
| T10 | Revoke REVOCABLE qua bản nhận và fork; grantor rời A/hết gói với LONG_TERM | Bản cần nguồn bị chặn; lịch sử giữ; LONG_TERM còn đúng điều kiện và không có unilateral revoke; US7 |
| T11 | Chạm mốc hết hạn native time policy trước/đúng/sau; thử create/read/submit/grade/extend | Read/hoàn tất bài cũ còn quyền được phép; tạo mới/extend/new attempt/reassign bị chặn; US8 |
| T12 | Gỡ enrollment/license rồi thử completion đang được ngoại lệ hết hạn | Không được dùng ngoại lệ để vượt revoke; US5/US8 |
| T13 | Ngắt provisioner/PDP giữa mutation, crash trước READY, retry idempotency; revoke/write đồng thời | PENDING/FAILED không báo hoàn tất, 503 fail-closed, retry không grant trùng, không stale write sau revoke; FR-049/052 |
| T14 | Thiếu/sai CSRF, logout/back-channel logout hợp lệ và giả, restart Spring; đổi workspace và back navigation | Browser mutation không CSRF bị chặn; back-channel không cần browser CSRF nhưng logout_token giả bị từ chối; restart giữ session/authorized client và correlation đúng; không trả private cache nhầm tenant; US1/US5 |
| T15 | Truy vấn tenant tables bằng runtime role không workspace rồi workspace A; thử FK chéo lớp | Không context không thấy row; không thấy B; insert quan hệ sai bị từ chối; FR-013/051 |
| T16 | Người nghiệm thu thao tác theo SC-001/002/009 | Đo thời gian/thành công, không báo đã đạt khi chỉ có test kỹ thuật |

Mỗi case lưu actor/workspace/object/action, response, operation revision và bằng chứng
truy vấn tối thiểu; không log secret/token/password/nội dung cá nhân. Kết quả test thất
bại phải nêu cặp quyền/dữ kiện cụ thể, không gộp thành "Keycloak lỗi".

## 8. Điều kiện chuyển bước

- Gate native ở [authorization.md](contracts/authorization.md) qua bằng chứng thật trước khi mở rộng các module CRUD.
- 56 acceptance scenarios gốc và 5 scenarios bổ sung ngày 12/11 (tổng 61) phải có phép thử hoặc biên bản tương ứng; các mục tiêu con người không được đánh dấu pass bằng unit test. Ma trận cũ chỉ ghi bằng chứng cho phạm vi/revision đã chạy, không thay nghiệm thu FR-055–057.
- Không mở direct console membership edits ngoài giao thức fence/reconcile ở mục 6; cấu hình MFA khi vận hành vẫn độc lập.
- Không mở public fixture routes, Direct Access Grants, wildcard allow hoặc dùng database DEV trong cấu hình production/test nghiệm thu.

**Readiness hiện hành (12/11/2026)**: Theo GitHub ngày 12/11/2026, #94 CLOSED và PR #302 MERGED (`6be8371` trên `origin/main`); gate #95 vẫn OPEN. Nhánh tài liệu tại `f3d2e618` chưa ghép commit #302. Chỉ chạy bước kiểm chứng được gate hiện hành cho phép; không coi các task nghiệm thu sau gate là đã sẵn sàng vì native blocker riêng lẻ đã đóng.

`TenantMigrationRunner.migrateAllTenants()` trên PR #323 HEAD Phase 12 hiện **chỉ prepare/migrate**, KHÔNG kích hoạt `schema_name`: migration runner không được quyền làm cutover. `TenantSchemaProvisioner.provisionSchema()` từ chối kích hoạt workspace còn dữ kiện ở schema hệ thống (kiểm các bảng theo scope dưới khóa row workspace). Workspace legacy tiếp tục hoạt động trên `public`; trước cutover phải có bước copy/verify toàn bộ dữ liệu và permission, **chưa được triển khai** và chưa đủ native gate của T102. Không coi việc chuẩn bị schema là dữ liệu đã được chuyển. Không reset DEV hoặc sửa baseline đã apply để vượt bước này.

Trước pilot dùng học sinh thật: chốt chính sách giữ/xóa hồ sơ, loại hình lớp và trách nhiệm vận hành; xác minh người đại diện ngoài hệ thống, chỉ lưu tham chiếu tối thiểu khi duyệt quyền xem. Hoàn thành đợt học giữ quyền xem kết quả của chính học sinh trong thời gian hồ sơ còn được lưu hợp lệ; thu hồi bảo mật vẫn chặn. Bài cần bảo đảm hoàn tất dùng nguồn sở hữu/LONG_TERM; nếu hết gói và giáo viên duy nhất nghỉ, phải gia hạn rồi phân công người thay. Các luồng mới này chưa có trong code tại nền đối chiếu; xem [báo cáo hội tụ](../../docs/doi-chieu-spec-code-20261112.md) và task bổ sung.

### Bàn giao hướng dẫn vận hành (T114, không có gate người dùng thật)

Chủ repo đã miễn nghiệm thu người dùng thật SC-001/002/009 cho đợt #2; #98 đóng
`NOT_PLANNED`, không có kết quả 4/5. [Bản đồ/hướng dẫn theo 22 luồng](../../docs/luong-nghiep-vu-theo-vai-tro.md)
chia tính năng đã có và các bước còn **CHƯA TRIỂN KHAI**; không dùng tài liệu đó
làm bằng chứng một luồng đã chạy thành công. Trước khi nhập dữ liệu học sinh thật,
phải xác minh/ghi lại chủ thể xử lý, loại hình hoạt động, thời hạn giữ/xóa, quy
trình người đại diện và đầu mối yêu cầu/tranh chấp theo AS-013. Nếu chưa có các
quyết định này, giữ môi trường test với tài khoản/dữ liệu giả, không tự mặc định
việc triển khai phần mềm đã đáp ứng mọi điều kiện vận hành.
