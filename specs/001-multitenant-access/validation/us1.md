# US1 — Minh chứng nghiệm thu 9 scenarios (T033)

## Phạm vi và phương pháp

- Nền: `origin/main` `0c2af50` (sau merge PR #149, chồng US1 đã vào main).
- File đích duy nhất của task này: `specs/001-multitenant-access/validation/us1.md` (tạo mới —
  file chưa tồn tại trên main). Các sửa code kèm theo chỉ là những điểm Note treo của review
  #32 đã xác minh là đúng và thuộc phạm vi gate (xem mục “Các Note treo của review #32”);
  không sửa hành vi nghiệp vụ US1 ngoài các điểm đó.
- Stack TEST native local: PostgreSQL 18.6 ở `127.0.0.1:15432`, Keycloak 26.8.1 ở
  `127.0.0.1:18081` (realm TEST `giaokhoa-it`, service account fixtures đủ 5 role),
  Keycloak scratch ở `127.0.0.1:18091`. Stack chết một lần giữa chừng vì máy bị thay mới
  (`/var/tmp` trống) và được dựng lại theo `~/workspace/tools/test-stack/RUNBOOK.md`
  (`rebuild-stack.sh` chạy detached) trước khi chạy cổng.
- Thứ tự cổng: `clean verify` trước, reset schema public về trống, rồi `-Pintegration verify`
  sau (nạp biến `TEST_*` từ phần export của `/var/tmp/test-stack/run-it.sh` trước khi chạy).
  Không chạy `clean verify` sau lượt integration để không ghi đè `failsafe-reports`.
- T1–T2 là hai kịch bản quickstart của US1 (tasks.md:276 xếp vào cột "Kịch bản
  quickstart"; quickstart.md mục 6): T1 = tự đăng ký chưa xác minh email, thử vào nghiệp
  vụ, xác minh email rồi thử lại; T2 = cấp username student không email, đăng nhập/đổi
  mật khẩu tạm, thêm cùng danh tính vào trung tâm B. Bằng chứng là các test đã trích
  dưới US1.1/US1.4/US1.5 (`IdentityFlowIT`, `BootstrapProfileIT`, `RecoveryWorkflowIT`),
  không phải "build xanh". (T001/T002 là task khởi tạo Spring Boot/Next.js ở
  tasks.md:25-26, không phải kịch bản nghiệm thu.)
- Ràng buộc của issue: không đánh dấu story hoàn tất nếu issued student phải email hoặc
  baseline mở author/student role. Cả hai điều kiện này đều KHÔNG xảy ra — xem US1.2 và
  US1.4. Việc đóng story vẫn do bước review/merge quyết; tài liệu này chỉ ghi bằng chứng.

## Kết quả tổng hợp

| Cổng | Lệnh | Kết quả |
| --- | --- | --- |
| Unit + kiểm tra tĩnh backend | `./backend/mvnw -B -ntp -s /var/tmp/test-stack/maven-settings.xml -f backend/pom.xml clean verify` | **BUILD SUCCESS** — unit 162 test, 0 failures, 0 errors, 0 skipped (Spotless, PMD, SpotBugs đều xanh; xem ghi chú về 2 lần sửa PMD/SpotBugs ở Phụ lục) |
| Integration toàn suite | `./backend/mvnw -B -ntp -s /var/tmp/test-stack/maven-settings.xml -f backend/pom.xml -Pintegration verify` | **197 test, 0 failures, 1 error, 9 skipped** — error duy nhất là test SMTP (không có Mailpit ở local, xanh ở CI); `too many clients already` = 0 lần; `ConcurrentRevocationIT` 16/16 |
| Frontend lint/test/build | `npm run lint`, `npm test`, `npm run build` | **Lint xanh, unit 36 test / 7 file đều pass, build xanh** (routes: `/`, `/onboarding`, `/personal`, `/platform/recovery-requests`) |
| E2E Playwright | `npm run test:e2e` | Không chạy ở local (cần backend 8080 + Mailpit); ở CI 3 passed / 15 skipped — 3 passed là `auth-code`/`home`, còn toàn bộ spec scenario US1 (`identity`, `session-security`, `native-gate`) đều bị skip vì thiếu `NATIVE_GATE_APP_ORIGIN`, nên e2e không cho bằng chứng scenario US1 nào (xem dưới) |

Bằng chứng CI trên chính head đã merge (`51b31f24`, PR #149, job integration
`128315371509`, log `hidden_files/ci-us1d-integration.log`): backend ✓, frontend ✓,
integration ✓. Maven integration 194 test, 0 failures, 0 errors, 9 skipped;
`too many clients already` = 0 lần; e2e 3 passed / 15 skipped — 3 passed là 2 test
`auth-code.spec.ts` + 1 test `home.spec.ts`, còn 15 skipped là toàn bộ 7 test
`identity.spec.ts` + 6 test `session-security.spec.ts` + 2 test `native-gate.spec.ts`
(vì CI không đặt `NATIVE_GATE_APP_ORIGIN` nên cả ba spec tự skip); `verify-native.sh` pass;
`verify-oidc-logout.mjs` in dòng `PASS: Keycloak-signed logout, issuer/aud/events/sid/sub,
DB deletion, restart and old cookie rejection`.

## Minh chứng từng scenario

### US1.1 — Người tự đăng ký chưa xác minh email bị từ chối nghiệp vụ; vẫn được xác minh hoặc khôi phục

- `IdentityFlowIT.unverifiedSelfRegisteredUserHasNoEligibilityUntilEmailVerified`
- `IdentityFlowIT.changingEmailToAnUnverifiedOneRevokesEligibility` (đổi sang email chưa xác
  minh làm mất eligibility)
- `RecoveryFlowIT.verifiedUserGetsPasswordEmail` (khôi phục qua email)
- E2E `identity.spec.ts`: “email chưa xác minh: đăng nhập bị chặn ở bước verify email của
  Keycloak và không lập phiên ứng dụng”; “bấm link xác minh trong email (Mailpit) thì đăng
  nhập được và session báo email đã xác minh” — **chưa có bằng chứng e2e**: cả 7 test của
  spec này đều bị skip ở CI vì thiếu `NATIVE_GATE_APP_ORIGIN` (3 passed e2e ở CI là
  `auth-code`/`home`, không phải scenario US1). Hành vi đã khóa ở tầng integration bằng 3
  test trên.

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped (test invitations đã disable), 0 đỏ; `RecoveryFlowIT` 10 run, đỏ duy nhất test SMTP (xem US1.7).

### US1.2 — Email đã xác minh, đăng nhập lần đầu: tài khoản trung tính, được làm đề hệ thống, chưa có kho soạn học liệu, không bị gán vai trò học sinh/giáo viên toàn hệ thống

- `IdentityFlowIT.verifiedUserBaselineIsNeutral`
- `IdentityFlowIT.neutralUserWorkspacesEndpointListsOnlyPersonalWorkspace`
- `BootstrapProfileIT.bootstrapCreatesAccountAndPersonalWorkspaceForNewSelfRegisteredIdentity`
- `ResourcePrimitivesSchemaIT` (7 test: resource/version canonical cho quyền nền)
- `SystemPracticeHistoryIT` (7 test: practice mẫu chỉ cần resource hệ thống đã phát hành,
  không cần enrollment hoặc gói author; RLS cô lập theo personal workspace)

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ; `BootstrapProfileIT` 12/12; `ResourcePrimitivesSchemaIT` 7/7; `SystemPracticeHistoryIT` 7/7.

### US1.3 — Quản lý tại A và học sinh tại B: chuyển không gian chỉ áp quyền hợp lệ tại B

- `IdentityFlowIT.oppositeRolesInTwoCentersStaySeparated`
- `BootstrapProfileIT.directoryListsOnlyValidWorkspacesAndRolesStayPerWorkspace`
- E2E `identity.spec.ts`: “workspace switch: danh sách theo hợp đồng, workspace lạ là 404
  và cookie active-workspace giả không đổi được danh sách” — **chưa có bằng chứng e2e**:
  test này nằm trong 7 test của `identity.spec.ts` đều bị skip ở CI vì thiếu
  `NATIVE_GATE_APP_ORIGIN`. Hành vi đã khóa ở tầng integration bằng 2 test trên.

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ (gồm `oppositeRolesInTwoCentersStaySeparated`); `BootstrapProfileIT` 12/12.

### US1.4 — Học sinh chưa có tài khoản và không có email: được cấp tài khoản, đăng nhập bằng tên đăng nhập, nhận vai trò học sinh đúng phạm vi

- `IdentityFlowIT.centerIssuedUserWithoutEmailIsEligibleWithoutVerification`
- `BootstrapProfileIT.registrarIssuesStudentWithoutEmailAndReissueIsIdempotent`
- `BootstrapProfileIT.registrarActivatesPendingMembershipOnReissue`
- `RecoveryWorkflowIT.issueHandsOffCredentialOnce`
- `RecoveryWorkflowIT.issueFailureDeletesOrphanUser`
- `RecoveryWorkflowIT.issueWithSquattedNameCompensates`
- `RecoveryWorkflowIT.issueWithExistingIdentityConflicts`

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ; `BootstrapProfileIT` 12/12; `RecoveryWorkflowIT` 17/17 (gồm test cấp credential một lần và 3 test reconcile/compensation/xung đột). Điều kiện “issued student phải email” của issue KHÔNG xảy ra:
học sinh không email vẫn eligible và đăng nhập được (test đầu tiên trong danh sách trên).

### US1.5 — Học sinh đã có tài khoản được mời vào trung tâm khác: dùng lại danh tính hiện có

- `IdentityFlowIT.existingIdentityJoinsSecondCenterWithoutChangingOrigin`
- `BootstrapProfileIT.registrarKeepsOriginOfExistingIdentityAndRejectsUsernameMismatch`
- `BootstrapProfileIT.bootstrapKeepsOriginOfExistingCenterIssuedAccount`
- `IdentityFlowIT.invitationEndpointRegistersExistingIdentityWithoutOriginChange` —
  **test này đang `@Disabled("Endpoint invitations thuộc US2, chưa triển khai")`**: endpoint
  invitations thuộc US2 (T034/T036/T038/T040), chưa có trên main. Hành vi dùng lại danh
  tính/không đổi origin đã được khóa ở tầng service và bootstrap (3 test trên); phần HTTP
  của endpoint invitations chờ US2, không tự tick pass.

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ; `BootstrapProfileIT` 12/12. Test endpoint invitations là 1 skipped duy nhất (đang `@Disabled`, xem trên).

### US1.6 — Tài khoản không email cần khôi phục: chỉ platform operator xử lý sau xác minh; quản lý trung tâm không tự đổi mật khẩu của danh tính dùng chung

- `RecoveryFlowIT.operatorResetKeepsIdentity`
- `RecoveryFlowIT.ordinaryUserCannotResetOthers`
- `RecoveryFlowIT.resetUserLogsInWithNewPasswordOnly`
- `RecoveryFlowIT.recoveryEndpointsNeedOperatorScope` (không có scope operator
  thì list/resolve bị 403)
- `RecoveryWorkflowIT`: `submitForSelfCreatesRequestedRequest`,
  `submitForMissingTargetReturnsNotFound`, `submitForOtherWithoutWorkspace404`,
  `centerManagerSubmitsForStudentMember`, `studentCannotSubmitForAnother`,
  `foreignManagerCannotSubmitHere`,
  `verifyTransitionsAndRejectsRepeat`,
  `resolveVerifiedApproveResolvesAndLeavesIdentityUntouched`,
  `resolveVerifiedRejectMarksRejected`, `resolveRequestedVerifiesInlineBeforeResolving`,
  `resolveTerminalRequestConflicts`, `listRequestsOldestFirst`,
  `submitKeepsReasonForOperator` (mới trong gate này)
- `RecoveryFlowIT.submitWithoutReasonIsRejected` (mới trong gate này: thiếu lý do → 400)
- `RecoveryFlowIT.rejectedRequestPollShowsRejectionCode` (mới trong gate này: poll sau khi
  bị từ chối trả `FAILED` + `errorCode` `RECOVERY_REJECTED`)
- Happy-path HTTP của platform operator (list/resolve trả 200): **chưa có test** — xem Note
  mở (a) dưới đây. Mức đã chứng minh: resolve/list ở tầng service + chặn 403 ở tầng HTTP.

Kết quả local: `RecoveryFlowIT` 10 run, 0 failure, 1 error duy nhất là test SMTP (xem US1.7); `RecoveryWorkflowIT` 17/17; `RecoveryRequestIT` 4/4. Cả 3 test mới của gate đều pass: `submitKeepsReasonForOperator`, `submitWithoutReasonIsRejected`, `rejectedRequestPollShowsRejectionCode`.

### US1.7 — Tài khoản có email đã xác minh quên mật khẩu: khôi phục qua email đó

- `RecoveryFlowIT.verifiedUserGetsPasswordEmail` — test này gửi mail thật
  qua Mailpit: **xanh ở CI, đỏ ở local** (local không có Mailpit; Keycloak trả 500 “Failed
  to send execute actions email”). Đây là giới hạn môi trường local đã biết, không phải lỗi
  code; bằng chứng xanh lấy từ CI head `51b31f24` (suite 194 test 0 error).
- E2E `identity.spec.ts`: “mật khẩu tạm: lần đăng nhập đầu bị ép đổi mật khẩu, mật khẩu cũ
  không dùng lại được” — **chưa có bằng chứng e2e**: test này nằm trong 7 test của
  `identity.spec.ts` đều bị skip ở CI vì thiếu `NATIVE_GATE_APP_ORIGIN`.

Kết quả local: `RecoveryFlowIT.verifiedUserGetsPasswordEmail` đỏ đúng như dự kiến — 500 `Failed to send execute actions email` vì local không có Mailpit (xem chi tiết ở mục “Hạn chế”). Toàn suite còn lại 0 failure.

### US1.8 — Rời A nhưng còn tham gia B: danh tính, quyền hợp lệ tại B và gói cá nhân vẫn giữ

- `IdentityFlowIT.leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter`
- `BootstrapProfileIT.leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter`

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ; `BootstrapProfileIT` 12/12 (cả hai class đều có test rời center giữ danh tính).

### US1.9 — Chưa đăng nhập hoặc thông tin không hợp lệ/đã hết hạn: bị từ chối, được hướng dẫn đăng nhập; không dùng vai trò tự khai

- `IdentityFlowIT.anonymousCallerIsRedirectedToLogin`
- `IdentityFlowIT.forgedSessionCookieDoesNotAuthenticate`
- `IdentityFlowIT.sessionEndpointDescribesAnonymousState`
- `RecoveryFlowIT.anonymousRequestRedirectsToLogin`
- `RecoveryFlowIT.forgedCookieStaysUnauthenticated`
- E2E `session-security.spec.ts`: không token trong DOM/network/SSR; no-store; CSRF (mutation
  thiếu token bị 403; token sai hình dạng vẫn bị 400); chưa eligible thì không tạo được action
  mới (503 fail-closed); khởi động lại trình duyệt giữ phiên từ storage state; storage state
  cũ sau logout không hồi sinh được phiên — **chưa có bằng chứng e2e**: cả 6 test của spec
  này đều bị skip ở CI vì thiếu `NATIVE_GATE_APP_ORIGIN`. Hành vi từ chối ẩn danh/phiên
  giả đã khóa ở tầng integration bằng 5 test trên; các khẳng định CSRF/no-store/token ở
  biên trình duyệt chưa có bằng chứng chạy thật.

Kết quả local: `IdentityFlowIT` 12 run / 1 skipped, 0 đỏ (gồm 3 test ẩn danh/phiên giả); `RecoveryFlowIT` 10 run, 0 failure (gồm 2 test ẩn danh); `SecurityConfigurationIT` 4/4.

## Minh chứng restart giữ session + authorized client + OIDC correlation

- `OidcSessionPersistenceIT` (6 test): `sessionSurvivesNewRegistry`,
  `subjectLogoutKillsOwnSessionsOnly`,
  `sessionDeleteAndRotateSync`,
  `expiredSessionSkipsBackChannel`,
  `tokensSurviveSessionReload`,
  `cleanupOnlyDeletesOldOrphans`. Kết quả local: 6/6 trong lượt integration (197 test).
- `infra/keycloak/verify-oidc-logout.mjs` trên CI head `51b31f24` (job integration
  `128315371509`): `PASS: Keycloak-signed logout, issuer/aud/events/sid/sub, DB deletion,
  restart and old cookie rejection` — script đăng nhập thật bằng Code+PKCE, restart backend,
  khẳng định JDBC session còn 1 dòng và OIDC correlation giữ nguyên, cookie SESSION cũ vẫn
  xác thực được (không 302), rồi back-channel logout thật của Keycloak xóa session khỏi DB.
  Script này cần Docker nên **không chạy được ở local** — đây là Note, chốt cuối là CI.
- `NativeGateAcceptanceIT` (9 nhóm case, §9.1–§9.9) chạy qua `verify-native.sh` ở CI và
  pass trên head `51b31f24` (job integration success); trong lượt Maven `-Pintegration` class
  này bị skip theo thiết kế (failsafe exclude 3 lớp native). `verify-native.sh` cần Docker
  nên không chạy nguyên văn ở local — Note tương tự.

## Các Note treo của review #32 — xử lý trong gate này

### (a) Happy-path platform operator list/resolve trả 200 — CHƯA XỬ LÝ, ghi Note mở

- Đã xác minh: không có test nào gọi `GET /api/platform/recovery-requests` hoặc
  `POST /api/platform/recovery-requests/{id}/resolve` với operator có quyền. Mức đã có:
  resolve/list ở tầng service (`RecoveryWorkflowIT`, 17 test) và chặn 403 ở tầng HTTP
  (`RecoveryFlowIT.recoveryEndpointsNeedOperatorScope`).
- Vì sao chưa làm trong gate này: để PDP cho phép cần fixture platform grant trong realm IT
  — tạo client resource-server `giaokhoa-it-api`, resource `platform`, scope-permission
  `account.recover` + group policy cho `/gk/platform/operators` rồi chờ Keycloak lan truyền
  policy (vùng đã biết flake về thời gian, xem `ConcurrentRevocationIT`). Đó là viết test
  mới với fixture PDP, không phải chạy test đã viết; làm vội trong gate dễ thêm một test
  flake vào suite. Ghi lại để task sau làm với provisioning qua `PolicyGraphWriter` như
  `NativeGateAcceptanceIT`.
- Không tự tick pass cho happy-path HTTP này.

### (b) Trường `reason` trong `http-api.md:58` không có trong SubmitRequest/model — ĐÃ SỬA, chọn sửa model

- Đã xác minh là đúng thật và là lỗi thật, không phải lệch doc đơn thuần:
  - `specs/001-multitenant-access/contracts/http-api.md:58` ghi request
    `{targetUserId,workspaceId?,reason}`.
  - Cả hai UI đều thu thập và gửi `reason`: `recovery-request-form.tsx` (bắt buộc nhập,
    gửi `{targetUserId, reason}`) và `onboarding-content.tsx` (cùng hình dạng); test unit
    `recovery-personal.test.ts` cũng khóa hình dạng body này.
  - Nhưng backend `RecoveryController.SubmitRequest` chỉ có `(targetUserId, workspaceId)` và
    `recovery_request` (V007) không có cột `reason` — lý do người dùng nhập bị Jackson lặng
    lẽ bỏ, operator không bao giờ thấy được lý do khi xử lý.
- Quyết định: sửa model (không sửa doc), vì doc và UI đã thống nhất với nhau, chỉ backend
  lệch. Thay đổi trong cùng thay đổi này:
  - Migration mới `V010__recovery_request_reason.sql`: thêm cột `reason VARCHAR(1000)`
    (cho phép NULL để các yêu cầu cũ vẫn hợp lệ; CHECK không trống khi có giá trị).
  - `RecoveryRequest` (entity): thêm trường `reason` + getter; constructor thêm tham số.
  - `RecoveryService.submit`: thêm overload 4 tham số giữ `reason` (chuẩn hoá khoảng trắng,
    dài quá 1000 ký tự thì 400 `INVALID_REASON`); `RequestView` mang `reason`; `INSERT`,
    `listRequests` và `loadView` đều đọc/ghi cột mới.
  - `RecoveryController.submit`: bắt buộc `reason` không trống (thiếu → 400
    `VALIDATION_FAILED`); `RequestItem` trả `reason` cho operator.
  - `platform-recovery-content.tsx`: kiểu `PlatformRecoveryRequest` + parse + hiển thị
    “Lý do cần hỗ trợ” trong danh sách operator.
  - Test mới: `RecoveryWorkflowIT.submitKeepsReasonForOperator`,
    `RecoveryFlowIT.submitWithoutReasonIsRejected`,
    `RecoveryRequestIT` round-trip entity với `reason`,
    `recovery-personal.test.ts` parse danh sách giữ `reason`.
- Không đổi hành vi khác: các lời gọi service cũ (3 tham số) giữ nguyên qua overload uỷ quyền
  với `reason` null.

### (c) `requirePlatformOperator` nuốt IllegalStateException không ghi WARN log — ĐÃ SỬA

- Đã xác minh: khối `catch (IllegalStateException ex)` trong
  `RecoveryController.requirePlatformOperator` chỉ có comment, không log — realm chưa
  bootstrap và lời gọi trái phép không phân biệt được trong log vận hành.
- Sửa: thêm `Logger` (SLF4J, theo mẫu `ProjectionReconciler`) và ghi
  `LOG.warn("Không tra được resource platform khi kiểm tra quyền operator", ex)` trong
  khối catch. Không đổi hành vi từ chối (vẫn 403 như không có resource).

### (d) REJECTED → FAILED với errorCode null làm mất lý do từ chối khi poll — ĐÃ SỬA

- Đã xác minh: `OperationController.recoveryStatus` ánh xạ `REJECTED → FAILED` nhưng truyền
  `errorCode` là `null` cứng — client poll chỉ thấy FAILED chung chung. Lý do từ chối
  (ghi chú xác minh của operator) đã lưu trong `verification_note` nhưng chỉ operator thấy
  ở danh sách platform, không trả qua poll của người gửi.
- Sửa: poll trả `errorCode` `"RECOVERY_REJECTED"` khi yêu cầu ở trạng thái REJECTED; các
  trạng thái khác giữ nguyên (RESOLVED → SUCCEEDED, còn lại → PENDING, errorCode null).
  Chỉ trả mã ổn định, không trả nội dung ghi chú tự do qua poll (ghi chú chỉ operator thấy).
- Test mới: `RecoveryFlowIT.rejectedRequestPollShowsRejectionCode` — gửi yêu cầu, operator
  REJECT, poll bằng session của người gửi → `FAILED` + `errorCode` `RECOVERY_REJECTED`.
  Frontend đã hiển thị `Mã lỗi: {errorCode}` khi có mã (cả form khôi phục và trang platform).

## Hạn chế và Note chưa kiểm chứng được

- `verify-oidc-logout.mjs` và `verify-native.sh` cần Docker: không chạy được ở local; bằng
  chứng restart/back-channel lấy từ CI head `51b31f24` (job integration success).
- `RecoveryFlowIT.verifiedUserGetsPasswordEmail` cần Mailpit: đỏ ở local,
  xanh ở CI head `51b31f24` (suite 194 test, 0 error).
- Test invitations `IdentityFlowIT.invitationEndpointRegistersExistingIdentityWithoutOriginChange`
  đang `@Disabled` — endpoint invitations thuộc US2, chờ T034/T036/T038/T040.
- E2E Playwright không chạy ở local trong gate này (cần backend ở cổng 8080 + Mailpit);
  ở CI head `51b31f24` e2e cũng không cho bằng chứng scenario US1 nào: 3 passed là
  `auth-code`/`home`, còn 15 skipped là toàn bộ spec `identity`/`session-security`/
  `native-gate` vì thiếu `NATIVE_GATE_APP_ORIGIN`. Muốn có bằng chứng e2e thật phải chạy
  gate có đặt biến này (xem kết luận, mục 2).
- Happy-path HTTP của platform operator (list/resolve 200) chưa có test — xem Note mở (a).
- `ConcurrentRevocationIT` là test nhạy với thời gian lan truyền policy trên Keycloak: nếu
  là test fail duy nhất thì chạy lại đúng một lần theo tiền lệ PR #148; hai lần liên tiếp
  thì mở điều tra riêng, không re-run tiếp.

## Kết luận

**Gate đạt cho 9/9 scenarios với bằng chứng thực tế** (số liệu lượt chạy local + CI ở
trên): mỗi scenario đều có ít nhất một test **integration** chạy thật khẳng định hành vi,
không test nào được tick pass từ tài liệu. Bằng chứng e2e cho các scenario US1 chưa có
(toàn bộ spec scenario bị skip ở CI) — xem mục Hạn chế. Hai điều kiện cấm đánh dấu story hoàn tất đều
KHÔNG xảy ra: issued student không cần email (US1.4) và baseline không mở author/student
role (US1.2).

Còn mở, đã ghi rõ và không giấu trong kết luận:

1. Happy-path HTTP của platform operator (list/resolve 200) chưa có test — Note (a). Mức đã
   chứng minh: resolve/list ở tầng service + chặn 403 ở tầng HTTP.
2. E2E Playwright chưa có bằng chứng chạy thật cho scenario US1 nào (ở CI toàn bộ spec
   scenario bị skip vì thiếu `NATIVE_GATE_APP_ORIGIN`; local không chạy được vì cần
   backend 8080/Mailpit). `verify-oidc-logout.mjs` và `verify-native.sh` chỉ có bằng
   chứng CI (head `51b31f24`, job integration `128315371509` success); local không chạy
   được vì cần Docker/Mailpit/backend 8080.
3. `RecoveryFlowIT.verifiedUserGetsPasswordEmail` đỏ ở local vì thiếu
   Mailpit; xanh ở CI.

Việc đóng story do bước review/merge quyết định trên cơ sở tài liệu này.

## Phụ lục — Số liệu lượt integration chốt ở local (lượt 2, sau khi sửa test)

Lượt 1 (197 test, 1 failure, 1 error) đỏ 1 test mới do chính nô tài viết sai khẳng định
(`submitKeepsReasonForOperator` cho rằng hàng đợi chỉ có 1 yêu cầu, thực tế
class dùng chung schema nên hàng đợi có 9 yêu cầu); đã sửa test (tìm đúng yêu cầu vừa gửi
theo ID) rồi chạy lại đủ hai cổng theo đúng thứ tự (clean verify trước, integration sau).
Lượt 2 là số liệu chốt:

| Class | run / failures / errors / skipped |
| --- | --- |
| AuthorizationControlSchemaIT | 31 / 0 / 0 / 0 |
| ConcurrentRevocationIT | 16 / 0 / 0 / 0 |
| NativeGateAcceptanceIT | 6 / 0 / 0 / 6 (skip theo thiết kế, chạy qua verify-native.sh) |
| OperationStatusIT | 5 / 0 / 0 / 0 |
| ProjectionFenceGateIT | 6 / 0 / 0 / 0 |
| ProjectionReadyMigrationIT | 1 / 0 / 0 / 0 |
| IdempotencyServiceIT | 5 / 0 / 0 / 0 |
| PolicyGraphWriterNativeIT | 2 / 0 / 0 / 2 (skip theo thiết kế) |
| ResourceMutationCoordinatorIT | 1 / 0 / 0 / 0 |
| ProjectionRecoveryIT | 4 / 0 / 0 / 0 |
| BootstrapProfileIT | 12 / 0 / 0 / 0 |
| IdentityFlowIT | 12 / 0 / 0 / 1 (skip: test invitations `@Disabled`, thuộc US2) |
| RecoveryFlowIT | 10 / 0 / 1 / 0 (error duy nhất: test SMTP, thiếu Mailpit ở local) |
| RecoveryRequestIT | 4 / 0 / 0 / 0 |
| RecoveryWorkflowIT | 17 / 0 / 0 / 0 |
| IdentityEligibilitySynchronizerIT | 3 / 0 / 0 / 0 |
| OidcSessionPersistenceIT | 6 / 0 / 0 / 0 |
| SecurityConfigurationIT | 4 / 0 / 0 / 0 |
| ResourcePrimitivesSchemaIT | 7 / 0 / 0 / 0 |
| AuditEventSchemaIT | 5 / 0 / 0 / 0 |
| IntegrationEnvironmentIT | 1 / 0 / 0 / 0 |
| DirectorySchemaIT | 25 / 0 / 0 / 0 |
| WorkspaceTransactionIT | 7 / 0 / 0 / 0 |
| SystemPracticeHistoryIT | 7 / 0 / 0 / 0 |
| **Tổng** | **197 / 0 / 1 / 9** |

Trong lượt `clean verify` chốt: unit 162 test, 0 failures, 0 errors, 0 skipped. Lượt này
từng đỏ 2 lần ở kiểm tra tĩnh trước khi chốt, đều do code mới của gate và đã sửa đúng nguyên
nhân (không suppression): PMD `AvoidLiteralsInIfCondition` vì literal `1000` trong
`normalizeReason` — sửa bằng hằng `MAX_NOTE_LENGTH` dùng chung cho cả `normalizeNote`;
SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` vì `body.reason()` được gọi hai lần —
sửa bằng biến cục bộ giữ giá trị sau lần kiểm tra null.
