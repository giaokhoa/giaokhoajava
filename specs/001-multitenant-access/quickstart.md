# Hướng dẫn chạy và nghiệm thu bộ khung

**Trạng thái hiện tại**: Backend, frontend, wrapper, cấu hình infra và test suite đã có trong repository; cách bắt đầu và các lệnh kiểm chứng tổng quát nằm ở [README.md](../../README.md). Tài liệu này mô tả cách chạy và nghiệm thu bộ khung theo [spec.md](spec.md).

## 1. Điều kiện

- Java 25, Node 24; Maven Wrapper trong backend và npm lockfile trong frontend.
- PostgreSQL 18.6 app-db và keycloak-db riêng; Keycloak 26.8.1. Tận dụng `.devcontainer/` hiện có, không dùng app database DEV để chạy test phá dữ liệu.
- Docker/Testcontainers nếu có; nếu không có Docker, cung cấp PostgreSQL test database và Keycloak realm test tách biệt qua môi trường. Không thay bằng H2 hoặc mock PDP cho acceptance cốt lõi.
- SMTP test/Mailpit để nhận email xác minh/khôi phục; credential dev/test riêng, không commit secret.
- Một issuer URL browser và Spring cùng truy cập được, đúng issuer token và callback URI. Nếu `localhost:8081` trong container không trỏ tới Keycloak, dùng dev gateway/network alias trong compose override; không tắt issuer validation hoặc sửa token để vượt vấn đề định tuyến.

## 2. Cấu hình dự kiến

Các file được tạo ở implementation: `.env.example`, `frontend/.env.local.example`, `infra/compose.dev.yaml`, `infra/compose.test.yaml` và baseline/fixtures Keycloak. Copy root template thành `.env` cho compose/backend và frontend template thành `frontend/.env.local` chỉ chứa cấu hình frontend. File root dùng cú pháp biến shell tương thích để load vào terminal; không truyền secret Keycloak/DB sang Next. Cung cấp tối thiểu:

| Biến | Nội dung |
| --- | --- |
| `APP_ORIGIN` | Origin public của ứng dụng, thống nhất gateway/callback |
| `OIDC_ISSUER_URI` | Issuer realm giaokhoa có thể truy cập từ browser và backend |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | Confidential web client; secret chỉ tại Spring |
| `KEYCLOAK_AUTHZ_AUDIENCE` | giaokhoa-api |
| `KEYCLOAK_ADMIN_BASE_URL` | Địa chỉ Admin/Protection API backend truy cập được |
| `KEYCLOAK_PROVISIONER_CLIENT_ID` / `KEYCLOAK_PROVISIONER_CLIENT_SECRET` | Client provisioner quyền tối thiểu, không evaluate thay user |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | App database; runtime không owner/BYPASSRLS |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | Migration role tách runtime |
| `MAIL_HOST` / `MAIL_PORT` | SMTP xác minh/khôi phục trong Keycloak hoặc baseline bootstrap |
| `TEST_DATABASE_URL` / `TEST_KEYCLOAK_BASE_URL` | Chỉ dùng khi test services external thay Testcontainers |
| `COMPOSE_PROJECT_NAME` | Dùng đúng tên project của DevContainer hiện có cho dev; test dùng project/volumes khác |
| `BACKEND_INTERNAL_URL` | Chỉ trong frontend/.env.local, địa chỉ Spring cho Server Components; không public và không có credential |

Khi dùng `infra/compose.test.yaml` với test chạy **trên Docker host**, sử dụng `TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/giaokhoa_test` và `TEST_KEYCLOAK_BASE_URL=http://auth.test.giaokhoa.localhost:18081`. Nếu runner **tham gia Docker network của TEST**, dùng `jdbc:postgresql://test-app-db:5432/giaokhoa_test` và `http://auth.test.giaokhoa.localhost:18081` (Keycloak nghe cổng `18081` cả trong container). Test chạy **trong DevContainer mặc định** không thuộc network TEST: `127.0.0.1` là chính DevContainer, còn service name TEST không phân giải được; cần chạy runner trong TEST network hoặc cấu hình kết nối riêng, không dùng database DEV.

Keycloak baseline phải có exact redirects/logout/back-channel, audience mapper phù hợp, no default wildcard allow, no group claim cho policy, không Direct Access Grants. `registrationEmailAsUsername=false`, email optional ở profile, verifyEmail bật; không default VERIFY_EMAIL/UPDATE_PROFILE lên mọi account. Nguồn cấp tài khoản chỉ server xác lập.

Origin local đề xuất là `http://giaokhoa.localhost:3000` và issuer `http://auth.giaokhoa.localhost:8081/realms/giaokhoa`. Gateway giữ các port public 3000/8081, Spring dev nghe 8080, Next dev/start nghe 3001. Compose override phải giải quyết network aliases cho cả host/container và bỏ mapping 8081 trực tiếp của service Keycloak để không xung đột gateway; không tạo thêm stack cùng port khi DevContainer đã chạy. Dùng HTTPS/cookie Secure ở production; local HTTP chỉ trong dev. Không dùng cookie `Domain=.giaokhoa.com`.

## 3. Khởi động dev sau implementation

Từ repository root, với các file dự kiến đã được tạo:

```bash
set -a
. ./.env
set +a
docker compose -f .devcontainer/compose.yml -f infra/compose.dev.yaml up -d app-db keycloak-db keycloak mailpit gateway
./backend/mvnw -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

Terminal frontend riêng:

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

Next tự đọc `frontend/.env.local` trong project frontend; script dev được định nghĩa dùng port 3001. Không dựa vào việc compose tự đọc root `.env` để nghĩ rằng lệnh Maven ở terminal đã nhận biến. Terminal kiểm thử backend cũng phải load các biến test được phép theo cách tương tự; frontend không nhận credential backend.

Bootstrap realm/client/operator và fixtures test/review được cung cấp bằng entrypoint `infra/keycloak/` ở implementation; không dùng default password production, không reset volume hoặc import đè realm đang có. Cấu hình gateway/Next dev port thống nhất với APP_ORIGIN.

Kiểm tra trước chạy nghiệp vụ: gateway tải UI; Spring readiness xác nhận app-db/issuer/policy integration sẵn sàng; browser đến Keycloak đăng nhập, callback về ứng dụng, `/api/session` không có bearer tokens. Authorized client dùng HttpSessionOAuth2AuthorizedClientRepository để token tồn tại cùng session JDBC qua restart; OIDC session correlation lưu bền và handler dùng đúng cookie SESSION. Next không có token/localStorage/session riêng.

## 4. Bộ dữ kiện nghiệm thu tối thiểu

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

Dữ kiện chỉ chứa nội dung giả; actions đi qua cùng dịch vụ/quyền như vận hành. `fixture.manage` chỉ bật trong test/review; không mở endpoint cho phép đổi actor hoặc tự sửa permissions.

## 5. Lệnh kiểm tra đích

```bash
./backend/mvnw -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

Các script frontend trên là tên contract dự kiến của package scripts, chưa tồn tại. E2E dùng Playwright webServer, realm/database test riêng và login Authorization Code thật; không lấy token password grant hoặc mock JWT để thay flow.

## 6. Kịch bản và kết quả mong đợi

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

Mỗi case lưu actor/workspace/object/action, response, operation revision và bằng chứng truy vấn tối thiểu; không log secret/token/password/nội dung cá nhân. Kết quả test thất bại phải nêu cặp quyền/dữ kiện cụ thể, không gộp thành “Keycloak lỗi”.

## 7. Điều kiện chuyển bước

- Gate native ở [authorization.md](contracts/authorization.md) qua bằng chứng thật trước khi mở rộng các module CRUD.
- Toàn bộ 56 acceptance scenarios của spec phải có phép thử hoặc biên bản nghiệm thu tương ứng; các mục tiêu con người không được đánh dấu pass bằng unit test.
- Không mở direct console membership edits ngoài giao thức fence/reconcile; cấu hình MFA khi vận hành vẫn độc lập.
- Không mở public fixture routes, Direct Access Grants, wildcard allow hoặc dùng database DEV trong cấu hình production/test nghiệm thu.
- Hướng dẫn này không tự deploy hoặc yêu cầu khởi động lại hạ tầng đang chạy trong bước lập kế hoạch.
