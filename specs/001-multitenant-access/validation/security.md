# Kiểm tra biên bảo mật xuyên suốt (T095)

> **Trạng thái: ĐẠT 6/7 hạng mục; 1 hạng mục ĐẠT MỘT PHẦN (token lời mời trên URL — finding đã có trong hàng đợi đợt review gộp 2).**
> Cây kiểm chứng: main `59137da4223268efbe17bb07ebbddd7084eac8a0` (sau khi PR #289 land).
> Mọi khẳng định đều có nguồn kiểm chứng trực tiếp ghi kèm: file:line đọc trên cây này,
> truy vấn chỉ-đọc trên PostgreSQL của stack TEST làn 1 (cổng 15432, database `giaokhoa_test`)
> và kết quả CI 8/8 của PR #289 tại head `07f3b353` — cây của head đó chính là cây của main
> `59137da4` sau squash-merge nên kết quả CI áp trực tiếp cho cây này.
> Lượt IT không chạy lại tại local trong phiên kiểm chứng này: cửa sổ một-lượt-test-nặng
> trên máy dùng chung do đội gate #88 vòng 3 giữ liên tục; các ca IT được dẫn chiếu là
> ca thuộc suite đã xanh trong CI nêu trên.

Issue #97: “[T095][Polish] Kiểm tra RLS/runtime role/FK, protected DTO/IDOR, role
prerequisites, CSRF ngoại lệ hẹp back-channel, cookie/sid correlation qua restart, no
cache/token/password leaks và production không fixture/password grant/wildcard allow”.

Bối cảnh kiến trúc đối chiếu là trạng thái HIỆN TẠI sau Wave 6: RLS đã tháo toàn bộ có
chủ đích, cách ly tenant bằng schema riêng per workspace + role runtime bị giới hạn;
suite TEST chạy bằng datasource `giaokhoa_runtime`. Không áp tiêu chí của kiến trúc
RLS cũ cho các hạng mục dưới đây.

## 1. RLS / runtime role / FK — ĐẠT

- **Không còn RLS**: `grep -rniE "row level security|create policy|force row|enable row"`
  trên `backend/src/main/resources/db/` cho 0 kết quả. Truy vấn động trên DB test:
  `SELECT count(*) FROM pg_policies` → **0**.
- **Runtime role bị giới hạn**: `db/migration/V001__system_baseline.sql:2534` —
  `CREATE ROLE giaokhoa_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS`; grants theo schema ở
  dòng 2543–2557 (DML trên mọi bảng, `flyway_schema_history` bị revoke quyền ghi).
  Truy vấn động: `giaokhoa_runtime(super=false, bypassrls=false)`.
  `db/migration/afterMigrate__assert_runtime_role_grants.sql` (và bản tenant tương ứng)
  tự kiểm chứng sau mỗi lần migrate: runtime phải đủ DML trừ `flyway_schema_history`
  chỉ được đọc, thiếu là migrate fail ngay.
- **Datasource tách vai**: `backend/src/main/resources/application.yml` — datasource
  runtime dùng `SPRING_DATASOURCE_USERNAME` (role `giaokhoa_runtime`), Flyway giữ user
  riêng `SPRING_FLYWAY_USER`; `ddl-auto: none`.
- **FK đầy đủ**: đếm tĩnh `REFERENCES|FOREIGN KEY` — baseline hệ thống 93, baseline
  tenant 20. Đếm động trên DB test (schema `public`): `information_schema.table_constraints`
  loại `FOREIGN KEY` → **93**, khớp đếm tĩnh của baseline hệ thống. (Lúc truy vấn, DB test
  không còn schema tenant nào đang tồn tại nên phía tenant chỉ có bằng chứng tĩnh.)

## 2. Protected DTO / IDOR — ĐẠT

- Mọi protected action đi qua `authorization/decision/ProtectedActionExecutor.java`:
  actor resolve từ phiên (không từ body), target key phải khớp template cố định —
  `Target` record từ chối target key không thuộc `NativePolicyTemplates` và scope không
  thuộc tập scope cố định của template; quyết định hỏi Keycloak PDP bằng token của chính
  user; không xác minh được thì fail-closed 503, deny thì 403 (javadoc lớp + mã nguồn).
- Trước quyết định, executor đồng bộ eligibility của actor:
  `ProtectedActionExecutor.java:162` gọi `eligibility.synchronizeEligibility(actorUserId)`.
- Bằng chứng IDOR động trong suite: các ca chéo workspace ở
  `guardians/GuardianLinkServiceIT.java:494,682` và các ca tương tự trong
  `teaching/TeachingStructureIT.java`, `resources/ResourcePrimitivesSchemaIT.java`,
  `verification/SystemPracticeHistoryIT.java` (xanh trong CI của cây này).

## 3. Role prerequisites — ĐẠT

- **Eligibility là tiền đề của mọi quyền nghiệp vụ**: `identity/eligibility/IdentityEligibilityService.java`
  — origin của account là dữ kiện do server kiểm soát; account tự đăng ký chỉ eligible khi
  email của profile Keycloak hiện hành (đọc mới qua Admin API, không lấy từ request/token cũ)
  đã verified; profile không đọc được hoặc subject không khớp → fail-closed 503, không ghi gì.
- **Tập nguồn nhóm là tập đóng**: `authorization/policy/NativePolicyTemplates.java:20-21,28-30,100-105`
  — chỉ công nhận `/gk/identity/eligible`, `/gk/platform/operators` và
  `/gk/workspaces/{workspaceId}/…`; đường nhóm ngoài mẫu bị từ chối.
- Quyền nền tảng chỉ phát theo group operators (descriptor PLATFORM_OPERATOR của
  `PlatformPolicyProjector`); tạo trung tâm đòi owner phải có `user_account` trước
  (`CenterService.requireAccount`) — không có đường tự phong vai trò.

## 4. CSRF — ngoại lệ hẹp duy nhất là back-channel — ĐẠT

- `identity/security/SecurityConfiguration.java`:
  `.csrf(csrf -> csrf.ignoringRequestMatchers(inboundBackChannel))` với
  `inboundBackChannel` = đúng một matcher `POST /logout/connect/back-channel/keycloak`.
  Mọi request còn lại đều qua CSRF mặc định; `httpBasic`/`formLogin` tắt.
- Back-channel vẫn an toàn: `identity/security/OidcLogoutConfiguration.java` đặt filter
  gác chỉ preflight hình dạng JWT (thiếu/sai hình dạng → 400), chữ ký và claim do
  `OidcBackChannelLogoutHandler` của Spring xác minh.
- Bằng chứng động: `frontend/tests/e2e/session-security.spec.ts:120` — mutation thiếu
  CSRF token bị 403; back-channel là ngoại lệ duy nhất và logout token sai hình dạng vẫn 400.

## 5. Cookie / sid correlation qua restart — ĐẠT

- Phiên bền: Spring Session JDBC (`application.yml`: `spring.session.jdbc.initialize-schema: never`,
  timeout chốt 30m); authorized client lưu trong HttpSession bền
  (`identity/security/OidcSessionConfiguration.java`).
- Tương quan sid ↔ session bền ở DB: `JpaOidcSessionRegistry` qua
  `OidcSessionCorrelationRepository` — registry dựng lại (mô phỏng restart) vẫn thấy
  tương quan cũ. Migration `db/migration/V002__oidc_session_correlation_conflict.sql`
  sửa trigger `sync_oidc_correlation_session()` cho ca đổi id phiên đụng khóa chính
  (defect callback 500 trước đây).
- Chống fixation chốt tường minh: `SecurityConfiguration` —
  `sessionFixation().changeSessionId()`.
- Cookie: `OidcLogoutConfiguration.java:30-48` — cookie `SESSION` HttpOnly, SameSite=Lax,
  Secure suy từ scheme của app-origin và **fail-fast** nếu profile prod/production chạy
  với origin không phải https.
- Bằng chứng động: `identity/security/OidcSessionPersistenceIT.java` — 7 ca:
  `sessionSurvivesNewRegistry`, `subjectLogoutKillsOwnSessionsOnly`,
  `sessionDeleteAndRotateSync`, `sessionRotateKeepsNewCorrelationOnConflict`,
  `expiredSessionSkipsBackChannel`, `tokensSurviveSessionReload`,
  `cleanupOnlyDeletesOldOrphans` (xanh trong CI của cây này).

## 6. No cache / token / password leaks — ĐẠT MỘT PHẦN

Đạt:

- **Cache**: đáp án bảo vệ mang `Cache-Control: private, no-store` —
  `authorization/operation/OperationController.java:68,88`,
  `authorization/decision/CapabilitiesController.java:100`,
  `guardians/web/GuardianLinkController.java:242`, `guardians/web/StudentResultController.java:59`,
  `identity/web/RecoveryController.java:123,197,233`, `identity/web/SessionController.java`
  (hợp đồng ở javadoc :44-46). Test khóa: `OperationStatusIT.java:76`,
  `IdempotencyServiceTest.java:118`, `OperationControllerTest.java:64`,
  `ProblemDetailsAdviceTest.java:124`; phía frontend `session-security.spec.ts:98`
  (cả ẩn danh lẫn đã đăng nhập) và `frontend/src/lib/api-client.ts:119` (`cache: "no-store"`).
- **Token**: `grep -rn "Bearer|access_token|id_token" frontend/src` → 0 kết quả (BFF giữ
  token ở server); `session-security.spec.ts:46` khóa việc trình duyệt không gửi
  Authorization và storage không giữ token. Session view không trả bearer/secret
  (`SessionController.java:44-46`). Log backend: `grep -rnE "log\.(info|debug|warn|error).*(token|password|secret)"`
  trên `backend/src/main/java` → 0 kết quả.
- **Mật khẩu tạm**: sinh ở server (`identity/service/InitialCredentialService.java:71`),
  chỉ trả một lần trong đáp án tạo tài khoản cho người vận hành, không ghi log.
- **Token lời mời**: DB chỉ lưu SHA-256 hex (`tenancy/service/InvitationService.java:40-43,390-391`),
  tra cứu theo hash (`:215-216`); đáp án tạo lời mời mang token trong thân đáp án kèm
  `no-store`, không nằm trong URL của đáp án.

Chưa đạt trọn vẹn (finding đã biết, đã xếp hàng):

- Token lời mời thô vẫn đi trong **đường dẫn URL** của `POST /api/invitations/{token}/accept`
  (`tenancy/web/MembershipController.java:218`) → có thể lọt access log của gateway.
  Đây là finding “token lời mời trên URL” đã ghi nhận ở đợt rà soát trước và đang nằm
  trong hàng đợi của PR gộp finding đợt 2 (chuyển token sang body, đổi contract kèm
  frontend) — chưa land tại cây này. Ngoài điểm này, không phát hiện rò rỉ token/mật khẩu
  nào khác trong phạm vi kiểm tra.

## 7. Production: không fixture / password grant / wildcard allow — ĐẠT

- **Không password grant**: `infra/keycloak/baseline/realm.json` — cả 3 client
  (`giaokhoa-web`, `giaokhoa-api`, `giaokhoa-provisioner`) đều
  `directAccessGrantsEnabled: false`; `giaokhoa-web` confidential, chỉ standard flow.
  Cấu hình OAuth2 của app (`application.yml`) chỉ khai `authorization-grant-type: authorization_code`.
  `grant_type` duy nhất trong backend là UMA-ticket của PDP
  (`authorization/decision/KeycloakDecisionClient.java:40,159`) bằng token của chính user.
- **Không wildcard allow**: `giaokhoa-api` có `policyEnforcementMode: ENFORCING`,
  `decisionStrategy: UNANIMOUS`. `infra/keycloak/bootstrap.sh:62,158-176` — realm bị vô
  hiệu hóa cho tới khi gỡ xong wildcard permission mặc định của Keycloak, ép enforcing và
  **tự kiểm chứng sau bootstrap**: mọi resource phải không có uri `/*`, sai là bootstrap
  không được tuyên bố thành công.
- **Không fixture ở production**: `verification/web/VerificationFixtureController.java`
  gắn `@Profile({"test", "review"})` — route không đăng ký khi hai profile này không bật;
  fixture tự nó cũng là protected action qua PDP (scope `fixture.manage`, actor từ phiên).
  `grep SPRING_PROFILES_ACTIVE` trên toàn bộ artifact triển khai trong repo
  (`infra/`, Dockerfile, compose) → 0 kết quả: production không bật profile test/review.
  Realm production chỉ có 3 client nêu trên — không có client fixtures (client fixtures
  chỉ tồn tại ở realm test `giaokhoa-it`).

## Ghi chú về file đích của task

Hai file đích `backend/src/test/java/com/giaokhoa/acceptance/SecurityBoundaryIT.java` và
`frontend/tests/e2e/security-boundaries.spec.ts` **chưa tồn tại** trên main. Phạm vi bảo
mật của chúng hiện được phủ bởi các suite đã có: `OidcSessionPersistenceIT`,
`SecurityConfigurationIT` (tầng e2e-backend), `frontend/tests/e2e/session-security.spec.ts`
và các IT chéo workspace dẫn ở §2 — tất cả xanh trong CI của cây này. Bản kiểm chứng này
không tự tạo hai file đó; việc có dựng thêm artifact trùng tên hay không để điều phối chốt.
Checkbox T095 trong `tasks.md` chưa tick trong đợt này vì §6 còn một finding chưa land.
