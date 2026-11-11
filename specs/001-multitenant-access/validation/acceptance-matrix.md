# Ma trận nghiệm thu T094 — 56 acceptance scenarios, T1–T16, FR-001–FR-054

- **Revision nghiệm thu:** `main` `59137da4223268efbe17bb07ebbddd7084eac8a0`, tree
  `72006a35a4b4c8536261864916f8c239a22dcb9e`. Tree này **trùng byte-for-byte** với tree của
  head PR #289 `07f3b3537ca59b48619654daaed5e5b8564c3297` (kiểm chứng qua GitHub API ngày
  2026-11-11: cả hai commit cùng tree SHA), nên kết quả CI tại head đó là bằng chứng chạy
  tại đúng revision này.
- **Nguyên tắc:** chỉ ghi ĐẠT khi có kết quả chạy thật (CI tại đúng tree, lượt chạy local có
  log, hoặc gate đã kết luận kèm bằng chứng). Ca chưa đạt/chưa có bằng chứng ghi rõ
  **CHƯA ĐẠT** hoặc **CHƯA CÓ BẰNG CHỨNG**, không tick theo sự hiện diện của test.

## Nguồn bằng chứng

| Mã | Nguồn | Nội dung |
| --- | --- | --- |
| CI-MAIN | CI run `43007731600` tại head `07f3b353` (= tree của main `59137da4`) | 8/8 job success. Artifact failsafe `backend-integration-reports-{it-pure,e2e-backend}`: **458 test IT, 0 failures, 0 errors, 8 skipped** (6 `NativeGateAcceptanceIT` + 2 `PolicyGraphWriterNativeIT` — cần stack native, xem CI-NATIVE). Artifact Playwright `e2e-reports-{chromium-core,chromium-teaching-guardians}`: **43/43 + 13/13 passed, 0 skipped**. Job `backend`/`backend-build` success (unit 252 test — số đếm local tại cùng tree của đội chuẩn hoá e2e, PR #290). Job `frontend` success (lint + unit + build). |
| CI-NATIVE | Job `integration` của CI run `43007731600` (success) | Bước "Verify native Keycloak bootstrap and provisioning" chạy `infra/keycloak/verify-native.sh` — chạy thật `NativePermissionGateIT`, `NativeTimePolicyIT`, `PolicyGraphWriterNativeIT`, `NativeGateAcceptanceIT` trên Keycloak native; bước "Verify signed OIDC back-channel logout across restart" cũng pass. Đây là bằng chứng cho 8 test bị skip ở tầng failsafe thường. |
| LOCAL-289 | Log đội sửa gate #88 tại đúng tree, làn 2: `~/workspace/logs-gate88-r2/it-lane2-r7-rebased.log` + `verify-unit-rebased.log` | `ResourceSharingIT` 6/6, `ResourceAccessIT` 5/5, `RevocationMatrixIT` 6/6 xanh tại local; unit 252/252. |
| GATE-95 | `specs/001-multitenant-access/validation/us8.md` (worktree gate-us8, nghiệm thu tại `da8ba071`) | US8.1–US8.7 phán ĐẠT kèm test của PR #288; Run A local trên main: `SubscriptionServiceIT` 9/9, `PersonalEntitlementIT` 5/5, `EnrollmentAssignmentIT` 6/6, `TeachingAccessIT` 3/3, `TeachingServiceIT` 15/15, `ResultSourceMutationIT` 5/5. Code production US8 không đổi giữa `da8ba071` và `59137da4` (diff PR #289 chỉ chạm module resources + test) nên kết luận giữ nguyên tại revision này, trừ nhánh gia hạn ở US8.7 (xem CI-288). |
| CI-288 | CI run `43008539662` của PR #288 tại head `525ea147e8901e5b6df503a84f1ba50fb2507d8f` | Các job backend (it-pure, e2e-backend), frontend, chromium-teaching-guardians success — tức `SubscriptionExpiryIT` (7 ca) + `CompletionAfterExpiryIT` (3 ca) xanh ở tầng native. Job `integration (chromium-core)` **failure**: đúng 1 test fail — `subscription-expiry.spec.ts:596` "Center hết hạn khi phiên còn mở: cookie cũ vẫn nộp/chấm/công bố được bài đã giao, tăng thêm bị chặn, gia hạn mở lại", fail ở bước gia hạn (spec:757, `xacNhanGoiCuaSo`): `operation 5f00b7dc-986b-4023-8aba-5d79f615b976 chưa SUCCEEDED` — Expected `"SUCCEEDED"`, Received `"FAILED"`; 41 passed, 3 did not run do suite chạm timeout 1200s ở teardown (cả 3 test đó đều pass trong CI-MAIN). |

## Hạn chế phương pháp (khai thẳng)

- Đội T094 **không chạy lại lượt IT nặng tại local**: trong toàn bộ cửa sổ làm việc, máy dùng
  chung đang có lượt nặng của đội khác (gate #88 vòng 3 trên làn 2) và quy định chỉ một lượt
  nặng tại một thời điểm. Bằng chứng dùng CI tại **đúng tree** (đã chứng minh trùng) +
  log local của các gate tại cùng/kề revision như bảng trên.
- `FeatureAcceptanceIT.java` (file đích thứ hai của T094) **chưa tồn tại trên main và chưa
  được tạo trong đợt này**: bộ scenario đã được thực thi qua hai tầng failsafe
  (`it-pure` + `e2e-backend`) theo đúng danh sách lớp trong ma trận này. Tạo lớp suite
  tổng hợp cần thêm dependency `junit-platform-suite` (không có trong kho Maven offline
  hiện tại) và nếu đặt tên `*IT` sẽ chạy trùng toàn bộ lớp thành viên trong CI. Điều phối
  quyết định có cử việc riêng hay không; ma trận này là đầu vào cho quyết định đó.
- Vì các điểm trên + US8.7 còn một nhánh chưa đạt + T16 chưa xong, **T094 chưa được tick
  hoàn tất trong `tasks.md`**.

## Kết quả tổng hợp

| Phạm vi | Đạt | Chưa đạt | Ghi chú |
| --- | --- | --- | --- |
| 56 scenarios US1–US8 | 55 | 1 một phần (US8.7) | US8.7 đạt mọi nhánh trừ gia hạn gói center có hoạt động dạy học |
| T1–T16 (quickstart) | 15 | 1 (T16) | T16 thuộc nghiệm thu người dùng thật (#98), chưa hoàn tất |
| FR-001–FR-054 | 52 trọn | 2 một phần (FR-007, FR-028) | Cùng gốc: nhánh gia hạn của US8.7 |

## US1 — Một tài khoản dùng ở nhiều không gian (9)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US1.1 | Người tự đăng ký, email chưa xác minh | `IdentityFlowIT.unverifiedSelfRegisteredUserHasNoEligibilityUntilEmailVerified`, `changingEmailToAnUnverifiedOneRevokesEligibility`; E2E `identity.spec.ts` (chặn ở bước verify email; bấm link Mailpit thì đăng nhập được) — CI-MAIN | ĐẠT |
| US1.2 | Email đã xác minh, đăng nhập lần đầu | `IdentityFlowIT.verifiedUserBaselineIsNeutral`, `neutralUserWorkspacesEndpointListsOnlyPersonalWorkspace`; `BootstrapProfileIT.bootstrapCreatesAccountAndPersonalWorkspaceForNewSelfRegisteredIdentity`; `SystemPracticeHistoryIT` 6/6 — CI-MAIN | ĐẠT |
| US1.3 | Quản lý tại A, học sinh tại B | `IdentityFlowIT.oppositeRolesInTwoCentersStaySeparated`; `BootstrapProfileIT.directoryListsOnlyValidWorkspacesAndRolesStayPerWorkspace`; E2E `identity.spec.ts` workspace switch — CI-MAIN | ĐẠT |
| US1.4 | Học sinh chưa có tài khoản/email, trung tâm cấp | `IdentityFlowIT.centerIssuedUserWithoutEmailIsEligibleWithoutVerification`; `BootstrapProfileIT.registrarIssuesStudentWithoutEmailAndReissueIsIdempotent`; `MembershipInvitationIT.studentIssueWithoutEmailHasOnlyPasswordChangeAction`; E2E `identity.spec.ts` mật khẩu tạm bị ép đổi — CI-MAIN | ĐẠT |
| US1.5 | Học sinh đã có tài khoản, được mời vào trung tâm khác | `IdentityFlowIT.existingIdentityJoinsSecondCenterWithoutChangingOrigin`; `BootstrapProfileIT.registrarKeepsOriginOfExistingIdentityAndRejectsUsernameMismatch` — CI-MAIN | ĐẠT |
| US1.6 | Tài khoản không email cần khôi phục; chỉ QTV Giáo Khoa xử lý | `RecoveryWorkflowIT.centerManagerSubmitsForStudentMember`, `studentCannotSubmitForAnother`, `completeAfterOperatorConfirms`; `RecoveryFlowIT.operatorResetKeepsIdentity`, `ordinaryUserCannotResetOthers` — CI-MAIN | ĐẠT |
| US1.7 | Tài khoản có email đã xác minh, quên mật khẩu | `RecoveryFlowIT.verifiedUserGetsPasswordEmail`; `RecoveryWorkflowIT.submitForSelfCreatesRequestedRequest` — CI-MAIN | ĐẠT |
| US1.8 | Rời A, còn tham gia B | `IdentityFlowIT.leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter` (cũng có ở `BootstrapProfileIT`) — CI-MAIN | ĐẠT |
| US1.9 | Chưa đăng nhập / credential không hợp lệ | `IdentityFlowIT.anonymousCallerReceivesProblemUnauthorized`, `forgedSessionCookieDoesNotAuthenticate`; `SecurityConfigurationIT.apisRejectBearerAndBasicWhenAnon` — CI-MAIN | ĐẠT |

## US2 — Khởi tạo và quản trị trung tâm (7)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US2.1 | QTV Giáo Khoa tạo tenant + chỉ định chủ sở hữu | `CenterAdministrationIT.operatorCreateCenterReturnsPendingOperation`, `operatorDeepLinkSynchronizesEligibilityWithoutWorkspaceRequest`; `CenterServiceIT` 9/9; E2E `center-administration.spec.ts` tạo center qua UI — CI-MAIN | ĐẠT |
| US2.2 | Người dùng thường tự tạo tenant / tự kích hoạt gói | `CenterAdministrationIT.ordinaryUserCannotCreateTenant`, `ordinaryUserCannotConfirmSubscription`; xác nhận hợp lệ: `operatorConfirmsSubscriptionWithValidPeriod`, `subscriptionWithEndBeforeStartIsRejected` — CI-MAIN. (Nhánh *gia hạn* gói center có hoạt động: xem US8.7.) | ĐẠT |
| US2.3 | Chủ sở hữu bổ nhiệm/gỡ quản lý; quản lý không tự bổ nhiệm | `CenterAdministrationIT.ownerAppointsManagerInOwnCenterOnly`, `managerCannotAppointAnotherManager`, `managerCannotPromoteSelfToOwner`, `cannotRemoveLastOwner`; E2E owner bổ nhiệm manager — CI-MAIN | ĐẠT |
| US2.4 | Chủ sở hữu/quản lý quản lý thành viên trong thẩm quyền | `MembershipInvitationIT` 15/15 (mời theo email/userId, cấm OWNER, `managerCanInviteStudentButNotManager`, cấp học sinh + handoff); `CenterAdministrationIT` role-change cases — CI-MAIN | ĐẠT |
| US2.5 | Hai trung tâm dùng chung bộ quyền cố định, không vai trò tùy chỉnh | `CenterAdministrationIT.workspacesEndpointDoesNotLeakOtherCenters`, `capabilitiesForAnotherProfileIsRejected`; `IdentityFlowIT.oppositeRolesInTwoCentersStaySeparated`; catalog quyền cố định `PermissionCatalogTest` (unit) — CI-MAIN | ĐẠT |
| US2.6 | Giáo viên A tạo lớp / tự phân công | `CenterAdministrationIT.teacherCannotCreateCenterClassEvenWithPersonalPackage`; E2E `center-administration.spec.ts` cùng ca — CI-MAIN | ĐẠT |
| US2.7 | Quản lý A / QTV nền tảng không có nguồn quyền trên tài nguyên cá nhân X | `CenterAdministrationIT.capabilitiesForAnotherProfileIsRejected`; `PlatformPolicyProjectorTest` + `PlatformProjectionIT` (descriptor nền tảng chỉ gồm scope platform, không phát quyền học liệu) — CI-MAIN | ĐẠT |

## US3 — Phân công và ghi danh trong khóa đa môn (8)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US3.1 | Quản lý tạo lớp, khóa, nhiều môn; mỗi khóa thuộc một lớp | `TeachingStructureIT.ownerCreatesClassReturnsPendingOperation`, `courseWithThreeSubjectsHasCourseView`, `courseRequiresAtLeastOneSubject`; `TeachingServiceIT.createClassReadsBackScopedToWorkspace`; E2E `teaching-administration.spec.ts` tạo L1 + khóa ba môn — CI-MAIN | ĐẠT |
| US3.2 | Ghi danh X chỉ môn thứ nhất | `TeachingStructureIT.studentChoosesOnlySelectedSubjects`; `TeachingAuthorizationIT.teacherReadsOwnSubjectRoster`, `studentCannotReadUnenrolledSubjectRoster`; E2E ghi danh chọn môn — CI-MAIN | ĐẠT |
| US3.3 | X chưa thuộc roster lớp L, ghi danh vào môn của L | `TeachingStructureIT.rosterAddRequiresCenterMembership`, `enrollmentBatchAcrossClassesIsRejected`; `TeachingServiceIT.rosterRequiresCenterMembershipAndBatchIsAtomic` — CI-MAIN | ĐẠT |
| US3.4 | Giáo viên được phân công môn M trong khóa K | `TeachingAuthorizationIT.teacherReadsOwnSubjectRoster`, `teacherOfOneSubjectCannotReadOtherSubjectRoster`; `EnrollmentAssignmentIT.teacherAssignmentRequiresTeacherRoleInSameCenter` — CI-MAIN | ĐẠT |
| US3.5 | Hai giáo viên cùng môn; phân công bổ sung | `TeachingStructureIT.twoTeachersShareOneCourseSubject`; `TeachingAuthorizationIT.twoTeachersOfSameCourseSubjectBothReadRoster`, `teacherWithSecondAssignmentReadsBothSubjectRosters`; E2E nhiều giáo viên cùng môn — CI-MAIN | ĐẠT |
| US3.6 | Chủ nhiệm không có phân công chuyên môn M | `TeachingAuthorizationIT.homeroomCanReadClassRosterButCannotAssignTeachers`, `homeroomCannotAddEnrollments`, `homeroomCannotCreateCourses`; quyền xem đáp án tách riêng: `ResourceAccessIT.bodyAndAnswerAreSeparateReads` — CI-MAIN | ĐẠT |
| US3.7 | Chủ nhiệm L1 thao tác khóa thuộc L2 cùng trung tâm | `TeachingAuthorizationIT.homeroomOfOneClassCannotReadOtherClassRoster`, `teacherCannotReadOtherClassRoster` — CI-MAIN | ĐẠT |
| US3.8 | Ghi danh toàn bộ môn hiện có; môn thêm sau không tự ghi danh/phân công | `TeachingStructureIT.addingSubjectDoesNotAutoEnroll`; `TeachingServiceIT.addingSubjectDoesNotAutoEnroll`; E2E thêm môn không tự ghi danh/phân công — CI-MAIN | ĐẠT |

## US4 — Phụ huynh xem kết quả của đúng con (5)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US4.1 | Phụ huynh chưa được duyệt liên kết | `PublishedResultContractIT.parentWithoutLinkCannotReadChildResults`, `pendingLinkParentCannotRead`; `GuardianAccessIT.parentRequestsLinkSeesPending` — CI-MAIN | ĐẠT |
| US4.2 | Đã duyệt tại A: chỉ kết quả đã công bố của đúng con tại A | `PublishedResultContractIT.approvedParentReadsOwnChildOnly`, `approvedParentCannotReadOthers`; `PublishedResultServiceIT.approvedParentReadsOwnChildOnly`; E2E `guardian-results.spec.ts` — CI-MAIN | ĐẠT |
| US4.3 | Liên kết tại A dùng tại B | `PublishedResultContractIT.linkInOneCenterGrantsNoOther`; E2E đổi sid/wid bị từ chối — CI-MAIN | ĐẠT |
| US4.4 | Chủ nhiệm (không quản lý/chủ sở hữu) duyệt liên kết | `GuardianAccessIT.homeroomTeacherCannotDecideLink`, `subjectTeacherCannotDecideLink`; `GuardianLinkServiceIT.teacherCannotDecideOrRevoke` — CI-MAIN | ĐẠT |
| US4.5 | Đã gỡ liên kết, yêu cầu tiếp theo bằng phiên cũ | `PublishedResultContractIT.revokedLinkStopsParentFromReadingResults`; `PublishedResultServiceIT.revokedLinkStopsParentFromReadingResults`; E2E gỡ liên kết — CI-MAIN | ĐẠT |

## US5 — Thu hồi quyền và ngăn truy cập vượt phạm vi (6)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US5.1 | Gỡ phân công chấm M tại A, phiên cũ còn | `RevocationMatrixIT.revokedTeacherLosesRosterRead`; `TeachingAuthorizationIT.sameSessionAfterTeacherRevokeIsDenied`; E2E `revocation.spec.ts` US5.1 — CI-MAIN; LOCAL-289 | ĐẠT |
| US5.2 | Gỡ membership khỏi A, phiên cũ; quyền tại B giữ | `RevocationMatrixIT.removedMemberLosesWorkspaceAccess`; E2E `revocation.spec.ts` US5.2 — CI-MAIN; LOCAL-289 | ĐẠT |
| US5.3 | Gỡ ghi danh M, truy cập bài/kết quả theo ghi danh đó | `RevocationMatrixIT.unenrolledStudentLosesResultRead`; E2E `revocation.spec.ts` US5.3 — CI-MAIN; LOCAL-289 | ĐẠT |
| US5.4 | Sửa thông tin actor/trung tâm/lớp/môn/học sinh để vượt quyền | `RevocationMatrixIT.forgedTenantIdentityIsRejected`; E2E `revocation.spec.ts` US5.4 (đổi tenant, header giả, role giả) — CI-MAIN; LOCAL-289 | ĐẠT |
| US5.5 | Nhiều nguồn quyền, một nguồn bị thu hồi | `RevocationMatrixIT.revokingOneSubjectKeepsOtherSubject`; E2E `revocation.spec.ts` US5.5 — CI-MAIN; LOCAL-289 | ĐẠT |
| US5.6 | Không xác lập được quyền hiện hành / cập nhật quyền thất bại | `ConcurrentRevocationIT` 16/16 (`abandonedOperationStaysFenced`, `crashBeforeAckReplaysCleanly`, `staleWorkerCannotPublishOldRevision`, `writeAfterRevokeSeesNoOldRevision`); `ProjectionFenceGateIT` 6/6; E2E `revocation.spec.ts` US5.6 (202 không phải thành công) — CI-MAIN | ĐẠT |

## US6 — Tách quyền cá nhân khỏi quyền trung tâm (5)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US6.1 | Chưa có gói cá nhân, tạo tài nguyên/lớp cá nhân | `PersonalEntitlementIT.personalCreateWithoutPlanDenied`, `practiceHistoryAvailableNoPlan` (lịch sử làm đề hệ thống vẫn giữ); E2E `personal-entitlements.spec.ts` US6.1 — CI-MAIN | ĐẠT |
| US6.2 | QTV kích hoạt gói cá nhân, tạo học liệu/lớp cá nhân | `PersonalEntitlementIT.operatorActivationEnablesCreate`; `PersonalClassAuthorizationIT.ownerWithPlanOrganizesPersonalClassCourseRosterAndEnrollment`; E2E US6.2 — CI-MAIN | ĐẠT |
| US6.3 | Giáo viên chưa mua gói cá nhân, làm việc theo phân công tại A | `PersonalClassAuthorizationIT.centerTeacherWithoutPersonalPlanAuthorsAtCenterButNotPersonal`; `PersonalEntitlementIT.centerMembershipOpensNoAuthoring`; E2E US6.3 — CI-MAIN | ĐẠT |
| US6.4 | Có gói cá nhân, không phải quản lý A, tạo lớp tại A | `PersonalClassAuthorizationIT.personalPlanHolderCannotCreateClassAtCenter`; `PersonalEntitlementIT.personalPlanOpensNoCenterClass`; E2E US6.4 — CI-MAIN | ĐẠT |
| US6.5 | Có cả gói cá nhân và phân công A, rời A | `PersonalClassAuthorizationIT.leavingCenterKeepsPersonalRights`; E2E US6.5 — CI-MAIN | ĐẠT |

## US7 — Giữ quyền nguồn khi lưu và chia sẻ học liệu (9)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US7.1 | Lưu Q/V1 của hệ thống vào kho, giữ định danh/chủ sở hữu nguồn | `ResourceAccessIT.savingSystemVersionKeepsSourceIdentityAndOwner` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.2 | Nguồn phát hành V2 / đính chính; bản lưu giữ V1, nhận cập nhật chủ động | `ResourceAccessIT.savedCopyStaysOnOldVersionUntilUpdateIsReceived`, `correctionReachesOnlyRecipientsOfAffectedVersion`; `ResourceLibraryReplayIT.receiveUpdateCreatesNewItemAndReplaysIt`, `receiveUpdateReusesExistingItemForReplacementVersion` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.3 | Người có quyền sửa tạo bản chỉnh sửa; người chỉ có quyền dùng không được sửa | `ResourceAccessIT.forkCreatesNewIdentityWithProvenanceAndUseOnlyCannotFork` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.4 | Giáo viên chia sẻ một phiên bản cho A; A nhận vào kho; thành viên B không có quyền | `ResourceSharingIT.shareMustBeAcceptedBeforeRecipientCenterCanUse` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.5 | Bản chỉ được dùng tại A; lưu kho cá nhân / tạo bản chỉnh sửa không thoát ra B | `ResourceSharingIT.versionRestrictedToOneCenterCannotEscapeThroughSaveOrFork` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.6 | Thu hồi chia sẻ có thể thu hồi; chặn cả bản lưu/bản chỉnh sửa, giữ lịch sử | `ResourceSharingIT.revokingRevocableShareBlocksOriginalAndDerivedCopiesButKeepsHistory` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.7 | Chia sẻ lâu dài; người chia sẻ rời A | `ResourceSharingIT.longTermShareSurvivesGrantorExitAndRejectsUnilateralRevoke`; `NativeGateAcceptanceIT.longTermGrantSurvivesGrantorExit` — CI-MAIN; CI-NATIVE; LOCAL-289 | ĐẠT |
| US7.8 | Giáo viên chưa có gói cá nhân lưu học liệu trong A | `ResourceSharingIT.teacherWithoutPersonalPlanSavesAtCenterButNotIntoPersonalLibrary` — CI-MAIN; LOCAL-289 | ĐẠT |
| US7.9 | Đề gồm hai câu hỏi có điều kiện nguồn khác nhau, chia sẻ vượt phạm vi một câu | `ResourceSharingIT.sharingCompositeExamRequiresPermissionFromEveryComponent`; `ResourceAccessIT.bodyAndAnswerAreSeparateReads` — CI-MAIN; LOCAL-289 | ĐẠT |

## US8 — Bảo lưu dữ liệu khi gói hết hạn (7)

| Case | Actor / context | Test / bằng chứng | Kết quả |
| --- | --- | --- | --- |
| US8.1 | Gói cá nhân hết hạn: đọc được; tạo/sửa học liệu, tạo lớp, giao bài mới bị từ chối | `SubscriptionExpiryIT.expiredKeepsReadsLosesCreates` (PR #288 — xanh ở CI-288 tầng native); `PersonalEntitlementIT` 5/5 — CI-MAIN; GATE-95 | ĐẠT |
| US8.2 | Bài cá nhân đã giao: nộp/chấm/công bố theo lịch cũ được; gia hạn hạn nộp, mở lượt mới, giao lại bị từ chối | `CompletionAfterExpiryIT.completionSurvivesExpiry` (PR #288); GATE-95. Điểm lệch đã phán đạt: ba action mở rộng bị PDP trả 403 `PERMISSION_DENIED` thay vì `SUBSCRIPTION_INACTIVE` (tiêu chí chỉ đòi bị từ chối + 403); đội sửa sau đang ánh xạ lại mã | ĐẠT |
| US8.3 | Gói center hết hạn: đọc/phụ huynh xem kết quả công bố được; mở rộng bị từ chối | `TeachingServiceIT.expiredSubscriptionBlocksExpansionButNotRevoke` 15/15; `EnrollmentAssignmentIT.expiredSubscriptionGatesExpansionButNotRevocation`; `CenterAdministrationIT.expiredSubscriptionBlocksInviteButNotRevoke`; `MembershipInvitationIT.expiredSubscriptionBlocksStudentIssue`; nửa đầu ca trình duyệt `subscription-expiry.spec.ts:596` (nộp/chấm/công bố bằng cookie cũ, tăng thêm bị chặn) pass — CI-MAIN; GATE-95; CI-288. Điểm lệch đã phán đạt: `CourseScope.UPDATE` chưa gate gói (đổi tên khóa sau hết hạn vẫn qua PDP; tiêu chí chỉ liệt kê tạo lớp/khóa); đội sửa sau đang gate | ĐẠT |
| US8.4 | Bài tại center đã giao: hoàn tất theo lịch cũ | `CompletionAfterExpiryIT.completionSurvivesExpiry` (center, PR #288); `ResultSourceMutationIT` 5/5 — CI-MAIN; GATE-95 | ĐẠT |
| US8.5 | Center hết hạn: gỡ thành viên/phân công/ghi danh/liên kết vẫn được, hiệu lực từ yêu cầu sau | `SubscriptionExpiryIT.revokeAfterExpiryStillWorks` (PR #288); `TeachingServiceIT.expiredSubscriptionBlocksExpansionButNotRevoke`; `ResultSourceMutationIT` (thu hồi lớp có hoạt động → 202, lan xuống ghi danh/phân công) — CI-MAIN; GATE-95 | ĐẠT |
| US8.6 | Gói cá nhân và gói center độc lập | `SubscriptionExpiryIT.personalAndCenterIndependent` (PR #288); `PersonalEntitlementIT.centerMembershipOpensNoAuthoring`, `personalPlanOpensNoCenterClass` — CI-MAIN; GATE-95 | ĐẠT |
| US8.7 | Gia hạn/kích hoạt lại gói; quan hệ đã gỡ không tự phục hồi; grantor hết gói không tự hủy share; nguồn thu hồi chặn completion | Đạt: `SubscriptionExpiryIT.renewalDoesNotResurrectRevoked` (PR #288, CI-288 tầng native); `CompletionAfterExpiryIT.enrollmentRevokeBlocksCompletion` (PR #288); `NativeGateAcceptanceIT.longTermGrantSurvivesGrantorExit` (CI-NATIVE). **Chưa đạt — nhánh gia hạn gói center có hoạt động dạy học:** ca trình duyệt `subscription-expiry.spec.ts:596` fail tại bước gia hạn (spec:757): operation xác nhận gói trả `FAILED` thay vì `SUCCEEDED` (nguyên văn CI-288). Gốc rễ đã xác minh tại code: `ActivityPolicyProjector.bundleDescriptors` nhúng descriptor `ACTIVITY_ACTOR_PERIOD` theo từng kỳ gói, nhưng `SubscriptionService.partitionChange` không ghi lại target activity khi gia hạn → reconcile dựng descriptor kỳ [P1, P2] khác hash đã lưu [P1] → `DESIRED_SOURCE_MISMATCH`. Test native gia hạn vẫn xanh vì workspace không có activity. Defect đang được sửa ở nhánh `fix/us8-renewal-activity-projections` (chưa land tại thời điểm nghiệm thu) | **CHƯA ĐẠT (một nhánh)** |

## Kịch bản quickstart T1–T16

| Case | Nội dung | Bằng chứng | Kết quả |
| --- | --- | --- | --- |
| T1 | Self-register chưa verified → bị chặn; xác minh email → có quyền nền; tự khai origin issued vô hiệu | `identity.spec.ts` (verify qua Mailpit); `IdentityFlowIT` US1.1/US1.2 — CI-MAIN | ĐẠT |
| T2 | Cấp username student không email, đổi mật khẩu tạm; thêm cùng identity vào B | `MembershipInvitationIT.studentIssue*`; `BootstrapProfileIT.registrar*`; `identity.spec.ts` mật khẩu tạm — CI-MAIN | ĐẠT |
| T3 | Operator tạo A/owner/gói; owner bổ nhiệm manager; teacher thử tạo lớp center | `CenterAdministrationIT` + `TeachingStructureIT` + E2E `center-administration.spec.ts` — CI-MAIN | ĐẠT |
| T4 | Khóa ba môn, nhiều giáo viên; ghi danh chọn môn; thêm môn sau batch | `TeachingStructureIT` + E2E `teaching-administration.spec.ts` — CI-MAIN | ĐẠT |
| T5 | Chủ nhiệm giao đề M3 đã duyệt, thử grade/answer; giáo viên giao đề sai môn | `TeachingAuthorizationIT` (nhóm homeroom); `ResourceAccessIT.bodyAndAnswerAreSeparateReads`; `EnrollmentAssignmentIT.teacherAssignmentRequiresTeacherRoleInSameCenter` — CI-MAIN | ĐẠT |
| T6 | Phụ huynh xem kết quả: đúng con/center/published; đổi sid/wid; chưa duyệt | `guardian-results.spec.ts` 5/5; `PublishedResultContractIT` 7/7 — CI-MAIN | ĐẠT |
| T7 | Giữ phiên, manager revoke phân công tại A rồi gọi lại; thử tại B | `revocation.spec.ts` US5.1/US5.2; `RevocationMatrixIT` — CI-MAIN; LOCAL-289 | ĐẠT |
| T8 | Quyền target assign true nhưng artifact use false; request nhiều permission chỉ một được cấp | `RequiredPermissionsTest`, `CapabilitiesControllerTest`, `ProtectedActionExecutorTest` (unit, job `backend` CI-MAIN); `ResourceSharingIT.sharingCompositeExamRequiresPermissionFromEveryComponent` (FR-041) | ĐẠT |
| T9 | Lưu Q/V1, phát hành V2/correction, nhận cập nhật, fork | `ResourceAccessIT` US7.2/US7.3; `ResourceLibraryReplayIT` — CI-MAIN; LOCAL-289 | ĐẠT |
| T10 | Revoke REVOCABLE qua bản nhận và fork; LONG_TERM khi grantor rời A/hết gói | `ResourceSharingIT` US7.6/US7.7 — CI-MAIN; LOCAL-289 | ĐẠT |
| T11 | Trước/đúng/sau mốc hết hạn (native time policy); create/read/submit/grade/extend | `NativeTimePolicyIT` — CI-NATIVE; `CompletionAfterExpiryIT` (PR #288); `TeachingServiceIT.expiredSubscriptionGatesEveryExpansionExceptRenameCourse` (ngoại lệ rename là điểm lệch `CourseScope.UPDATE` ở US8.3) — CI-MAIN; GATE-95 | ĐẠT |
| T12 | Gỡ enrollment/license rồi thử completion theo ngoại lệ hết hạn | `CompletionAfterExpiryIT.enrollmentRevokeBlocksCompletion` (PR #288); `ResultSourceMutationIT`; `ConcurrentRevocationIT` — CI-MAIN; GATE-95 | ĐẠT |
| T13 | Ngắt provisioner/PDP giữa mutation, crash trước READY, retry idempotency, revoke/write đồng thời | `ConcurrentRevocationIT` 16/16; `IdempotencyServiceIT` 5/5; `OperationStatusIT` 5/5; `ProjectionRecoveryIT` 7/7 — CI-MAIN | ĐẠT |
| T14 | CSRF thiếu/sai; back-channel logout hợp lệ và giả; restart Spring; đổi workspace + back navigation | `SecurityConfigurationIT.csrfRequiredExceptBackChannel`; `OidcSessionPersistenceIT` 7/7; `session-security.spec.ts` 6/6; bước verify OIDC logout — CI-MAIN; CI-NATIVE | ĐẠT |
| T15 | Truy vấn tenant bằng runtime role không context / context A; FK chéo lớp | `TenancyInfrastructureIT` 14/14; `DirectorySchemaIT` 25/25; `TeachingRawConstraintsIT` 4/4 — CI-MAIN. Ghi chú kiến trúc: cách ly hiện hành là schema-per-tenant + runtime role (RLS đã tháo ở Wave 6); văn bản quickstart viết trước thay đổi này, kiểm chứng theo cơ chế hiện hành | ĐẠT |
| T16 | Người nghiệm thu thao tác theo SC-001/002/009 (đo thật) | Thuộc #98, đang thực hiện trên production: NV-1 chu trình email ĐẠT ở địa chỉ mới; NV-2 ĐẠT; NV-3 bị chặn bởi defect trung tâm mới tạo (đội sửa đang xử lý), NV-4/NV-5 chưa chạy. Chưa đủ điều kiện kết luận; không tính bằng chứng kỹ thuật thay thế (đúng cảnh báo trong quickstart) | **CHƯA ĐẠT** |

## FR-001–FR-054 (kết quả suy từ scenario/test phủ tương ứng)

| FR | Phủ bởi | Kết quả |
| --- | --- | --- |
| FR-001 Đăng nhập chung Keycloak, một danh tính nhiều không gian | US1.2, US1.3, T1 | ĐẠT |
| FR-002 Xác minh email trước nghiệp vụ; xác minh/khôi phục vẫn truy cập được | US1.1, US1.7 | ĐẠT |
| FR-003 Tài khoản mới trung tính, quyền nền làm đề hệ thống | US1.2, `SystemPracticeHistoryIT` | ĐẠT |
| FR-004 Mời danh tính có sẵn / cấp tài khoản học sinh không bắt buộc email; rời trung tâm không xóa danh tính | US1.4, US1.5, US1.8 | ĐẠT |
| FR-005 Khôi phục qua email; không email chỉ QTV Giáo Khoa xử lý | US1.6, US1.7 | ĐẠT |
| FR-006 Chuyển không gian hợp lệ; quyền nơi này không cấp quyền nơi khác | US1.3, US2.5 | ĐẠT |
| FR-007 Chỉ QTV tạo tenant/chỉ định owner; xác nhận + kích hoạt/**gia hạn** thủ công | US2.1, US2.2 — nhánh gia hạn center có hoạt động hỏng cùng gốc US8.7 | **ĐẠT MỘT PHẦN** |
| FR-008 Bộ vai trò chung cố định, không vai trò tùy chỉnh | US2.5, `PermissionCatalogTest` | ĐẠT |
| FR-009 Owner bổ nhiệm/gỡ quản lý; quản lý không tự bổ nhiệm owner/quản lý | US2.3 | ĐẠT |
| FR-010 Owner/quản lý quản lý thành viên, lớp, khóa, môn, ghi danh, phân công, liên kết trong đúng trung tâm | US2.4, US3.1 | ĐẠT |
| FR-011 Giáo viên không tạo lớp / tự phân công / mở rộng quyền | US2.6, US3.4 | ĐẠT |
| FR-012 Vai trò quản trị không tự cấp quyền kho cá nhân / trung tâm khác / đọc mọi học liệu | US2.5, US2.7 | ĐẠT |
| FR-013 Lớp/khóa/môn đúng quan hệ; không tham chiếu chéo trung tâm/lớp | US3.1, US3.3, T15 | ĐẠT |
| FR-014 Học sinh của lớp + ghi danh độc lập từng môn | US3.2, US3.3 | ĐẠT |
| FR-015 Chọn một/nhiều/toàn bộ môn khi ghi danh; thêm môn không tự ghi danh/phân công | US3.8 | ĐẠT |
| FR-016 Phân công chỉ rõ giáo viên–trung tâm–lớp–khóa–môn; nhiều–nhiều | US3.4, US3.5 | ĐẠT |
| FR-017 Giáo viên bộ môn chỉ trong phân công còn hiệu lực | US3.4, US5.1 | ĐẠT |
| FR-018 Giao đề đồng thời đúng phân công + môn + quyền dùng tài nguyên | US3.4, US3.6, T8 | ĐẠT |
| FR-019 Chủ nhiệm giao đề nhiều môn đã duyệt, theo dõi hoàn thành | US3.6 | ĐẠT |
| FR-020 Chủ nhiệm không tự có quyền sửa đề / xem đáp án / chấm mọi môn | US3.6, `ResourceAccessIT.bodyAndAnswerAreSeparateReads` | ĐẠT |
| FR-021 Học sinh chỉ nhận/nộp/xem kết quả của mình theo ghi danh + công bố | US3.2, US4.2 | ĐẠT |
| FR-022 Phụ huynh gửi yêu cầu; chỉ owner/quản lý xác nhận/gỡ; chưa duyệt không tạo quyền | US4.1, US4.4 | ĐẠT |
| FR-023 Liên kết gắn đúng phụ huynh–học sinh–trung tâm; A không cấp quyền tại B | US4.3 | ĐẠT |
| FR-024 Phụ huynh chỉ xem kết quả đã công bố của con còn liên kết | US4.2, US4.5 | ĐẠT |
| FR-025 Gói cá nhân hiệu lực mở kho soạn/lớp/giao bài cá nhân | US6.2 | ĐẠT |
| FR-026 Membership/phân công không mở kho soạn cá nhân | US6.1, US6.3 | ĐẠT |
| FR-027 Gói đúng chủ thể/trạng thái/thời gian; xét đúng không gian | US6.4, US8.6 | ĐẠT |
| FR-028 Kích hoạt/**gia hạn** hiệu lực từ yêu cầu tiếp theo; không tự phục hồi quan hệ đã thu hồi | US8.7 — vế "không tự phục hồi" đạt; vế gia hạn center có hoạt động chưa đạt (cùng gốc US8.7) | **ĐẠT MỘT PHẦN** |
| FR-029 Phân biệt không gian tài nguyên cá nhân/trung tâm/hệ thống; sở hữu vs được cấp quyền dùng | US7.1, US7.8 | ĐẠT |
| FR-030 Tự soạn thuộc đúng không gian; tạo tại trung tâm cần phân công biên soạn | US6.3, US7.8 | ĐẠT |
| FR-031 Lưu nguyên bản giữ định danh/phiên bản nguồn, không đổi chủ sở hữu | US7.1 | ĐẠT |
| FR-032 Chỉnh sửa tài nguyên nguồn khác: định danh riêng, truy vết, giữ giới hạn nguồn | US7.3 | ĐẠT |
| FR-033 Phiên bản đã lưu/đã giao ổn định; nhận cập nhật áp dụng lần dùng sau | US7.2 | ĐẠT |
| FR-034 Thông báo đính chính cho nơi đã nhận phiên bản bị ảnh hưởng | US7.2 (`correctionReachesOnlyRecipientsOfAffectedVersion`) | ĐẠT |
| FR-035 Chia sẻ chọn hình thức/bên nhận/phiên bản/hành động/phạm vi; không cấp rộng hơn nguồn | US7.4, US7.5 | ĐẠT |
| FR-036 Tài nguyên cá nhân chia sẻ cho trung tâm nhận vào kho trung tâm theo điều kiện | US7.4 | ĐẠT |
| FR-037 Chia sẻ lâu dài còn hiệu lực khi người chia sẻ rời/hết gói; không đơn phương thu hồi | US7.7 | ĐẠT |
| FR-038 Thu hồi chia sẻ chặn từ yêu cầu tiếp theo kể cả qua bản lưu/chỉnh sửa; giữ lịch sử | US7.6 | ĐẠT |
| FR-039 Lưu kho cá nhân cần quyền kho + điều kiện nguồn; giáo viên dùng tại trung tâm không cần kho cá nhân | US7.5, US7.8 | ĐẠT |
| FR-040 Các hành động xem nội dung/đáp án/dùng/chỉnh sửa/chia sẻ xét riêng | US3.6, US7.3, US7.9 | ĐẠT |
| FR-041 Tài nguyên tổng hợp phải thỏa quyền mọi thành phần | US7.9, T8 | ĐẠT |
| FR-042 Gói cá nhân hết hạn: bảo lưu kho, đọc được; từ chối tạo/sửa/lớp/giao bài mới | US8.1 | ĐẠT |
| FR-043 Gói trung tâm hết hạn: bảo lưu quyền xem; từ chối mở rộng | US8.3 | ĐẠT |
| FR-044 Bài đã giao được nộp/chấm/nhận xét/công bố theo lịch cũ; ngoại lệ không gồm gia hạn/mở lượt/giao lại | US8.2, US8.4 | ĐẠT |
| FR-045 Ngoại lệ hoàn tất còn cần nguồn quyền; không vượt thu hồi | US8.2, T12 | ĐẠT |
| FR-046 Center hết hạn vẫn gỡ được thành viên/phân công/ghi danh/liên kết | US8.5 | ĐẠT |
| FR-047 Bảo lưu giữ nội dung/phiên bản/lịch sử; người bị gỡ không đọc được chỉ vì dữ liệu còn | US8.5, US5.3 | ĐẠT |
| FR-048 Mọi hành động xét danh tính/không gian/đối tượng/hành động/nguồn hiện hành; mặc định từ chối | US5.4, T8, toàn bộ ma trận deny-case | ĐẠT |
| FR-049 Gỡ membership/phân công/ghi danh/liên kết/chia sẻ hiệu lực từ yêu cầu tiếp theo, kể cả phiên cũ | US5.1–US5.3, US4.5, US7.6, T7 | ĐẠT |
| FR-050 Một nguồn hợp lệ là đủ; thu hồi một nguồn không mất nguồn độc lập | US5.5 | ĐẠT |
| FR-051 Không tin thông tin tự khai để mở quyền | US5.4, T15 | ĐẠT |
| FR-052 Chỉ ghi nhận hoàn tất khi thay đổi đã hiệu lực; chưa xong thì báo chưa thành công | US5.6, T13 | ĐẠT |
| FR-053 Kiểm chứng bằng cùng cơ chế quyền vận hành, không cơ chế riêng cho dữ liệu mẫu | Toàn bộ IT chạy qua PDP thật (CI-MAIN, CI-NATIVE) | ĐẠT |
| FR-054 Luồng quản trị sử dụng được cho tài khoản/tenant/gói/thành viên/lớp/khóa/môn/ghi danh/phân công/liên kết; học liệu/bài làm/kết quả ở mức kiểm chứng tối thiểu | E2E `center-administration.spec.ts`, `teaching-administration.spec.ts`, `guardian-results.spec.ts` — CI-MAIN. Phần đo người dùng thật SC-001/002/009 thuộc T16/#98 | ĐẠT (kỹ thuật); phần đo người dùng xem T16 |

## Việc còn lại sau ma trận này

1. **Defect gia hạn gói center có hoạt động** (US8.7 / FR-007 / FR-028): đang sửa ở nhánh
   `fix/us8-renewal-activity-projections`; sau khi land, chạy lại ca
   `subscription-expiry.spec.ts:596` (CI của PR #288) để kết luận lại US8.7.
2. **T16 / #98**: chờ defect trung tâm mới tạo được sửa + deploy, rồi chạy nốt NV-3..NV-5.
3. **FeatureAcceptanceIT.java**: chờ điều phối quyết (xem "Hạn chế phương pháp").
4. Hai điểm lệch không chặn của GATE-95 (mã `PERMISSION_DENIED` thay
   `SUBSCRIPTION_INACTIVE` cho extend/open/reassign sau hết hạn; `CourseScope.UPDATE`
   chưa gate gói): đội sửa sau US8 đang xử lý ở PR riêng; không đổi kết luận ĐẠT của
   US8.2/US8.3 theo tiêu chí hiện hành.
