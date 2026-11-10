# Nghiệm thu US5 — Thu hồi quyền và ngăn vượt phạm vi (T068)

> **Trạng thái: ĐẠT — gate #70.**
> Nghiệm thu ngày 2026-11-10 ~02:10 (Asia/Saigon), trên cây main
> `b0e79b1c1424938ad48e7853bc9cdd11aacf6335`.
> Bằng chứng CI main xanh gần nhất: run `42665897288` trên cây
> `1c73075e` — cả 7 job đều `completed success` (3 check bắt buộc
> backend / frontend / integration, kiểm chứng trực tiếp qua GitHub
> API). Main sau đó nhích qua #208 và #207; bằng chứng full-suite
> trên đúng cây nghiệm thu cuối là run CI của chính PR gate này (§3.3).
>
> Mọi khẳng định dưới đây đều có nguồn kiểm chứng trực tiếp ghi kèm (GitHub
> API tại thời điểm lập, hoặc file log trên đĩa); phần lấy theo bàn giao của
> đội khác được ghi rõ là "theo bàn giao" và không trình bày như đã kiểm
> chứng độc lập.

Issue #70: “[T068][US5] Chạy core gate hồi quy và revoke matrix/E2E, ghi
bằng chứng thứ tự quyết định–commit–revoke và không actor override”.
US5.1–US5.6 là 6 acceptance scenarios của User Story 5 trong
`specs/001-multitenant-access/spec.md`; T7/T13 là kịch bản kiểm chứng ở
`specs/001-multitenant-access/quickstart.md`. Tiền đề gate cứng
(blocked_by native, kiểm chứng trực tiếp qua GitHub API ngày 2026-11-09):
**#70 ← #69 (closed)** — đã đủ để làm gate; #65–#69 đều đã đóng.

## 1. Chuỗi land US5 — đã kiểm chứng trực tiếp qua GitHub API

| Issue | PR | Merge commit | Trạng thái |
| --- | --- | --- | --- |
| #65 ma trận thu hồi | #197 | `f1253bbf` | merged, issue closed |
| #66 E2E thu hồi | #198 | `eadd3f0f` | merged, issue closed |
| #67 coordinator thu hồi | #199 | `78a4c26d` | merged, issue closed |
| #68 crash recovery/reconcile | #201 | `c752b8a5` | merged, issue closed |
| #69 pending/error UI + refresh | #204 | `54b44078` | merged, issue closed |

- Main sau chuỗi US5: `54b44078b645cf1142f127178f2e525fa5fa70b2`.
  Sau đó main nhích qua năm PR ngoài chuỗi US5: #210 (`c5c7df32`, đồng bộ
  authorization/phân trang/refresh operation), #211 (`7cb68a86`, nâng
  timeout CI backend + integration lên 120 phút), #213 (`1c73075e`,
  giảm fixture lặp + chia CI: backend 2 tier, integration 2 project
  Playwright), #208 (`2ce4a5eb`, trang /personal/classes + sửa nhiễu
  409 ở `GuardianFixture.deleteUser`, không đổi hành vi US5) và #207
  (`b0e79b1c`, tối ưu test B1/B6 — dùng chung context, shared-center,
  tách tầng e2e-backend; 0 file production, không đổi hành vi US5).
  Cây nghiệm thu cuối là
  **`b0e79b1c1424938ad48e7853bc9cdd11aacf6335`** — bằng chứng
  full-suite trên đúng cây này là run CI của chính PR gate này
  (§3.3), bao phủ toàn bộ nội dung US5 cùng các thay đổi kèm theo.
- PR #203 (refactor backend, ngoài chuỗi US5) cũng đã merge trước đó:
  main `ae5c7628`; V020/V021 đã thuộc về #203. Migration cao nhất trên
  main hiện tại là V021; V022 đang được PR #212 (chưa land, issue #74)
  giữ — US7 lấy số trống tiếp theo theo thứ tự merge.
- **Sổ trên main: B62–B73 đủ 12 mục + C6** (file
  `docs/diem-chua-chac-chan-va-lech-spec.md`, 165 dòng, kiểm chứng trực
  tiếp nội dung qua API ngày 2026-11-10). Không thiếu mục nào trong dải US5.

## 2. Bản đồ phủ US5.1–US5.6

### Ma trận integration — `RevocationMatrixIT` (6 test, #65)

| Scenario | Test method | Kết quả |
| --- | --- | --- |
| US5.1 gỡ phân công giảng dạy | `revokedTeacherLosesRosterRead` | 6/6 xanh (xem §3.1) |
| US5.2 gỡ membership tại A | `removedMemberLosesWorkspaceAccess` | 6/6 xanh |
| US5.3 gỡ ghi danh | `unenrolledStudentLosesResultRead` | 6/6 xanh |
| US5.4 thông tin giả không mở quyền | `forgedTenantIdentityIsRejected` | 6/6 xanh |
| US5.5 nguồn OR độc lập | `revokingOneSubjectKeepsOtherSubject` | 6/6 xanh |
| US5.6 202/fail-closed | `revokeReturns202UntilCompletion` | 6/6 xanh |

Phạm vi ma trận ghi rõ trong javadoc đầu lớp (đã đọc trực tiếp file trên
worktree `test-perf-round2`): **không bao phủ** cạnh tranh thu hồi đồng
thời nhiều partition (đã có `ConcurrentRevocationIT`, tái dùng); khôi phục
sau lỗi ở biên worker (đã có `ProjectionRecoveryIT`); lỗi
directory/graph/commit ép buộc (không lái được qua route HTTP công khai —
Sổ B62); action chấm/công bố mẫu là US7 (Sổ B63); **case share hoãn sang
gate US7 (Sổ B64) — không tính là pass ở US5**.

### E2E — `frontend/tests/e2e/revocation.spec.ts` (6 test, #66/#69)

| Scenario | Kết quả lượt local làn 2 (log trên đĩa) |
| --- | --- |
| US5.1 gỡ phân công, phiên cũ bị từ chối | passed (3,1 phút) |
| US5.2 gỡ membership, mất quyền ở A, còn quyền ở B | passed (3,6 phút) |
| US5.3 gỡ ghi danh, mất quyền đọc kết quả, lịch sử còn | passed (7,3 phút) |
| US5.4 thông tin giả không mở quyền | passed (2,6 phút) |
| US5.5 nguồn độc lập còn nguyên | passed (2,9 phút) |
| US5.6 202 không phải thành công | **đỏ — phân loại ở §3.2, không phải lỗi nghiệp vụ** |

Tổng lượt 2: **5/6, 39,1 phút** (log
`~/workspace/logs-issue69/playwright.log`, đọc trực tiếp từ đĩa).

## 3. Kết quả chạy thật đã có bằng chứng

### 3.1 Full IT — ma trận 6/6 + các lớp liên quan xanh

- Log `~/workspace/logs-fix200/it-full-v2.log` (lượt full IT trên cây
  `ab9b89a4` = main `8b011aeb` + sửa #200, chạy trước khi #203/#69 land):
  `RevocationMatrixIT` **6/0/0/0** (309,4s);
  `GuardianAccessIT` 14/14; `GuardianLinkServiceIT` 8/8;
  `GuardianRawConstraintsIT` 5/5; `PublishedResultContractIT` 7/7;
  `PublishedResultServiceIT` 8/8. Lượt này dừng ở 361 test với 1 failure
  duy nhất là `ResultSourceMutationIT` case [2] ở khâu **dựng** scenario
  (chờ operation quá Awaitility 90s, không ở khẳng định thu hồi) + 1 error
  SMTP nền đã biết của `RecoveryFlowIT`; phần suite còn lại chưa chạy.
  Case [2] đó chạy riêng sau đó xanh 5/5
  (`~/workspace/logs-fix200/it-rsm-v2.log`, 579,7s) — củng cố là nhiễu
  tải, không phải lỗi thu hồi.
- Lượt gate B1 (`~/workspace/logs-perf-round2/b1-gate2-full3.log`,
  382/0/1-SMTP/9) **KHÔNG được tính làm bằng chứng ma trận**: đối chiếu
  danh sách lớp chạy trong log với cây hiện tại cho thấy lượt này chạy
  trên cây trước khi `RevocationMatrixIT` có mặt (thiếu đúng lớp này;
  3 lớp Native\*IT còn lại bị profile integration loại trừ theo thiết
  kế). Lỗi 1 duy nhất trong lượt đó đúng là SMTP `RecoveryFlowIT` đã
  biết (không có Mailpit native).
- **Bằng chứng full-suite trên cây nghiệm thu cuối là CI (§3.3), không
  phải hai lượt local trên.**

### 3.2 US5.6 đỏ ở E2E local — nhiễu tải ở khâu dựng scenario, không phải lỗi code

- Điểm đỏ duy nhất trong `playwright.log` lượt 2: test US5.6 rớt tại
  `choOperationThanhCong` (ngưỡng poll 60s) trong `capHocSinhDayDu`
  (dòng 624), tức ở **khâu dựng scenario**, trước mọi khẳng định nghiệp
  vụ của test (dòng test 1253, khẳng định ở 1262 chưa từng chạy tới).
  US5.1 đã đỏ lượt 1 vì cùng chữ ký nay đã xanh ở lượt 2 — mẫu nhiễu
  tải lặp lại, đổi test đỏ theo lượt.
- Theo bàn giao đội #69 (chưa kiểm chứng lại độc lập — schema
  `e2e_29643_21546` trên làn 2 đã mất sau lần VM restart gần nhất, làn 2
  chưa dựng lại nên không tra lại được): operation
  `2aca9c9e-3888-4093-9828-49dae0ebcf9a` đã **SUCCEEDED,
  attempt_count=1, last_error_code NULL**; tạo 13:47:52Z, hoàn tất
  14:01:44Z (~14 phút vì máy chạy đè full IT #200 + B6); toàn suite 39,1
  phút so với mốc 12,7 phút của #67.
- Kết luận phân loại: **không sửa code, không nới ngưỡng poll**. Hành vi
  US5.6 ở tầng backend đã được khóa bởi `RevocationMatrixIT` 6/6 (§2);
  phần E2E của US5.6 sẽ được chạy lại trên CI (§3.3), nơi máy không chịu
  tải đè local.

### 3.3 CI — chế độ phán quyết hiện hành

- PR #204, head `838eb468`: kiểm chứng trực tiếp check-runs —
  **backend success, integration success, frontend success (3/3)**.
  Job backend chạy `clean verify` + `-Pintegration verify` (full IT,
  gồm `RevocationMatrixIT` — lớp này nằm trong profile integration mặc
  định, đã chứng minh bằng lượt `it-full-v2`); job integration chạy E2E
  gồm `revocation.spec.ts` với `E2E_SCHEMA=e2e_ci`.
  - Lưu ý: cây PR head (`0469a51d`) khác cây merge main (`2dda777e`)
    đúng phần diff của PR #203 (PR #204 tách nhánh trước khi #203 land).
    Vì vậy CI của PR #204 **không phải** bằng chứng full-suite trên cây
    main cuối — nó chỉ chứng minh #69 + nền trước #203.
  - Lỗi CI lượt đầu của #204 (integration failure: 6 test E2E đỏ vì spec
    seed đọc `E2E_SCHEMA` mà workflow không đặt) đã sửa gốc trong chính
    PR: `run-backend.sh` dùng `${E2E_SCHEMA:-e2e_${RANDOM}_${RANDOM}}`,
    `ci.yml` đặt `E2E_SCHEMA: e2e_ci`. Đây là lỗi tiềm ẩn trên main từ
    thời CI tắt, không do #69.
- **CI trên main, cây `54b44078`, run `42639091726`: kết luận
  `cancelled`.** Frontend + backend success; job integration bị huỷ ở
  mốc `timeout-minutes: 25` sau khi E2E chạy xong 48 test passed — không
  phải lỗi code, mà là timeout CI cũ. Run này không đủ làm bằng chứng.
- **CI trên main, cây nghiệm thu cuối `1c73075e`, run `42665897288`:
  kết luận `success` — cả 7 job đều `completed success`: `backend
  (it-pure)`, `backend (e2e-backend)`, `backend` (tổng hợp), `frontend`,
  `integration (chromium-core)`, `integration
  (chromium-teaching-guardians)`, `integration` (tổng hợp) (kiểm chứng
  trực tiếp qua GitHub API ngày 2026-11-10).** Đây là bằng chứng full
  IT + E2E trên đúng cây nghiệm thu cuối, và là bằng chứng còn thiếu
  cuối cùng của gate này. Run trước đó trên cây `7cb68a86`
  (`42658331608`) cũng đã xanh 3/3, trước khi main nhích qua #213.
- **CI trên main, cây `2ce4a5eb`, run `42669968396`: kết luận
  `failure` — không phải lỗi code.** 5 job thành phần đều `completed
  success` (`frontend`, `backend (it-pure)`, `backend (e2e-backend)`,
  `integration (chromium-core)`, `integration
  (chromium-teaching-guardians)`); 2 job tổng hợp `backend` và
  `integration` không hề được tạo (danh sách job chỉ có 5, không có
  check-run nào tên đó — kiểm chứng trực tiếp qua GitHub API ngày
  2026-11-10). Nguyên nhân: sự cố GitHub Actions — component Actions ở
  trạng thái `major_outage`, incident `tp8cghg7tlc4` đang điều tra
  (kiểm chứng qua githubstatus.com ngày 2026-11-10 ~01:20). Run này
  không đủ làm bằng chứng.
- **CI của PR gate này, trên cây nghiệm thu trước `2ce4a5eb` +
  file này, run `42674416410`: kết luận `success` — cả 7 job đều
  `completed success` (`backend (it-pure)`, `backend (e2e-backend)`,
  `backend` (tổng hợp), `frontend`, `integration (chromium-core)`,
  `integration (chromium-teaching-guardians)`, `integration` (tổng hợp),
  kiểm chứng trực tiếp qua GitHub API ngày 2026-11-10).** Đây từng là
  bằng chứng full IT + E2E trên cây nghiệm thu lúc đó (cây PR chỉ khác
  main `2ce4a5eb` đúng file tài liệu này). Main sau đó nhích qua #207
  (chỉ đổi test, không chạm code production — xem §1) nên PR gate
  rebase lên `b0e79b1c`; hai run CI trên đúng cây nghiệm thu cuối đã
  điền bổ sung vào đây sau khi merge:
  - **CI của PR gate này, trên cây nghiệm thu cuối `b0e79b1c` +
    file này, run `42681617877`: kết luận `success` — cả 7 job đều
    `completed success` (`backend (it-pure)`, `backend (e2e-backend)`,
    `backend` (tổng hợp), `frontend`, `integration (chromium-core)`,
    `integration (chromium-teaching-guardians)`, `integration` (tổng hợp),
    kiểm chứng trực tiếp qua GitHub API ngày 2026-11-10).** Đây là bằng
    chứng full IT + E2E trên đúng cây nghiệm thu cuối (cây PR chỉ khác
    main `b0e79b1c` đúng file tài liệu này).
  - **CI trên main, cây nghiệm thu cuối `b0e79b1c`, run `42679274082`:
    kết luận `success` — cả 7 job đều `completed success` (cùng 7 job
    như trên, kiểm chứng trực tiếp qua GitHub API ngày 2026-11-10).**
    Đây là bằng chứng full IT + E2E trên đúng cây main đã nghiệm thu.

### 3.4 Kiểm chứng tĩnh của #69 (frontend)

- Log `~/workspace/logs-issue69/`: `npm test` **77 passed / 10 file**,
  lint **0 error/warning**, build frontend/backend đạt (đọc trực tiếp từ
  log trên đĩa). Review lại PR #204 trước merge: 0 Must, 0 Should, kết
  luận Ship (theo bàn giao đội land).

## 4. Thứ tự quyết định–commit–revoke và không actor override

- **Quyết định → commit → revoke**: `SourceRevocationCoordinator` (#67,
  PR #199) — mọi đường đổi nguồn ghi kèm toàn bộ bundle kết quả trong
  cùng operation/transaction; guardian giữ nguyên theo B58. Sửa gốc B67
  đã nêu trong Sổ; E2E revocation khóa thứ tự ở tầng người dùng (manager
  khác revoke, actor cũ gọi lại bằng phiên cũ bị từ chối từ yêu cầu tiếp
  theo — US5.1/US5.2 xanh).
- **Không actor override**: US5.4 (ma trận + E2E đều xanh) — header
  `X-Tenant-Id` giả, role giả, id ngoài phạm vi đều bị chặn; quyền chỉ
  xuất phát từ nguồn đã provision, không từ thông tin tự khai trong
  body/header (FR-051). Quy tắc đọc rỗng/403 chốt ở Sổ B56: chính học
  sinh tự đọc kết quả thì nhận tập được phép (có thể rỗng, HTTP 200);
  actor khác mà tập rỗng thì 403.
- **ACCOUNT pending chặn mọi nghiệp vụ; A revoke không fence toàn tài
  khoản gây mất B**: khóa bởi US5.2 (quyền ở B còn nguyên trong cùng
  phiên sau khi gỡ membership ở A) + `ConcurrentRevocationIT` 16/16 và
  `ProjectionFenceGateIT` 6/6 trong lượt full IT (§3.1).

## 5. Kết luận gate #70 — ĐẠT

1. **CI main run `42665897288` trên cây `1c73075e` xanh 7/7 job**
   (3 check bắt buộc backend / frontend / integration) — **đã đạt**
   (§3.3). Main sau đó nhích qua #208 và #207 (không đổi hành vi US5);
   bằng chứng full-suite trên đúng cây nghiệm thu cuối `b0e79b1c` là
   run CI của chính PR gate này (`42681617877`, xanh 7/7) và run CI
   main trên cùng cây (`42679274082`, xanh 7/7) — đã điền đủ ở §3.3.
2. Sổ B62–B73 + C6 đủ trên main, ma trận 6/6, E2E 5/6 với US5.6 đỏ là
   nhiễu tải đã phân loại (§3.2) — **đã đạt**.
3. File này (`specs/001-multitenant-access/validation/us5.md`) được đưa
   lên main qua PR gate; sau khi PR gate CI xanh và merge thì đóng #70
   và mở US6 (#71/#72) theo Issue #2.

## 6. Ghi chú cho gate US7 (không chặn gate này)

- Case share trong ma trận hoãn sang gate US7 (Sổ B64): gate #88 chạy
  lại cùng suite sau khi US7 triển khai share; không coi chưa triển
  khai share là test đã pass ở US5.
- Bổ sung grade vào US5.1 khi US7 land (Sổ B63); chạy lại US5.1 bằng lời
  gọi chấm thật (Sổ B62/B68); khôi phục đường seed sample-action trong
  E2E (Sổ B60).
- #200 (issue #194, ngoài chuỗi US5) vẫn chưa land tại thời điểm nghiệm
  thu — không thuộc phạm vi nghiệm thu #70; không chặn gate US5.
