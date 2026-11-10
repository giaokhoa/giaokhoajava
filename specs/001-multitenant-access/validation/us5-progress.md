# US5 — Bằng chứng sửa lệch spec và trạng thái gate (chưa nghiệm thu T068)

Ngày: 2026-11-09. Nhánh local: `review/backend-java-spring-20261109` trên nền `ae5c762`.
Các thay đổi trong báo cáo này **chưa commit/chưa merge**; không dùng báo cáo này để đóng T068.

## Phạm vi sửa trên nhánh

- `PublishedResultService`, `StudentResultController`, `ProtectedActionExecutor`: dựng DTO trong authorization fence; kể cả trang kết quả không có hàng vẫn kiểm tra eligibility/ACCOUNT; keyset `(published_at,id)` và HMAC cursor gắn actor/workspace/student/limit; lọc trạng thái `PUBLISHED` ở bước dựng DTO; không trả totalCount.
- `GuardianLinkController`, `MembershipController`: đọc list dưới workspace advisory lock đồng bộ với coordinator revoke; không thay ngoại lệ quyết role bằng SQL đã chốt trong `contracts/authorization.md`.
- `AuthorizationProvisioningClient`, `GuardianPolicyProjector`: query organization theo exact+pagination, đọc các realm group theo trang và chỉ một lần trên mỗi parent khi reconcile nhiều student.
- `CenterService`, `CenterController`: phân trang SQL keyset metadata của platform center, cursor gắn actor/limit; endpoint trả `{items,nextCursor?}` thay mảng trần; cập nhật các client/test liên quan.
- `frontend`: operation pending được poll dựa trên trạng thái server; chỉ `SUCCEEDED`+`completed=true` mới coi là hoàn tất; dữ liệu/capabilities được refresh theo actor/workspace sau mutation; đổi phiên/workspace remount state và hủy poll; không dùng dữ liệu cấp quyền lấy từ một trang membership duy nhất.
- `contracts/http-api.md`, `docs/diem-chua-chac-chan-va-lech-spec.md`: cập nhật hợp đồng và trạng thái các ghi nhận A2/A3/A5/A13, giữ nguyên lịch sử để đối chiếu.
- Các lệch chuẩn trước US5: CI action chuyển sang runtime Node 24 và luôn cài OS deps Playwright; sửa comment PMD; tài liệu T014 ngừng chỉ dẫn chạy verifier không tồn tại (A8/A9/A10). CI YAML đã được parse bằng `js-yaml`, **chưa chạy GitHub Actions cloud**. Đối chiếu R12 cho thấy `registrationAllowed=true` là chủ đích chứ không phải bug; `sslRequired:none` cho production và baseline logout URL chưa được xử lý khi chưa chốt TLS/origin.

## Kiểm tra đã có kết quả

| Gate | Kết quả | Bằng chứng tại máy TEST |
| --- | --- | --- |
| Maven `verify` (unit, NullAway, Spotless, PMD, SpotBugs) | ĐẠT, 190/190 unit tests, 0 failure/error | `/tmp/gk-fix-verify9.log`, `backend/target/surefire-reports/` |
| `PublishedResultServiceIT#approvedParentReadsOwnChildOnly` trên PostgreSQL/Keycloak TEST | ĐẠT 1/1 (lần cuối `BUILD SUCCESS`), gồm hai trang và không tái dùng cursor giữa học sinh hay khi đổi `limit` | `/tmp/gk-fix-result-it-final.log` |
| `CenterAdministrationIT#operatorListsPlatformCenters` trên PostgreSQL/Keycloak TEST | ĐẠT 1/1 ở lần chạy lại; test chờ có giới hạn chỉ khi response có `AUTHORIZATION_NOT_READY` | `/tmp/gk-fix-center-it-retry.log` (`BUILD SUCCESS`) |
| Frontend TypeScript, ESLint, Vitest | ĐẠT, 80/80 unit tests | `/tmp/gk-fix-front-advanced.log` |
| Next.js production build | ĐẠT | `/tmp/gk-fix-next-final.log` |
| Playwright UI-regression với HTTP fixture | ĐẠT, 3/3, gồm pending→success, retry idempotency, membership/roster >20 | `/tmp/gk-fix-teaching-ui-e2e3.log` |
| Regression mock hai thread revocation/materialization | ĐẠT; mới chứng minh giao thức giữ callback trong critical section, chưa thay thế Postgres concurrency IT thật | `/tmp/gk-fix-concurrency-unit2.log` |

Lượt integration `/tmp/giaokhoa-review-full-it-fixed.log` đã khởi chạy **trước** các thay đổi này, **không** được dùng để chốt regression trên code sau sửa. Lượt đó đã báo `TeachingAuthorizationIT` 14 test với 12 failure (`Operation did not succeed in time`) trong lúc dùng chung realm TEST với nhiều thử nghiệm. Chưa phân loại được timeout do worker/projection quá tải hay defect thực; đây là **blocker full gate**, không được tính là pass và không được che bằng retry tùy tiện. Lần chạy chọn lọc đầu của center gặp 503 dưới tải cùng suite, rồi được thử lại chỉ với `AUTHORIZATION_NOT_READY` và đạt; không biến mọi 503 thành retry.

## Chưa đóng gate T068

- Chưa chạy lại **toàn bộ** `-Pintegration verify` từ đầu trên một cây đã đóng băng và realm TEST ổn định. Cần điều tra/reproduce 12 timeout của `TeachingAuthorizationIT` trên instance TEST cô lập, không dùng kết quả của suite cũ để suy ra code mới tốt hoặc xấu.
- Chưa chạy toàn bộ Playwright E2E native revocation và guardian/center trên mã mới (UI fixture không thay thế OIDC thật).
- Chưa chạy lại `verify-native.sh` và `verify-oidc-logout.mjs` trên cây này.
- Chưa có bằng chứng integration rào cản PostgreSQL giữa revoke và DTO materialization thực tế sau thay đổi.
- Các phần US6–US8 không thuộc gate này. Các ngoại lệ SQL trong hợp đồng authorization vẫn là quyết định thiết kế có ghi nhận, không được tự đổi trong một bản vá US5.

**Trạng thái**: fix có test xanh ở các lát cắt đã ghi, **T068: CHƯA ĐẠT/CHƯA NGHIỆM THU**. Không đóng issue và không merge/push thay người duyệt.
