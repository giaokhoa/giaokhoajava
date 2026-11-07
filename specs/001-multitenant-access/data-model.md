# Mô hình dữ liệu: danh tính, trung tâm và quyền theo đối tượng

**Căn cứ**: [spec.md](spec.md), [research.md](research.md). Đây là thiết kế dữ liệu, chưa phải migration hoặc entity triển khai.

## Quy ước chung

- ID nghiệp vụ dùng UUID do ứng dụng cấp; `keycloak_sub`, Organization ID, group ID, policy/resource ID là định danh opaque từ Keycloak, không thay thế khóa nghiệp vụ.
- Thời gian lưu UTC; mốc hiệu lực trao đổi với native time policies thống nhất độ chính xác giây và phải qua gate biên thời gian. Quy ước hiệu lực nghiệp vụ là `[valid_from, expires_at)`; không cho scheduler là nguồn duy nhất xác định hết hạn.
- Bản ghi thay đổi có `created_at`, `updated_at`, `version` phục vụ optimistic concurrency. Quan hệ đã tạo lịch sử chuyển trạng thái, không hard-delete mất dữ liệu phụ thuộc.
- Bảng trong tenant có `workspace_id` NOT NULL. Khóa ngoại tổng hợp kèm `workspace_id` chứng minh đúng lớp–khóa–môn; UUID khó đoán không thay authorization.
- Scope và loại role là danh mục cố định, không có bảng custom-role/permission editor.
- Không lưu mật khẩu Keycloak hoặc đọc database Keycloak. App-db và Keycloak-db tách riêng; phiên ứng dụng ở app-db theo schema Spring Session JDBC quản lý qua Flyway. Authorized client/token dùng HttpSessionOAuth2AuthorizedClientRepository trong phiên JDBC; thêm bảng framework `oidc_session_correlation` cho issuer/client/sub/sid/session_id, có vòng đời theo session, không giữ registry chỉ trong bộ nhớ.

## Directory và gói

| Entity | Trường nghiệp vụ chính | Ràng buộc |
| --- | --- | --- |
| `user_account` | `id`, `keycloak_sub`, `username`, `email?`, `email_verified`, `origin`, `identity_state` | Unique sub/username; origin `SELF_REGISTERED` hoặc `CENTER_ISSUED` do server xác lập, không có user_type teacher/student/parent toàn cục; email là thông tin đồng bộ, Keycloak là nguồn danh tính |
| `workspace` | `id`, `kind`, `owner_user_id?`, `center_id?`, `state` | `PERSONAL` có đúng một owner; `CENTER` có đúng một center; `SYSTEM` không thuộc cá nhân/trung tâm; mỗi user có một personal workspace |
| `center` | `id`, `workspace_id`, `keycloak_organization_id`, `name`, `owner_user_id`, `state` | Unique workspace/Organization; chủ sở hữu có membership hợp lệ; không tự chuyển chủ sở hữu ở bản đầu |
| `center_membership` | `id`, `workspace_id`, `user_id`, `state`, `joined_at`, `revoked_at?` | Một quan hệ hiện hành mỗi user/workspace; danh tính tồn tại độc lập với membership |
| `membership_role` | `membership_id`, `role` | Role cố định `OWNER`, `MANAGER`, `TEACHER`, `STUDENT`, `PARENT`; một membership có nhiều role; OWNER khớp center.owner_user_id; không nhận role ngoài danh mục |
| `invitation` | `id`, `workspace_id`, `target_email?`, `target_user_id?`, `token_hash`, `intended_roles`, `expires_at`, `state` | Token một lần; không lưu token thô; người chấp nhận phải đúng sub đích hoặc chứng minh email đích đã xác minh; tên trùng không đủ ghép tài khoản |
| `subscription_period` | `id`, `workspace_id`, `valid_from`, `expires_at`, `confirmation_ref`, `confirmed_by`, `confirmed_at` | Chỉ gói personal/center; thời gian kết thúc lớn hơn bắt đầu; xác nhận thủ công bởi platform operator; không có payment gateway hoặc giá gói trong bản đầu |
| `recovery_request` | `id`, `requester_user_id`, `workspace_id?`, `target_user_id`, `state`, `verified_by?`, `resolved_at?`, `verification_note?` | Chỉ platform operator xử lý tài khoản không email; ghi chú tối thiểu, không đính kèm tài liệu cá nhân trong tính năng này; không trả mật khẩu cho quản lý trung tâm |

Membership/role trong app là dữ kiện nguồn để điều phối directory và projection. Keycloak Organization membership và các policy sources chỉ được báo ACTIVE khi cập nhật đã hoàn tất. Không coi một role trong database hoặc token là một quyết định allow.

Role là tiền đề của quan hệ center tương ứng: cấp phân công giảng dạy/chủ nhiệm cần TEACHER, roster/ghi danh center cần STUDENT, liên kết phụ huynh cần PARENT. Khi gỡ role, cùng mutation phải thu hồi các quan hệ phụ thuộc role đó hoặc đóng gate tiền đề tương ứng; không để phân công mồ côi tiếp tục cấp quyền. Những role/quan hệ độc lập khác của cùng người được giữ. PERSONAL dùng owner và enrollment riêng, không đòi các role center.

Gói đã được xác nhận có trạng thái hiệu lực suy ra từ thời gian. Gia hạn tạo kỳ xác nhận mới hoặc cập nhật kỳ tương lai có kiểm soát phiên bản, giữ lịch sử xác nhận. Graph gói OR các kỳ hợp lệ; không dựa vào một cờ active được cập nhật trễ.

## Dạy học

| Entity | Trường chính | Ràng buộc |
| --- | --- | --- |
| `school_class` | `id`, `workspace_id`, `name`, `state` | Tạo trong center cần owner/manager; tạo personal cần gói; khác workspace không chia sẻ class |
| `class_student` | `id`, `workspace_id`, `class_id`, `student_user_id`, `state`, `revoked_at?` | Unique class/student hiện hành; center student cần membership tương ứng; dữ liệu lịch sử còn tham chiếu |
| `subject` | `id`, `code`, `name`, `state` | Danh mục môn tham chiếu tối thiểu; mã duy nhất; không xây cây chương trình GDPT hoặc taxonomy đầy đủ |
| `course` | `id`, `workspace_id`, `class_id`, `name`, `state` | Chính xác một lớp cùng workspace; chưa có khóa gom nhiều lớp |
| `course_subject` | `id`, `workspace_id`, `class_id`, `course_id`, `subject_id`, `state` | Unique course/subject hiện hành; composite FK kiểm tra course thuộc class; cùng môn ở hai khóa là hai phạm vi quyền |
| `homeroom_assignment` | `id`, `workspace_id`, `class_id`, `teacher_user_id`, `state` | Gắn lớp, không gắn mọi môn như quyền chấm; actor là thành viên hợp lệ; tránh nguồn phân công trùng |
| `teaching_assignment` | `id`, `workspace_id`, `course_subject_id`, `teacher_user_id`, `state` | Nhiều giáo viên cùng course_subject, một giáo viên nhiều course_subject; unique cặp hiện hành |
| `enrollment` | `id`, `workspace_id`, `class_student_id`, `course_subject_id`, `state` | Hai phía cùng lớp/workspace; unique cặp hiện hành; ghi danh tất cả chỉ tạo cho môn được chọn lúc thao tác |
| `guardian_link` | `id`, `workspace_id`, `parent_user_id`, `student_user_id`, `state`, `requested_by`, `approved_by?`, `approved_at?`, `revoked_at?` | Actor parent hiện hành trong center khi được duyệt; child thuộc phạm vi center; chỉ owner/manager duyệt; không đồng nhất với quan hệ gia đình toàn nền tảng |

Bản đầu lớp cá nhân dùng cùng cấu trúc lớp/khóa/môn/ghi danh. Chủ personal workspace là người tổ chức theo gói; không có manager/teacher trung tâm được kế thừa sang đó hoặc chức năng đồng giảng cá nhân riêng.

## Tài nguyên mẫu và nguồn quyền

| Entity | Trường chính | Ràng buộc |
| --- | --- | --- |
| `resource` | `id`, `owner_workspace_id`, `created_by_user_id`, `kind`, `subject_id`, `state` | Owner bất biến trong thao tác thường; kind mẫu tương ứng câu hỏi/đề/ma trận; created_by không tự là nguồn quyền của tài nguyên center |
| `resource_version` | `id`, `resource_id`, `version_no`, `label`, `body_stub`, `answer_stub?`, `review_state`, `locked_at?` | Unique resource/version_no; V1 đã lưu/giao không bị sửa tại chỗ; body/answer chỉ dữ liệu giả để xét quyền; câu trả lời có scope riêng |
| `resource_dependency` | `id`, `derived_resource_id`, `derived_version_id`, `source_resource_id`, `source_version_id`, `share_grant_id?`, `usage_constraints` | Đúng version/resource hai phía; graph không vòng; provenance bất biến, không xóa giới hạn nguồn khi tạo bản chỉnh sửa |
| `library_item` | `id`, `workspace_id`, `resource_id`, `version_id`, `share_grant_id?`, `custody` | Cặp resource/version tham chiếu cùng nguồn; custody `OWNED` hoặc `LICENSED`; mục lưu có ID riêng nhưng resource ID/phiên bản nguồn không đổi; LICENSED không đổi owner canonical |
| `resource_assignment` | `id`, `workspace_id`, `user_id`, `resource_id?`, `subject_id?`, `actions`, `state` | Nguồn quyền biên soạn/duyệt/xem đáp án/chia sẻ tường minh thuộc danh mục hành động cố định; bản đầu chuẩn bị nguồn này bằng fixture/luồng kiểm chứng, chưa mở trình cấu hình quyền tùy ý |
| `share_grant` | `id`, `source_resource_id`, `source_version_id`, `grantor_user_id`, `recipient_workspace_id`, `mode`, `actions`, `allowed_workspace_ids`, `valid_from`, `expires_at?`, `revoked_at?`, `accepted_at?` | Mode `REVOCABLE` hoặc `LONG_TERM`; cấp đúng version; không cấp rộng hơn nguồn; LONG_TERM không phụ thuộc membership/gói của grantor, không có thao tác đơn phương revoke |
| `correction_notice` | `id`, `resource_id`, `affected_version_id`, `replacement_version_id`, `reason`, `created_at` | Replacement thuộc cùng resource, không thay payload lịch sử; thông báo chỉ chứa metadata cần thiết |
| `notification_receipt` | `notice_id`, `recipient_workspace_id`, `acknowledged_at?` | Một thông báo mỗi nơi từng nhận version bị lỗi; thông báo không tự cấp quyền xem version không còn được cấp |

Canonical resource/version không bị nhân bản thành resource mới khi lưu về kho khác. Authorization projection được định danh thêm workspace; không đưa workspace nhận vào business owner của nguồn. Chỉnh sửa từ nguồn khác tạo resource mới và dependency vào version/gate nguồn còn áp dụng.

RLS áp dụng `library_item` theo workspace nhận. Canonical resource/version/dependency dùng repository riêng: resolve metadata đích tối thiểu để lấy authorization key; chỉ đọc body/answer sau quyết định tương ứng. Không có endpoint/repository generic trả tất cả nội dung canonical cho actor hoặc cho frontend.

## Hoạt động và kết quả mẫu

| Entity | Trường chính | Ràng buộc |
| --- | --- | --- |
| `sample_activity` | `id`, `workspace_id`, `course_subject_id`, `resource_id`, `version_id`, `assigned_by`, `assigned_at`, `due_at`, `attempt_limit`, `subscription_period_id`, `state` | Đúng subject; cần target assign và quyền dùng version; snapshot lịch/period/attempt trước hết hạn; state `ASSIGNED`, `CLOSED`, `ARCHIVED`; không là trình giao đề thật |
| `sample_submission` | `id`, `workspace_id`, `activity_id`, `student_user_id`, `attempt_no`, `submitted_at`, `content_stub`, `state` | Đúng học sinh ghi danh/đúng hoạt động; unique activity/student/attempt; không tăng số lượt sau hết hạn theo đường hoàn tất |
| `sample_result` | `id`, `workspace_id`, `submission_id`, `student_user_id`, `course_subject_id`, `score_stub?`, `comment_stub?`, `state`, `published_at?`, `graded_by?` | State `DRAFT` hoặc `PUBLISHED`; chỉ parent scope trên PUBLISHED; teacher chuyên môn được grade/publish theo assignment còn hiệu lực; subject/student suy ra từ submission, không tin body tự khai |
| `system_practice_attempt_sample` | `id`, `personal_workspace_id`, `user_id`, `system_resource_id`, `version_id`, `attempted_at`, `content_stub`, `outcome_stub?` | Quyền nền cho user eligible, không cần enrollment hoặc gói author; chỉ resource hệ thống phát hành để practice; lịch sử thuộc người làm, không tạo role học sinh toàn cục và không trộn vào kết quả center |

Dữ liệu mẫu đi qua cùng domain services, projection và PEP. Fixture tạo trạng thái nguồn và dữ liệu giả, không tạo đường bypass để một actor tự xưng là người khác. Không đưa mẫu bài thật/nội dung thực lên production.

## Đồng bộ quyền và kiểm toán

| Entity | Trường chính | Ràng buộc |
| --- | --- | --- |
| `authz_partition` | `id`, `kind`, `owner_key`, `desired_revision`, `applied_revision`, `state` | Kind `WORKSPACE`, `GRANT`, `ACCOUNT`; state `READY`, `PENDING`, `FAILED`; chỉ READY khi revisions bằng nhau; có row ngay khi tạo phạm vi |
| `authz_projection` | `id`, `partition_id`, `target_key`, `keycloak_resource_id`, `policy_names`, `dependency_partition_ids`, `desired_hash`, `applied_hash`, `state` | Unique target_key; target gồm workspace/object/version/purpose; chỉ chứa locator/control metadata, không có body/điểm; không cấp quyền theo field này |
| `authorization_operation` | `id`, `initiator_user_id`, `workspace_id?`, `idempotency_key`, `payload_hash`, `affected_partitions`, `affected_revisions`, `desired_revision`, `state`, `attempt_count`, `next_attempt_at?`, `last_error_code?` | Vừa là operation status vừa là durable work queue trong Spring; `affected_partitions` và `affected_revisions` phải là mảng một chiều bắt đầu ở chỉ số 1; `affected_revisions` là snapshot revision theo vị trí tương ứng với `affected_partitions`, `desired_revision` là revision của partition đầu tiên (không bắt các partition có cùng revision); không thêm bảng outbox thứ hai/broker; idempotency cùng key khác body trả conflict; không chứa secret/token; trạng thái `SUCCEEDED`/`FAILED` là kết thúc |
| `audit_event` | `id`, `actor_user_id?`, `workspace_id?`, `action`, `target_type`, `target_id`, `operation_id?`, `outcome`, `occurred_at` | Ghi biến động đặc quyền/gói/quan hệ/khôi phục và lỗi đồng bộ; không ghi password/token/body bài làm hoặc toàn bộ thông tin cá nhân |

Không phân phối revision qua cookie hoặc tin revision frontend gửi. Mọi business request bắt buộc có ACCOUNT partition của actor, WORKSPACE đích và closure các source WORKSPACE/GRANT partitions; account PENDING cũng phải fail-closed. Dependency manifest của resource/grant bắt buộc có các source partitions để một bản chỉnh sửa không sống độc lập sau revoke nguồn.

## Trạng thái và chuyển trạng thái

| Đối tượng | Chuyển trạng thái | Điều kiện/kết quả |
| --- | --- | --- |
| Invitation | `PENDING → ACCEPTED / EXPIRED / REVOKED` | Một lần, đúng danh tính/email; không tạo membership trùng |
| Membership/assignment/enrollment | `PENDING → ACTIVE → REVOKED` | ACTIVE sau sync; revoke fence trước sync; cấp lại là thao tác mới, không phục hồi ngầm từ gia hạn gói |
| Guardian link | `REQUESTED → APPROVED / REJECTED → REVOKED` | APPROVED chỉ sau owner/manager; một request bị từ chối cần yêu cầu mới |
| Resource version | `DRAFT → APPROVED → LOCKED` | Lưu/chia sẻ/giao khóa version; cập nhật nguồn bằng version mới; bản chỉnh sửa của người nhận là resource mới |
| Share | `OFFERED → ACCEPTED → REVOKED / EXPIRED` | Chỉ REVOCABLE có revoke; LONG_TERM giữ điều kiện nguồn, không có revoke một phía |
| Recovery | `REQUESTED → VERIFIED → RESOLVED / REJECTED` | Chỉ platform operator; không xác minh bằng role manager đơn lẻ |
| Authorization operation | `PENDING → APPLYING → SUCCEEDED / RETRYABLE_FAILURE / FAILED` | Chỉ SUCCEEDED khi tất cả partition liên quan READY đúng revision; retry idempotent, không tự mở fence khi hết số lần thử |

## Cách ly, khóa và chỉ mục

1. RLS cho lớp, class_student, course, course_subject, phân công, enrollment, guardian_link, library_item, sample_activity/submission/result, personal practice history và nguồn resource_assignment theo workspace. Runtime role không owner/BYPASSRLS; `SET LOCAL` workspace trong transaction, thiếu context không thấy hàng. Session JDBC/OIDC correlation và control directory không dùng tenant RLS; có repository/service chuyên biệt.
2. Mọi unique/FK dạy học bao gồm workspace và parent keys; không thể gắn enrollment của lớp A vào course_subject lớp B. Môn dùng FK danh mục nhưng course_subject có ID phạm vi riêng.
3. Đọc danh sách học sinh/kết quả không chỉ dựa vào RLS: xét quyền từng row hoặc tập cặp đầy đủ với Keycloak trước khi trả DTO. Phụ huynh không nhận entity JPA, hidden fields hoặc tổng số hàng của học sinh khác.
4. Mutation dùng expected row version; stale version trả 409. Nguồn thu hồi serialize với protected action qua authz partitions; lock nhiều partition theo thứ tự ổn định, không giữ transaction khi chờ worker provision mạng.
5. Chỉ mục theo `(workspace_id, state)`, các FK parent, `(course_subject_id, student/teacher)`, `(resource_id, version_no)`, `(source_version_id, share_grant_id)`, `(recipient_workspace_id, state)`, `(state, next_attempt_at)` và target_key của projection.
6. ID/password/token chỉ dùng đúng chức năng; migration role, runtime role, Keycloak provisioning client và confidential web client có credential riêng, không chia sẻ quyền quản trị realm cho người dùng trung tâm.
