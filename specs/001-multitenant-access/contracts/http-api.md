# Hợp đồng HTTP và giao diện quản trị

**Ứng dụng**: Spring Boot cung cấp API/BFF; Next.js App Router tiêu thụ hợp đồng. **Prefix**: `/api`. Đối chiếu với code `f3d2e618` ngày 12/11/2026; các mục đánh dấu bổ sung là thiết kế chưa triển khai, không là endpoint đã nghiệm thu.

## Quy tắc chung

- Browser dùng cookie phiên host-only; không nhận hoặc lưu bearer token. `/api/session` cung cấp CSRF token/header name; mọi POST/PUT/PATCH/DELETE từ browser có CSRF hợp lệ, gồm logout. Ngoại lệ duy nhất là inbound OIDC back-channel protocol endpoint, không có browser session/CSRF và phải xác minh signed logout_token bằng Spring handler.
- JSON dùng camelCase, UUID dạng chuỗi, thời gian ISO-8601 UTC. Không nhận actorId, owner override, effective permissions, accountOrigin hoặc Keycloak role/group claims trong body nghiệp vụ.
- `workspaceId` trên route là nơi người dùng yêu cầu hoạt động, không là bằng chứng quyền. Backend resolve dữ kiện/permission và không tin active-workspace cookie. Không cho `X-Tenant-Id` ghi đè route.
- Response bảo vệ có `Cache-Control: private, no-store`; Next fetch `cache: no-store`, không static generation/cache cho dữ liệu theo tài khoản. Không trả secret trong SSR payload.
- Mutation có `Idempotency-Key`; PATCH đổi tên lớp/khóa, PUT role và resolve recovery dùng `If-Match` chứa row version. Guardian decision dùng `expectedVersion` trong body. POST cộng hợp tập và DELETE thu hồi hiện không đòi `If-Match`; không suy yêu cầu header chỉ từ việc target đã tồn tại (B49). Thiếu precondition bắt buộc trả 428; version cũ hoặc cùng idempotency key khác payload trả 409.
- Tạo/cập nhật quyền thường trả 202 `{operationId, state, completed:false, statusUrl}`. Chỉ operation SUCCEEDED/`completed:true` mới là hoàn tất nghiệp vụ; resource mới chưa READY không được sử dụng. Endpoint có thể trả 200/201 khi đã đồng bộ xong, với `completed:true`.
- Khi PDP unavailable hoặc projection PENDING/FAILED, trả 503 với mã cụ thể, không dùng allow cũ. Deny quyền hiện hành trả 403; object không tồn tại hoặc không được phép tiết lộ sự tồn tại trả 404. Không trả nội dung riêng trong message lỗi.
- Query danh sách: `cursor?`, `limit=20`, tối đa 100; kết quả `{items, nextCursor?}` sau lọc quyền từng row. Không trả totalCount chưa được authorize; cursor bound với actor/workspace/query.
- Hành động cần nhiều cặp permission phải kiểm tra đủ từng cặp, theo [authorization.md](authorization.md).

Biên nhận mutation quản trị là `OperationReceipt` (`operationId`), GET status là `OperationView` (`id`); không ép hai DTO thành một. Cấp học sinh bọc trạng thái ngắn trong `operation`; action mẫu trả đồng bộ `SampleActionResult`. Phải đọc đúng loại response của endpoint, không giả mọi response đều có `operationId` hoặc `statusUrl`.

Lỗi thống nhất dạng `application/problem+json`: `type`, `title`, `status`, `code`, `detail` an toàn, `instance`, `traceId`; lỗi field thêm `violations:[{field,code,message}]`. Không lộ stacktrace, policy JSON, credential, email của người ngoài thẩm quyền.

## Kiểu dữ liệu dùng chung

| Kiểu | Các field bắt buộc / tùy chọn |
| --- | --- |
| `SessionView` | `authenticated`, `csrf:{headerName,token}`; khi đã login có `user:{id,username,email?,emailVerified,identityState}`, không có tokens |
| `WorkspaceView` | `id`, `kind:PERSONAL/CENTER/SYSTEM`, `name`, `subscription:{effective,expiresAt?}`, `roles[]`, `syncState` |
| `OperationView` | `id`, `state:PENDING/APPLYING/SUCCEEDED/RETRYABLE_FAILURE/FAILED`, `completed`, `targetType`, `targetId?`, `errorCode?`; không có payload nội bộ |
| `OperationReceipt` | `operationId`, `state`, `completed`, `statusUrl`; dùng cho mutation quản trị có durable operation |
| `SampleActionResult` | `id`, `state`, `completed`, `targetType`, `targetId`, `correctionNotice?`; action đồng bộ trả 201 sau hoàn tất, không là URL poll authorization operation |
| `CenterCreate` | `name`, `ownerUserId`; owner đã có danh tính; kích hoạt gói là thao tác riêng |
| `SubscriptionConfirm` | `workspaceId`, `validFrom`, `expiresAt`, `confirmationRef`; actor/thời điểm xác nhận do server ghi |
| `InvitationCreate` | đúng một `email` hoặc `userId`, `roles[]`; không có OWNER; MANAGER chỉ owner được cấp |
| `StudentIssue` | `username`, `displayName`; email tùy chọn, không có origin/globalRole/password do client chọn |
| `StudentIssueView` | `operation:{id,state,completed}`, `handoff?:{username,temporaryPassword}`; handoff chỉ ở response CREATE đầu tiên sau tạo identity thành công, không trong status poll/retry/list/SSR |
| `MembershipView` | `id`, `user:{id,username,displayName}`, `roles[]`, `state`, `version`; thông tin tối thiểu của thành viên đúng center |
| `ClassCreate` | `name`; workspace lấy từ route |
| `CourseCreate` | `name`, `subjectIds[]` không rỗng; class lấy từ route |
| `CourseView` | `id`, `classId`, `name`, `subjects:[{id,subjectId,name}]`, `version` |
| `EnrollmentBatch` | `classStudentIds[]`, `courseSubjectIds[]`; mỗi phần tử phải cùng lớp; không có all-future-subjects |
| `TeacherAssignment` | `teacherUserIds[]`; courseSubject lấy từ route |
| `HomeroomAssignment` | `teacherUserId`; class lấy từ route |
| `GuardianRequest` | `studentUserId`; requester là actor hiện hành; field lạ (ví dụ `parentUserId` của người khác) bị bỏ qua, không tạo link cho phụ huynh khác |
| `GuardianDecision` | `decision:APPROVE/REJECT`, `expectedVersion`; approver do server ghi. **Theo FR-056**: `verificationRef` (1–160 ký tự) bắt buộc khi APPROVE, chỉ giữ tham chiếu xác minh ngoài hệ thống; không trả trong GuardianLinkView; không bắt buộc khi REJECT |
| `GuardianLinkView` | `id`, `workspaceId`, `parentUserId`, `studentUserId`, `state`, `version`; danh sách trả dạng `{items}`, không `totalCount` |
| `PublishedResultView` | `id`, `student:{id,displayName}`, `className`, `courseName`, `subjectName`, `score?`, `comment?`, `publishedAt`; không chứa submission/body/answer/draft fields |
| `CapabilitiesRequest` | `targets:[{type,id,versionId?,scopes[]}]`; chỉ đối tượng trong route workspace và actor hiện hành |
| `CapabilitiesView` | từng target/scope có `allowed`, `reasonCode`; metadata cần cho UI, không có native policy/group ID hoặc grant cho người khác |

Trong contract, các scope `read`, `update`, `revoke` luôn đi kèm loại đối tượng được chỉ rõ, không là quyền toàn cục.

## Phiên, đăng ký và khôi phục

| Method / path | Request / response | Bảo vệ / hành vi |
| --- | --- | --- |
| `GET /api/session` | SessionView, kể cả anonymous | Không trả dữ liệu center; bootstrap CSRF và trạng thái onboarding |
| `GET /oauth2/authorization/keycloak` | Redirect Authorization Code | Spring tạo state/nonce/PKCE, returnTo chỉ path cùng origin |
| `GET /login/oauth2/code/keycloak` | Callback OIDC | Spring xác minh flow; không cho open redirect |
| `POST /api/logout` | Form CSRF; redirect logout SSO | Hủy session ứng dụng và RP-Initiated Logout; không logout mọi user |
| `POST /logout/connect/back-channel/keycloak` | Form `logout_token` do Keycloak gửi | Spring OIDC handler xác minh chữ ký/issuer/audience/events/sid hoặc sub và correlation; miễn browser CSRF/session authentication chỉ route này, không miễn các API khác |
| `POST /api/identity/verification` | Yêu cầu luồng thêm/xác minh email của chính actor | Cho onboarding chưa eligible; gửi tới email/profile Keycloak, không nhận emailVerified/actor override |
| `GET /api/me/workspaces` | WorkspaceView[] được phép | account `workspace.list`; chưa eligible chỉ trả onboarding, không lộ membership payload |
| `GET /api/me/practice-history` | Lịch sử practice mẫu của chính user | account `history.read`, không cần gói soạn học liệu; không chứa kết quả của người khác |
| `POST /api/me/recovery-requests` | `{targetUserId,workspaceId?,reason}` → OperationReceipt/requestId | Chỉ tự yêu cầu hoặc quản lý center gửi đề nghị hợp lệ; không đổi password ở đây, không tiết lộ tài khoản ngoài phạm vi |
| `GET /api/me/operations/{operationId}` | OperationView | Chỉ operation của initiator; control metadata, không trả payload nghiệp vụ đang fenced |

Đăng ký và quên mật khẩu có email đi qua màn hình/required actions của Keycloak; không xây kho mật khẩu trong Next hoặc Spring. Người self-register không email có thể có danh tính nhưng chỉ dùng onboarding/khôi phục đến khi email verified. Center-issued có exception từ record nguồn do server tạo, không từ form đăng ký.

Cấp học sinh mới tạo identity/temporary credential đồng bộ trước khi trả StudentIssueView, còn graph projection có thể tiếp tục async với operation PENDING. Nếu cần giao credential không email, handoff chỉ có trong response CREATE đầu tiên, không log/lưu plaintext/echo trong poll/retry/danh sách hoặc SSR. Nếu tạo identity thành công nhưng ghi app-db thất bại, identity đó chưa có eligibility/quyền và phải reconcile/compensate an toàn. Mất response/credential đi qua khôi phục tập trung; API này không là đường reset tài khoản hiện có cho quản lý.

## Vận hành nền tảng

| Method / path | Body / response | Scope |
| --- | --- | --- |
| `GET /api/platform/centers?cursor&limit` | `{items:CenterView[],nextCursor?}`; `limit=20`, tối đa 100, keyset theo `(created_at,id)` | platform `center.list`; chỉ metadata, không kèm kết quả/học liệu |
| `POST /api/platform/centers` | CenterCreate → OperationReceipt | platform `center.create`; tạo Organization, workspace, owner membership và projection |
| `POST /api/platform/subscriptions` | SubscriptionConfirm → OperationReceipt | platform `subscription.confirm`; không gọi payment gateway |
| `GET /api/platform/recovery-requests` | Danh sách yêu cầu hỗ trợ tối thiểu | platform `account.recover` |
| `POST /api/platform/recovery-requests/{id}/resolve` | `{decision:APPROVE/COMPLETE/REJECT,verificationNote?}` → OperationReceipt | platform `account.recover`; APPROVE chỉ xác minh, COMPLETE xác nhận đã khôi phục/bàn giao, REJECT từ chối; không gửi password cho center |

Recovery list trả thêm `version` cho từng yêu cầu. Resolve bắt buộc `Idempotency-Key` và `If-Match: "<version>"`; cùng key/body/version replay biên nhận, payload khác trả 409. APPROVE cần ghi chú xác minh và trả 202/PENDING, yêu cầu giữ VERIFIED. Operator khôi phục bằng Keycloak và bàn giao qua kênh riêng cho đúng người dùng; sau đó COMPLETE kèm ghi chú xác nhận mới ghi RESOLVED và trả completed=true. Chưa thực hiện xong thì giữ VERIFIED. Operator đã gửi mutation có thể poll biên nhận của mình qua operations; không trả credential trong list/poll/SSR.

## Thành viên trung tâm

Các route này chỉ dùng workspace kind CENTER.

| Method / path | Body / response | Scope / lưu ý |
| --- | --- | --- |
| `GET /api/workspaces/{wid}/memberships` | MembershipView page | workspace `membership.list` |
| `POST /api/workspaces/{wid}/invitations` | InvitationCreate → OperationReceipt | `membership.invite`; nếu cấp manager cần thêm `manager.assign` |
| `POST /api/invitations/{token}/accept` | Không actorId → OperationReceipt | Actor login đúng sub/email xác minh; token một lần; không đổi origin account |
| `POST /api/workspaces/{wid}/students` | StudentIssue → StudentIssueView | `student.issue`; chỉ create user mới; username trùng không reset/chiếm user đã có |
| `PUT /api/workspaces/{wid}/memberships/{id}/roles` | `{roles[]}` → OperationReceipt | Phần thêm cần `membership.role.assign`/`manager.assign`; phần gỡ cần `membership.role.revoke`/`manager.revoke` |
| `DELETE /api/workspaces/{wid}/memberships/{id}` | OperationReceipt | `membership.revoke`; không xóa Keycloak account; không gỡ owner cuối cùng |

Gói hết hạn chặn invite/cấp account/thêm role; các scope thu hồi còn dùng được theo thẩm quyền. Membership có nhiều role không được cho phép một source bị gỡ làm mất mọi quyền độc lập còn lại.

## Lớp, khóa, môn và ghi danh

| Method / path | Body / response | Scope / invariant |
| --- | --- | --- |
| `GET /api/workspaces/{wid}/classes` | Class page | workspace `class.list` + từng class `read` |
| `POST /api/workspaces/{wid}/classes` | ClassCreate → OperationReceipt | workspace `class.create`; center owner/manager, personal owner+gói |
| `GET /api/workspaces/{wid}/classes/{cid}` | Class metadata | class `read`; không kèm toàn roster |
| `PATCH /api/workspaces/{wid}/classes/{cid}` | `{name}` → OperationReceipt | class `update`; cần gói active |
| `DELETE /api/workspaces/{wid}/classes/{cid}` | OperationReceipt | Thu hồi lớp: chuyển REVOKED, giữ lịch sử, đóng quan hệ phụ thuộc; chưa có scope revoke riêng trong catalog — quyết quyền quản trị theo ngoại lệ ở authorization.md mục 11 |
| `GET /api/workspaces/{wid}/classes/{cid}/students` | Class-student page | class `roster.read`; manager/owner/homeroom theo source; teacher không mặc định thấy mọi học sinh lớp |
| `POST /api/workspaces/{wid}/classes/{cid}/students` | `{studentUserIds[]}` → OperationReceipt | class `roster.add`; CENTER cần membership, PERSONAL cần owner tổ chức và danh tính người học hợp lệ |
| `DELETE /api/workspaces/{wid}/classes/{cid}/students/{id}` | OperationReceipt | class `roster.revoke`; revoke enrollment phụ thuộc, giữ lịch sử |
| `GET /api/workspaces/{wid}/classes/{cid}/homeroom-assignments` | Homeroom assignment page | class `roster.read`; quản trị hoặc chủ nhiệm hiện hành của lớp; chỉ phân công hiện hành, kèm id cho route thu hồi |
| `POST /api/workspaces/{wid}/classes/{cid}/homeroom-assignments` | HomeroomAssignment → OperationReceipt | class `homeroom.assign` |
| `DELETE /api/workspaces/{wid}/classes/{cid}/homeroom-assignments/{id}` | OperationReceipt | class `homeroom.revoke` |
| `GET /api/workspaces/{wid}/classes/{cid}/courses` | Course page | class `course.list` + từng course `read` |
| `POST /api/workspaces/{wid}/classes/{cid}/courses` | CourseCreate → OperationReceipt | class `course.create`; ít nhất một môn, đúng lớp/workspace |
| `GET /api/workspaces/{wid}/courses/{kid}` | CourseView | course `read` |
| `PATCH /api/workspaces/{wid}/courses/{kid}` | `{name}` → OperationReceipt | course `update` |
| `DELETE /api/workspaces/{wid}/courses/{kid}` | OperationReceipt | Thu hồi khóa: chuyển REVOKED, giữ lịch sử, đóng ghi danh/phân công phụ thuộc; chưa có scope revoke riêng trong catalog — quyết quyền quản trị theo ngoại lệ ở authorization.md mục 11 |
| `POST /api/workspaces/{wid}/courses/{kid}/subjects` | `{subjectIds[]}` → OperationReceipt | course `subject.add`; không tự ghi danh/phân công |
| `DELETE /api/workspaces/{wid}/courses/{kid}/subjects/{csid}` | OperationReceipt | Gỡ môn khỏi khóa: chuyển REVOKED, giữ lịch sử, đóng ghi danh/phân công phụ thuộc; chưa có scope revoke riêng trong catalog — quyết quyền quản trị theo ngoại lệ ở authorization.md mục 11 |
| `GET /api/subjects` | Danh mục tham chiếu tối thiểu | Danh mục không chứa dữ liệu riêng; không tạo taxonomy chương trình |
| `GET /api/workspaces/{wid}/course-subjects/{csid}/enrollments` | Enrollment page | course-subject `roster.read`; chỉ roster môn đó |
| `POST /api/workspaces/{wid}/enrollments` | EnrollmentBatch → OperationReceipt | Mỗi course-subject `enrollment.add`; tất cả cùng lớp, kiểm tra đủ cặp |
| `DELETE /api/workspaces/{wid}/enrollments/{id}` | OperationReceipt | course-subject `enrollment.revoke` |
| `GET /api/workspaces/{wid}/course-subjects/{csid}/teachers` | Teacher assignment page | course-subject `roster.read`; chỉ phân công hiện hành, kèm id cho route thu hồi |
| `POST /api/workspaces/{wid}/course-subjects/{csid}/teachers` | TeacherAssignment → OperationReceipt | course-subject `teacher.assign`; hỗ trợ nhiều giáo viên |
| `DELETE /api/workspaces/{wid}/course-subjects/{csid}/teachers/{id}` | OperationReceipt | course-subject `teacher.revoke` |

Batch có cùng idempotency key, kiểm tra invariant trước khi tạo desired changes. Không trả hoàn tất một phần như toàn bộ thành công; tất cả projection liên quan READY mới operation SUCCEEDED. Không mở DELETE vật lý class/course/subject làm mất lịch sử.

Trong PERSONAL workspace, owner thực hiện class/course/roster/ghi danh và giao/chấm/công bố theo gói; không yêu cầu center_membership hoặc teacher assignment center. Người học personal dựa class_student + enrollment. Không mở UI đồng giảng hoặc homeroom/manager riêng cho lớp cá nhân; các route phân công giáo viên/chủ nhiệm center không áp dụng cho personal.

## Phụ huynh và kết quả

| Method / path | Body / response | Scope / invariant |
| --- | --- | --- |
| `POST /api/workspaces/{wid}/guardian-links` | GuardianRequest → OperationReceipt | workspace `guardian.request`; parent là actor, không nhận parentId khác; lỗi nghiệp vụ: `GUARDIAN_PARENT_NOT_CURRENT` 403, `GUARDIAN_STUDENT_NOT_FOUND` 404, `GUARDIAN_LINK_ALREADY_CURRENT` 409, `GUARDIAN_PREREQUISITE_NOT_CURRENT` 409 |
| `GET /api/workspaces/{wid}/guardian-links` | Danh sách theo quyền | Parent chỉ các request của mình; owner/manager được danh sách cần duyệt; không lộ kết quả qua route này; không có quyền xem → 403 `PERMISSION_DENIED` |
| `POST /api/workspaces/{wid}/guardian-links/{id}/decision` | GuardianDecision → OperationReceipt | workspace `guardian.approve`; teacher/homeroom đứng riêng bị từ chối; lỗi nghiệp vụ: `GUARDIAN_LINK_NOT_FOUND` 404, `GUARDIAN_DECISION_INVALID` 400, `GUARDIAN_LINK_ALREADY_DECIDED` 409, `VERSION_CONFLICT` 409 khi `expectedVersion` lệch, `GUARDIAN_PREREQUISITE_NOT_CURRENT` 409, `SUBSCRIPTION_INACTIVE` 403 chỉ khi APPROVE |
| `DELETE /api/workspaces/{wid}/guardian-links/{id}` | OperationReceipt | workspace `guardian.revoke`; vẫn cho revoke khi gói hết hạn; lỗi nghiệp vụ: `GUARDIAN_LINK_NOT_FOUND` 404, `GUARDIAN_LINK_NOT_APPROVED` 409, `GUARDIAN_LINK_ALREADY_CLOSED` 409, `GUARDIAN_LINK_ALREADY_REVOKED` 409 |
| `GET /api/workspaces/{wid}/students/{sid}/results?cursor&limit` | `{items:PublishedResultView[],nextCursor?}`, `limit=20`, tối đa 100; không `totalCount` | Từng result `read.published`; parent đúng child/center/link và published; tập được phép rỗng vẫn kiểm tra ACCOUNT eligibility/fence: học sinh tự đọc 200 rỗng, actor khác 403 `PERMISSION_DENIED` |

Kết quả bản đầu là sample_result cùng policy path sẽ dùng cho nghiệp vụ sau. Không xây sổ điểm, không có endpoint parent lấy bài làm/đáp án. Parent không được thay sid để đọc người khác hoặc thay wid để dùng liên kết A tại B.

Luồng parent bản đầu bắt đầu sau khi trung tâm mời/cấp vai trò PARENT trong membership; bản thân vai trò chưa có quyền xem con. Parent gửi request cho chính mình, manager xác nhận đúng học sinh. Không mở self-join trung tâm hoặc tự khai vai trò phụ huynh qua đăng ký chung.

## Hợp đồng kiểm chứng quyền

| Method / path | Body / response | Quy tắc |
| --- | --- | --- |
| `POST /api/workspaces/{wid}/capabilities` | CapabilitiesRequest → CapabilitiesView | Xét actor hiện tại, đủ từng target/scope; chỉ phục vụ UI, không cấp token quyền |
| `POST /api/verification/fixtures` | `{scenarioSet}` → fixture IDs | Chỉ profile test/review và platform `fixture.manage`; production không đăng ký route |
| `GET /api/workspaces/{wid}/samples/resources/{rid}/versions/{vid}` | Stub body | Resource-context `content.read`; answer không có trong DTO |
| `GET /api/workspaces/{wid}/samples/resources/{rid}/versions/{vid}/answer` | Stub answer | Resource-context `answer.read` riêng |
| `POST /api/workspaces/{wid}/samples/actions` | SampleAction → SampleActionResult | Mọi action đi cùng domain/PDP; không nhận actor thay thế |

`SampleAction` có discriminator `action` thuộc danh mục cố định và các field cần thiết:

| action | Field | Permission bắt buộc |
| --- | --- | --- |
| `PRACTICE_SYSTEM_VERSION` | `resourceId`, `versionId`, `contentStub` | System resource `practice` + identity eligible; không cần gói author/enrollment; ghi lịch sử mẫu vào personal workspace của actor |
| `CREATE_RESOURCE` | `courseSubjectId?`, `subjectId`, `kind`, `label` | personal owner+gói hoặc target `resource.create`/content source hợp lệ |
| `SAVE_VERSION` | `resourceId`, `versionId` | `library.save` trong workspace nhận + source/license đúng version |
| `FORK_VERSION` | `resourceId`, `versionId`, `label` | `fork` + quyền tạo tại nơi nhận; tạo resource mới, giữ source dependencies |
| `SHARE_VERSION` | `resourceId`, `versionId`, `recipientWorkspaceId`, `mode`, `actions`, `allowedWorkspaceIds`, `expiresAt?` | `share` và không rộng hơn nguồn; không nhận grantor/owner override |
| `ACCEPT_SHARE` | `grantId` | grant `accept` trong workspace nhận |
| `REVOKE_SHARE` | `grantId` | grant `revoke`, chỉ REVOCABLE; LONG_TERM từ chối |
| `RECEIVE_UPDATE` | `libraryItemId`, `replacementVersionId` | `library.save` + nguồn cập nhật; không đổi hoạt động/lịch sử đã giao |
| `CORRECT_SOURCE` | `resourceId`, `affectedVersionId`, `replacementVersionId`, `reason` | Bản mẫu B87 dùng version `review` tại đúng workspace sở hữu; chưa có projector resource `version.create`. Tạo correction_notice, không sửa V1; không tự mở quyền điều chỉnh điểm đã công bố |
| `ASSIGN_ACTIVITY` | `courseSubjectId`, `resourceId`, `versionId`, `dueAt`, `attemptLimit`; **bổ sung chưa triển khai** `completionPolicy:INTERRUPTIBLE/GUARANTEED` | target `activity.assign` + từng source/version `use`; đúng môn; code mẫu hiện đòi APPROVED cho mọi người giao. FR-057 bổ sung GUARANTEED từ chối mọi nguồn REVOCABLE và LONG_TERM có `expiresAt` hữu hạn trong closure khi chưa định hạn chấm/công bố; `dueAt` chỉ là hạn nộp |
| `SUBMIT_ACTIVITY` | `activityId`, `contentStub` | activity `submit`, actor đang ghi danh đúng center/personal; attempt server xác lập; system practice dùng action riêng. FR-044/045 còn phải bổ sung kiểm hạn nộp và nguồn học liệu cần dùng; code hiện chưa đủ |
| `GRADE_RESULT` | `resultId`, `scoreStub`, `commentStub?` | result `grade`; FR-045 còn phải bổ sung gate license/thành phần áp dụng cho chấm, không đòi gói còn hạn |
| `PUBLISH_RESULT` | `resultId` | result `publish`; parent branch chỉ có hiệu lực khi chuyển PUBLISHED và sync hoàn tất; FR-045 còn phải bổ sung gate license cần thiết |
| `EXTEND_DEADLINE` | `activityId`, `dueAt` | activity `schedule.extend`, gói active |
| `OPEN_ATTEMPT` | `activityId`, `studentUserId` | activity `attempt.open`, gói active |
| `REASSIGN_ACTIVITY` | `activityId`, `dueAt` | activity `reassign` + target/source permissions, gói active |

Không có action tự đặt actor, tự gán role hoặc duyệt học liệu nhiều bước. Fixture có trạng thái APPROVED để kiểm chứng chủ nhiệm. API kiểm chứng không chứa engine quyết định riêng, không dùng sample flag để bỏ gate.

**Bổ sung FR-055, chưa có route trong code**: `POST /api/workspaces/{wid}/courses/{kid}/students/{sid}/complete` hoàn thành **đợt của một học sinh tại một khóa**, không phải từng enrollment. Body `{expectedEnrollments:[{id,version}]}` là danh sách đầy đủ ID/row version của mọi ghi danh ACTIVE của học sinh tại các môn trong khóa, lấy từ roster được phép đọc; bắt buộc `Idempotency-Key`. Đây là ngoại lệ precondition theo tập thay vì `If-Match` của một row. Backend so sánh tập và version trong cùng transaction với khóa bảo vệ khỏi ghi danh mới đồng thời; thiếu danh sách/key trả 428, lệch tập/version trả 409 (không thay đổi phần nào), sai khóa/học sinh/workspace trả 404. Mọi enrollment ACTIVE phù hợp cùng chuyển COMPLETED trong một mutation/operation/revision, không đụng ghi danh REVOKED hoặc khóa khác; `OperationReceipt` chỉ hoàn tất sau SUCCEEDED. GET kết quả hiện có giữ DTO published-only và thêm nguồn lịch sử của chính học sinh theo authorization contract mục 14. Scope completion riêng phải được review trước triển khai, không tái dùng quyền thu hồi để tăng quyền. Schema/service/UI/test ở T104/T105.

**Theo FR-056 (PR #323, phải kiểm native trước merge)**: APPROVE thiếu `verificationRef` hợp lệ trả 400 `GUARDIAN_VERIFICATION_REQUIRED` và không đổi link/audit nguồn quyền; người/thời điểm xác nhận lấy từ server. Tham chiếu không chứa giấy tờ nhạy cảm và chỉ phục vụ người có thẩm quyền duyệt.

Thông báo đính chính cho bên nhận (FR-034) hiện mới có receipt lưu trong DB và payload `CORRECT_SOURCE` cho người phát hành. Chưa có đường đọc notification của workspace nhận trong HTTP/UI; cần contract đọc metadata sau authorization và UI nhận cập nhật. Không coi việc tồn tại receipt là đã thông báo được cho người nhận.

## Màn hình Next.js

| Route UI | Chức năng | Nguồn dữ liệu |
| --- | --- | --- |
| `/` | Giới thiệu/quyền nền và điều hướng đăng nhập | Nội dung công khai; không dùng protected cache |
| `/onboarding` | Trạng thái email, hướng dẫn xác minh/khôi phục | Session và identity endpoints |
| `/personal` | Trạng thái gói/kho cá nhân, giới hạn quyền; mẫu trong profile review | Workspace/capabilities; không giả đã có trình soạn |
| `/personal/classes` | Lớp/khóa/môn/roster và ghi danh cá nhân theo gói | Teaching APIs với personal workspace; tái dùng component, không giả vai trò quản lý center |
| `/centers/{wid}` | Chọn và xem ngữ cảnh trung tâm | Workspace được phép |
| `/centers/{wid}/members` | Thành viên/lời mời/cấp học sinh | Membership APIs |
| `/centers/{wid}/classes` | Lớp, khóa, môn, ghi danh, phân công | Teaching APIs |
| `/centers/{wid}/guardian-links` | Phụ huynh đề nghị, quản lý duyệt | Guardian APIs |
| `/centers/{wid}/children/{sid}/results` | Kết quả công bố của con từ mẫu | PublishedResultView |
| `/platform` | Tenant, gói thủ công, khôi phục không email | Platform APIs |
| `/review` | Chạy các action mẫu khi profile review bật | Verification APIs, cùng PDP |

UI luôn cho biết workspace và trạng thái PENDING/FAILED/SUCCEEDED. Ẩn nút không thay kiểm tra backend. Không hiển thị khái niệm policy graph/native group/revision cho người dùng; lỗi nghiệp vụ giải thích ngắn như chưa có phân công, gói hết hạn, hoặc đang cập nhật quyền.
