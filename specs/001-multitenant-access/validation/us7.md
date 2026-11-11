# Nghiệm thu US7 — Giữ quyền nguồn khi lưu và chia sẻ học liệu (gate #88)

Mọi kết quả dưới đây là bằng chứng chạy thật: integration test trên stack TEST native local
(PostgreSQL + Keycloak thật, làn riêng) và truy vấn trực tiếp trạng thái Keycloak/DB sau chạy.
Không scenario nào được tick pass từ tài liệu.

## 0. Trạng thái vòng nghiệm thu lại (main `0625003`, PR #278 đã land)

- Base nghiệm thu lại: `main` tại `06250030d07a9ed4708c17f8e2b50f1532af01cb` (merge của PR #278:
  sửa defect provisioning group managers, Sổ B104).
- Vòng 1 (trên main `cb5bc1ef`, xem §7): **KHÔNG ĐẠT** — defect managers (§7.3).
- Vòng này (lượt chạy r1 + chẩn đoán + lượt chạy r2, stack lane 2): **KHÔNG ĐẠT** — defect
  managers đã hết (xem §6), nhưng xuất hiện hai defect production khác (§3, §4).
- Issue #88 đã bị GitHub tự động đóng lúc 06:43:49Z, chỉ 2 giây sau khi PR #278 merge, với
  0 comment — do thân PR #278 có cụm từ khớp bộ máy từ khoá đóng issue của GitHub ("close
  #88" trong câu phủ định vẫn khớp). Việc đóng diễn ra TRƯỚC khi có kết luận nghiệm thu lại.
  Kết luận của vòng này (§9): issue #88 **cần mở lại** vì gate vẫn chưa đạt.

## 1. Chuỗi land US7 — trạng thái chốt

| Task | Issue | Trạng thái |
|---|---|---|
| T077–T082 (schema/model, save/fork/read, share/accept/revoke, correction, sample actions nền) | #79–#84 | Đã đóng; code trên main qua các đợt land nền tảng, chốt ở squash PR #262 (`299f486`) |
| T083 sample action assign/submit/grade/publish | #85 | Đã đóng — PR #269, main `48f2cc1`, Sổ B88 |
| T084 expose body/answer + capabilities | #86 | Đã đóng — PR #270, main `d444d0d`, Sổ B89 |
| T085 review panel | #87 | Đã đóng |
| Sửa defect provisioning group managers | — | Đã đóng — PR #278, main `0625003`, Sổ B104; kiểm chứng hết ở §6 |
| T075 integration/contract matrix US7 | #77 | **Còn mở** — test ở PR #218 (mở, đỏ theo thiết kế test-first); đã port vào PR gate này, xem §2/§5 |
| T076 E2E resource actions | #78 | **Còn mở** — test ở PR #216 (mở, đỏ); file E2E chưa có trên main, xem §5 |
| T086 gate US7 | #88 | File này — **KHÔNG ĐẠT** vòng nghiệm thu lại, xem §9 |

## 2. Bản đồ phủ và kết quả từng scenario — vòng nghiệm thu lại

Nguồn test: `ResourceAccessIT` (5 test) và `ResourceSharingIT` (6 test) port nguyên assertion
hành vi từ PR #218 vào PR gate này, chạy trên stack TEST native local (làn 2).
Log: `~/workspace/logs-gate88/it-lane2-reeval-r1.log` (lượt gộp ba class),
`~/workspace/logs-gate88/it-lane2-reeval-r2.log` (chạy lại `ResourceAccessIT` với tiền đề
đính chính đã sửa, xem §5).

Lượt r1 — failsafe `Tests run: 17, Failures: 5, Errors: 0` (unit 238/238; `RevocationMatrixIT`
6/6, 198,1s; `ResourceAccessIT` 4/5, 62,48s; `ResourceSharingIT` 2/6, 89,19s).

| Scenario | Test | Kết quả vòng này |
|---|---|---|
| US7.1 lưu system version giữ định danh/chủ nguồn | `ResourceAccessIT.savingSystemVersionKeepsSourceIdentityAndOwner` | **ĐẠT** |
| US7.2 (nửa cập nhật) bản đã lưu đứng yên ở V1, chủ động nhận V2 | `ResourceAccessIT.savedCopyStaysOnOldVersionUntilUpdateIsReceived` | **ĐẠT** |
| US7.2 (nửa đính chính) thông báo đính chính chỉ tới nơi đã nhận version bị lỗi | `ResourceAccessIT.correctionReachesOnlyRecipientsOfAffectedVersion` | **FAIL** — defect §4 (đã dựng được tiền đề đúng, xem §5) |
| US7.3 fork có định danh mới + provenance; use-only không fork được | `ResourceAccessIT.forkCreatesNewIdentityWithProvenanceAndUseOnlyCannotFork` | **ĐẠT** |
| Hợp đồng phụ: body/answer là hai lần đọc tách quyền | `ResourceAccessIT.bodyAndAnswerAreSeparateReads` | **ĐẠT** |
| US7.4 share phải được accept trước khi trung tâm nhận dùng được | `ResourceSharingIT.shareMustBeAcceptedBeforeRecipientCenterCanUse` | **FAIL** — defect §3 (dòng 129: đọc thân bài sau accept) |
| US7.5 version giới hạn một trung tâm không thoát qua save/fork | `ResourceSharingIT.versionRestrictedToOneCenterCannotEscapeThroughSaveOrFork` | **FAIL** — defect §3 (dòng 236: fork tại nơi nhận) |
| US7.6 revoke share REVOCABLE chặn bản gốc + bản phái sinh, giữ lịch sử | `ResourceSharingIT.revokingRevocableShareBlocksOriginalAndDerivedCopiesButKeepsHistory` | **FAIL** — defect §3 (dòng 346: fork tại nơi nhận; save trước đó đạt) |
| US7.7 share LONG_TERM sống sót khi người chia sẻ rời đi; không đơn phương revoke | `ResourceSharingIT.longTermShareSurvivesGrantorExitAndRejectsUnilateralRevoke` | **FAIL** — defect §3 (dòng 467: đọc thân bài sau accept) |
| US7.8 giáo viên chưa có gói cá nhân lưu tại trung tâm, không mở kho cá nhân | `ResourceSharingIT.teacherWithoutPersonalPlanSavesAtCenterButNotIntoPersonalLibrary` | **ĐẠT** |
| US7.9 đề gộp bị từ chối khi chỉ một thành phần thiếu quyền (many-source ALL) | `ResourceSharingIT.sharingCompositeExamRequiresPermissionFromEveryComponent` | **ĐẠT** (vòng 1 fail ở SHARE đầu tiên vì defect managers; vòng này share chạy qua, đề gộp bị từ chối đúng như kỳ vọng) |

US5 chạy lại (share dependency, fork, nguồn quyền độc lập):

- `RevocationMatrixIT` trên main (6 test: thu hồi giáo viên mất quyền đọc roster, thành viên bị
  gỡ mất quyền workspace, học sinh rút ghi danh mất quyền đọc kết quả, định danh tenant giả bị
  từ chối, revoke một môn giữ môn còn lại, revoke trả 202 tới khi hoàn tất): **6/6 ĐẠT** khi chạy
  lại trên stack local của gate (198,1s).
- Phần US5 phụ thuộc luồng share (revoke qua bản nhận và fork) chính là test US7.6 ở trên:
  **FAIL** vì defect §3 — chưa kiểm chứng được trên main hiện tại.

## 3. Defect production 1: sau copy-on-share, target version nguồn tại nơi nhận chỉ còn `library.save`

**Hiện tượng:** SHARE_VERSION và ACCEPT_SHARE đều thành công (grant ở trạng thái ACCEPTED),
nhưng mọi lượt dùng quyền license tại nơi nhận đều bị 403 PERMISSION_DENIED: giáo viên của
trung tâm nhận đọc thân bài (US7.4, US7.7), fork version đã share (US7.5, US7.6). Riêng
SAVE_VERSION vẫn đạt — vì `library.save` là scope duy nhất còn lại trên target đó (xem §3.4).

**Cơ chế (đã chứng minh bằng trạng thái Keycloak/DB trực tiếp, xem §3.1–§3.4):**

1. Khi accept, `ResourceShareService.acceptShare` (`ResourceShareService.java:377`) vật chất
   hoá một bản sao copy-on-share tại workspace nhận (`copySourceVersion`, dòng 473) kèm mục
   thư viện LICENSED trỏ bản sao. Bản sao có cạnh provenance `origin=SHARE` mang đúng grant.
2. `ResourcePolicyProjector.licenseSources` (`ResourcePolicyProjector.java:691-708`): khi
   `copies.grantsWithCopyAt(...)` thấy đã có bản sao, nhánh `copied` (dòng 700) chỉ phát
   descriptor `library.save` trên target của version nguồn — comment ghi "quyền đọc/dùng sống
   trên target của bản sao".
3. Target của bản sao đúng là có đủ quyền: nhánh `derivedLicenseSources`
   (`ResourcePolicyProjector.java:503`, scope `content.read`/`use` trong
   `DERIVED_LICENSE_SCOPES` dòng 112) chiếu `content.read` + `use` lên target bản sao.
4. Nhưng **không đường request nào hỏi PDP về target bản sao**: `ResourceLibraryService.readVersion`
   (`ResourceLibraryService.java:136-145`, dùng chung cho `readBody`/`readAnswer`) và
   `forkVersion` (dòng 331-342) đều dựng target key từ (resource, version) trong URL/payload —
   tức version nguồn. Chỉ `saveVersion` có `resolveSaveTarget` phân giải sang bản sao cho việc
   ghi mục thư viện (dòng 267-290), còn quyết định PDP của save vẫn hỏi trên target nguồn với
   scope `library.save` — scope duy nhất còn đó — nên save đạt.
5. Hệ quả: PDP đánh giá một permission không tồn tại trên target nguồn → 403. Thu hồi grant
   vẫn đúng về mặt thiết kế (cả hai target đều mất nguồn khi grant không còn ACCEPTED), nên
   hướng sửa không được phá bất biến đó.

**Bằng chứng (chẩn đoán tạm `ShareDiagnosisIT`, không commit; log
`it-lane2-diag-r5.log`, `it-lane2-diag-r6.log`):**

- Grant ACCEPTED, mode REVOCABLE, actions `{content.read,use,library.save}`, `allowed=[B]`.
- Group `/gk/workspaces/<B>/members` chứa cả owner B và teacher B; group `managers` tồn tại
  dạng rỗng (do #278 tạo, xem §6). Membership không phải nguyên nhân.
- Projection của target version nguồn tại B: state READY, desired_hash == applied_hash,
  `keycloak_resource_id=2cbf0bd6-...`; danh sách permission của resource đó chỉ có đúng một
  mục `gk-root:...#library.save` — không có `content.read`/`use`/`fork`.
- Projection của target bản sao: state READY; policy evaluation cho teacherB với
  `content.read` cho thấy aggregate license (`gk-source:34b88509...`) **PERMIT**, chỉ có gate
  eligibility (`gk-group:/gk/identity/eligible`) DENY tại thời điểm soi — và gate đó là đúng
  thiết kế: teacherB chưa mở phiên lúc soi; sau khi mở phiên thì eligibility = true và
  SAVE_VERSION trả 201 (`it-lane2-diag-r6.log`). Phía license hoàn toàn khoẻ.
- Đối chứng quyết định: owner B (đã eligible, thành viên `members`) đọc thân bài cũng 403 —
  loại trừ nguyên nhân membership/eligibility, chỉ còn thiếu permission trên target nguồn.

**Phạm vi ảnh hưởng:** mọi lượt dùng version đã share tại workspace nhận sau khi accept
(đọc thân bài/đáp án, dùng, fork) — tức toàn bộ giá trị sử dụng của share sau accept. Đây là
đường production, không phải lỗi fixture/test.

**Hướng sửa đề xuất (thuộc đội sửa, gate không tự sửa):** giữ đủ scope của grant trên target
version nguồn kể cả khi đã có bản sao — nới nhánh `copied` trong `licenseSources`
(`ResourcePolicyProjector.java:700-708`) để phát toàn bộ scope grant cấp trên target nguồn,
trong khi target bản sao giữ nguyên các scope phái sinh của nó. Mọi đường request hiện tại
đều hỏi target nguồn nên cách này không đụng các đường đó; thu hồi vẫn chặn cả hai target vì
cả hai nhánh đều yêu cầu grant ACCEPTED. Phương án thay thế (đổi mọi đường đọc/fork/use sang
phân giải target bản sao như `resolveSaveTarget`, đồng thời bổ sung `fork`/`library.save` vào
`DERIVED_LICENSE_SCOPES`) xâm lấn hơn và đổi định danh target của các lần đọc — chỉ chọn nếu
đội sửa chứng minh phương án 1 phá bất biến copy-on-share. Kèm test hồi quy: share → accept
→ giáo viên nơi nhận đọc thân bài, fork, save đều đạt (chính 4 test của §2).

## 4. Defect production 2: CORRECT_SOURCE luôn 500 vì ép kiểu `created_at`

**Hiện tượng:** với tiền đề đúng (share → accept → save, xem §5), `CORRECT_SOURCE` của tác giả
tại center nguồn vượt qua kiểm tra PDP `review`, ghi notice xong rồi 500 INTERNAL_ERROR;
toàn bộ action rollback, không có notice nào tồn tại. Lượt r2: `ResourceAccessIT` 4/5, test
đính chính fail tại dòng 475 (`CORRECT_SOURCE`), `traceId 8e4ea6ec-b488-41a1-a9a7-d23174d70785`.

**Cơ chế (stack trace trong `it-lane2-reeval-r2.log`):**

```
java.lang.ClassCastException: class java.sql.Timestamp cannot be cast to class java.time.OffsetDateTime
    at com.giaokhoa.resources.CorrectionService.noticeView(CorrectionService.java:206)
    at com.giaokhoa.resources.CorrectionService.applyCorrection(CorrectionService.java:186)
    at com.giaokhoa.resources.CorrectionService.lambda$correctSource$2(CorrectionService.java:155)
    ...
    at com.giaokhoa.resources.CorrectionService.correctSource(CorrectionService.java:137)
```

`CorrectionService.noticeView` (dòng 196-206) đọc `created_at` của `correction_notice` bằng
`jdbc.queryForMap` rồi ép trực tiếp `(OffsetDateTime) row.get("created_at")`. Cột
`created_at` là `timestamp with time zone` (`V001__system_baseline.sql:1309`), nhưng driver
JDBC trả về `java.sql.Timestamp` qua `queryForMap` trong môi trường này — ép kiểu vỡ ngay lần
`CORRECT_SOURCE` thành công đầu tiên. Đây là lần duy nhất trong code production ép kiểu
`OffsetDateTime` từ `queryForMap` (grep toàn `backend/src/main/java` chỉ có đúng chỗ này).
Chưa từng bị bắt trước đây vì chưa có lượt chạy nào tới được `CORRECT_SOURCE` thành công
(vòng 1 kẹt ở tiền đề save, xem §5).

**Phạm vi ảnh hưởng:** mọi lượt phát hành đính chính nguồn (FR-033/FR-034) đều 500 — đường
đính chính chưa từng chạy thành công đầu-cuối trên production.

**Hướng sửa đề xuất:** đọc `created_at` theo cách không phụ thuộc kiểu trả về của driver —
vd `row.get("created_at")` rồi chuyển đổi an toàn (`Timestamp` → `toInstant().atOffset(UTC)`,
`OffsetDateTime` giữ nguyên), hoặc dùng `queryForObject` với `RowMapper`/`getObject(...,
OffsetDateTime.class)`. Kèm test hồi quy: `CORRECT_SOURCE` thành công trả metadata notice với
`createdAt` đúng, rồi `RECEIVE_UPDATE` + đọc bản đính chính đạt (chính test đính chính §5).

## 5. US7.2 nửa đính chính: tiền đề đã sửa, tới được CORRECT_SOURCE, gặp defect §4

Vòng 1 test đính chính fail tại dòng 444 — lời gọi SAVE_VERSION của người nhận tại workspace
cá nhân, vì version thuộc sở hữu của một trung tâm và người lưu là người ngoài không có
grant/assignment/activity nào: `ResourcePolicyProjector.buildBundle` cho ngữ cảnh đó trả bundle
rỗng, PDP từ chối `library.save` (đúng mô hình: version của trung tâm chỉ tới được workspace
khác qua owner/tác giả, phân công, hoạt động, hoặc grant ACCEPTED).

Vòng này tiền đề đã dựng lại bằng đường share hợp lệ (commit trên nhánh PR gate này, cùng
PR): tác giả share version center → workspace cá nhân của người lưu (REVOCABLE,
`content.read`/`use`/`library.save`), người lưu accept rồi mới SAVE_VERSION. Lượt r2 cho thấy
share → accept → save đều đạt; `CORRECT_SOURCE` tới được service (quyết định PDP `review`
của tác giả tại center đạt) nhưng 500 vì defect §4, nên các assertion `RECEIVE_UPDATE` và đọc
bản đính chính chưa tới được. Nửa đính chính của US7.2 vì vậy chuyển từ CHƯA KIỂM CHỨNG ĐƯỢC
(vòng 1) thành **FAIL** với defect §4 — không còn bị chặn ở tiền đề nữa.

## 6. Kiểm chứng sửa defect managers của PR #278 — ĐÃ HẾT

- Chữ ký cũ `Realm group is not provisioned`: **0 lần** trong toàn bộ log lượt r1.
- SHARE_VERSION và ACCEPT_SHARE đều thành công trong cả 6 test share; grant về ACCEPTED.
- Group `managers` của trung tâm nhận được tạo dạng rỗng trước khi áp bundle (đúng thiết kế
  `ensureReferencedGroups` của #278, Sổ B104) — kiểm kê trực tiếp trên Keycloak làn 2.
- US7.9 chuyển từ FAIL (vòng 1, gục ở SHARE đầu tiên) sang **ĐẠT** vì share chạy qua được.
- Defect §3/§4 là tầng lỗi mới sau accept/đính chính, không phải hồi quy của #278.

## 7. Lịch sử vòng 1 và PR test-first #216/#218

Vòng 1 (main `cb5bc1ef`, base `d444d0db`): ĐẠT — US7.1, US7.2 (nửa cập nhật), US7.3, US7.8,
hợp đồng body/answer tách quyền; US5 `RevocationMatrixIT` 6/6. FAIL — US7.4, US7.5, US7.6,
US7.7, US7.9 do defect provisioning group `managers`: `SharePolicyProjector` phát vô điều
kiện descriptor group `managers` của trung tâm nhận; `PolicyGraphWriter.realmGroupId`
(`PolicyGraphWriter.java:772`) đòi group tồn tại sẵn và ném `IllegalStateException: Realm
group is not provisioned: /gk/workspaces/<uuid>/managers`; group chỉ tạo khi có user join;
bundle grant nằm trên GRANT partition nên đường sync group của WORKSPACE partition không chạy.
Bằng chứng Keycloak lúc đó: 7 workspace có group, **0 workspace có `managers`**. Defect này
đã được PR #278 sửa và kiểm chứng hết ở §6.

- PR #218 (IT của #77): hai file `ResourceAccessIT`/`ResourceSharingIT` cùng `ResourceFixture`
  được port vào PR gate này, giữ nguyên assertion hành vi. Điều chỉnh thuần hạ tầng:
  (1) mẫu dọn schema sau class theo pattern hiện hành trên main (`ProjectionRecoveryIT`) thay
  cho helper đã bị loại bỏ; (2) javadoc class cập nhật theo trạng thái đã cài đặt; (3) fixture
  `seedDependency` bổ sung `usage_constraints` theo đúng định dạng production mà migration của
  bảng `resource_dependency` yêu cầu (marker `origin=COMPOSITION` trung thực — production chỉ
  diễn giải provenance khi marker là `origin=SHARE`); (4) test composite (US7.9) thêm một assert
  trực tiếp truy vấn DB (đề bị từ chối không để lại grant) vì cổng PMD của repo yêu cầu test có
  assert trực tiếp — assert này khóa thêm bất biến, không đổi assertion hành vi gốc;
  (5) sau khi PR #271 land (test chạy bằng runtime role), bước dọn schema cuối class đổi sang
  `migratorJdbc()` theo đúng pattern hiện hành của `ProjectionRecoveryIT`.
  Vòng này bổ sung: (6) tiền đề test đính chính đổi sang share → accept → save (xem §5).
- PR #216 (E2E của #78): file `frontend/tests/e2e/resource-permissions.spec.ts` **chưa có trên
  main**, PR còn mở và đỏ. Bằng chứng E2E giao diện cho US7 vẫn thiếu; gate này không dựa vào
  nó và không đóng/merge cả hai PR.
- Nợ quy ước đã biết của bản port: hai file IT giữ tên method dài theo PR #218, chưa đổi sang
  mẫu tên ngắn + `@DisplayName` tiếng Việt như phần lớn IT trên main. Gate giữ nguyên để báo
  cáo failsafe của các lượt chạy khớp từng ký tự với file trong PR.

## 8. Rà lại hai điểm Sổ B88/B89

- B88(iv): cổng CORRECT_SOURCE hai lớp fail-closed vẫn đứng như Sổ ghi; việc dựng bundle target
  `resource:{id}` để chuyển sang `version.create` vẫn thuộc luồng soạn thật (chưa có trong
  US7). Vòng này xác nhận thêm: quyết định PDP `review` của CORRECT_SOURCE đạt; lỗi nằm ở
  mapping kết quả trong `noticeView` (§4), không phải ở cổng.
- B89(iv): route tạo fixture không idempotent, chấp nhận cho công cụ kiểm chứng — giữ nguyên
  đánh giá; không phát hiện thêm hệ quả nào tại gate này.

## 9. Kết luận gate #88 — KHÔNG ĐẠT

- ĐẠT: US7.1, US7.2 (nửa cập nhật), US7.3, US7.8, US7.9; hợp đồng body/answer tách quyền đạt;
  US5 chạy lại: ma trận thu hồi 6/6 đạt.
- FAIL thật trên production: US7.4, US7.5, US7.6, US7.7 — cùng một defect license/copy-on-share
  (§3); US7.2 nửa đính chính — defect ép kiểu `created_at` trong `CORRECT_SOURCE` (§4).
- Defect managers của vòng 1 đã hết (PR #278, §6); hai defect §3/§4 là tầng lỗi mới lộ ra sau
  khi share/accept và đính chính chạy được tới bước tiếp theo.
- Theo quy tắc gate: PR chứa bằng chứng này được để ở trạng thái draft, **không merge**,
  issue #88 **cần mở lại** (đã tự đóng trước kết luận, xem §0). Điều kiện nghiệm thu lại:
  sửa defect §3 (kèm test hồi quy share → accept → đọc/fork/save tại nơi nhận) và defect §4
  (kèm test hồi quy `CORRECT_SOURCE` thành công + `RECEIVE_UPDATE` + đọc bản đính chính), rồi
  chạy lại `ResourceSharingIT` + `ResourceAccessIT` trên main mới và đạt đủ.
