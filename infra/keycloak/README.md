# Keycloak — bootstrap, reconcile và kiểm chứng

Thư mục `infra/keycloak/` giữ baseline realm, script bootstrap/reconcile và các
script kiểm chứng native. Tài liệu này mô tả đúng cơ chế đang có trong repo; hướng
dẫn chạy tổng thể ở [quickstart](../../specs/001-multitenant-access/quickstart.md).

## Các realm và nguồn sự thật

| Realm | Nguồn | Cách nạp | Ghi chú |
| --- | --- | --- | --- |
| `giaokhoa` | `baseline/realm.json` | `bootstrap.sh` qua Admin API | Realm vận hành DEV; bootstrap tạo ở trạng thái tắt và chỉ bật sau khi đã gỡ quyền mặc định (xem dưới) |
| `giaokhoa-it` | `test-realm.json` | Keycloak tự import khi stack TEST khởi động (`start-dev --import-realm` trong `infra/compose.test.yaml`) | Chỉ cho integration test; đã khai SMTP tới Mailpit TEST; `registrationAllowed=false` |
| Realm tạm của `verify-native.sh` | `baseline/realm.json` + fixture trong `fixtures/` | Script tự dựng stack riêng | Phục vụ native gate, tự dọn container/network sau khi chạy |

## Bootstrap/reconcile realm `giaokhoa`

`bootstrap.sh` chạy từ máy có `curl` và `jq`, dùng tài khoản bootstrap admin **chỉ tại
thời điểm bootstrap** (password grant của `admin-cli` không được cấp cho bất kỳ client
hướng người dùng nào). Các biến bắt buộc — script dừng ngay nếu thiếu:

| Biến | Nội dung |
| --- | --- |
| `KEYCLOAK_ADMIN_BASE_URL` | Địa chỉ Keycloak Admin API truy cập được từ nơi chạy script |
| `KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME` / `KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD` | Tài khoản bootstrap admin (DEV mặc định `admin`/`admin` trong `.devcontainer/compose.yml`; môi trường khác dùng credential riêng) |
| `OIDC_CLIENT_SECRET` | Secret của client `giaokhoa-web` |
| `KEYCLOAK_PROVISIONER_CLIENT_SECRET` | Secret của client `giaokhoa-provisioner` |
| `APP_ORIGIN` | Origin public chính xác của ứng dụng (không wildcard, không credential trong URL) |

Script kiểm tra trước khi ghi: hai secret phải khác nhau, dài tối thiểu 16 ký tự và
không còn là placeholder `replace-with-...`.

Hành vi reconcile (idempotent, chạy lại được mỗi khi đổi `APP_ORIGIN`, secret hoặc
baseline):

- Realm chưa có: tạo từ baseline ở trạng thái **tắt** kèm thuộc tính
  `gk.bootstrap.pending=true`, gỡ Default Permission/Default Resource/Default Policy
  và mọi wildcard allow mặc định của Keycloak, rồi mới bật realm. Realm bị tắt vì lý
  do khác không bị script tự bật.
- Đồng bộ các trường realm theo baseline (gồm `registrationEmailAsUsername=false`,
  `verifyEmail=true`, chính sách phiên/token), bỏ bắt buộc `email`/`lastName` trong
  user profile, và tắt `defaultAction` của `VERIFY_EMAIL`, `UPDATE_PROFILE`,
  `VERIFY_PROFILE` để không tự gắn các action này cho mọi identity (đặc biệt là học
  viên không có email).
- Đồng bộ ba client `giaokhoa-web`, `giaokhoa-api`, `giaokhoa-provisioner`. Redirect
  URI, post-logout redirect và back-channel logout URL của web client suy từ
  `APP_ORIGIN` tại thời điểm chạy — đổi origin phải chạy lại script. Script từ chối
  tiếp tục nếu web client có group-membership mapper; access token chỉ mang audience
  mapper `giaokhoa-api-audience`.
- Ép resource server `giaokhoa-api` về `policyEnforcementMode=ENFORCING`,
  `decisionStrategy=UNANIMOUS`, `allowRemoteResourceManagement=true`, và **từ chối
  tuyên bố thành công** nếu vẫn còn resource wildcard `/*`.
- Đảm bảo cây realm group cố định `/gk`, `/gk/identity`, `/gk/identity/eligible`,
  `/gk/platform`, `/gk/platform/operators`, `/gk/workspaces` tồn tại (đây là group
  cố định của hệ thống, không phải Organization group).
- Gán cho service account của `giaokhoa-provisioner` đúng các role realm-management
  cần cho directory + authorization Admin API (manage/view/query users,
  query-groups, view-clients, manage/view/query organizations, manage/view
  authorization) — không cấp `realm-admin` hoặc `uma_protection`.
  Bootstrap thu hồi grant UMA cũ nếu realm đã từng được bootstrap trước thay đổi này.

Script **không** chạm vào directory (user/organization/membership) và policy graph đã
có: chạy lại bootstrap không reset dữ kiện và không thay thế reconcile của ứng dụng
(ProjectionWorker/ProjectionReconciler trong backend).

Baseline realm **chưa khai SMTP**. Ở DEV, stack có Mailpit (`infra/compose.dev.yaml`,
SMTP `mailpit:1025`, UI `http://127.0.0.1:8025`); muốn realm `giaokhoa` gửi thư xác
minh/khôi phục ở DEV thì đặt SMTP của realm tới Mailpit đó trong Admin Console
(Realm settings → Email). Realm TEST đã khai sẵn SMTP trong `test-realm.json`.

## Kiểm chứng native

```bash
npm --prefix frontend ci
cd frontend && npx playwright install chromium
bash infra/keycloak/verify-native.sh
```

Script tạo Keycloak 26.8.1 tại cổng 18091 và PostgreSQL tạm trên mạng Docker riêng,
bootstrap realm, đăng nhập hai actor thử nghiệm bằng Chromium qua Authorization Code
+ PKCE S256, chạy `AuthorizationProvisioningClientNativeIT`,
`NativePermissionGateIT`, `NativeTimePolicyIT` rồi tự dọn container/network. CI gọi
script với `--use-build-artifact` để dùng test đã biên dịch từ job build; chạy độc
lập ở local thì không truyền cờ này.

```bash
node infra/keycloak/verify-oidc-logout.mjs
```

Kiểm tra back-channel logout qua restart backend bằng `logout_token` thật do Keycloak
TEST ký; cần stack TEST đang chạy và các biến `TEST_DATABASE_URL`,
`TEST_KEYCLOAK_BASE_URL`, `TEST_KEYCLOAK_REALM` đã nạp theo `.env.test.example`.
Script tự dọn tài nguyên test, không đụng DEV. Chi tiết ở
[backend/README.md](../../backend/README.md).

## Emergency console

Sửa membership/policy trực tiếp trên Admin Console là thao tác emergency ngoài luồng
ứng dụng và phải theo giao thức ở
[contracts/authorization.md](../../specs/001-multitenant-access/contracts/authorization.md):
**fence phạm vi trước, sửa, reconcile, kiểm tra rồi mở lại**. Không có listener đồng
bộ tức thời cho thao tác ngoài luồng; desired state do ứng dụng sở hữu được
ProjectionWorker đối chiếu mỗi giây nên thay đổi console không qua giao thức có thể
bị reconcile ghi đè hoặc làm lệch fence. Lệch cấu hình thuộc baseline realm thì xử lý
bằng cách chạy lại `bootstrap.sh`, không sửa tay từng trường.

## Fixtures

`fixtures/` chỉ chứa fixture nghiệm thu cho test/review (native gate, actor theo
story), không chạy trên production và không nhập đè realm hay dữ kiện DEV. Xem
[fixtures/README.md](fixtures/README.md) để biết bộ dữ kiện theo story và điều kiện
cài đặt trước khi chạy `verify-native.sh`.
