# Nghiệm thu US4 — Phụ huynh xem kết quả của đúng con (T062)

Issue #64: “[T062][US4] Nghiệm thu 5 scenarios US4 và T6 bằng parent thật
trong realm test”. US4.1–US4.5 là 5 acceptance scenarios của User Story 4
trong `specs/001-multitenant-access/spec.md`; T6 là kịch bản kiểm chứng ở
`specs/001-multitenant-access/quickstart.md` mục 6: “P1 xem S1
published/draft, đổi sid sang S2 và wid sang B; P2 chưa approved — chỉ P1
đúng con/center/published được xem; không có answer/submission trong DTO”.

## Kết luận

**Gate #64 ĐẠT.** Toàn bộ hành vi US4.1–US4.5 và T6 đã được kiểm chứng
bằng test thật trên stack TEST native (PDP thật, không mock, không skip
ngoài các điểm skipped-by-design ghi tường minh ở mục “Quyết định về
nhóm Native”), review của từng issue trong chuỗi đã kết luận APPROVE,
và mọi issue của US4 (#56–#63) đã land vào `main`.

## Cây và phạm vi nghiệm thu

- Chuỗi US4 đã land vào `main` tại `222a8ded`: #56 qua PR #181, #57 qua
  PR #182, #58 qua PR #183, #59 qua PR #185, #60 qua PR #187, #61 qua
  PR #189, #62 qua PR #193, #63 qua PR #195. Các issue #56–#63 đã đóng.
- Bằng chứng chạy thật (clean verify, integration toàn suite) lấy trên
  cây tích hợp cuối `5c5c5cd` của nhánh `us4-integration`. Chênh lệch duy
  nhất giữa cây bằng chứng này và `main` đã land là **thứ tự mục C6
  trong Sổ** `docs/diem-chua-chac-chan-va-lech-spec.md` — nội dung giống
  hệt từng dòng, code và test khớp tuyệt đối; thứ tự trên `main` đã land
  được chốt giữ nguyên. Sổ trên `main` đủ A1–A14, B1–B61, C6.
- Kiểm chứng trên hai stack TEST native (làn 1: PostgreSQL
  127.0.0.1:15432, Keycloak 127.0.0.1:18081; làn 2: PostgreSQL
  127.0.0.1:15433, Keycloak 127.0.0.1:18082; realm `giaokhoa-it`), thứ tự
  cổng `clean verify` trước, `-Pintegration verify` sau.

## Bản đồ phủ US4.1–US4.5, T6, RLS/FK

### US4.1 — Phụ huynh chưa được duyệt: xem kết quả bị từ chối dù đã gửi yêu cầu

- `PublishedResultContractIT.pendingLinkParentCannotRead`
  (link REQUESTED → 403 ở đường đọc kết quả).
- `GuardianAccessIT.parentRequestsLinkSeesPending` (request
  tạo được, trạng thái REQUESTED, không mở quyền đọc).
- Tầng giao diện: scenario 1 của E2E `guardian-results.spec.ts` phủ luồng
  parent thật gửi request và chỉ đọc được sau khi được duyệt.

### US4.2 — Đã duyệt tại A: chỉ xem kết quả đã công bố của đúng con tại A

- `PublishedResultContractIT.approvedParentReadsOwnChildOnly`,
  `approvedParentCannotReadOthers`,
  `parentWithoutLinkCannotReadChildResults`,
  `studentReadsOnlyOwnPublishedResults`.
- `PublishedResultServiceIT.approvedParentReadsOwnChildOnly`,
  `onlyStudentAndSubjectTeacherRead` (scope
  read/grade tách nhau; quản lý/chủ nhiệm không đọc qua đường này).
- Hình dạng DTO: chỉ `PublishedResultView` cho parent (không answer/
  submission); quy tắc đọc rỗng/403 chốt ở Sổ B56, quyết quyền route
  guardian chốt ở Sổ B57.
- Tầng giao diện: E2E scenario 1 (kết quả đã công bố của đúng con) và
  scenario 2 (đổi sid/wid không lấy được kết quả của con khác/center
  khác).

### US4.3 — Liên kết tại A không tự cấp quyền tại B

- `PublishedResultContractIT.linkInOneCenterGrantsNoOther`.
- `GuardianAccessIT.parentCannotLinkForeignStudent`.
- Tầng giao diện: E2E scenario 2 (đổi `wid` sang center B bị chặn).

### US4.4 — Chủ nhiệm không có vai trò quản lý/chủ sở hữu: duyệt liên kết bị từ chối

- `GuardianAccessIT.homeroomTeacherCannotDecideLink`,
  `subjectTeacherCannotDecideLink`.
- Sau sửa Should fix S1 của review #62 (commit `0fd0b2e`, review lại
  APPROVE), tầng service tự kiểm tra lại OWNER/MANAGER ở decide/revoke:
  `GuardianLinkServiceIT.teacherCannotDecideOrRevoke`.
- Tầng giao diện: E2E scenario 3 (chủ nhiệm không có vai trò quản lý bị
  từ chối khi duyệt liên kết).

### US4.5 — Đã gỡ liên kết: yêu cầu tiếp theo bằng phiên cũ mất quyền xem; dữ liệu giữ cho người còn thẩm quyền

- `PublishedResultContractIT.revokedLinkStopsParentFromReadingResults`
  khóa luồng HTTP cùng session sau unlink bị chặn ở yêu cầu đầu tiên;
  test trùng trong `GuardianAccessIT` đã được gộp vào đây.
- Kiểm chứng read side độc lập:
  `PublishedResultServiceIT.revokedLinkStopsParentFromReadingResults`.
- Đóng nguồn cùng operation khi mất tiền đề:
  `GuardianLinkServiceIT.removingParentRoleClosesLink`,
  `revokingMembershipClosesLink`,
  `approvalRechecksPrerequisites`.
- Ghi kèm bundle kết quả trong cùng operation khi mutation guardian đổi
  nguồn của bundle: Sổ B58. Trạng thái REVOKED được quan sát qua danh
  sách của chính parent (danh sách của manager chỉ giữ liên kết cần xử
  lý): Sổ B61.
- Tầng giao diện: E2E scenario 4 (phiên parent cũ mất quyền xem và back
  navigation không hiện dữ liệu cũ).

### T6 (quickstart)

- Phủ ở tầng backend bởi tổ hợp `PublishedResultContractIT` (7 test:
  pending bị chặn, approved chỉ published của đúng con, con khác bị chặn,
  link center khác không cấp quyền, revoke dừng đọc).
- Phủ ở tầng giao diện bởi E2E scenario 1 và 2 của
  `frontend/tests/e2e/guardian-results.spec.ts`.
- Seed của E2E dùng fixture trực tiếp như các IT vì đường sample action
  (gán/làm/chấm/công bố) thuộc phạm vi US7: Sổ B60.

### Raw-query RLS/FK của guardian_link và primitive mẫu

`GuardianRawConstraintsIT` (5 test, chạy qua role NOSUPERUSER
NOBYPASSRLS vì user của stack TEST là superuser):

- `rawSelectSeesOnlyRowsOfContextWorkspace`,
  `rawInsertWrongContextViolatesRls` (RLS theo
  workspace context).
- `rawForeignKeyAndStateChecksRejectBadRows` (FK + CHECK hình dạng
  trạng thái), `rawMembershipGateAtInsertAndApproval` (trigger membership
  ACTIVE), `rawUniquenessAndHistoryGuardsHold` (unique
  từng phần cho cặp hiện hành, đóng băng lịch sử).
- `GuardianLinkServiceIT.serviceRunsUnderNonBypassRlsRole` (service chạy
  đúng dưới role không bypass RLS).
- Các quyết định tầng schema tự chốt ghi ở Sổ B53 (guardian_link) và B54
  (primitive mẫu V019).

## Kết quả chạy thật

Số liệu đọc từ XML/txt trong `backend/target/failsafe-reports/` và các
log dưới `~/workspace/logs-integration/`, `~/workspace/logs-issue63-e2e-fix3/`
trên máy chạy pipeline, ngày 2026-11-09.

### Clean verify trên cây cuối

- **BUILD SUCCESS** — unit 172 run / 0 failures / 0 errors / 0 skipped;
  compiler, Spotless, PMD, SpotBugs đạt.

### Integration verify toàn suite trên cây cuối (làn 1)

- Tổng: **382 run / 0 failures / 1 error / 9 skipped**, 56 phút.
- Lỗi duy nhất là `RecoveryFlowIT.verifiedUserGetsPasswordEmail`
  — lỗi SMTP vì môi trường native local không có Mailpit; đây là lỗi đã
  biết và được phép ở local, không thuộc hành vi US4. Không test nào
  khác đỏ.
- Nhóm guardian trên cây cuối: `GuardianAccessIT` 13/13,
  `GuardianLinkServiceIT` 8/8 (gồm test của bản sửa S1 #62),
  `PublishedResultContractIT` 7/7, `PublishedResultServiceIT` 6/6,
  `GuardianRawConstraintsIT` 5/5.
- `ConcurrentRevocationIT` 16/16.

### E2E guardian-results (T061)

- `frontend/tests/e2e/guardian-results.spec.ts`: **4 passed (17,1 phút)**
  bằng parent thật trong realm test — scenario 1: parent gửi request,
  manager duyệt, parent chỉ xem kết quả đã công bố của đúng con;
  scenario 2: đổi sid/wid bị chặn; scenario 3: chủ nhiệm không có vai
  trò quản lý bị từ chối khi duyệt; scenario 4: gỡ liên kết, phiên
  parent cũ mất quyền và back navigation không hiện dữ liệu cũ.
- Commit bằng chứng `9035921` (seed bằng fixture), đã land qua PR #195.
- Frontend: vitest 77/77 trên cây đã land.

### Review trong chuỗi

- Từng issue #56–#63 qua một đội review độc lập (góc yêu cầu issue +
  kiểm chứng thực thi) kết luận APPROVE mới land; mọi Should fix được
  sửa ngay trong cùng issue (gần nhất: S1 của #62 ở commit `0fd0b2e` —
  service tự kiểm tra lại OWNER/MANAGER khi decide/revoke — review lại
  APPROVE kèm clean verify 172/0 và IT chọn lọc 28/28).

## Quyết định về nhóm Native

Ba class `KeycloakProvisioningClientNativeIT`, `NativePermissionGateIT`,
`NativeTimePolicyIT` bị `backend/pom.xml` loại khỏi profile integration
theo thiết kế (chú thích trong pom: Native Keycloak dùng stack riêng và
được CI chạy qua `verify-native.sh`). Trên máy chạy pipeline hiện tại,
`verify-native.sh` cần Docker — không chạy được (môi trường chặn Docker)
— và CI đang tắt theo lệnh của chủ repo cho tới khi parent #2 đóng.

Trong lượt integration toàn suite trên cây cuối, `NativeGateAcceptanceIT`
6 skipped và `PolicyGraphWriterNativeIT` 2 skipped là **skipped-by-design**
(nằm trong tổng 9 skipped; điểm skipped còn lại là placeholder đã vô hiệu
từ trước ở `IdentityFlowIT`). Hành vi Native liên quan US4 được phủ gián
tiếp qua toàn bộ IT/E2E chạy trên Keycloak thật của stack TEST (PDP thật
ra quyết định trong mọi scenario ở trên).

**Quyết định tường minh:** chấp nhận gate #64 trên cơ sở bằng chứng
skipped-by-design này; kiểm chứng Native đầy đủ qua `verify-native.sh`
sẽ được thực hiện khi bật lại CI sau khi parent #2 đóng. Điểm này không
bị bỏ qua lặng lẽ và là đầu việc bắt buộc của khâu khôi phục CI.
