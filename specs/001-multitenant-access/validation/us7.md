# Nghiệm thu US7 — Giữ quyền nguồn khi lưu và chia sẻ học liệu (gate #88)

Base nghiệm thu: `main` tại `d444d0db054d560710e7afadab37b9b9606a5603` (merge của PR #270).
Trong thời gian chạy gate, `main` đã tiến tới `69c21e9` qua PR #271 (Wave 6, consolidation
migration/RLS) — PR #271 không chạm các file của defect ở §3 (projector/writer của mặt phẳng
chia sẻ), nhưng kết luận dưới đây chỉ khẳng định trên base `d444d0db`; nghiệm thu lại trên main
mới thuộc vòng gate sau khi sửa defect.

Mọi kết quả dưới đây là bằng chứng chạy thật: integration test trên stack TEST native local
(PostgreSQL + Keycloak thật, làn riêng) và truy vấn trực tiếp trạng thái Keycloak/DB sau chạy.
Không scenario nào được tick pass từ tài liệu.

## 1. Chuỗi land US7 — trạng thái chốt

| Task | Issue | Trạng thái |
|---|---|---|
| T077–T082 (schema/model, save/fork/read, share/accept/revoke, correction, sample actions nền) | #79–#84 | Đã đóng; code trên main qua các đợt land nền tảng, chốt ở squash PR #262 (`299f486`) |
| T083 sample action assign/submit/grade/publish | #85 | Đã đóng — PR #269, main `48f2cc1`, Sổ B88 |
| T084 expose body/answer + capabilities | #86 | Đã đóng — PR #270, main `d444d0d`, Sổ B89 |
| T085 review panel | #87 | Đã đóng |
| T075 integration/contract matrix US7 | #77 | **Còn mở** — test ở PR #218 (mở, đỏ theo thiết kế test-first); đã port vào PR gate này, xem §2/§5 |
| T076 E2E resource actions | #78 | **Còn mở** — test ở PR #216 (mở, đỏ); file E2E chưa có trên main, xem §5 |
| T086 gate US7 | #88 | File này — **KHÔNG ĐẠT**, xem §6 |

## 2. Bản đồ phủ và kết quả từng scenario

Nguồn test: `ResourceAccessIT` (5 test) và `ResourceSharingIT` (6 test) port nguyên assertion
hành vi từ PR #218 vào PR gate này (lý do port và các điều chỉnh hạ tầng: §5), chạy trên stack
TEST native local. Log: lượt gộp hai class — failsafe `Tests run: 11, Failures: 6, Errors: 0`.

| Scenario | Test | Kết quả |
|---|---|---|
| US7.1 lưu system version giữ định danh/chủ nguồn | `ResourceAccessIT.savingSystemVersionKeepsSourceIdentityAndOwner` | **ĐẠT** |
| US7.2 (nửa cập nhật) bản đã lưu đứng yên ở V1, chủ động nhận V2 | `ResourceAccessIT.savedCopyStaysOnOldVersionUntilUpdateIsReceived` | **ĐẠT** |
| US7.2 (nửa đính chính) thông báo đính chính chỉ tới nơi đã nhận version bị lỗi | `ResourceAccessIT.correctionReachesOnlyRecipientsOfAffectedVersion` | **CHƯA KIỂM CHỨNG ĐƯỢC** — bị chặn ở tiền đề, xem §4 |
| US7.3 fork có định danh mới + provenance; use-only không fork được | `ResourceAccessIT.forkCreatesNewIdentityWithProvenanceAndUseOnlyCannotFork` | **ĐẠT** |
| Hợp đồng phụ: body/answer là hai lần đọc tách quyền | `ResourceAccessIT.bodyAndAnswerAreSeparateReads` | **ĐẠT** |
| US7.4 share phải được accept trước khi trung tâm nhận dùng được | `ResourceSharingIT.shareMustBeAcceptedBeforeRecipientCenterCanUse` | **FAIL** — defect §3 |
| US7.5 version giới hạn một trung tâm không thoát qua save/fork | `ResourceSharingIT.versionRestrictedToOneCenterCannotEscapeThroughSaveOrFork` | **FAIL** — defect §3 |
| US7.6 revoke share REVOCABLE chặn bản gốc + bản phái sinh, giữ lịch sử | `ResourceSharingIT.revokingRevocableShareBlocksOriginalAndDerivedCopiesButKeepsHistory` | **FAIL** — defect §3 |
| US7.7 share LONG_TERM sống sót khi người chia sẻ rời đi; không đơn phương revoke | `ResourceSharingIT.longTermShareSurvivesGrantorExitAndRejectsUnilateralRevoke` | **FAIL** — defect §3 |
| US7.8 giáo viên chưa có gói cá nhân lưu tại trung tâm, không mở kho cá nhân | `ResourceSharingIT.teacherWithoutPersonalPlanSavesAtCenterButNotIntoPersonalLibrary` | **ĐẠT** |
| US7.9 đề gộp bị từ chối khi chỉ một thành phần thiếu quyền (many-source ALL) | `ResourceSharingIT.sharingCompositeExamRequiresPermissionFromEveryComponent` | **FAIL** — defect §3 (gục ở SHARE đầu tiên, trước assertion 403 của chính scenario) |

US5 chạy lại (share dependency, fork, nguồn quyền độc lập):

- `RevocationMatrixIT` trên main (6 test: thu hồi giáo viên mất quyền đọc roster, thành viên bị
  gỡ mất quyền workspace, học sinh rút ghi danh mất quyền đọc kết quả, định danh tenant giả bị
  từ chối, revoke một môn giữ môn còn lại, revoke trả 202 tới khi hoàn tất): **6/6 ĐẠT** khi chạy
  lại trên stack local của gate (382,8s).
- Phần US5 phụ thuộc luồng share (revoke qua bản nhận và fork) chính là test US7.6 ở trên:
  **FAIL** vì defect §3 — chưa kiểm chứng được trên main hiện tại.

## 3. Defect production: share tới trung tâm chưa từng có MANAGER gục vĩnh viễn

**Hiện tượng:** mọi action `SHARE_VERSION` tới một trung tâm nhận chưa từng có thành viên vai
MANAGER đều trả `503 AUTHORIZATION_NOT_READY`; operation share-sync fail ở cả 5 lần thử rồi
FAILED. Cả 5 test share ở §2 đều gục tại SHARE đầu tiên với cùng chữ ký này.

**Cơ chế (đã chứng minh bằng stack trace và trạng thái Keycloak thật):**

1. `SharePolicyProjector` (dòng 154–166) phát **vô điều kiện** descriptor group `owners` và
   `managers` của trung tâm nhận cho scope `accept` của grant ở trạng thái OFFERED;
   `ResourcePolicyProjector.gatesFor` biến chúng thành `Gate.GroupMembership`.
2. `PolicyGraphWriter.realmGroupId` (`PolicyGraphWriter.java:772`) đòi group phải tồn tại sẵn
   — writer không tự tạo group — và ném `IllegalStateException: Realm group is not provisioned:
   /gk/workspaces/<uuid>/managers`, caused by Keycloak 404 `Group path does not exist`
   (log app: `PROJECTION_RECONCILE_FAILED` lặp cho operation của share-sync).
3. Group realm chỉ được tạo qua `AuthorizationProvisioningClient.setRealmGroupMembership`
   (đường `ensureRealmGroup`) khi có user cần JOIN group đó;
   `CenterPolicyProjector.syncGroupMemberships` chỉ gọi khi membership lệch. Trung tâm chưa
   từng có MANAGER nào thì group `/managers` không bao giờ được tạo.
4. Bundle của grant nằm trên **GRANT partition**; `syncWorkspaceGroupMemberships` trong
   `ProjectionReconciler` (dòng 413) chỉ chạy cho partition kind WORKSPACE nên không có bước
   sync nào tạo group trước khi áp bundle grant. Comment tại `ProjectionReconciler.java:301`
   giả định "mọi descriptor đều có ít nhất một member ACTIVE" — sai với descriptor `managers`.

**Bằng chứng độc lập:** sau các lượt chạy, kiểm kê group trên Keycloak của stack: 7 workspace
có group, tất cả chỉ gồm `members`/`owners`/`roles` — **0 workspace có `managers`**.

**Phạm vi ảnh hưởng:** đây là đường production, không phải lỗi fixture — bất kỳ share nào tới
trung tâm chưa từng có manager đều không thể thực hiện. Hướng sửa (thuộc đội sửa, gate không
tự sửa): bảo đảm group của trung tâm nhận tồn tại trước khi áp bundle grant (provisioning trên
đường grant), hoặc chỉ phát descriptor `managers` khi trung tâm có ít nhất một manager ACTIVE,
hoặc cho writer áp dụng cùng cơ chế bảo đảm group như đường sync của WORKSPACE partition.

## 4. US7.2 nửa đính chính: bị chặn ở tiền đề, chưa tới được CORRECT_SOURCE

Test đính chính fail tại dòng 445 — lời gọi **SAVE_VERSION của người nhận tại workspace cá
nhân**, không phải `CORRECT_SOURCE` (chưa lời gọi nào tới được service đính chính: schema test
giữ lại sau chạy cho thấy không tồn tại operation `#correct` nào; operation `#save` SUCCEEDED
nhưng version target không hề được materialize trên Keycloak).

Nguyên nhân: version thuộc sở hữu của một **trung tâm**, người lưu là người ngoài trung tâm đó
và không có grant/assignment/activity nào — `ResourcePolicyProjector.buildBundle` cho ngữ cảnh
này trả bundle rỗng, nên quyết định PDP từ chối (fail-closed) `library.save`. Đây là hành vi
**đúng theo mô hình đã cài**: javadoc của `ResourcePolicyProjector` ghi rõ nguồn `library.save`
trước-khi-lưu chỉ tồn tại cho version **hệ thống đã LOCKED**; version của trung tâm chỉ tới
được workspace khác qua owner/tác giả, phân công, hoạt động, hoặc grant ACCEPTED (nhánh
license). Tiền đề của test (lưu trực tiếp version của trung tâm vào kho cá nhân của người
ngoài) không phải đường nhận hợp lệ trong mô hình này.

Hệ quả nghiệm thu: nửa đính chính của US7.2 **chưa kiểm chứng được** tại gate này — đường tạo
nơi nhận hợp lệ xuyên workspace chính là luồng share đang bị defect §3 chặn. Không có bằng
chứng nào cho thấy `CorrectionService` tự nó có lỗi (nó chưa từng được gọi trong các lượt
chạy); trên main hiện không có test nào phủ `CORRECT_SOURCE` thành công ngoài test này.
Khuyến nghị vòng sau: sửa defect §3 xong, dựng lại tiền đề test bằng share → accept → save
rồi chạy lại đúng test này.

## 5. PR test-first #216/#218 và các điều chỉnh khi port

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
  `migratorJdbc()` theo đúng pattern hiện hành của `ProjectionRecoveryIT` — lượt CI đầu của PR
  gate xác nhận lỗi `must be owner of schema` nếu dùng datasource ứng dụng, đã sửa trong cùng
  PR.
- PR #216 (E2E của #78): file `frontend/tests/e2e/resource-permissions.spec.ts` **chưa có trên
  main**, PR còn mở và đỏ. Bằng chứng E2E giao diện cho US7 vẫn thiếu; gate này không dựa vào
  nó và không đóng/merge cả hai PR.
- Nợ quy ước đã biết của bản port: hai file IT giữ tên method dài theo PR #218, chưa đổi sang
  mẫu tên ngắn + `@DisplayName` tiếng Việt như phần lớn IT trên main. Gate giữ nguyên để báo
  cáo failsafe của các lượt chạy khớp từng ký tự với file trong PR; việc chuẩn hoá tên thuộc
  vòng gate lại, cùng lúc với sửa tiền đề test đính chính (§4).

## 6. Rà lại hai điểm Sổ B88/B89

- B88(iv): cổng CORRECT_SOURCE hai lớp fail-closed vẫn đứng như Sổ ghi; việc dựng bundle target
  `resource:{id}` để chuyển sang `version.create` vẫn thuộc luồng soạn thật (chưa có trong
  US7). Nhất quán với §4: đường đính chính chưa được kiểm chứng đầu-cuối vì bị chặn ở
  tiền đề, không phải vì cổng này.
- B89(iv): route tạo fixture không idempotent, chấp nhận cho công cụ kiểm chứng — giữ nguyên
  đánh giá; không phát hiện thêm hệ quả nào tại gate này.

CI của PR gate (head `f94488f5`, chạy trên merge commit với main `69c21e9`) xác nhận độc lập
đúng chữ ký trên: job `backend (it-pure)` failure với 6 test fail — 5 test share của
`ResourceSharingIT` và test đính chính của `ResourceAccessIT`; các job frontend, backend-build,
e2e-backend và integration (chromium-core, chromium-teaching-guardians) đều success. Hai lỗi
`must be owner of schema` ở bước dọn dẹp cuối class trong lượt CI đầu là hệ quả của #271 (mục
§5 điểm 5), không thuộc chữ ký fail của scenario.

## 7. Kết luận gate #88 — KHÔNG ĐẠT

- ĐẠT: US7.1, US7.2 (nửa cập nhật), US7.3, US7.8; hợp đồng body/answer tách quyền đạt;
  US5 chạy lại: ma trận thu hồi 6/6 đạt.
- FAIL thật trên production: US7.4, US7.5, US7.6, US7.7, US7.9 — cùng một defect provisioning
  group `managers` (§3).
- CHƯA KIỂM CHỨNG ĐƯỢC: US7.2 nửa đính chính (§4) và phần US5 phụ thuộc share/fork.
- Theo quy tắc gate: PR chứa bằng chứng này được để ở trạng thái draft, **không merge**,
  issue #88 **giữ mở**. Điều kiện nghiệm thu lại: sửa defect §3 (kèm test hồi quy share tới
  trung tâm không có manager), dựng lại tiền đề test đính chính bằng đường share, chạy lại
  `ResourceSharingIT` + test đính chính trên main mới và đạt đủ.
