# Gate native — kết quả nghiệm thu

## Kết luận gate: KHÔNG ĐẠT — dừng mở rộng, có một lỗi production phải xử lý trước

Gate chạy thật trên Keycloak 26.8.1 (realm dùng một lần `giaokhoa`, đúng baseline của
`infra/keycloak/verify-native.sh`) với dữ kiện semantic được provision qua chính
`PolicyGraphWriter` production và quyết định qua chính `KeycloakDecisionClient` production.
**3 trong 9 nhóm case của mục 9 không đạt** vì một lỗi production trong `PolicyGraphWriter`:
một target không thể nhận scope thứ hai trở đi. Chi tiết finding ở ngay mục tiếp theo; theo
quy tắc của gate, kết quả âm tính này được ghi nguyên vẹn — không hạ spec, không đổi PDP,
không sửa writer trong phạm vi việc này.

**Cập nhật:** Finding 1 sau đó đã được sửa trong cùng thay đổi này và gate đã chạy lại đạt
9/9 nhóm case — xem mục "Chạy lại gate sau khi sửa Finding 1" ở dưới. Toàn bộ ghi chép của
lượt đầu dưới đây được giữ nguyên làm lịch sử.

## Finding 1 (chặn gate): PolicyGraphWriter không gắn được scope thứ hai cho một target

- Triệu chứng: `PolicyGraphWriter.apply(...)` cho scope thứ hai của cùng một target (ví dụ
  `activity.assign` sau `completion.read` trên cùng course-subject) ném
  `400 Bad Request: {"error":"invalid_request","error_description":"Scope [activity.assign] is not associated with any of the resources set to the permission"}`
  từ bước tạo root permission (`PolicyGraphWriter.ensureRootPermission`).
- Nguyên nhân gốc, đã tái hiện độc lập bằng curl với admin token trên cùng Keycloak 26.8.1:
  `resourceWithScope` cập nhật resource bằng `PUT /authz/resource-server/resource/{id}` với
  payload scopes chỉ gồm `{"id": ...}`. Keycloak 26.8.1 chỉ giữ scope khi entry có cả `id`
  và `name`: PUT union với entries `{id}`-only trả 204 nhưng tập scope sau đó chỉ còn một
  phần tử; PUT tương tự với `{id, name}` giữ đủ cả hai scope. Vì vậy scope mới không bao giờ
  được gắn vào resource và bước tạo permission bị Keycloak từ chối.
- Vì sao các tầng kiểm chứng trước không bắt được: unit test của writer
  (`PolicyGraphWriterTest`, quanh dòng 392) khẳng định đúng payload `{id}`-only trên mock
  server — mock chấp nhận mọi payload. `PolicyGraphWriterNativeIT` trên Keycloak thật chỉ
  apply một scope cho một target. Đây là ca đầu tiên apply nhiều scope cho cùng target trên
  Keycloak thật.
- Ảnh hưởng production: mọi target cần từ hai scope trở lên (course-subject có
  `completion.read` + `activity.assign`, activity có `submit` + `attempt.open` +
  `schedule.extend` + `reassign`, resource version có `content.read` + `use` + `fork`...) đều
  không provision trọn được; graph của scope thứ hai trở đi sẽ fail ở worker chiếu.
- Hệ quả cho gate: các case §9.4, §9.6, §9.7 (đều cần target đa scope) **không đạt** ở bước
  provision, trước khi tới được khẳng định quyết định. Ba case này phải chạy lại sau khi
  writer được sửa (việc sửa nằm ngoài phạm vi gate này).

## Chạy lại gate sau khi sửa Finding 1 — ĐẠT 9/9

Bản sửa (cùng thay đổi này, sau khi kết quả âm tính ở trên đã được hai vòng review xác
nhận): `PolicyGraphWriter` mang cả `id` và `name` trong mọi entry scope khi tạo resource
và khi cập nhật resource (`resourceWithScope` giữ lại name của các scope đã gắn đọc từ
chính representation hiện hành, scope mới dùng đúng tên đang apply). Unit test của writer
nay khẳng định payload `{id, name}` ở cả hai đường và bao phủ đường cập nhật khi resource
đã có một scope khác gắn sẵn. `PolicyGraphWriterNativeIT` có thêm case apply hai scope
(`enter`, `read`) cho cùng một workspace trên Keycloak thật: khẳng định cùng một resource
giữ đủ hai scope, cả hai graph nguyên vẹn theo `isGraphIntact` và PDP cho phép cả hai cặp
resource/scope.

Môi trường lượt chạy lại: stack TEST native lại chết trước lượt (máy thay mới, `/var/tmp`
trống) và được dựng lại theo runbook trước khi chạy cổng — PostgreSQL 15432, Keycloak TEST
18081 (đủ 5 role của service account fixtures), Keycloak scratch 18091 trên database
`keycloak_native` mới tinh; token actor được cấp tươi ngay trước lượt native nên không có
lỗi token hết hạn nào.

Các cổng của lượt chạy lại, theo đúng thứ tự:

```
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
→ Tests run: 153, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify   (đã nạp env TEST_* của stack TEST native)
→ unit 153/153; failsafe Tests run: 121, Failures: 0, Errors: 0, Skipped: 8 — BUILD SUCCESS
  (8 skip là các lớp native-only theo thiết kế của pom, tăng 1 so với lượt đầu vì
  PolicyGraphWriterNativeIT có thêm case đa scope; chúng được chạy thật ở lượt native riêng)
Lượt native riêng (driver tương đương verify-native.sh trên Keycloak scratch 18091,
bootstrap chạy hai lần, fixture native gate, đăng nhập actor bằng PKCE qua Chromium thật):
→ AuthorizationProvisioningClientNativeIT 1/1
→ NativePermissionGateIT 3/3, NativeTimePolicyIT 1/1, PolicyGraphWriterNativeIT 2/2,
  NativeGateAcceptanceIT 6/6 — tổng 12, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

| Case | Kết quả chạy lại |
|---|---|
| §9.1 | ĐẠT (`NativePermissionGateIT` 3/3) |
| §9.2 | ĐẠT (`NativeGateAcceptanceIT`, cùng lượt native) |
| §9.3 | ĐẠT (`NativePermissionGateIT`) |
| §9.4 | **ĐẠT** — apply `activity.assign` (scope thứ hai) thành công; toàn bộ khẳng định quyết định của case được thực thi và pass |
| §9.5 | ĐẠT (`NativeTimePolicyIT` 1/1) |
| §9.6 | **ĐẠT** — apply `attempt.open` (scope thứ hai) thành công; toàn bộ khẳng định quyết định được thực thi và pass |
| §9.7 | **ĐẠT** — apply `use` (scope thứ hai) thành công; toàn bộ khẳng định quyết định được thực thi và pass |
| §9.8 | ĐẠT (trong 121 IT của cổng integration) |
| §9.9 | ĐẠT (cả hai test trong `NativeGateAcceptanceIT`) |

Hạn chế của lượt chạy lại: spec e2e UI (§9.1 ở tầng UI) không chạy lại vì thay đổi này chỉ
đụng writer và test phía backend; kết quả e2e của lượt đầu (2 passed) giữ nguyên giá trị.
`verify-native.sh` nguyên văn vẫn chỉ chạy được trên CI (máy local không có Docker); lượt
native local là bản tương đương từng bước chức năng như lượt đầu.

## Môi trường chạy và sự cố hạ tầng

- Docker bất khả thi trên máy này (sandbox chặn `setns`), nên `verify-native.sh` không chạy
  nguyên văn được ở local. Thay vào đó gate chạy tương đương từng bước chức năng của script
  trên Keycloak 26.8.1 native: một Keycloak scratch ở cổng 18091 dùng PostgreSQL native
  (database `keycloak_native`), `bootstrap.sh` chạy hai lần, provisioner tạo user không
  email/organization/group, `fixtures/install-native-gate.sh` được source với đúng các biến
  của script, đăng nhập actor bằng `fixtures/native-pkce-login.mjs` qua Chromium thật.
  Trên CI, `verify-native.sh` (có Docker) là đường chạy chính thức và đã được nối thêm
  `NativeGateAcceptanceIT` vào danh sách `-Dtest` của nó trong thay đổi này.
- **Sự cố**: trong lúc chạy gate, máy bị restart, `/var/tmp` bị xoá sạch và toàn bộ stack
  TEST native chết (đúng kịch bản đã ghi trong `~/workspace/tools/test-stack/RUNBOOK.md`).
  Stack đã được dựng lại theo runbook (PostgreSQL + hai Keycloak + cấp lại 5 role cho
  service account fixtures; phải tạo lại user hệ điều hành `pgtest` và mirror
  `/opt/test-stack/pgsql` vì máy thay mới chưa có). Toàn bộ kết quả native và e2e dưới đây
  là của lượt chạy lại trên môi trường dựng lại, realm scratch mới tinh — finding tái hiện
  y hệt, nên kết luận không phụ thuộc dữ liệu cũ. Riêng báo cáo surefire/failsafe của hai
  cổng Maven nằm trong `backend/target` của worktree nên còn nguyên qua restart.

## Kết quả theo từng case của mục 9

Lệnh native (chạy trên Keycloak scratch local, env `PROVISIONING_TEST_*` + `NATIVE_GATE_*`
do driver tương đương `verify-native.sh` cấp):

```
./backend/mvnw -B -ntp -f backend/pom.xml \
  -Dtest=AuthorizationProvisioningClientNativeIT,NativePermissionGateIT,NativeTimePolicyIT,PolicyGraphWriterNativeIT,NativeGateAcceptanceIT test
→ Tests run: 12, Failures: 0, Errors: 3, Skipped: 0
```

| Case | Lớp kiểm chứng | Kết quả |
|---|---|---|
| §9.1 Không group claim nhưng policy nhận đúng membership; claim/role giả không mở quyền | `NativePermissionGateIT` | ĐẠT (3/3 test) |
| §9.2 Cùng session/token, gỡ membership: lần gọi sau bị từ chối, actor B không đổi | `NativeGateAcceptanceIT.tokenDeniedAfterMembershipRemoval` (tầng writer + PDP) và các suite sẵn có `ProjectionFenceGateIT`, `ConcurrentRevocationIT` ở profile integration | ĐẠT |
| §9.3 Hai cặp permission, chỉ một được cấp: batch không đủ, từng cặp phải đúng | `NativePermissionGateIT` | ĐẠT (trong 3/3 test) |
| §9.4 Ba môn/nhiều giáo viên/học sinh chọn môn/homeroom giao + track/phụ huynh đúng con + published | `NativeGateAcceptanceIT.rosterSemanticsAcrossRoles` | **KHÔNG ĐẠT** — lỗi ở Finding 1 khi apply `activity.assign` (scope thứ hai của course-subject) |
| §9.5 Time policy `[start, end)` UTC trước/đúng/sau mốc, không phụ thuộc cron | `NativeTimePolicyIT` | ĐẠT (1/1 test) |
| §9.6 Expired completion: hoàn tất attempt cũ trong deadline activity; extend/new attempt/reassign bị từ chối; revoke enrollment chặn cả completion | `NativeGateAcceptanceIT.expiredEnrollmentCompletesOldAttemptOnly` | **KHÔNG ĐẠT** — lỗi ở Finding 1 khi apply `attempt.open` (scope thứ hai của activity) |
| §9.7 Grant dài hạn sống sau grantor rời; grant revocable bị thu hồi chặn bản nhận và bản fork; nguồn độc lập còn tồn tại | `NativeGateAcceptanceIT.longTermGrantSurvivesGrantorExit` | **KHÔNG ĐẠT** — lỗi ở Finding 1 khi apply `use` (scope thứ hai của resource version) |
| §9.8 Lỗi/crash giữa directory/policy/commit; retry không nhân bản; concurrent revoke/write không commit trên revision cũ | `ConcurrentRevocationIT`, `ProjectionFenceGateIT` (profile integration, realm TEST) | ĐẠT (nằm trong 120 IT của cổng integration dưới đây) |
| §9.9 Self-register chưa verified không có nghiệp vụ; center-issued không email vẫn login được, không bị ép bổ sung email | `NativeGateAcceptanceIT.unverifiedUserHasNoBusinessAccess`, `NativeGateAcceptanceIT.userWithoutEmailLogsInCleanly` | ĐẠT (chi tiết hành vi thật ở dưới) |

Hành vi thật ghi nhận ở §9.9 (đúng hợp đồng dòng "User self-register chưa verified không có
nghiệp vụ; center-issued không email vẫn login được mà không bị ép bổ sung email"):

- User có email chưa verified: vòng đăng nhập Code + PKCE bị chặn ở required action
  `VERIFY_EMAIL` (realm giữ `verifyEmail`), không lấy được code/token. Sau khi email được
  đánh dấu verified ở tầng directory, đăng nhập được nhưng `KeycloakDecisionClient` vẫn từ
  chối vì common gate eligibility chưa được chiếu; chỉ sau khi membership
  `/gk/identity/eligible` được thêm thì cùng token mới được phép.
- User center-issued đúng hình dạng hợp đồng (có họ tên, không email, mật khẩu tạm +
  required action `UPDATE_PASSWORD`): đăng nhập đi qua đúng một required action đổi mật
  khẩu của lần cấp, không xuất hiện yêu cầu bổ sung email hay verify profile nào; quyết
  định nghiệp vụ sau đó được phép.
- Ghi nhận phụ: account không email **và** không họ tên bị Keycloak chuyển tới
  `VERIFY_PROFILE` khi đăng nhập (thiếu thuộc tính profile bắt buộc là họ tên, không phải
  email). Account center-issued thật luôn có họ tên từ hồ sơ học sinh nên không chạm nhánh
  này; ghi lại để các issue cấp account sau biết.

## Case §9.1 ở tầng UI: đăng nhập Code qua giao diện thật (e2e)

Spec `frontend/tests/e2e/native-gate.spec.ts` chạy thật với backend (jar build từ chính
worktree này) chạy trên stack TEST native và `NATIVE_GATE_APP_ORIGIN=http://127.0.0.1:8080`:

```
npm --prefix frontend run test:e2e -- tests/e2e/native-gate.spec.ts
→ 2 passed (chưa đăng nhập bị 302 tới /oauth2/authorization/keycloak;
  đăng nhập thật qua UI Keycloak, identity chưa có thư mục thì 503 AUTHORIZATION_NOT_READY,
  không lộ marker secret)
```

Không đặt `NATIVE_GATE_APP_ORIGIN` (môi trường gate chưa khởi động, như CI hiện tại): cả 2
test skip sạch — cơ chế skip giữ nguyên để CI không đỏ giả.

## Các cổng bắt buộc (theo đúng thứ tự)

```
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
→ Tests run: 153, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify   (đã nạp env TEST_* của stack TEST native)
→ unit 153/153; failsafe Tests run: 120, Failures: 0, Errors: 0, Skipped: 7 — BUILD SUCCESS
  (7 skip là các lớp native-only bị excludes theo thiết kế của pom ở profile này;
  chúng được chạy thật ở lượt native riêng ở trên)
```

## Ghi chú phương pháp của lớp nghiệm thu mới

- `NativeGateAcceptanceIT` chỉ kích hoạt khi `PROVISIONING_TEST_KEYCLOAK_BASE_URL` được đặt
  (đúng cơ chế của `PolicyGraphWriterNativeIT`); ngoài môi trường native nó tự vô hiệu, nên
  `clean verify` và profile integration không đổi hành vi.
- Actor phụ được tạo bằng provisioner thật (có `manage-users` ở realm scratch), mật khẩu
  chỉ dùng cho môi trường thử; token lấy bằng form login Authorization Code + PKCE S256 qua
  client public `native-gate-test`. Keycloak 26 ở realm này tách form thành hai bước
  (username trước, password sau) và phát hành cookie Secure trên HTTP loopback nên test gom
  cookie thủ công theo tên như cookie jar của trình duyệt — cùng hai pitfall mà
  `fixtures/native-pkce-login.mjs` đã xử lý cho actor fixture.
- `PolicyGraphWriter` được dựng bằng constructor production; coordinator khóa phân tán qua
  PostgreSQL của ứng dụng không tồn tại trong realm dùng một lần, và gate chạy một tiến
  trình tuần tự, nên test dùng coordinator truyền thẳng — semantics của writer và PDP
  không đổi.
- Các test đa scope được viết fail-first theo đúng thứ tự provision của production (mỗi
  graph một lần `apply`); chúng fail vì thiếu hành vi của writer trên Keycloak thật, không
  phải vì môi trường: các khẳng định default-deny và các case đơn scope chạy cùng lượt đều
  cho kết quả đúng.

## Giới hạn còn lại

- Ba case §9.4/§9.6/§9.7 mới chỉ fail ở bước provision; phần khẳng định quyết định của
  chúng (ma trận allow/deny) chưa được thực thi và phải chạy lại sau khi Finding 1 được
  sửa. Không kết luận gì về semantics quyết định của ba case này từ lượt chạy này.
  *(Đã hết hiệu lực: cả ba case đã chạy lại trọn vẹn và đạt sau bản sửa — xem mục "Chạy
  lại gate sau khi sửa Finding 1".)*
- `verify-native.sh` chưa được chạy nguyên văn ở local vì không có Docker; bản tương
  đương native đã bao phủ mọi bước chức năng của script trừ các assert cấu hình realm
  bằng curl của chính script (các assert đó vẫn chạy ở CI).
- Mailpit không chạy native nên luồng bấm link verify email thật qua hộp thư không được
  kiểm chứng ở local; trạng thái verified được đặt ở tầng directory (đúng tầng mà
  eligibility đọc) — đã ghi rõ trong test §9.9.
