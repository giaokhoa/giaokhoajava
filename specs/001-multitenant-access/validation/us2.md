# Nghiệm thu US2 — Tạo và quản lý trung tâm (gate #44, T042)

Issue #44: “[T042][US2] Nghiệm thu 7 scenarios US2, chéo role A/B, operator không có
academic wildcard và teacher không tạo lớp center”. Cây nghiệm thu: branch
`stack/platform-grant` trên nền `origin/main` `a0aecb92` (PR #164, D1/D2/D4) — cây
này chứa trọn stack US2 (#36 nền + #38, #39, #40, #41 ghép + #42 controller + M1
publish grant platform) và toàn bộ việc của gate #44 (rebase, D3, test-realm.json,
M2/C1, đồng bộ Sổ, sửa flake partial-graph). Issue này không liệt kê sẵn mã
US2.1–US2.7 trong specs; 7 scenario dưới đây suy từ FR-007–FR-012
(`specs/001-multitenant-access/spec.md`) + tiêu đề Issue #44 + T3 của
`specs/001-multitenant-access/quickstart.md`.

> Ghi chú về E2E: `frontend/tests/e2e/center-administration.spec.ts` có 8 test
> active + 1 `test.skip` (dòng 70, cần `NATIVE_GATE_APP_ORIGIN`). E2E tự skip khi
> thiếu biến nên **không chạy ở local**; bằng chứng E2E chờ CI trên PR #158 sau
> khi cập nhật. Tài liệu này tuyệt đối không trích test bị skip làm bằng chứng
> chạy thật — xem mục “Hạn chế”.

## Phạm vi và phương pháp

- Gate chạy trên **làn 1** của stack TEST native (PostgreSQL 127.0.0.1:15432,
  Keycloak 127.0.0.1:18081, realm `giaokhoa-it` dựng lại tươi từ
  `test-realm.json` mới của gate — có cây group `/gk` và client
  `giaokhoa-it-api`, đủ 8 role cho service-account fixtures).
- Thứ tự cổng: `clean verify` trước (unit + kiểm tra tĩnh), RESET schema public
  bằng lệnh riêng, rồi `-Pintegration verify` sau. Không chạy `clean verify`
  sau lượt integration để không ghi đè failsafe-reports.
- Frontend **không đổi** so với main `a0aecb92` (`git diff origin/main --stat --
  frontend` trống) nên không chạy lại lint/test/build ở local trong gate này;
  CI trên PR #158 sẽ chạy đủ 3 job.
- Mỗi scenario đều có ít nhất một test **integration** chạy thật khẳng định hành
  vi; không test nào được tick pass từ tài liệu.

## Kết quả tổng hợp

| Hạng mục | Kết quả lượt chốt (lượt 4, xem Phụ lục) |
| --- | --- |
| Unit (`clean verify`) | 170 run / 0 failures / 0 errors / 0 skipped |
| Integration (`-Pintegration verify`) | 264 run / 0 failures / **1 error** / 9 skipped |
| Kiểm tra tĩnh (Spotless/PMD/SpotBugs) | xanh (chạy riêng sau lượt 4, exit 0) |
| E2E Playwright | chưa chạy ở local; chờ CI trên PR #158 |

Error duy nhất là test SMTP `RecoveryFlowIT.verifiedUserGetsPasswordEmail`
(local không có Mailpit; xanh ở CI). 9 skipped: 6 của `NativeGateAcceptanceIT`
(skip theo thiết kế, chạy qua `verify-native.sh`), 2 của
`PolicyGraphWriterNativeIT` (skip theo thiết kế), 1 của `IdentityFlowIT`.
Chữ ký xấu đều 0: `53300` = 0, `LifecycleProcessor` = 0, `302→200` = 0.

## Minh chứng từng scenario

### US2.1 — Operator tạo tenant + chỉ định owner (FR-007)

- `CenterAdministrationIT.operatorCreateCenterReturnsPendingOperation` — operator
  tạo center trả 202 + operation, poll tới SUCCEEDED, center xuất hiện ở danh
  sách platform.
- `CenterAdministrationIT.ordinaryUserCannotCreateTenant` — user thường tạo
  tenant bị từ chối.
- `CenterServiceIT.createCenterProvisionsTenantOwnerAndProjection` — tạo center
  provision đúng tenant, owner và projection.
- `CenterServiceIT.createCenterReplayCreatesSingleTenant` — replay cùng
  Idempotency-Key chỉ tạo một tenant.

Kết quả local: `CenterAdministrationIT` 21/21, `CenterServiceIT` 8/8.

### US2.2 — Operator xác nhận kỳ gói thủ công, end > start (FR-007)

- `CenterAdministrationIT.operatorConfirmsSubscriptionWithValidPeriod` — operator
  xác nhận kỳ gói hợp lệ trả 202 + poll tới SUCCEEDED.
- `CenterAdministrationIT.subscriptionWithEndBeforeStartIsRejected` — kỳ gói
  end ≤ start bị từ chối.
- `CenterAdministrationIT.ordinaryUserCannotConfirmSubscription` — user thường
  không xác nhận được kỳ gói.
- `SubscriptionServiceIT.confirmCenterRecordsBundle` —
  xác nhận ghi đúng kỳ, audit và bundle mong muốn.
- `SubscriptionServiceIT.confirmRejectsPeriodWithoutPositiveRange` — kỳ không
  có khoảng dương bị từ chối.

Kết quả local: `CenterAdministrationIT` 21/21, `SubscriptionServiceIT` 8/8.

### US2.3 — Owner bổ nhiệm/gỡ manager; manager không tự bổ nhiệm (FR-009)

- `CenterAdministrationIT.ownerAppointsManagerInOwnCenterOnly` — owner bổ nhiệm
  manager chỉ trong center của mình.
- `CenterAdministrationIT.managerCannotAppointAnotherManager` — manager không bổ
  nhiệm được manager khác.
- `CenterAdministrationIT.managerCannotPromoteSelfToOwner` — manager không tự
  nâng mình lên owner.
- `CenterAdministrationIT.cannotRemoveLastOwner` — không gỡ được owner cuối cùng.
- `CenterServiceIT.lastOwnerCannotBeRemovedByRoleChangeOrRevoke` — owner cuối
  không bị gỡ bằng đổi role hay revoke.

Kết quả local: `CenterAdministrationIT` 21/21, `CenterServiceIT` 8/8.

### US2.4 — Owner/manager quản lý thành viên (FR-010)

- `MembershipInvitationIT.inviteByEmailReturnsPendingOperation` — mời bằng email
  trả operation PENDING.
- `MembershipInvitationIT.inviteExistingIdentityReturnsPendingOperation` — mời
  danh tính đã có trả operation PENDING.
- `MembershipInvitationIT.inviteCannotGrantOwnerRole` — lời mời không cấp được
  role OWNER.
- `MembershipInvitationIT.managerCanInviteStudentButNotManager` — manager mời
  được student, không mời được manager.
- `MembershipInvitationIT.studentIssueReturnsHandoffOnlyOnCreate` — cấp student
  trả handoff (mật khẩu tạm) chỉ ở response CREATE; poll/list/retry không lộ lại.
- `MembershipInvitationIT.duplicateUsernameDoesNotTakeOverExistingUser` —
  username trùng không chiếm user đã có (trả 409, mật khẩu tạm cũ còn dùng được).
- `CenterAdministrationIT.roleChangeBatchReturnsPendingOperation` — đổi role
  hàng loạt trả operation PENDING.
- `CenterAdministrationIT.expiredSubscriptionBlocksInviteButNotRevoke` — gói hết
  hạn chặn invite nhưng không chặn revoke.
- `MembershipInvitationIT.expiredSubscriptionBlocksStudentIssue` — gói hết hạn
  chặn cấp student.
- `InvitationMembershipServiceIT.acceptByInvitedUserCreatesActiveMembershipWithIntendedRoles`
  — người được mời accept tạo membership ACTIVE với đúng role dự kiến.

Kết quả local: `MembershipInvitationIT` 12/12,
`InvitationMembershipServiceIT` 7/7, `CenterAdministrationIT` 21/21.

### US2.5 — Chéo role A/B (FR-008/FR-012)

- `CenterAdministrationIT.capabilitiesForAnotherProfileIsRejected` — capability
  của profile khác bị từ chối.
- `CenterAdministrationIT.workspacesEndpointDoesNotLeakOtherCenters` — endpoint
  workspaces không lộ center của người khác.
- `CenterAdministrationIT.ownerAppointsManagerInOwnCenterOnly` — owner chỉ tác
  động trong center của mình (đã nêu ở US2.3, dùng lại làm bằng chứng chéo role).
- `IdentityFlowIT.oppositeRolesInTwoCentersStaySeparated` — hai role đối lập ở
  hai center vẫn tách biệt.

Kết quả local: `CenterAdministrationIT` 21/21, `IdentityFlowIT` 17 run / 0
failures / 0 errors / 1 skipped.

### US2.6 — Teacher không tạo lớp center (FR-011)

- `CenterAdministrationIT.teacherCannotCreateCenterClassEvenWithPersonalPackage`
  — teacher có gói personal vẫn không tạo được lớp center.
- `MembershipInvitationIT.teacherCannotIssueStudent` — teacher không cấp được
  student.

Kết quả local: `CenterAdministrationIT` 21/21, `MembershipInvitationIT` 12/12.

### US2.7 — Operator không có academic wildcard (FR-012)

Bằng chứng **cấu trúc** (không phải một test phủ định HTTP duy nhất):

- `PlatformPolicyProjectorTest.managedScopes` (unit, trong lượt `clean verify`
  170/0/0/0) — khoá `managedScopes()` đúng 5 scope platform
  (`center.create`, `center.list`, `subscription.confirm`, `account.recover`,
  `fixture.manage`); không scope academic nào vào được bundle platform.
- `PlatformProjectionIT.publishGrantsCurrentOperators` —
  publish grant theo đúng membership hiện tại của group
  `/gk/platform/operators`.
- `PlatformProjectionIT.leavingOperatorGroupDeniesToken`
  — rời group operator thì quyết định tiếp theo bị từ chối.

Kết quả local: `PlatformProjectionIT` 5/5.

**Chưa chứng minh trực tiếp:** không có test “operator gọi endpoint academic thì
403”, vì endpoint giảng dạy chưa tồn tại (thuộc US3/T043). Nửa phủ định này của
US2.7 chờ US3 — xem mục “Hạn chế”, không tự tick pass.

## T3 — quickstart E2E (`center-administration.spec.ts`)

8 test active của spec đối chiếu với scenario US2 (chưa chạy — xem ghi chú đầu
tài liệu):

| Test E2E | Scenario |
| --- | --- |
| operator tạo center: mutation 202 rồi poll tới SUCCEEDED, center có trong danh sách platform | US2.1 |
| operator xác nhận kỳ/gói: subscription 202 rồi poll tới SUCCEEDED, workspace có gói hiệu lực | US2.2 |
| owner bổ nhiệm manager: PUT roles 202 rồi poll, danh sách thành viên ghi MANAGER | US2.3 |
| manager mời thành viên: invite 202; cấm mời OWNER; manager không cấp được MANAGER | US2.4 |
| manager cấp student: handoff chỉ ở response CREATE; poll/list/retry không lộ mật khẩu tạm | US2.4 |
| username trùng không chiếm user đã có: cấp lại trả 409, mật khẩu tạm cũ còn dùng được | US2.4 |
| teacher không tạo được lớp center dù đã có gói personal | US2.6 |
| giao diện quản trị: trang members hiển thị thành viên và không lộ mật khẩu tạm; trang platform liệt kê center | US2.3/US2.4 |

1 `test.skip` ở dòng 70 (“Cần `NATIVE_GATE_APP_ORIGIN` của môi trường
gate/review để chạy spec quản trị center”).

## Các Note treo của review #42 — xử lý trong gate này

### M1 — Publish grant platform — ĐÃ XONG, cổng đạt

- Thiết kế: một projection cho target `platform` (`PlatformPolicyProjector` +
  `PlatformProjectionSeeder`, migration V013 nới CHECK kind thêm `'PLATFORM'`).
  Chi tiết ở `hidden_files/platform-grant/design.md` của goal pipeline.
- Review M1 hai vòng: vòng 1 REQUEST CHANGES (0 Must, 1 Should, 2 Note), sửa
  Should bằng test HTTP `account.recover` trọn đường
  (`RecoveryFlowIT.operatorResolvesRequestOverHttp`,
  commit `8a4b8f8`); vòng 2 APPROVE.
- Cổng M1: toàn suite 263/0/1/9; ở cây gate sau rebase + sửa worker của
  `RecoveryFlowIT`, lượt chốt là 264/0/1/9 (xem Phụ lục).

### M2/C1 — Ngoại lệ route thành viên — ĐÃ LÀM, ghi vào spec

- Route thành viên quyết quyền bằng SQL thay vì PDP (điểm A1 của Sổ): đã ghi
  ngoại lệ vào mục 10 mới của
  `specs/001-multitenant-access/contracts/authorization.md` (commit
  `c6747a0`) — phạm vi đúng 6 route của `MembershipController`, điểm lệch đã
  biết, lý do, giới hạn; là thay đổi quyết định riêng theo cơ chế mục 9 của
  chính spec. Không hỏi lại, tự quyết theo spec.

### N1–N5 của review #42 — ĐỂ LẠI, đã ghi Sổ

- N1 = A2 (phân trang `GET /api/platform/centers` chưa đúng hình dạng hợp đồng
  `{items, nextCursor?}`): **để lại** — không thuộc 7 scenario của gate; ghi ở
  Sổ A2, bên đánh giá rà lại.
- N2 = A14 (`requirePlatformScope` nhân bản 3 bản ở 3 controller): **để lại** —
  ghi ở Sổ A14.
- N3 (`ensureApiClient` nhân bản ~90 dòng, hướng D2): **để lại** — D2 trên main
  đã hợp nhất helper đăng nhập/schema nhưng `ensureApiClient` vẫn nhân bản ở
  các class; gộp là việc dọn dẹp riêng, không chặn gate.
- N4 = A13 (phân trang danh sách thành viên cắt trong bộ nhớ): **để lại** — ghi
  ở Sổ A13.
- N5 = A3 (`POST /api/workspaces/{wid}/classes` mới có biên quyền rồi trả 404):
  **để lại** — ghi ở Sổ A3.

### B6 (#39), B7 (#40) — kiểm chứng cuối ở cây gộp

- B6 (tương đương single-Apply chỉ đúng theo hình dạng descriptor hiện hành)
  và B7 (ba hành vi biên của lời mời) là Note của review các issue, đã ghi Sổ;
  gate không đổi hành vi nào liên quan. Kiểm chứng cuối: toàn bộ test của #39
  (`CenterServiceIT` 8/8) và #40 (`MembershipInvitationIT` 12/12,
  `InvitationMembershipServiceIT` 7/7) xanh trong lượt chốt 264/0/1/9.

## Hạn chế và Note chưa kiểm chứng được

- E2E Playwright chưa chạy ở local trong gate này (cần `NATIVE_GATE_APP_ORIGIN`
  + backend ở cổng 8080 + Mailpit); bằng chứng E2E cho US2 chờ CI trên PR #158
  sau khi cập nhật.
- US2.7: chưa có test phủ định trực tiếp “operator gọi endpoint academic thì
  403” vì endpoint giảng dạy chưa tồn tại (US3/T043). Mức đã chứng minh: bundle
  platform chỉ chứa đúng 5 scope + thu hồi grant khi rời group.
- `RecoveryFlowIT.verifiedUserGetsPasswordEmail` cần Mailpit: đỏ
  ở local, xanh ở CI (error duy nhất được phép trong phân rã cổng local).
- A2 (phân trang platform) vẫn mở — xem mục Note N1–N5 ở trên.
- B7 (ba hành vi biên của lời mời) vẫn mở, đã ghi Sổ.
- B14 (cửa sổ đua hẹp trong `stopSchedulerAndDropSchema`) vẫn mở, đã ghi Sổ;
  không quan sát thấy trong các lượt cổng của gate.
- B15 (operator gọi thẳng endpoint platform trước lời gọi có fence thì 403) vẫn
  mở ở tầng production, đã ghi Sổ; fixture test đã xử lý bằng lời gọi fence
  trước lời gọi platform.
- Môi trường local khác CI ở nhiều điểm (B10): không Docker, không Mailpit
  native; mọi kết luận “xanh local” đều mang điều kiện này.

## Kết luận

**Gate đạt cho 7/7 scenarios ở tầng integration với bằng chứng thực tế**
(số liệu lượt chạy local ở trên): mỗi scenario đều có ít nhất một test
integration chạy thật khẳng định hành vi, không test nào được tick pass từ tài
liệu. Riêng US2.7 được chứng minh ở tầng cấu trúc (bundle platform chỉ chứa
đúng 5 scope, không scope academic nào) + hành vi thu hồi grant; khẳng định phủ
định trực tiếp qua endpoint giảng dạy chờ US3 (T043) vì endpoint đó chưa tồn
tại — không tự tick pass phần đó. Bằng chứng E2E cho các scenario US2 chưa có
(spec tự skip ở local vì thiếu `NATIVE_GATE_APP_ORIGIN`) — chờ CI trên PR #158.

Việc đóng story do bước review/merge quyết định trên cơ sở tài liệu này.

## Phụ lục — Số liệu lượt integration chốt ở local (lượt 4, sau khi sửa flake)

Lượt 1 (264 run / 1 failure / 1 error / 9 skipped) đỏ 1 test
`RecoveryFlowIT.operatorResolvesRequestOverHttp` — 503
`AUTHORIZATION_NOT_READY` vì rebase mang `@MockitoBean ProjectionWorker` của
PR #163 vào `RecoveryFlowIT`, worker không chạy nên eligibility của operator
không được đồng bộ. Đã sửa trong cây (commit `6634c40`): gỡ mock worker, dùng
teardown `stopSchedulerAndDropSchema` (Pattern A) vì test này cần worker thật.

Lượt 2 chạy chọn lọc `RecoveryFlowIT`: 11 run / 0 failures / 1 error (SMTP) / 0
skipped — test platform operator đã xanh; nhưng `-Dit.test` chỉ lọc đúng class
đó nên không thay được lượt toàn suite.

Lượt 3 toàn suite (264 / 1 / 1 / 9) đỏ 1 test
`ConcurrentRevocationIT.workerReconcilesPartialGraph`
ở khẳng định `policyCount() > policiesBefore` (dòng 838) — flake partial-graph
đã biết (Sổ B2/B13), khuếch đại bởi client dùng chung nay tồn tại bền trong
realm. Đã sửa trong cây (commit `a78277b`, chỉ chạm code test): giữ retry ở
tương lai (`next_attempt_at = now() + interval '5 minutes'`) tới khi test khẳng
định xong trạng thái trước reconcile (sửa luôn đua worker ở dòng 772), và đếm
policy theo graph (`scopedPolicyCount()`) thay vì đếm toàn cục. Kiểm chứng
chọn lọc class trên realm đã tích luỹ nhiều lượt: 16/0/0/0.

Lượt 4 là số liệu chốt (toàn bộ 30 report đều mới, 20:24–20:28):

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
| PlatformProjectionIT | 5 / 0 / 0 / 0 |
| ProjectionRecoveryIT | 4 / 0 / 0 / 0 |
| BootstrapProfileIT | 12 / 0 / 0 / 0 |
| IdentityFlowIT | 17 / 0 / 0 / 1 |
| RecoveryFlowIT | 11 / 0 / 1 / 0 (error duy nhất: test SMTP, thiếu Mailpit ở local) |
| RecoveryRequestIT | 4 / 0 / 0 / 0 |
| RecoveryWorkflowIT | 17 / 0 / 0 / 0 |
| IdentityEligibilitySynchronizerIT | 3 / 0 / 0 / 0 |
| OidcSessionPersistenceIT | 6 / 0 / 0 / 0 |
| SecurityConfigurationIT | 4 / 0 / 0 / 0 |
| ResourcePrimitivesSchemaIT | 7 / 0 / 0 / 0 |
| AuditEventSchemaIT | 5 / 0 / 0 / 0 |
| SubscriptionServiceIT | 8 / 0 / 0 / 0 |
| IntegrationEnvironmentIT | 1 / 0 / 0 / 0 |
| CenterAdministrationIT | 21 / 0 / 0 / 0 |
| CenterServiceIT | 8 / 0 / 0 / 0 |
| DirectorySchemaIT | 25 / 0 / 0 / 0 |
| InvitationMembershipServiceIT | 7 / 0 / 0 / 0 |
| MembershipInvitationIT | 12 / 0 / 0 / 0 |
| WorkspaceTransactionIT | 7 / 0 / 0 / 0 |
| SystemPracticeHistoryIT | 7 / 0 / 0 / 0 |
| **Tổng** | **264 / 0 / 1 / 9** |

Trong lượt `clean verify` chốt trước đó: unit 170 test, 0 failures, 0 errors, 0
skipped. Lượt này từng đỏ ở PMD trước khi chốt, do code mới của gate và đã sửa
đúng nguyên nhân (không suppression): `RecoveryFlowIT:460`
`assertTrue(item != null, ...)` vi phạm `SimplifiableTestAssertion` — đổi thành
`assertNotNull(item, ...)` (commit `696afbb`).
