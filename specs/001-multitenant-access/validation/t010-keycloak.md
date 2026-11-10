# T010 — Keycloak baseline: kết quả nghiệm thu

## Scope và sửa lỗi review

- Branch `feat/issue-12-keycloak-baseline`; blocker #6 và #8 đã CLOSED.
- Sửa HTTP 404 do ghi vào `PUT /authz/resource-server/settings` thay vì `PUT /authz/resource-server` trên Keycloak 26.8.1.
- Realm mới có marker `gk.bootstrap.pending=true` cho đến khi hoàn tất; lần chạy tiếp theo phục hồi realm bị gián đoạn nhưng không bật realm do admin cố ý tắt.
- Tra cứu group bằng `group-by-path`, không tìm theo tên với giới hạn 1.000 bản ghi. Revoke không được báo thành công nếu không xác định được group.
- Provisioner dùng OAuth2 client credentials manager của Spring Security để tái sử dụng và làm mới token hợp lệ.

## Unit/build

- `bash -n infra/keycloak/bootstrap.sh infra/keycloak/verify-native.sh`: đạt.
- `mvn -q -f backend/pom.xml verify`: đạt; 6 unit test provisioning, 1 integration environment smoke.
- `git diff --check`: đạt.

## Native acceptance Keycloak 26.8.1 — 2026-11-05

Script có thể chạy lại: `bash infra/keycloak/verify-native.sh` (yêu cầu Docker, curl, jq; cổng 18091). Dùng container `quay.io/keycloak/keycloak:26.8.1` mới, tài khoản và client secret **chỉ dành cho môi trường thử nghiệm**, rồi tự stop/remove sau khi xong. Không đọc secret hoặc sửa realm `giaokhoa-it` và không đụng DEV.

**Kết quả thực chạy**: `NATIVE_T010_ACCEPTANCE_PASSED`, exit 0 (lần chạy đầy đủ cuối: 17.17 giây).

- Bootstrap lần đầu từ Keycloak sạch: **PASS**.
- Bootstrap lần hai: **PASS**, giữ nguyên native ID của 3 client và 6 group nền: **PASS**.
- Realm bật; Organizations và verifyEmail bật; marker pending đã xóa: **PASS**.
- `giaokhoa-web` là confidential client Code+PKCE S256, exact redirect/logout/back-channel và secret đúng: **PASS**.
- API bật Authorization Services, ENFORCING + UNANIMOUS; không còn Default Permission/Default Policy/Default Resource hoặc URI `/*`: **PASS**.
- Cả ba client không có Direct Access Grants; web không có group-claim mapper, audience mapper tới API tồn tại: **PASS**.
- Keycloak user profile có email không bắt buộc; VERIFY_EMAIL, UPDATE_PROFILE và VERIFY_PROFILE không còn defaultAction: **PASS**.
- Provisioner là service-account riêng, client secret đúng, có các realm-management/uma_protection roles cần thiết nhưng không có direct realm-admin hoặc manage-clients: **PASS**.
- Native provisioner token đã tự tạo user **không email**, một Organization và một realm group: **PASS**.
- Mô phỏng bootstrap bị gián đoạn bằng realm disabled có pending marker, chạy lại tự bật và xóa marker: **PASS**.
- Mô phỏng admin cố ý disable realm không có marker, chạy bootstrap vẫn giữ `enabled=false`: **PASS**.
- Container thử nghiệm được dọn sau khi chạy (không lưu database test).

## Review follow-up — Java native và kiểm thử quyền phủ định (2026-11-05)

Hai nhận xét Should fix trên commit `86b4ea4` được xác nhận trong mã nguồn trước khi bổ sung test:
- Unit test Java trước đó dùng `MockRestServiceServer` và token manager giả, trong khi script native chỉ dùng curl.
- Kiểm tra role provisioner mới xét direct grant, chưa chứng minh hành vi bị Keycloak từ chối.

Đã bổ sung và **chạy thành công** trong `bash infra/keycloak/verify-native.sh`:
- Maven chạy riêng `-Dtest=AuthorizationProvisioningClientNativeIT`: **1 test, 0 failure/error**. Dùng public constructor thật và Spring OAuth2 client credentials để tạo Organization lặp lại ổn định, tạo realm group lặp lại ổn định, thêm và thu hồi user membership; GET lại Keycloak xác nhận có/không có membership sau mỗi thao tác.
- Đọc **effective** realm-management composite role mappings và xác nhận không có `realm-admin`, `manage-clients` hoặc `manage-realm`.
- Dùng service-account token thử `PUT /admin/realms/giaokhoa/clients/{web-client-id}`: **HTTP 403** (kiểm thử phủ định).
- Log `NATIVE_T010_ACCEPTANCE_PASSED`, exit code 0 sau 28.51 giây; mặc định Maven verify vẫn đạt 7/7 test. Test native chỉ chạy khi gọi script vì phụ thuộc Docker/realm scratch.
- Container `gk-t010-native-proof` được dọn sau khi chạy. Không ảnh hưởng DEV hoặc `giaokhoa-it`.

**Giới hạn đã nêu:** Integration test xác minh cấp token thật, không chủ động ép access token hết hạn để kiểm tra làm mới. Đây là nghiệm thu baseline **T010**. PDP theo actor, resource, scope cho các nghiệp vụ đầy đủ là phạm vi T013/T016/T022; không coi những luồng chưa triển khai đó là đã pass.
