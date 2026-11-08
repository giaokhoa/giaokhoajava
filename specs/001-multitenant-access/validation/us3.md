# Chuẩn bị gate US3 — Lớp/khóa đa môn, nhiều giáo viên, ghi danh từng môn (T053)

> **Cập nhật 2026-11-08 sau review #180:** xem mục “Khắc phục review #180” cuối tài liệu.
> Phần chuẩn bị bên dưới được giữ làm lịch sử; không dùng các lượt chọn lọc cũ để thay thế
> kết quả trên cây sửa hiện tại. PR #184 chờ CI và review ngoài trước khi nghiệm thu lại.

Issue #55: “[T053][US3] Chạy đầy đủ US3.1–US3.8 và raw-query RLS/FK tests, giáo
viên chỉ roster môn phụ trách, current/future subject và class khác”. Cây chuẩn
bị: branch `issue-55-gate-tests` trên nền commit `4fb7e81` (đỉnh chuỗi
#45→#53, PR #166/#167/#168/#169/#171/#173 và nhánh #53 đã review đạt ở local).

> **Trạng thái: CHUẨN BỊ — CHƯA NGHIỆM THU #55.** Tài liệu này ghi bản đồ phủ
> và kết quả chạy thật của phần backend đã có đủ đầu vào (#45–#53). Nghiệm thu
> #55 chỉ được kết luận khi có thêm đầu vào #54 (UI + E2E T044), một lượt
> `-Pintegration verify` toàn suite trên cây tích hợp cuối và CI xanh — xem
> mục “Còn thiếu để nghiệm thu”. Không mục nào dưới đây được tick pass từ tài
> liệu; mọi khẳng định đều trỏ tới test đã chạy.

US3.1–US3.8 là 8 acceptance scenarios của User Story 3 trong
`specs/001-multitenant-access/spec.md`. Một số scenario chứa hành vi thuộc
story sau (nhận bài/xem kết quả — US4; giao đề/chấm/xem đáp án qua sample
action — US7); phần thuộc US3 là cấu trúc lớp–khóa–môn, roster, ghi danh, phân
công và phạm vi quyền tương ứng, được đối chiếu riêng ở từng mục.

## Phạm vi và phương pháp

- Gate chuẩn bị chạy trên **làn 2** của stack TEST native (PostgreSQL
  127.0.0.1:15433, Keycloak 127.0.0.1:18082, realm `giaokhoa-it`), PDP thật,
  không mock, không skip, không giảm rule.
- Thứ tự cổng: `clean verify` trước (unit + Spotless/PMD/SpotBugs), rồi
  `-Pintegration verify` chọn lọc toàn bộ test giảng dạy sau. Không chạy
  `clean verify` sau lượt integration để không ghi đè failsafe-reports.
- Lượt này chỉ thêm test và tài liệu ghi nhận; không sửa code production.

## Bản đồ phủ US3.1–US3.8

### US3.1 — Quản lý tạo lớp, tạo khóa, thêm nhiều môn; mỗi khóa thuộc một lớp

- `TeachingStructureIT.ownerCreatesClassReturnsPendingOperation`,
  `managerCreatesClassReturnsPendingOperation`,
  `courseWithThreeSubjectsHasCourseView` (khóa ba môn, CourseView đúng lớp).
- `TeachingServiceIT.createClassReadsBackScopedToWorkspace`,
  `courseRequiresAtLeastOneSubjectAtCreate`.
- Thuộc một lớp duy nhất ở tầng DB: FK composite của `course` —
  `TeachingRawConstraintsIT.rawCompositeForeignKeysRejectCrossWorkspaceReferences`
  và `rawEnrollmentAcrossClassesInSameWorkspaceViolatesSameClassTrigger` (mới).

### US3.2 — Ghi danh X chỉ vào môn thứ nhất; X chỉ trong phạm vi môn đã ghi danh

- `TeachingStructureIT.studentChoosesOnlySelectedSubjects` (chọn 2/3 môn, môn
  không chọn không có học sinh).
- `TeachingAuthorizationIT.studentCannotReadUnenrolledSubjectRoster`,
  `teacherReadsOwnSubjectRoster`.
- Phần “nhận bài và xem kết quả của môn đã ghi danh” thuộc US4/US7 — ngoài
  phạm vi gate US3, không khẳng định ở đây.

### US3.3 — X chưa thuộc roster lớp L thì không ghi danh được vào môn của L

- `TeachingStructureIT.rosterAddRequiresCenterMembership`.
- `TeachingServiceIT.rosterRequiresCenterMembershipAndBatchIsAtomic`.
- `EnrollmentAssignmentIT.batchEnrollmentValidatesWholeBatchBeforeWriting`
  (mục tiêu không tồn tại → 404 `ENROLLMENT_TARGET_NOT_FOUND`; lô trộn lớp →
  400 `ENROLLMENT_CLASS_MISMATCH`, không ghi một phần).
- Tầng DB: trigger membership —
  `TeachingRawConstraintsIT.rawRosterAndHomeroomInsertsRequireActiveCenterMembership`
  (mới).

### US3.4 — Giáo viên được phân công môn M trong khóa K thì đúng phạm vi đó

- `TeachingAuthorizationIT.teacherOfOneSubjectCannotAssignTeacherOfAnother`,
  `teacherOfOneSubjectCannotReadOtherSubjectRoster`.
- `EnrollmentAssignmentIT.teacherAssignmentRequiresTeacherRoleInSameCenter`.
- Phần “giao đề đúng môn / đề khác môn bị từ chối” là sample action của US7 —
  ngoài phạm vi gate US3.

### US3.5 — Hai giáo viên cùng một môn; phân công bổ sung mở thêm đúng môn

- `TeachingStructureIT.twoTeachersShareOneCourseSubject`.
- `TeachingAuthorizationIT.sameSessionAfterTeacherRevokeIsDenied` (hai giáo
  viên cùng môn, một người bị thu hồi thì người còn lại không đổi).
- `TeachingAuthorizationIT.teacherWithSecondAssignmentReadsBothSubjectRosters`
  (mới): phân công bổ sung môn thứ hai mở đúng roster môn đó, môn thứ ba chưa
  phân công vẫn 403.

### US3.6 — Chủ nhiệm không có phân công chuyên môn: phạm vi lớp, không phạm vi môn

- `TeachingAuthorizationIT.homeroomCanReadClassRosterButCannotAssignTeachers`,
  `homeroomCannotAddEnrollments`, `homeroomCannotCreateCourses`,
  `homeroomCannotAssignHomeroom`, `managerCanAssignHomeroom`.
- Phần “giao đề đã duyệt được phép; sửa đề/xem đáp án/chấm cần quyền riêng” là
  sample action của US7 — ngoài phạm vi gate US3.

### US3.7 — Chủ nhiệm L1 thao tác với khóa thuộc L2 (cùng trung tâm) bị từ chối

- `TeachingAuthorizationIT.teacherCannotReadOtherClassRoster`.
- `TeachingAuthorizationIT.homeroomOfOneClassCannotReadOtherClassRoster`
  (mới): chủ nhiệm lớp chính đọc được roster lớp mình, đọc roster lớp thứ hai
  bị 403.
- Chéo workspace: `TeachingStructureIT.enrollmentBatchAcrossWorkspacesIsRejected`,
  `TeachingAuthorizationIT.actorFromAnotherCenterCannotReadClass`.

### US3.8 — Ghi danh toàn bộ môn hiện có; môn thêm sau không tự ghi danh/phân công

- `TeachingStructureIT.addingSubjectDoesNotAutoEnroll` +
  `studentChoosesOnlySelectedSubjects` (batch tạo đúng từng ghi danh cho các
  môn hiện có tại thời điểm thao tác; môn thêm sau có 0 ghi danh).
- `TeachingServiceIT.addingSubjectDoesNotAutoEnroll`.

### Raw-query RLS/FK (yêu cầu riêng của #55)

`TeachingRawConstraintsIT` (mới, 6 test) kiểm bằng SQL thô qua role
NOSUPERUSER NOBYPASSRLS — user của stack TEST là superuser nên không tự chịu
RLS; các test RLS trước đây đi qua service, lượt này khẳng định chốt chặn tầng
DB độc lập với tầng ứng dụng:

- Không context workspace → 0 hàng trên cả 7 bảng giảng dạy; context A chỉ
  thấy hàng A, context B chỉ thấy hàng B.
- WITH CHECK: INSERT mang workspace khác context → SQLState 42501.
- FK composite chéo workspace/lớp → 23503: khóa thuộc lớp workspace khác;
  môn–khóa khai lớp khác lớp thật của khóa; phân công/chủ nhiệm nối sang đối
  tượng của workspace khác. Riêng ghi danh nối sang môn–khóa của workspace
  khác bị trigger cùng lớp chặn ở 23514 trước FK (trigger BEFORE INSERT chạy
  trước kiểm tra FK và hai phía khác lớp).
- Trigger cùng lớp của ghi danh → 23514; trigger membership ACTIVE cho
  roster/chủ nhiệm ở center → 23514.
- Unique từng phần cho cặp hiện hành → 23505; hard-delete và đổi định danh bị
  trigger giữ lịch sử chặn → 23514; sau thu hồi, ghi danh lại bằng hàng mới và
  lịch sử cũ vẫn còn.

## Kết quả chạy thật

Chạy trên làn 2 (PostgreSQL 15433, Keycloak 18082), PDP thật, ngày
2026-11-08. Số liệu đọc từ surefire/failsafe reports (XML) trong
`backend/target/`.

### Lượt trên cây tích hợp cuối US3 (sau rebase lên tip #54)

Cây chạy: phần chuẩn bị gate này đã rebase lên tip `5b135a2` của #54 —
tức toàn bộ chuỗi gate US3 (đã gồm bản sửa #172 và khóa workspace thống
nhất cho service giảng dạy) cộng #53 và #54. Trước khi chạy đã kiểm
chứng sức khoẻ làn 2: PostgreSQL 15433 mở, Keycloak 18082 trả 200,
service-account của client `giaokhoa-it-fixtures` đủ 9 role.

- `spotless:apply`: đạt, không đổi file nào.
- `clean verify` (JDK 25): BUILD SUCCESS — unit 172 run / 0 failures /
  0 errors / 0 skipped; Spotless, PMD, SpotBugs đạt.
- `-Pintegration verify` chọn lọc 5 class giảng dạy (nạp env `TEST_*`
  của làn 2 trước khi chạy): **53 run / 0 failures / 0 errors /
  0 skipped**, xanh trọn ngay lượt đầu trên cây mới. Theo class:
  `EnrollmentAssignmentIT` 6/0/0, `TeachingAuthorizationIT` 14/0/0 (gồm
  hai test mới của US3.5 và US3.7), `TeachingRawConstraintsIT` 6/0/0,
  `TeachingServiceIT` 11/0/0, `TeachingStructureIT` 16/0/0. Thời gian
  từng class dao động lớn (3–412 giây) do máy 8GB RAM chịu tải của hai
  stack TEST; không có test nào bị skip hay giảm rule.
- Phạm vi chọn lọc này không gồm `ConcurrentRevocationIT` và
  `RecoveryFlowIT` nên không xuất hiện flake họ B2 hay lỗi SMTP local;
  hai họ đó phân loại lại ở lượt toàn suite của gate thật (mục “Còn
  thiếu để nghiệm thu”).

### Lượt chuẩn bị trước đó (nền cũ, trước rebase)

Trên nền cũ, lượt chọn lọc đầu đạt 53 run nhưng có 3 failures /
7 errors, mọi lỗi đều đã phân loại và xử lý dứt điểm trước khi khép
chuẩn bị: cháy fixture do service-account làn 2 có 0 role sau rebuild
hậu restart (đã cấp lại đủ 9 role, chạy lại xanh); hai lỗi của chính
test mới (`TeachingRawConstraintsIT` seed email rỗng vi phạm
`ck_user_verified_email`, và kỳ vọng SQLState 23503 trong khi trigger
cùng lớp chặn trước ở 23514 — xem mục raw-query ở trên). Kết quả cuối
trên nền cũ: 53/0/0/0.
## Còn thiếu để nghiệm thu #55

1. Đầu vào #54: UI quản trị giảng dạy và E2E T044
   (`frontend/tests/e2e/teaching-administration.spec.ts`) chạy thật.
2. Lượt `-Pintegration verify` toàn suite trên cây tích hợp cuối (sau rebase
   qua main có bản sửa #172 và khóa workspace thống nhất cho service giảng
   dạy) cùng CI 3 job xanh trên PR tích hợp.
3. Phần hành vi của US3.2/US3.4/US3.6 thuộc US4/US7 (kết quả đã công bố,
   sample action giao/chấm/xem đáp án) — nghiệm thu ở gate của các story đó,
   không tính vào #55.
4. Flake đã biết họ B2 (`ConcurrentRevocationIT`,
   `CenterServiceIT.subscriptionPeriodAddsNativeTimePoliciesToGatedScopes`) và
   lỗi SMTP local của `RecoveryFlowIT` (không có Mailpit native; xanh ở CI)
   phải được phân loại lại ở lượt toàn suite của gate thật.

## Khắc phục review #180

PR [#184](https://github.com/giaokhoa/giaokhoa/pull/184) trên `main@9e88ea9` sau #179.
Đã rà diff và caller liên quan bằng `ponytail-review`, theo tải nhiều request cùng workspace.
Phạm vi là các hành vi đã triển khai của US1–US3; không bổ sung tính năng US4–US8.

- Route giảng dạy đồng bộ eligibility hiện hành qua ACCOUNT/target fence và PDP profile;
  khóa workspace bao trọn kiểm tra role, đọc/ghi nguồn và ghi operation. Ngoại lệ SQL
  về nguồn role theo authorization §11 vẫn được giữ và ghi rõ.
- Membership activation ghi lại bundle giảng dạy trong cùng operation; ghi danh kiểm tra
  trạng thái lớp/khóa cha và STUDENT hiện hành trước khi ghi cả lô.
- UI kết thúc attempt khi nhận receipt hoặc `OPERATION_FAILED`, giữ key khi lỗi mạng chưa
  rõ kết quả, chặn thao tác mới lúc pending và đọc đủ cursor của dữ kiện chọn người/quan hệ.
- E2E dùng backend, Next production và Caddy TEST cùng origin; CI thiếu origin phải lỗi.
  Fixture dùng đúng ID directory, receipt, phiên/CSRF và điều hướng thực của Keycloak.
- Chạy E2E thật còn phát hiện tài khoản được cấp có displayName ở firstName nhưng bị ép
  nhập lastName sau đổi mật khẩu tạm. Bootstrap giữ lastName tùy chọn; native acceptance
  kiểm đúng hình dạng này. Không bỏ email verification.

Kết quả local trên stack TEST riêng (PostgreSQL 15433, Keycloak 18082, Mailpit 18026), JDK 25:

| Lệnh | Kết quả |
| --- | --- |
| `./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply` | Đạt |
| `./backend/mvnw -B -ntp -f backend/pom.xml clean verify` | 172 unit đạt; compiler, Spotless, PMD, SpotBugs đạt |
| `./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify` | 334 run, 325 đạt, 0 failure/error, 9 skip có sẵn |
| `bash infra/keycloak/verify-native.sh` | 13 test đạt, 0 skip; bootstrap/provisioning/PDP đạt |
| `npm --prefix frontend run lint` và `npm --prefix frontend test` | Đạt, 67 unit |
| `npm --prefix frontend run test:e2e` | 38 ca đã chạy: 34 đạt, 4 lỗi selector |
| `npm --prefix frontend run test:e2e -- --grep 'giao diện quản trị:|quản lý ở trang hai|retry lỗi mạng'` | Sửa selector, chạy lại cả 4 ca: 4 đạt, 0 skip |

Các lệnh Maven dùng giới hạn heap 384 MB cho Maven, test JVM và SpotBugs trên máy local;
không dùng cờ skip hay giảm rule. Cả 7 integration regression mới đều chạy và đạt.
Trong 9 skip của profile integration, 8 native được chạy riêng và đạt qua script ở trên;
1 placeholder invitation cũ của `IdentityFlowIT` vẫn disabled. Không dùng placeholder
làm bằng chứng; luồng invitation thật được kiểm ở `MembershipInvitationIT` và E2E.
Các test lifecycle/hash tách worker để kiểm dữ kiện nguồn; test HTTP/PDP và acceptance
native không mock PDP.

Cả 8 ca `teaching-administration` đã được kiểm chứng với backend/Keycloak/PDP thật;
3 ca `teaching-ui-regressions` dùng component thật và HTTP fixture, tách khỏi acceptance.
Các lỗi selector là khớp nhiều phần tử hoặc đòi text khớp tuyệt đối khi roster có thêm
trạng thái; không đổi assertion nghiệp vụ. Chưa có một lượt local toàn suite xanh trên
commit cuối: bằng chứng hiện tại gồm lượt toàn suite và lượt chạy lại 4 ca đã sửa;
CI chạy lại toàn bộ 38 ca không có bộ lọc. Build Next production đạt trong webServer
của cả hai lượt Playwright.

Tự review sau sửa không còn Must fix trong diff và code liên quan. Chưa merge; kết quả
CI trên commit cuối và review ngoài vẫn là điều kiện trước khi nghiệm thu/merge.
