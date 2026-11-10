# T006 — Directory migration và entities

**Ngày xác minh:** 2026-11-05

**Nhánh:** `feat/issue-8-foundation-directory`

**Môi trường:** PostgreSQL 18.6 TEST riêng qua `infra/compose.test.yaml`, Keycloak TEST thật.

## Đã kiểm tra

- Flyway áp dụng `V001__identity_workspace_directory.sql` thành công từ database TEST sạch.
- Hibernate `ddl-auto=validate` chấp nhận cả sáu JPA entities và khóa composite `MembershipRole.Key`.
- `./backend/mvnw -B -ntp -f backend/pom.xml -Dtest=IntegrationEnvironmentIT,DirectorySchemaIT test`: **26 passed, 0 failed, 0 errors** (25 test directory và 1 test môi trường).
- `./backend/mvnw -B -ntp -f backend/pom.xml verify`: **BUILD SUCCESS**; test mặc định 1 passed.
- Các test dùng transaction rollback và `SET CONSTRAINTS ALL IMMEDIATE` để thực sự kiểm tra trigger trì hoãn; bao gồm:
  - Tạo center, membership owner và nhiều role trong cùng transaction.
  - Cấm owner khác, xóa role OWNER, thu hồi owner cuối cùng, chuyển owner.
  - Mỗi account đúng một personal workspace; cấm thiếu/xóa/nhân bản.
  - Unique membership hiện hành; cấm role ngoài danh mục; cấm membership ở personal.
  - FK cặp workspace–center, subscription ngoài SYSTEM, khoảng thời gian kết thúc lớn hơn bắt đầu, biên `[valid_from, expires_at)`.
  - Flow `PENDING → ACTIVE → REVOKED`, cấp lại bằng membership mới với role độc lập; không hồi sinh hay sửa lịch sử quan hệ cũ.
  - Không được xóa cứng membership hoặc kỳ gói xác nhận, không sửa metadata xác nhận/kỳ đã bắt đầu; cho phép đổi kỳ tương lai khi tăng `version`.
  - Cùng một transaction đi qua mốc `valid_from`: sau mốc này PostgreSQL phải từ chối gia hạn, bất kể `CURRENT_TIMESTAMP` vẫn là thời điểm bắt đầu transaction.

## Đối chiếu ba nhận xét review #8

1. **Đúng — kích hoạt lại membership đã thu hồi:** PostgreSQL thiếu ràng buộc chuyển trạng thái. Bổ sung trigger chặn mọi thay đổi đối với membership `REVOKED`, chặn `ACTIVE → PENDING`; cấp lại bằng row mới. Test ban đầu thất bại như dự đoán, hiện đạt sau sửa.
2. **Đúng — test chưa chạy trong CI:** job `integration` hiện chạy cả `IntegrationEnvironmentIT` và `DirectorySchemaIT`. GitHub có ruleset `Main ruleset` đang hoạt động, yêu cầu PR/squash **và đã có** required status check `integration` (xác minh trực tiếp qua API rulesets ngày 2026-11-05). Không cần thay đổi cấu hình repository.
3. **Đúng, có giới hạn — lịch sử bị sửa/xóa:** chặn hard-delete membership/kỳ xác nhận; khóa thông tin tham chiếu và xác nhận, khóa sửa các kỳ đã bắt đầu. Theo `data-model.md`, kỳ **tương lai** vẫn được chỉnh thời gian có kiểm soát `version`. Không tạo payment/CRUD/authorization flow mới.

Các test hồi quy đi qua transaction thật trên PostgreSQL TEST; không thêm fake PDP, service/controller giả hoặc test tạm chỉ để khớp cấu trúc triển khai. Bằng chứng ở vòng sửa trước: 7 test âm tính thất bại đúng lỗi; sau sửa: toàn bộ 25 test integration đạt.

## Review lại: thời gian transaction

**Nhận xét đúng.** `CURRENT_TIMESTAMP` cố định từ đầu transaction, khiến kỳ gói đã đi qua `valid_from` vẫn có thể bị đổi ngày. Test `subscriptionLockedAfterStart` bắt đầu transaction, tạo kỳ sắp có hiệu lực, dùng PostgreSQL `pg_sleep_until` chờ qua mốc, xác nhận `CURRENT_TIMESTAMP < valid_from` nhưng `clock_timestamp() > valid_from`, sau đó thử tăng `expires_at` và `version`.

- **Trước sửa:** test mới thất bại vì `UPDATE` vẫn được cho phép.
- **Sau sửa:** trigger dùng `clock_timestamp()` tại lúc cập nhật; test mới đạt, tổng cộng **26/26 integration tests đạt**; Maven `verify` thành công.

## Phạm vi và giới hạn

T006 chỉ tạo schema, JPA entities và thư mục mô tả scenario fixture; không tạo REST CRUD, dữ liệu Keycloak thật hay logic phân quyền. Việc xác nhận actor là platform operator và báo ACTIVE sau projection cần xử lý ở các task PAP/PEP, không suy ra từ một hàng dữ liệu. Fixture thực thi theo story chờ các task test/Keycloak tiếp theo; không tính là gate T022 đã đạt.

TEST database được tái tạo **chỉ trong stack TEST cô lập** để áp dụng lại bản V001 chưa merge sau thay đổi thiết kế; không chạm DEV. Không chạy frontend E2E vì không đổi UI.
