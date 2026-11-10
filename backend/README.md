# Kiểm tra chất lượng backend

Dùng JDK 25 và Maven Wrapper. Chạy các lệnh từ thư mục gốc repository.

```sh
./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
```

`verify` thất bại nếu compiler/NullAway có lỗi, test thất bại, format sai, PMD hoặc SpotBugs phát hiện vi phạm. Không cần chạy script kiểm tra null riêng; các phiên bản được cố định trong `pom.xml`.

| Bước | Phạm vi và mục đích |
| --- | --- |
| javac `-Xlint:all` và `failOnWarning` | Compile cả main/test; chặn raw type, unchecked, deprecated API và các warning compiler khác. |
| Error Prone + NullAway | Kiểm tra hợp đồng JSpecify trong các package `@NullMarked` khi compile cả main/test; lỗi nullability làm build thất bại. Chỉ bật check NullAway trong Error Prone để không thêm rule không liên quan. |
| Spotless / Google Java Format AOSP | Format 4 spaces, thứ tự import và dọn import thừa cho main/test. |
| PMD | Phân tích source main/test: code không dùng, thực hành Java, mẫu lỗi và security. |
| SpotBugs | Phân tích bytecode main/test, effort Max, ngưỡng Medium trở lên. |
| Surefire | Chạy unit test trong build thông thường. |
| Failsafe, profile `integration` | Chạy integration test cho stack PostgreSQL/OIDC TEST theo convention Maven, bao gồm `*IT`; native Keycloak chạy riêng qua script bên dưới. |

## Vai trò database

Hai vai trò tách biệt, không dùng chung:

- **Migration role** (biến `SPRING_FLYWAY_USER`): owner của schema, chỉ dùng để chạy
  Flyway. Các role bootstrap trong compose của repo (`giaokhoa` ở DEV, `giaokhoa_test`
  ở TEST) là owner như vậy; **không trỏ datasource runtime của ứng dụng vào role
  owner/superuser**, vì owner và superuser luôn bypass Row Level Security nên RLS sẽ
  không còn hiệu lực.
- **Runtime role** `giaokhoa_runtime` (biến `SPRING_DATASOURCE_USERNAME`): migration
  V027 tạo sẵn với `NOSUPERUSER NOBYPASSRLS NOLOGIN`, chỉ cấp DML trên các bảng của
  schema ứng dụng. Migration không đặt mật khẩu; khi dựng một môi trường, vận hành cấp
  riêng `ALTER ROLE giaokhoa_runtime LOGIN PASSWORD '...'` bằng credential của chính
  môi trường đó rồi mới trỏ datasource vào role này.

## Integration test

Chỉ dùng stack TEST tại `infra/compose.test.yaml`. Không dùng DB hoặc realm DEV. Chuẩn bị biến môi trường theo `.env.test.example`; xem bước khởi tạo TEST trong `.github/workflows/ci.yml`.

```sh
docker compose -p giaokhoa-test -f infra/compose.test.yaml up -d
# Nạp biến môi trường TEST theo .env.test.example trước lệnh dưới.
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify
```

Build mặc định không cần PostgreSQL/Keycloak và không chạy integration test. CI chạy riêng profile `integration` sau khi khởi tạo stack TEST, tự tìm test mới thay vì giữ danh sách tên lớp.

CI chia backend thành hai runner có stack TEST riêng: `-Pintegration,it-pure verify`
và `-Pintegration,e2e-backend verify`. Hai profile là hai tập bù nhau theo tag
`e2e-backend` đã có từ PR #207; ghép kết quả của cả hai mới đủ suite. Lệnh
`-Pintegration verify` ở local vẫn chạy toàn bộ. Mỗi runner vẫn chạy `clean verify`
bằng JDK 25 và toàn bộ quality gates; check `backend` chỉ xanh khi cả hai tập đạt.
Playwright chia hai project bù nhau trên hai runner độc lập: `chromium-teaching-guardians`
chạy hai file giảng dạy/phụ huynh, `chromium-core` chạy mọi file còn lại (kể cả file mới).
Mỗi runner giữ một worker và stack TEST riêng; native Keycloak và OIDC logout chạy một
lần ở `chromium-core`. Check `integration` chỉ xanh khi cả hai project đạt. XML backend và JSON/trace E2E
được tải lên cả khi test đạt để đối chiếu số ca và thời gian, tối đa 7 ngày;
lỗi quota artifact không thay đổi kết quả kiểm thử.

Test khóa kết quả nghiệp vụ và hợp đồng công khai. Test phân trang chạy PostgreSQL
thật, kiểm tra dữ kiện/trang/cursor thay vì bắt chuỗi SQL. Mỗi yêu cầu có test chịu
trách nhiệm chính; gộp test trùng phải giữ assertion riêng của các tầng khác như
cookie/session, HTTP serialization, RLS và browser Back. Fixture có thể dùng chung
danh tính trong một class, còn center, membership, schema và session của từng ca
vẫn riêng. Không chia sẻ center để tiết kiệm setup khi mutation làm graph tích lũy.

## VS Code và agent

Mở thư mục gốc của worktree. `backend/.settings/org.eclipse.jdt.core.prefs` là cấu hình JDT dùng chung; các file `.settings` còn lại do IDE tự sinh được Git ignore. Giữ một `package-info.java` cho mỗi package trong `src/main/java`; không tạo bản trùng trong `src/test/java`. Package chỉ dành cho test có thể khai báo riêng. Với package JPA `@NullUnmarked`, đặt `@NullMarked` trực tiếp trên class test nếu cần kiểm tra null mà không tạo thêm `package-info.java` trùng. Nếu IDE chưa cập nhật, chạy **Java: Update Project Configuration**.

JDT và javac/NullAway kiểm tra hai phần khác nhau. JDT **không** ánh xạ JSpecify `@NullMarked` sang Eclipse `@NonNullByDefault`: hai annotation có ngữ nghĩa khác nhau cho generic, wildcard và type variable, và phép ánh xạ cũ gây hàng trăm cảnh báo `nullUncheckedConversion` giả từ JDK/Mockito. Thay vào đó, JDT kiểm tra các annotation `@Nullable`/`@NonNull` tường minh và dereference; NullAway trong Maven là bộ kiểm tra chính thức cho các package JSpecify `@NullMarked`, gồm cả main/test. Các tùy chọn `nullReference`, `potentialNullReference`, `nullSpecViolation`, `nullUncheckedConversion`, `deadCode` không bị hạ mức để làm bảng Problems xanh. Nếu một API thư viện thực sự chưa khai báo nullability, ưu tiên Eclipse External Null Annotations (`.eea`) với contract đã kiểm chứng, thay vì gắn `requireNonNull` hoặc suppress ở hàng loạt caller. Maven `verify` là quality gate bắt buộc; PMD có `extraAssertMethodNames=andExpect` vì `MockMvc.andExpect` là assertion, và một ngoại lệ `NOPMD` theo dòng cho lớp base integration cố ý `abstract`.

Các rule PMD về số lượng assertion, message cho mọi assertion và hằng số hóa mọi literal không nằm trong convention này: một test có thể kiểm tra nhiều thuộc tính của cùng hành vi; SQL/fixture được giữ gần nơi dùng. Các rule kiểm tra lỗi vẫn áp dụng cả main/test. Ngoại lệ SpotBugs nằm trong `config/spotbugs-exclude.xml`, giới hạn theo lớp/member và loại lỗi, có lý do ngay tại mỗi mục.

CI chỉ tải báo cáo khi kiểm tra thất bại, lưu tối đa 7 ngày. Bước tải báo cáo phục vụ chẩn đoán và không làm thay đổi kết quả lint/test nếu GitHub hết quota artifact; các bước compiler, lint và test vẫn bắt buộc thành công.

## Native Keycloak

Native tests cần realm `giaokhoa`, khác realm `giaokhoa-it` của stack TEST. Sau `npm --prefix frontend ci` và `cd frontend && npx playwright install chromium`, chạy `bash infra/keycloak/verify-native.sh`: script tạo Keycloak 26.8.1 tại cổng 18091 và PostgreSQL tạm trên Docker network riêng, bootstrap, đăng nhập hai actor thử nghiệm bằng Chromium/Authorization Code + PKCE S256, chạy Java (`KeycloakProvisioningClientNativeIT`, `NativePermissionGateIT`, `NativeTimePolicyIT`) và tự dọn container/network. Phép thử biên thời gian dùng Keycloak admin native policy evaluator với UTC chính xác; UMA thực vẫn dùng thời gian server và bearer token của actor, không đưa context thời gian giả vào request vận hành. CI chạy script này trong job integration sau các kiểm tra của stack TEST; test này được loại khỏi Failsafe của profile `integration` để chạy đúng môi trường, không bị bỏ qua trong CI.

Sau `-Pintegration verify`, chạy `node infra/keycloak/verify-oidc-logout.mjs` khi đã nạp `TEST_DATABASE_URL`, `TEST_KEYCLOAK_BASE_URL` và `TEST_KEYCLOAK_REALM` theo TEST Compose. Script dùng JAR đã build, tạo client/user/schema TEST ngẫu nhiên, đăng nhập Code+PKCE, restart backend và nhận `logout_token` thật do Keycloak ký; kiểm tra độc lập chữ ký/issuer/audience/events bằng JWKS RSA tạm của TEST, sau đó quay về khóa Keycloak thật để kiểm tra xóa session JDBC/correlation. Script tự dọn tài nguyên test, không đụng DEV hoặc chạy Flyway repair. CI chạy test này trong job integration.

## Hỗ trợ khôi phục tài khoản không email

Platform operator xác minh danh tính và ghi chú trước khi chọn **Đã xác minh danh tính**: yêu cầu giữ `VERIFIED`, chưa báo hoàn tất. Operator có thẩm quyền trên Keycloak đặt credential tạm, bàn giao qua kênh riêng cho đúng người dùng và kiểm tra việc bàn giao; sau đó ghi chú và chọn **Xác nhận đã khôi phục và bàn giao** (`COMPLETE`). Không nhập credential vào ghi chú, không gửi qua center, danh sách hoặc operation status. Lỗi hoặc chưa bàn giao thì giữ `VERIFIED`; `REJECT` không đổi credential. Mỗi mutation dùng `Idempotency-Key`; resolve gửi `If-Match` theo `version` của yêu cầu đang xem.

## E2E frontend và backend thật

Sau `clean verify` bằng JDK 25 và `npm --prefix frontend ci`, nạp `TEST_*` đúng stack TEST
ở trên rồi chạy:

```sh
export NATIVE_GATE_APP_ORIGIN=http://127.0.0.1:8080
export TEST_FRONTEND_ORIGIN=http://127.0.0.1:8080
export BACKEND_INTERNAL_URL=http://127.0.0.1:18080
npm --prefix frontend run test:e2e
```

Playwright tự chạy JAR backend ở 18080 với schema TEST ngẫu nhiên, build/chạy Next production
ở 3001 và Caddy
TEST ở 8080 theo cùng cách chia route của gateway DEV. Trình duyệt dùng duy nhất origin
8080 cho UI, API, cookie, CSRF và OIDC; không nối vào DEV. Playwright dừng cả ba tiến trình
khi kết thúc. PostgreSQL/Keycloak/Mailpit TEST hiện có được giữ nguyên. Ở local schema
`e2e_*` được giữ để chẩn đoán; CI dùng database TEST dùng một lần. Thiếu origin trong CI
hoặc khi chạy nhóm giảng dạy là lỗi cấu hình, không thay bằng skip. Nhóm
`teaching-ui-regressions` dùng HTTP fixture để kiểm tra hành vi UI, tách biệt với E2E
`teaching-administration` dùng backend/Keycloak thật.

Runner dùng `TEST_KEYCLOAK_MASTER_USERNAME/PASSWORD` để chuẩn bị riêng realm `giaokhoa-it`:
email/lastName không bắt buộc theo bootstrap production, redirect sau logout về origin TEST và
back-channel qua `host.docker.internal:18080`. Chạy lại `docker compose up -d` theo file
TEST hiện tại để container có mapping `host-gateway`; không xóa volume hay đổi realm DEV.
Chỉ một lượt E2E dùng các cổng 8080/18080/3001 tại một thời điểm; có server cũ thì runner
báo lỗi thay vì tự dùng lại gateway DEV.
