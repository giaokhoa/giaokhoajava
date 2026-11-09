# T014 — Review lần 5: API audience, domain source, READY contrast và RED verifier

## Review lần 5 — fixture có thể chuyển từ RED sang GREEN

- **GRANT READY A/B trên đúng audience**: các test GRANT dùng resource server
  `TEST_KEYCLOAK_AUTHZ_AUDIENCE=giaokhoa-it-api`, với Keycloak resource name
  chuẩn `workspace:{wid}:resource:{rid}:version:{vid}`, scope `content.read`
  và user policy thật cho actor Keycloak có `user_account` trong PostgreSQL.
  Cả A và B được PDP *của API audience* cho phép trước request; sau đổi GRANT
  A sang PENDING/FAILED, PDP vẫn allow để chứng minh 503 phải đến từ fence.
  Projection `keycloak_resource_id` của A/B trỏ đúng native IDs. Client `t014-*`
  cũ được giữ riêng chỉ cho các kiểm chứng native PDP độc lập.

- **Worker partial recovery có nguồn tái dựng từ DB V001**: bỏ GRANT phiên bản
  ngẫu nhiên không có bảng `share_grant` và bỏ `desired_hash='bbbb...'`.
  Dùng workspace PERSONAL thực sự có `workspace.owner_user_id` nối với
  `user_account.keycloak_sub`; `target_key=workspace:{wid}`, scope `enter`.
  Descriptor `PERSONAL_OWNER|workspace=...|user=...|subject=...|target=...|scope=enter`
  được đọc từ JOIN SQL của domain nguồn đã commit và SHA-256 xác định.
  Projection nằm trong WORKSPACE partition, phụ thuộc ACCOUNT partition,
  operation revision vector `[ACCOUNT:0, WORKSPACE:1]`. Native resource tên
  theo target đã được provision, nhưng chưa có policy/permission và native
  PDP phải DENY tại thời điểm crash. RED đòi T016/T017 dựng lại owner-policy,
  giữ nguyên resource, tạo đúng một permission, đặt hash/revision READY và
  operation SUCCEEDED, rồi native PDP cho phép owner.

- **Cleanup TEST resource server**: fixture dọn scope permissions theo đúng
  resource IDs được test tạo, dọn policy fixture theo ID; khi worker ghi
  `authz_projection.policy_names`, dọn thêm những policy được *chỉ đích danh*
  và không còn được permission/policy nào khác tham chiếu. Không xóa shared
  policies, shared scope, native objects ngoài target; nếu client do test tạo
  thì xóa cả client. Có test GREEN hai fixture dùng chung API resource server:
  dọn B không làm mất A. Mọi thao tác giới hạn đúng TEST realm/host.

**Ranh giới chưa được giả lập:** Domain `share_grant`, `resource_version`
chưa có migration trong Foundation (thuộc user stories), vì vậy GRANT partition
hiện chỉ là control-plane fixture có target/source metadata chứ chưa có
business share row thật. Khi T021/US7 cung cấp nguồn grant, phải thay fixture
bằng source business thật và graph writer T016 để đóng nghiệm thu T022.
T016 có thể điều chỉnh serializer/hàm hash theo canonical PolicyGraph; bất biến
cần giữ là hash từ domain nguồn có thể tái dựng, không chuỗi `a`/`b` giả.
Worker T017 vẫn chưa được cài: RED do thiếu behavior, không do dữ liệu chuẩn bị.

## Tình trạng và cách đọc GREEN/RED

T014 là **test-first**. Kết quả GREEN của PostgreSQL và native Keycloak **không**
đồng nghĩa production PEP/worker đã hoàn chỉnh. Gate E2E cuối cùng là T022.
Tài liệu này bổ sung vào `t014-review.md` trước đó và dùng cùng worktree.

| Phát hiện | Biện pháp và bằng chứng |
| --- | --- |
| P1: không có đối chứng READY | Mỗi trường hợp ACCOUNT / WORKSPACE / GRANT PENDING / GRANT FAILED gọi **cùng** `POST /api/workspaces/{wid}/capabilities`, cùng cookie/session, cùng target trước và sau commit. Trước thay đổi, bắt buộc HTTP 200 **và** đúng một kết quả JSON `allowed=true`; sau fence bắt buộc 503. `assertAll` giữ cả hai assertion RED, không bỏ phần sau khi READY trả 404. |
| P1: GRANT A không được ảnh hưởng B | Khi A GRANT PENDING/FAILED, một GRANT B riêng có projection READY và native PDP allow; test gọi B sau A-fence và bắt buộc HTTP 200 / `allowed=true`. ACCOUNT và WORKSPACE giữ READY. |
| P1: worker không được nhân bản graph khi retry | `productionWorkerMustReconcilePartiallyProvisionedAppGraphAfterRetry` tagged RED tạo resource `workspace:{wid}#enter` thật trong TEST API audience. Personal owner và Keycloak subject có thể được truy ngược qua V001, SHA-256 intent lấy từ JOIN, không còn GRANT thiếu source hay hash giả. Native PDP deny lúc còn thiếu policy/permission. Worker T017 phải resume bằng graph writer, không nhân resource, thêm đúng một permission, ghi policy names và applied hash/revision rồi SUCCEEDED. Hiện RED `T014_WORKER_NOT_INSTALLED`, **không phải lỗi Keycloak**. |
| P2: RED verifier quá dễ chấp nhận | `infra/verify-pending-contracts.sh` xác nhận **chính xác 6 case**, không lỗi setup, và kiểm tra từng failure signature: READY 404 & fenced 404 & independent B 404; worker chưa có bean; concurrent HTTP kết thúc sớm **404**. HTTP 400/403/302/200, 503 mọi trường hợp, lỗi worker Keycloak hoặc thiếu case sẽ làm CI thất bại. |
| P2: khác API audience | Native PDP độc lập của session revoke vẫn dùng client riêng `t014-*`, nhưng GRANT READY A/B và partial worker RED nay dùng **đúng** TEST API audience `giaokhoa-it-api`, đúng Keycloak resource locator của DB projection. Cần bổ sung business `share_grant` thật khi các migration user-story có mặt; synthetic PDP riêng không đủ nghiệm thu T022. |

## Chạy trên TEST

Yêu cầu TEST PostgreSQL + Keycloak và biến `TEST_*` như `backend/README.md`. Không dùng DEV, không sửa schema/chạy migration repair vào TEST public hiện hữu.

```sh
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify
# Lịch sử T014: verify-pending-contracts.sh chưa từng được commit; không chạy lệnh này.
# Bằng chứng cổng hiện hành lấy từ -Pintegration verify và các E2E TEST.
bash infra/keycloak/verify-native.sh
```

Do schema TEST public trên máy kết nối có Flyway V002 checksum khác, thêm cùng ba
tham số sau cho `-Pintegration verify` khi chạy local ở thời điểm T014
(không áp dụng tham số này vào script RED không tồn tại):

```sh
'-Dspring.flyway.schemas=t014_issue16'
'-Dspring.flyway.default-schema=t014_issue16'
'-Dspring.datasource.hikari.connection-init-sql=SET search_path TO t014_issue16,public'
```

ConcurrentRevocationIT tự tạo/migrate schema `t014_concurrent_<UUID>` và drop sau test.
Spring Session JDBC test cũng dọn cookie session. Worker RED chỉ sử dụng
`http://127.0.0.1:18081/realms/giaokhoa-it` và API audience TEST được
allowlist; nếu API client đã có, fixture chỉ tạo và dọn resource/scope của test
ở trạng thái RED hiện tại. Khi worker T017 được triển khai và ghi thêm policy/
permission lên client có sẵn, phải bổ sung cleanup đúng các object do worker tạo.
Không sử dụng production Keycloak hoặc sửa code chạy T017 trước khi issue đó được triển khai.

## Kết quả có thể kiểm tra

- Unit / quality: **28/28 GREEN**, Spotless, PMD, SpotBugs `BUILD SUCCESS` — `/tmp/t014-r6-unit-final.log`.
- Integration (mặc định): **89/89 GREEN** — `/tmp/t014-r6-green-final.log`.
- RED contracts: **6/6 assertion RED, 0 error**, verifier exit 0 — `/tmp/t014-r6-red-final.log`.
- Verifier negative checks: đổi concurrent 404 thành 403, READY 404 thành 503, worker missing thành Keycloak error đều bị từ chối; log xuất từ chính XML report trên máy.
- Native T010/T013 acceptance: **PASS** (`NATIVE_T010_T013_ACCEPTANCE_PASSED`) — `/tmp/t014-r6-native-final.log`.

**Giới hạn còn tồn tại:** Test worker là fault *state* sau partial native provisioning,
chưa thực sự kill/restart process T017; test này RED cho đến khi T017/T016 tồn tại và
reconcile. HTTP READY/503/B vẫn RED do thiếu T021/T018. Test Code+PKCE/JDBC
Spring session GREEN vẫn không thay thế Spring callback/UI login E2E. Chuyển RED
sang CI GREEN khi implementation hoàn tất, không sửa expectation để hợp thức hóa 404.


## Ghi chú đối chiếu sau T066 (2026-11-09)

Bảng RED verifier phía trên ghi lại yêu cầu **lịch sử T014**, không phải hướng dẫn
CI đang hoạt động: `infra/verify-pending-contracts.sh` chưa từng có trong repo,
và các failure signature RED/404 ở mốc trước worker không còn đúng sau khi
worker và authorization fence được triển khai. Không được tạo một script giả
chỉ để công nhận các trạng thái cũ. Gate hiện hành là Maven `-Pintegration verify`
cùng test native/Playwright và acceptance theo từng US, xem `backend/README.md`,
`.github/workflows/ci.yml` và `validation/us5-progress.md`.
