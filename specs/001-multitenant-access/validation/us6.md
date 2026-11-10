# Nghiệm thu US6 — Tách quyền cá nhân khỏi quyền trung tâm (T074)

> **Trạng thái: ĐẠT — gate #76 chốt ngày 2026-11-10 (Asia/Saigon).**
> Cây nghiệm thu: main `0135a964` (merge của PR #212), là cây chứa
> trọn US6. Mọi khẳng định trong bản này đều có nguồn kiểm chứng
> trực tiếp ghi kèm (số run CI, log đọc từ đĩa, nội dung file đọc
> trên main qua GitHub API); không mục nào điền bằng số liệu suy
> đoán. Năm điều kiện chốt ở §6 đều đạt.

Issue #76: “[T074][US6] Nghiệm thu US6.1–US6.5, personal student/organizer
hoạt động không center membership và tách owner/creator; kiểm tra
activation/gia hạn không tự resurrect role/link/assignment đã gỡ”.
US6.1–US6.5 là 5 acceptance scenarios của User Story 6 trong
`specs/001-multitenant-access/spec.md`; T3 là kịch bản kiểm chứng ở
`specs/001-multitenant-access/quickstart.md` (dòng 99: teacher bị từ
chối tạo lớp center dù có personal package). Tiền đề gate (blocked_by
native, kiểm chứng trực tiếp qua GitHub API ngày 2026-11-10):
**#76 ← #75 (closed) + #74 (closed)**; #73 đã land qua PR #209;
#71/#72 đã đóng (PR #205/#206 đóng không merge — lý do ở §1).

Tài liệu đã đọc để lập bản đồ nghiệm thu (đọc trực tiếp trên main
qua GitHub API):

- `specs/001-multitenant-access/spec.md`, User Story 6 (dòng 105–125):
  5 acceptance scenarios US6.1–US6.5 — nguồn tiêu chí của §2.
- `specs/001-multitenant-access/tasks.md`, Phase 8 (dòng 148–161):
  T069–T074 và file đích từng task — nguồn chuỗi land của §1.
- `specs/001-multitenant-access/validation/us5.md`: mẫu cấu trúc và
  chuẩn bằng chứng của gate trước (mọi khẳng định ghi nguồn kiểm chứng
  trực tiếp; phần theo bàn giao ghi rõ là theo bàn giao).
- `docs/diem-chua-chac-chan-va-lech-spec.md` trên main `0135a964`:
  B74–B77 (§5).

## 1. Chuỗi land US6 — trạng thái chốt

| Issue | Nội dung | PR | Merge commit | Trạng thái |
| --- | --- | --- | --- | --- |
| #71 [T069] test US6 (IT) | PersonalEntitlementIT, PersonalClassAuthorizationIT | #205 | — | issue closed; PR đóng không merge (nội dung đã lên main qua #209/#212, xem dưới) |
| #72 [T070] E2E capability matrix | `personal-entitlements.spec.ts` (7 test) | #206 | — | issue closed; PR đóng không merge (nội dung đã lên main qua #209/#212, xem dưới) |
| #73 [T071] personal policy sources | PersonalEntitlementProjector, PersonalTeachingPolicyProjector | #209 | `d7adfb4f` | merged, issue closed |
| #74 [T072] tích hợp personal vào teaching + sample actions | SampleActionController 4 action, migration V022 | #212 | `0135a964` | merged, issue closed |
| #75 [T073] personal classes UI | trang `/personal/classes` | #208 | `2ce4a5eb` | merged, issue closed |

- Main tại thời điểm chốt gate: `0135a964` (merge của PR #212).
  Hai file test của #71 đã lên main theo PR #209 (nối chồng #205):
  `PersonalEntitlementIT.java` và `PersonalClassAuthorizationIT.java`
  đọc được trực tiếp trên main (kiểm chứng qua GitHub API ngày
  2026-11-10), ở dạng đã tiến hoá qua #212 (8 test cho
  PersonalClassAuthorizationIT, gồm biên nhận của #74). File E2E của
  #72 cũng đã lên main theo chuỗi nối chồng: trên main `0135a964`
  file `frontend/tests/e2e/personal-entitlements.spec.ts` có 44799
  byte (blob `fd55311e`), là bản đã gồm sửa seed `TEST_E2E_SCHEMA`
  của B77 — mới hơn bản trên branch PR #206 (43081 byte). Vì toàn bộ
  nội dung của #71/#72 đã nằm trên main ở dạng mới hơn, PR #205/#206
  được đóng không merge và issue #71/#72 đã đóng ngày 2026-11-10.
- **Sổ trên main `0135a964`: B74, B75, B76 và B77 đủ** (dòng 145–148
  của Sổ, đọc trực tiếp qua GitHub API sau khi #212 land) — §5.

## 2. Bản đồ phủ US6.1–US6.5

Kết quả IT trong §2 lấy từ log job `backend (it-pure)` của run CI
`42740172845` (PR #212, cây chứa trọn #73 + #74; log đã lưu ở
`~/workspace/logs-gate76/ci212-itpure.log`, đọc trực tiếp): toàn suite
**293 test, 0 failures, 0 errors, 8 skipped**.

### Integration — `PersonalEntitlementIT` (5 test, #71; file trên main qua #209)

| Scenario | Test method | Kết quả |
| --- | --- | --- |
| US6.1 chưa gói: tạo học liệu personal bị từ chối | `personalCreateWithoutPlanDenied` | 5/5 xanh (CI §3.1) |
| US6.1 lịch sử practice hệ thống vẫn giữ khi chưa gói | `practiceHistoryAvailableNoPlan` | 5/5 xanh |
| US6.2 operator kích hoạt gói → tạo học liệu được phép | `operatorActivationEnablesCreate` | 5/5 xanh |
| US6.3 membership center không mở author personal | `centerMembershipOpensNoAuthoring` | 5/5 xanh |
| US6.4 gói personal không mở tạo lớp center | `personalPlanOpensNoCenterClass` | 5/5 xanh |

### Integration — `PersonalClassAuthorizationIT` (8 test: 7 của #71 + 1 của #74)

| Scenario | Test method | Kết quả |
| --- | --- | --- |
| US6.2 owner có gói tổ chức lớp/khóa/roster/ghi danh | `ownerWithPlanOrganizesPersonalClassCourseRosterAndEnrollment` | 8/8 xanh (CI §3.1) |
| US6.1 owner chưa gói không tạo được lớp personal | `ownerWithoutPlanCannotCreatePersonalClass` | 8/8 xanh |
| US6.3 teacher không gói: biên soạn theo phân công ở center được, personal bị từ chối | `centerTeacherWithoutPersonalPlanAuthorsAtCenterButNotPersonal` | 8/8 xanh |
| US6.4 có gói nhưng không quản lý center: tạo lớp center bị từ chối | `personalPlanHolderCannotCreateClassAtCenter` | 8/8 xanh |
| US6.2 (luồng dạy) owner giao bài, học sinh đã ghi danh nộp bài | `ownerAssignsAndEnrolledStudentSubmitsInPersonalClass` | 8/8 xanh — 1 trong 2 test từng đỏ test-first |
| US6.2 (luồng dạy) owner chấm/công bố, học sinh đọc kết quả | `ownerGradesAndPublishesAndStudentReadsResultInPersonalClass` | 8/8 xanh — test test-first còn lại |
| Hợp đồng biên nhận #74: trùng key + cùng payload → replay; khác payload → 409 | `sampleActionRetryWithSameKeyReplaysAndDifferentPayloadConflicts` | 8/8 xanh |
| US6.5 rời center: quyền center chấm dứt, quyền personal giữ nguyên | `leavingCenterKeepsPersonalRights` | 8/8 xanh |

Lịch sử test-first của hai lớp này (theo hồ sơ đã kiểm chứng ở các đợt
trước): `PersonalEntitlementIT` 5/5 sau khi #73 land;
`PersonalClassAuthorizationIT` 5/7, đúng 2 test đỏ còn lại là hai
luồng ASSIGN/SUBMIT và GRADE/PUBLISH ở trên (400
`UNSUPPORTED_SAMPLE_ACTION`) vì 4 action thuộc #74 — đây là nợ
test-first đã biết, là lý do PR #209 không thể xanh hoàn toàn trên
nhánh riêng. Trên cây gộp của PR #212 (chứa cả #73 và #74), CI run
`42698454912` trên head `a6f8e5e4` đã xanh đủ 7/7 job — bằng chứng hai
test đó qua trên cây gộp; bằng chứng trên cây main cuối điền ở §3 sau
khi #212 land.

### E2E — `frontend/tests/e2e/personal-entitlements.spec.ts` (7 test, #72; đã có trên main `0135a964`)

| Scenario | Test | Kết quả |
| --- | --- | --- |
| US6.1 | “US6.1 chưa có gói cá nhân: tạo lớp và học liệu cá nhân bị từ chối, lịch sử luyện tập hệ thống vẫn còn” | passed (10,3s) |
| US6.2 | “US6.2 operator kích hoạt gói cá nhân: owner tổ chức lớp/khóa/roster/ghi danh và tạo học liệu theo gói” | passed (35,8s) |
| US6.3 | “US6.3 teacher không gói cá nhân: làm việc theo phân công ở center, cá nhân vẫn bị từ chối” | passed (59,0s) |
| US6.4 | “US6.4 có gói cá nhân nhưng không quản lý center: tạo lớp ở center bị từ chối, lớp cá nhân được phép” | passed (45,9s) |
| US6.5 | “US6.5 rời center: quyền center chấm dứt, gói và lớp cá nhân còn hiệu lực” | passed (51,5s) |
| Ma trận 3 tài khoản | “ma trận ba tài khoản: chỉ personal, chỉ center, có cả hai — quyền không rò rỉ giữa hai không gian” | passed (1,4 phút) |
| Giao diện cá nhân (#75) | “giao diện cá nhân: trang lớp cá nhân hiện lớp theo gói; trang cá nhân hiện trạng thái gói và không hiện editor học liệu” | passed (14,3s) |

Tổng lượt chạy gate: **7/7 passed, 5,1 phút** (§3.2). Đây là phần
bằng chứng còn nợ của #72 được gác lại chờ gate này — đã chạy qua UI
như người dùng thật trên cây main cuối, không thay bằng lượt chạy cũ
trên branch.

## 3. Kết quả chạy thật

### 3.1 CI — chế độ phán quyết hiện hành

- CI trên main, cây `25ae5424` (trước #209), run `42690715771`:
  kết luận `success`, xanh đủ 7/7 job (kiểm chứng trực tiếp qua
  GitHub API ngày 2026-11-10). Cây này chưa chứa US6 nên chỉ là nền.
- CI trên main, cây `d7adfb4f` (sau #209), run `42739679848`: chân
  `backend (it-pure)` đã kết luận `failure` với đúng chữ ký nợ
  test-first đã biết (đọc trực tiếp log job `129577376038`): tổng
  292 test, đúng 2 failures, 0 errors — cả hai ở
  `PersonalClassAuthorizationIT`
  (`ownerAssignsAndEnrolledStudentSubmitsInPersonalClass`,
  `ownerGradesAndPublishesAndStudentReadsResultInPersonalClass`),
  đều `expected: <201> but was: <400>` với mã
  `UNSUPPORTED_SAMPLE_ACTION`. Đây là trạng thái tạm thời đã lường
  trước khi land #209 trước #212 (quyết định bypass của đợt land
  chuỗi); không phải lỗi mới. Các chân còn lại của run vẫn đang chạy
  tại thời điểm kiểm chứng; kết luận đầy đủ của run điền sau khi
  #212 land và CI main trên cây cuối có hiệu lực thay thế.
- CI trên main, cây `d7adfb4f`, run `42739679848`: kết luận
  `failure` — 5/7 job `success` (frontend, backend e2e-backend, cả
  hai integration và job tổng hợp integration); `backend (it-pure)`
  và job tổng hợp `backend` failure với đúng 2 test test-first đã
  phân loại ở trên, không có lỗi nào khác (đếm job và đọc log trực
  tiếp qua GitHub API). Run này không đủ làm bằng chứng vì cây chưa
  chứa #74 — đúng như đã lường khi land #209 trước.
- **CI của PR #212, head `80170a61` (cây = main `d7adfb4f` + #74),
  run `42740172845`: kết luận `success` — cả 7 job đều `completed
  success`** (frontend, backend it-pure, backend e2e-backend,
  backend tổng hợp, integration chromium-core, integration
  chromium-teaching-guardians, integration tổng hợp; kiểm chứng trực
  tiếp qua GitHub API). Log it-pure của run: toàn suite 293 test,
  0 failures, 0 errors — gồm PersonalEntitlementIT 5/5,
  PersonalClassAuthorizationIT 8/8, SubscriptionServiceIT 9/9 (§2,
  §3.3). PR #212 sau đó đã squash merge, main `0135a964`.
- **CI trên main, cây `0135a964` (sau #212), run `42743181988`:
  kết luận `success`** (kiểm chứng trực tiếp qua GitHub API ngày
  2026-11-10) — full-suite xanh trên cây main chứa trọn US6
  backend, gồm cả E2E `chromium-core` chạy spec
  `personal-entitlements.spec.ts` trên cây này.
- Bằng chứng full-suite trên đúng cây nghiệm thu cuối: run CI của
  chính PR gate này (cây = main `0135a964` + tài liệu này) — số run
  ghi tại comment đóng issue #76; merge chỉ thực hiện khi run đó
  xanh đủ 7/7 job.

### 3.2 E2E giao diện cuối của #72 — ĐÃ CHẠY, 7/7 passed

- Lượt chạy của gate ngày 2026-11-10 trên cây main `0135a964`
  (worktree gate cập nhật đúng cây này), stack TEST native làn 1
  (PostgreSQL 15432, Keycloak 18081, realm `giaokhoa-it`), kiến trúc
  đúng job integration: backend jar cổng 18080 với schema riêng
  `e2e_6964_30957`, frontend production cổng 3001, Caddy native cổng
  8080, env TEST_* nạp từ `/var/tmp/test-stack/run-it.sh`. Playwright
  chạy đúng file `personal-entitlements.spec.ts`, 1 worker, qua UI
  như người dùng thật.
- Kết quả: **7 passed (5,1 phút), PLAYWRIGHT_RC=0** — chi tiết từng
  test ở §2. Log đầy đủ: `~/workspace/logs-gate76/playwright.log`
  (đọc trực tiếp từ đĩa); script harness của lượt chạy:
  `~/workspace/logs-gate76/run-e2e-gate76.sh` (build/up/test/down đều
  RC=0; pha down đã dọn đúng các tiến trình của lượt).

### 3.3 Kiểm chứng activation/gia hạn không tự resurrect

Tiêu chí riêng của issue #76. Tình trạng bằng chứng tại thời điểm lập
nháp:

- Chưa có test tự động riêng cho tình huống “gỡ role/link/assignment
  rồi kích hoạt/gia hạn gói” trong hai lớp IT của US6 (rà trực tiếp
  thân test ngày 2026-11-10: không test nào dựng đúng trình tự này).
- Bằng chứng thiết kế (Sổ B75): `PersonalEntitlementProjector` và
  `PersonalTeachingPolicyProjector` tính desired bundle chỉ từ nguồn
  hiện hành (user policy của owner, học sinh đang ghi danh, time
  policy của kỳ gói); reconciler đối chiếu cả tập — nguồn đã gỡ không
  nằm trong desired nên lần kích hoạt/gia hạn sau không thể ghi lại.
  Chiều “gỡ ở center, personal giữ nguyên” đã khóa bằng
  `leavingCenterKeepsPersonalRights`; chiều thu hồi nói chung đã khóa
  ở gate US5 (ma trận thu hồi 6/6). Cả hai test cơ chế này đều
  xanh trong run CI `42740172845` (SubscriptionServiceIT 9/9, §3.1).
- Bằng chứng trực tiếp ở tầng projector dùng chung (đọc thân test
  trên main `d7adfb4f` ngày 2026-11-10):
  `SubscriptionServiceIT.confirmSkipsRevokedOnlyGroups`
  khóa đúng hợp đồng “confirm gói không khôi phục nguồn đã thu hồi” —
  sau khi confirm, bundle không chứa group mà thành viên duy nhất đã
  bị revoked (managers, parents), trong khi group owners còn ACTIVE
  vẫn có nguồn. Phía personal, cùng cơ chế confirm được khóa bởi
  `confirmPersonalRecordsBundle` (kỳ +
  bundle entitlement từ nguồn hiện hành). Đây là bằng chứng ở tầng
  cơ chế dùng chung; kiểm chứng hành vi đầu-cuối phía personal ở mục
  tiếp theo.
- **Kiểm chứng hành vi trực tiếp tại gate — ĐÃ CHẠY, ĐẠT:** ngày
  2026-11-10, một IT tạm (chỉ dùng cho gate, không thuộc bộ test của
  repo, đã xoá khỏi cây trước khi mở PR gate) chạy trên stack TEST
  native làn 1, đúng trình tự hợp đồng: owner có gói personal →
  ASSIGN + học sinh SUBMIT đạt 201 (điểm xuất phát) → owner gỡ ghi
  danh qua HTTP DELETE (enrollment sang REVOKED) → ASSIGN hoạt động
  mới → học sinh SUBMIT bị 403 → operator gia hạn gói personal
  (`SubscriptionService.confirm`, kỳ mới, tham chiếu mới, chờ
  operation hoàn tất) → khẳng định: enrollment **vẫn REVOKED**,
  SUBMIT vẫn **403**, **0 submission** cho hoạt động mới, và owner
  CREATE_RESOURCE vẫn **201** (gói mới có hiệu lực thật, nên việc từ
  chối không phải do mất gói). Kết quả: **Tests run: 1, Failures: 0,
  Errors: 0** (46 giây). Log: `~/workspace/logs-gate76/it-renewal.log`;
  mã nguồn kiểm chứng lưu tại
  `~/workspace/logs-gate76/PersonalRenewalGateIT.java`. (Lượt chạy
  dừng ở spotless-check trên chính file tạm — lỗi định dạng của file
  kiểm chứng, không liên quan hành vi đã đo.)

## 4. Tách owner/creator và ranh giới hai không gian

- Personal không sinh center membership hay role nghề nghiệp toàn
  cục: nguồn personal chỉ gồm user policy của owner và học sinh đang
  ghi danh trong chính personal workspace đó (B75); lớp personal dùng
  owner/enrollment, học sinh personal không đòi center membership
  (test `ownerWithPlanOrganizesPersonalClassCourseRosterAndEnrollment`,
  §2).
- Hai không gian không rò quyền qua lại: US6.3 + US6.4 có test ở cả
  hai tầng IT và E2E (§2); kịch bản T3 của quickstart (teacher có
  personal package vẫn bị từ chối tạo lớp center) trùng test
  `personalPlanOpensNoCenterClass` và
  `centerTeacherWithoutPersonalPlanAuthorsAtCenterButNotPersonal`.
- Kích hoạt gói do operator thực hiện qua
  `POST /api/platform/subscriptions` trả operation 202; kỳ gói chỉ
  ghi khi operation SUCCEEDED (khẳng định đếm
  `subscription_period` trong
  `operatorActivationEnablesCreate`).

## 5. Rà Sổ B74–B77

- **B74 — dạng thành công của sample action: ĐÃ RÀ, khớp quyết định.**
  Đọc trực tiếp `SampleActionController` trên cây PR #212 (head
  `80170a61`): mọi action thành công trả HTTP 201
  (`HttpStatus.CREATED`) với thân kết quả `state: SUCCEEDED` đồng bộ,
  đúng tiền lệ `CREATE_RESOURCE` mà quyết định B74 đã khóa; không tồn
  tại dạng thứ hai (202 OperationView) trong controller. Test US6 đọc
  `targetId`/`id` từ thân 201 này — một dạng duy nhất trong toàn hệ.
- **B75 — personal dùng bundle như center, cổng gói nghiêm ngặt:
  ĐÃ RÀ ở mức quyết định.** Nội dung quyết định đã nằm trong PR #209
  (đã land): hai projector mới, xác nhận gói ghi bundle thật,
  `TeachingRepository.subscriptionCurrent` áp luật nghiêm ngặt cho
  personal. Tính đúng đắn hành vi do §2/§3 kiểm chứng.
- **B76 — 2 điểm lệch hợp đồng của #74: ĐÃ RÀ trên main `0135a964`.**
  Nội dung B76 khớp với bản đã biết: (i) `ASSIGN_ACTIVITY` chưa kiểm
  PDP `use` trên target version — thay bằng invariant dữ kiện nguồn
  trong fence (version thuộc đúng resource/workspace/môn, trạng thái
  `APPROVED`, kỳ gói hiệu lực); (ii) `SUBMIT_ACTIVITY` quyết qua
  scope `read` trên target môn–khóa thay vì `submit` trên target
  hoạt động — cộng invariant hoạt động `ASSIGNED`, service tính lượt
  nộp và trigger DB khóa ghi danh hiện hành. Kèm cơ chế ghi lại
  bundle hiện hành của target qua operation dùng chung trước khi hỏi
  PDP (fail-closed 503 khi quá hạn/FAILED). Cả hai điểm ghi rõ bổ
  sung ở T083 (US7) — phạm vi chuyển đúng, gate này ghi nhận, không
  chặn.
- **B77 — hệ quả của #200 lên test US6 và schema E2E: ĐÃ RÀ trên
  main `0135a964`.** Fence đọc kết quả rỗng cần partition thật của
  workspace; test
  `ownerGradesAndPublishesAndStudentReadsResultInPersonalClass` đã
  sửa ở T072 sang xác nhận kỳ qua `SubscriptionService.confirm` thay
  vì seed JDBC (test này xanh ở CI §3.1). Điểm kèm cho §3.2: seed
  psql của US6.1 trong `personal-entitlements.spec.ts` nay đọc
  `TEST_E2E_SCHEMA` trước, `E2E_SCHEMA` làm đường lui — lượt chạy
  E2E của gate dùng cây sau #206 land nên đã gồm sửa này.

## 6. Kết luận gate #76 — ĐẠT

Điều kiện chốt, đánh dấu theo trạng thái tại thời điểm chốt:

1. #71–#75 đều đóng: **ĐÃ ĐẠT** (#73/#74/#75 đóng qua merge;
   #71/#72 đóng ngày 2026-11-10, PR #205/#206 đóng không merge vì
   nội dung đã lên main qua #209/#212 — §1).
2. CI xanh đủ 7/7 job trên cây main cuối và trên PR gate này: phía
   main **ĐÃ ĐẠT** (run `42743181988`, cây `0135a964`); phía PR
   gate: merge chỉ thực hiện khi CI của PR này xanh đủ 7/7 job —
   số run và merge commit ghi tại comment đóng issue #76.
3. E2E giao diện cuối của #72 chạy qua UI trên cây cuối, 7 test có
   kết quả thật (§3.2): **ĐÃ ĐẠT — 7/7 passed**.
4. Kiểm chứng không resurrect (§3.3): **ĐÃ ĐẠT — IT kiểm chứng của
   gate đạt 1/1**.
5. Sổ B74–B77 đủ và đã rà trên main `0135a964` (§5): **ĐÃ ĐẠT**.

Đủ 5 điều kiện: bản này ở trạng thái ĐẠT, đưa lên main qua PR gate
riêng của #76 và đóng #76 kèm comment tóm tắt bằng chứng (số run CI
của PR gate và merge commit ghi tại comment đó).

## 7. Ghi chú cho gate US7 (không chặn gate này)

- B76: bổ sung kiểm PDP `use` cho ASSIGN và scope `submit` cho SUBMIT
  ở T083/US7.
- Case share của ma trận thu hồi và các mục B60/B62–B64 đã ghi ở
  validation US5 thuộc gate US7, không thuộc gate này.
