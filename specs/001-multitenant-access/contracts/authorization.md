# Hợp đồng quyết định quyền và đồng bộ Keycloak

**Phạm vi**: Làm rõ FR-008, FR-013–FR-024, FR-029–FR-053. Dùng cùng hợp đồng cho quản trị thực tế và các thao tác học liệu mẫu.

## 1. Trách nhiệm

| Thành phần | Trách nhiệm | Không được làm |
| --- | --- | --- |
| Keycloak | Xác thực, directory Organization, native policy graph và quyết định đối tượng–scope | Không tự biết quan hệ lớp/khóa/phụ huynh nếu chưa được provision |
| Spring PAP/provisioner | Ánh xạ dữ kiện nguồn sang policy/group/resource instances, cập nhật revision và retry | Không tính effective-allow rồi thay PDP bằng kết quả trong app |
| Spring PEP | Lấy actor từ phiên, resolve đối tượng có phạm vi, kiểm tra freshness, hỏi PDP và thực thi | Không dùng role claim, UI capability hoặc request body để cho phép trực tiếp |
| PostgreSQL | Dữ kiện nghiệp vụ, lịch sử, FK, RLS theo workspace, revision/khóa | Không chứa bộ luật teacher/parent/license thay cho PDP |
| Next | Điều hướng workspace, hiển thị capabilities và kết quả | Không quyết định quyền, không giữ bearer token hoặc cache dữ liệu riêng |

Invariant như môn của đề phải khớp môn trong khóa, học sinh phải thuộc lớp, cặp version/resource đúng và trạng thái chuyển hợp lệ được kiểm tra bởi domain services. Kiểm tra invariant chỉ có thể từ chối thao tác không hợp lệ; không cấp quyền thay Keycloak.

## 2. Realm, clients và nguồn nhóm

- Realm `giaokhoa`; mỗi center có một Organization. Thành viên dùng tài khoản độc lập, không tự xóa account khi xóa membership.
- `giaokhoa-web`: confidential OIDC client cho Spring BFF; Authorization Code, PKCE, exact redirect URI, post-logout URI và back-channel URI. Không dùng Direct Access Grants cho web hoặc kiểm thử E2E.
- `giaokhoa-api`: resource-server client, bật Authorization Services, Enforcing, tắt default wildcard allow. Resources do resource server quản lý; business center không được đưa vào user owner của resource Keycloak.
- `giaokhoa-provisioner`: service account riêng dùng Admin/Protection API với quyền tối thiểu cần thiết để quản lý directory/projections. Không cấp credential này cho frontend hoặc người dùng trung tâm.
- Không phát group claim vào access token dùng quyết định. Không cấu hình native group policy dựa trên claim tùy ý. Group policies lấy nhóm realm hiện hành; role claims tổng hợp không được dùng làm tenant authority.
- Baseline group chỉ biểu thị eligibility đã xác minh hoặc account được backend cấp, không là role học sinh/giáo viên toàn hệ thống. Mặc định không thuộc baseline thì không có nghiệp vụ; profile/xác minh/khôi phục vẫn dùng được.
- Realm giữ verifyEmail nhưng email optional trong User Profile; không email-as-username, không default VERIFY_EMAIL/UPDATE_PROFILE cho mọi account. Backend tạo học sinh không email với temporary password và UPDATE_PASSWORD, ghi origin CENTER_ISSUED trong dữ liệu do server kiểm soát. Unknown/self-register cần email verified; mời account có sẵn không đổi origin. Chi tiết và pitfall default required actions ở R12 của research.

Tên nhóm được tạo theo template cố định, policy tham chiếu group ID ổn định:

| Nguồn | Template tên nhóm |
| --- | --- |
| Vận hành nền tảng | `/gk/platform/operators` |
| Thành viên center | `/gk/workspaces/{workspaceId}/members` |
| Chủ sở hữu/quản lý | `/gk/workspaces/{workspaceId}/owners`, `/gk/workspaces/{workspaceId}/managers` |
| Role vận hành tiền đề | `/gk/workspaces/{workspaceId}/roles/teachers`, `/gk/workspaces/{workspaceId}/roles/students`, `/gk/workspaces/{workspaceId}/roles/parents` |
| Chủ nhiệm | `/gk/workspaces/{workspaceId}/classes/{classId}/homeroom` |
| Giáo viên môn–khóa | `/gk/workspaces/{workspaceId}/course-subjects/{courseSubjectId}/teachers` |
| Học sinh môn–khóa | `/gk/workspaces/{workspaceId}/course-subjects/{courseSubjectId}/students` |
| Phụ huynh được duyệt của X | `/gk/workspaces/{workspaceId}/students/{studentUserId}/guardians` |

Không dùng Organization groups cho authorization group policy. Không bật kế thừa nhóm con trên các policy này; việc cây tên nhóm tồn tại không tự thêm quyền. Nhóm là phép chiếu từ role/phân công cố định, không là tính năng custom-role.

Nguồn assignment/enrollment/guardian center có gate role tiền đề và membership hiện hành. Gỡ TEACHER/STUDENT/PARENT phải đóng các nguồn phụ thuộc tương ứng trong cùng revision, không chỉ xóa nhãn hiển thị trong DB. Role hoặc nguồn độc lập khác không bị gỡ theo; lớp personal dùng owner/enrollment riêng.

## 3. Định danh đối tượng và scope

`target_key` nội bộ gồm workspace + loại đối tượng + ID + version/purpose nếu có. Keycloak resource UUID là ID riêng của projection, không xuất hiện như ID tài nguyên nghiệp vụ cho người dùng.

| Loại đối tượng | Ví dụ khóa đích | Scope cố định dùng cho bản đầu |
| --- | --- | --- |
| Platform | `platform` | `center.create`, `center.list`, `subscription.confirm`, `account.recover`, `fixture.manage` |
| Profile | `account:{userId}` | `profile.read`, `workspace.list`, `recovery.request`, `history.read` |
| Workspace | `workspace:{wid}` | `enter`, `read`, `class.list`, `class.create`, `membership.list`, `membership.invite`, `membership.role.assign`, `membership.role.revoke`, `membership.revoke`, `manager.assign`, `manager.revoke`, `student.issue`, `guardian.request`, `guardian.approve`, `guardian.revoke` |
| Lớp | `workspace:{wid}:class:{id}` | `read`, `update`, `course.list`, `course.create`, `roster.read`, `roster.add`, `roster.revoke`, `homeroom.assign`, `homeroom.revoke`, `completion.read` |
| Khóa | `workspace:{wid}:course:{id}` | `read`, `update`, `subject.add` |
| Môn trong khóa | `workspace:{wid}:course-subject:{id}` | `read`, `roster.read`, `enrollment.add`, `enrollment.revoke`, `teacher.assign`, `teacher.revoke`, `resource.create`, `activity.assign`, `completion.read` |
| Version tài nguyên theo nơi dùng | `workspace:{wid}:resource:{rid}:version:{vid}` | `content.read`, `answer.read`, `use`, `fork`, `share`, `library.save`, `review`, `practice` |
| Tài nguyên tự biên soạn | `workspace:{wid}:resource:{rid}` | `draft.edit`, `version.create` |
| Hoạt động | `workspace:{wid}:activity:{id}` | `read`, `submit`, `schedule.extend`, `attempt.open`, `reassign` |
| Kết quả | `workspace:{wid}:result:{id}` | `read`, `read.published`, `grade`, `publish` |
| Grant | `grant:{id}` | `accept`, `revoke` |

Scope trên fixture không tạo vai trò mới. `fixture.manage` chỉ tạo dữ kiện mẫu trong profile kiểm chứng; actor thực hiện hành động mẫu vẫn dùng các scope nghiệp vụ thông thường.

Scope cấp mới và scope thu hồi được tách để gói hết hạn không vô tình chặn thu hồi hoặc cho phép thêm quyền. Một yêu cầu đổi nhiều role cần mọi scope tương ứng với phần thêm/gỡ; không dùng một scope manage chung để bỏ qua gate thời gian.

## 4. Chính sách mỗi nguồn

Mỗi cặp resource/scope có một root scope-permission. Root chứa OR các nguồn độc lập; từng nguồn chứa AND tất cả điều kiện bắt buộc. Eligibility danh tính là gate chung bên ngoài OR cho mọi business permission; profile/onboarding/recovery là ngoại lệ. Các hạn chế chung khác cũng đặt ngoài OR nếu áp dụng mọi nguồn. Không tạo thêm permission wildcard song song có thể bypass root. Không dùng mặc định AFFIRMATIVE giữa một policy giới hạn và một policy cấp quyền.

| Hành động | Nhánh nguồn hợp lệ | Điều kiện bắt buộc |
| --- | --- | --- |
| Tạo tenant/xác nhận gói/khôi phục không email | Platform operator hiện hành | Đúng scope quản trị, không nối nhánh này vào body/answer/result |
| Tạo lớp center | Owner OR manager của đúng workspace | Membership hiện hành AND gói center có hiệu lực |
| Ghi danh/phân công | Owner OR manager | Đúng đối tượng/workspace AND gói có hiệu lực |
| Quản trị lớp/khóa/roster personal | Owner/organizer của personal workspace | Không đòi center membership; mở rộng hoạt động cần gói personal, thu hồi vẫn được phép |
| Thu hồi quan hệ | Người có quyền quản trị tương ứng | Membership/thẩm quyền hiện hành; không yêu cầu gói còn hạn |
| Tạo lớp/resource personal | User owner của personal workspace | Gói personal hiện hành |
| Làm đề hệ thống quyền nền | Actor eligible | Resource hệ thống đã phát hành cho practice; không cần course/enrollment/gói personal; lịch sử chỉ của người làm |
| Giao bài môn–khóa | Teacher của môn OR homeroom của lớp OR owner/manager center | Membership, gói còn hạn, đề đúng môn; thêm quyết định `use` cho version/context và các thành phần cần dùng; homeroom chỉ dùng đề đã duyệt |
| Soạn resource center | Content-author source hoặc phân công chuyên môn có hành động soạn tương ứng | Membership, gói hiện hành, đúng subject/phạm vi; created_by đứng riêng không cấp quyền |
| Nộp bài center | Enrollment của đúng actor–môn–khóa | Membership hiện hành, hạn/lượt đã xác lập, quyền học liệu hiện hành |
| Nộp bài personal | Class-student và enrollment của đúng actor trong lớp personal | Identity/enrollment hiện hành, hạn/lượt, quyền học liệu; không yêu cầu center membership |
| Chấm/công bố center | Teacher assignment đúng môn–khóa | Membership/assignment hiện hành; không thêm homeroom hoặc manager đứng riêng vào nhánh này |
| Giao/chấm/công bố personal | Owner/organizer của personal workspace | Tạo hoạt động mới cần gói; completion của bài đã giao không đòi gói còn hạn nhưng vẫn cần nguồn học liệu và quan hệ người học hợp lệ |
| Xem kết quả chưa công bố | Teacher đúng môn theo source được cấp OR owner tổ chức lớp personal | Đúng phạm vi và quyền hiện hành |
| Xem kết quả công bố | Học sinh sở hữu kết quả OR guardian của học sinh đó OR teacher hợp lệ OR owner lớp personal | Nhánh học sinh cần enrollment; nhánh guardian cần parent membership và approved link đúng center; publication gate chỉ nối vào result đã công bố |
| Theo dõi hoàn thành | Teacher chuyên môn OR homeroom đúng lớp OR quản lý phạm vi | Trả trạng thái hoàn thành cần thiết, không tự có body bài làm/đáp án |
| Dùng tài nguyên sở hữu | Owner cá nhân hoặc assignment tài nguyên center | Đúng context và giới hạn nguồn; scope dùng không bao hàm sửa/share/answer |
| Dùng tài nguyên licensed | Recipient assignment trong workspace nhận | Grant/source gate và các giới hạn kế thừa còn hiệu lực |
| Hoàn tất hoạt động sau hết hạn gói | Completion source tạo khi giao bài hợp lệ | Giữ membership/assignment/enrollment/license; không có branch gia hạn/lượt mới/giao lại; không yêu cầu gói hiện còn hạn |

Native user policies dùng cho chủ cá nhân/người nhận cụ thể; group policies dùng cho nguồn membership/phân công; time policies dùng hiệu lực gói/grant; aggregated policies giữ AND/OR như trên. Để biểu diễn `[start,end)`, AND time-policy `nbf=start` POSITIVE với time-policy `nbf=end` NEGATIVE; không dùng `noa=end` đơn lẻ vì source 26.8.1 cho phép tại đúng end. Kiểm tra trạng thái published/approved tạo hoặc gỡ nhánh tương ứng qua PAP, không tin trạng thái frontend khai.

Graph của bản chỉnh sửa giữ các mandatory source-gate áp dụng. Revoke grant gỡ/đóng nguồn và refresh toàn bộ projection phụ thuộc trước khi báo hoàn tất; nguồn hợp lệ độc lập không bị xóa theo. LONG_TERM không nối source membership/gói cá nhân của người đã chia sẻ.

## 5. Lời gọi PDP

Chỉ Spring thực hiện lời gọi bằng token của user trong phiên:

```text
POST /realms/giaokhoa/protocol/openid-connect/token
Authorization: Bearer <access token của đúng người dùng>
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket
audience=giaokhoa-api
permission=<resource UUID>#<scope>
response_mode=decision
```

Mỗi lời gọi chỉ có một resource và một scope. Không gửi `rpt`, `ticket`, `claim_token` hoặc actor do client chọn. Nếu hành động cần 5 cặp, cả 5 phải được cho phép; true của một cặp không thay thế bốn cặp còn lại. Denial là 403; timeout/unavailable/không xác minh được quyết định là 503 và fail-closed. Không chuyển lỗi dịch vụ thành allow hoặc tái dùng allow trước đó.

Access token có thể được refresh để giữ phiên; role/group/permission trong token cũ không làm authority. Token của provisioner không dùng cho evaluate user. Phải giữ riêng hai loại credential trong log/cấu hình.

## 6. Giao thức mutation và revision

1. Resolve actor, target metadata và các partition phụ thuộc từ server; giữ khóa phạm vi thích hợp theo thứ tự ID ổn định. Xét quyền quản trị hiện hành trước khi thay dữ kiện nguồn.
2. Trong transaction app-db, ghi desired domain state, tăng desired_revision, đặt partition/projection PENDING, lưu authorization_operation với idempotency key; commit trước khi gọi mạng.
3. Worker trong Spring provision Organization/membership/group/resource/policy theo tên/ID có thể lặp lại an toàn. Không có hai worker áp dụng revision ngược thứ tự trong cùng partition.
4. Đọc lại đối tượng cấu hình cần thiết, đối chiếu hash/revision, dùng request kiểm chứng của gate khi cần; chỉ đặt READY nếu mọi dependency đúng desired_revision. Không suy READY chỉ từ việc đã gửi request HTTP.
5. API chỉ trả kết quả nghiệp vụ hoàn tất khi operation SUCCEEDED. Nếu chưa xong trả 202 với operationId và `completed=false`; UI ghi rõ đang xử lý, không hiển thị đã lưu/đã thu hồi.
6. Retry giữ cùng idempotency key và desired payload. Crash hoặc lỗi giữ PENDING/FAILED, tiếp tục fail-closed; không tự rollback thành READY với permission cũ còn sót.

Đơn vị fence mặc định WORKSPACE; nguồn share dùng thêm GRANT partitions và account-level thay đổi danh tính dùng ACCOUNT partition. Thu hồi phân công ở A không dùng ACCOUNT fence làm mất quyền B. Revoke source chia sẻ lấy toàn bộ dependency closure bị ảnh hưởng, không đóng các workspace không liên quan.

## 7. Giao thức protected request

1. Xác thực phiên, kiểm tra CSRF cho mutation, xác định actor từ SecurityContext. Với business request, đọc profile identity hiện hành từ Keycloak qua Admin API nội bộ; nếu eligibility thay đổi phải cập nhật source qua ACCOUNT fence trước đánh giá quyền. Không tin email_verified token cũ/exception do client tự khai; không lấy được identity hiện hành thì 503. Token quản trị chỉ đọc input danh tính, không dùng để evaluate quyền thay user.
2. Resolve metadata tối thiểu theo route workspace; không lấy payload/answer/result để trả trước khi xét quyền. Cung cấp UUID không chứng minh tồn tại hoặc thẩm quyền.
3. Resolve dependency manifest; tập bắt buộc luôn có ACCOUNT partition của actor, WORKSPACE đích và closure source WORKSPACE/GRANT liên quan. Khóa chia sẻ theo thứ tự ổn định và xác nhận READY/revisions bằng nhau, kể cả ACCOUNT. Khi phát hiện closure đã đổi phải resolve/lấy khóa lại, không tiếp tục với tập phụ thuộc cũ; identity đổi mà account projection còn pending không được dùng group cũ.
4. Gọi PDP mới cho từng cặp bắt buộc. Không lấy dữ liệu từ browser, cache hoặc token role để bỏ qua bước này.
5. Kiểm tra invariant domain, đọc đúng DTO hoặc ghi trong transaction, xác nhận revision không đổi đến điểm commit/materialize dữ liệu. Mutation quản trị quyền đi theo giao thức mục 6.
6. Trả `Cache-Control: private, no-store`; không serialize JPA entity hoặc trả hidden fields khi chỉ có quyền đọc kết quả công bố.

Thu hồi lấy khóa cập nhật xung đột với protected request. Request đã bắt đầu trước thu hồi được sắp thứ tự bằng khóa; sau xác nhận thu hồi thành công, request tiếp theo phải nhìn thấy revision mới và không thể dùng decision trước. Không giữ transaction chờ worker ngoài mạng; nếu PENDING từ chối tạm với 503.

Danh sách không dùng một permission trên collection để trả mọi row. Backend lọc candidates trong đúng workspace và kiểm tra quyền từng row; cursor/count/hasMore chỉ phản ánh tập được phép trả, không lộ tổng số hàng của học sinh khác. Bản đầu chưa tối ưu authorize hàng loạt hoặc cache allow.

## 8. Console, thất bại và vận hành

- Thay đổi nghiệp vụ qua API ứng dụng. Keycloak console không phải màn hình quản trị trung tâm cho khách hàng.
- Emergency sửa membership/policy bằng console: fence phạm vi trước, sửa, reconcile, kiểm tra rồi mở lại. Không cam kết đồng bộ tức thời thao tác ngoài luồng bằng một listener chưa tồn tại.
- Chặn thao tác xóa owner cuối cùng hoặc owner membership đang giữ center; không mở chuyển sở hữu ngoài scope.
- Operation status chỉ cho initiator hoặc operator có quyền hỗ trợ; không trả payload policy, token hoặc metadata riêng của người khác.
- Audit ghi actor/action/target/operation/outcome, không ghi token/password/body bài làm. Retry phải có giới hạn tốc độ và không tự vượt qua trạng thái lỗi.

## 9. Gate kiểm chứng native thật

Các test integration phải chạy đúng Keycloak 26.8.1 và PostgreSQL thật:

1. Không group claim nhưng group policy nhận đúng membership hiện hành; group/role claim giả không mở quyền.
2. Giữ cùng session/token trước và sau gỡ assignment/membership/link; sau SUCCEEDED lần gọi tiếp theo bị từ chối, B vẫn hoạt động.
3. Hai cặp permission trong đó chỉ một được cấp: test chứng minh batch decision không đủ; đường ứng dụng đòi từng cặp đều true.
4. Ba môn, nhiều giáo viên, học sinh chọn môn, homeroom chỉ giao/track và parent chỉ đúng con/publication.
5. Time policy khớp `[start,end)` theo UTC ở trước/đúng/sau mốc; gói hết hạn không phụ thuộc một cron chạy đúng giờ.
6. Hoàn tất bài cũ được phép nhưng extend/new-attempt/reassign bị từ chối; revoke enrollment/license vẫn chặn completion.
7. Long-term grant sống sau grantor rời/hết gói; revocable grant chặn cả bản nhận và bản chỉnh sửa, nguồn độc lập vẫn tồn tại.
8. Lỗi/crash giữa directory, policy và commit revision; retry không nhân bản grant; concurrent revoke/write không commit dựa vào revision cũ.
9. User self-register chưa verified không có nghiệp vụ; center-issued không email vẫn login được mà không bị ép bổ sung email bởi required actions/profile.

Nếu một gate thất bại, không mở rộng CRUD và không báo đạt spec; ghi bằng chứng rồi điều chỉnh thiết kế native. Đổi PDP hoặc giảm yêu cầu thu hồi phải là thay đổi quyết định riêng, không phải fallback trong code.

## 10. Ngoại lệ có chủ đích: route thành viên quyết quyền bằng SQL

Ngoại lệ này là một thay đổi quyết định riêng theo mục 9 ("Đổi PDP hoặc giảm yêu cầu thu hồi phải
là thay đổi quyết định riêng, không phải fallback trong code"), không phải đường tắt trong code:
`MembershipController` quyết quyền cho các route thành viên bằng dữ kiện SQL thay vì hỏi PDP, và
hành vi đó đã được chốt bằng đặc tả test hợp đồng của #36.

### Phạm vi

Chỉ các route sau của `MembershipController` (backend `tenancy/MembershipController.java`):

- `GET /api/workspaces/{workspaceId}/memberships` — cho phép khi actor có bất kỳ role ACTIVE nào
  của workspace (`actorRoles()`).
- `POST /api/workspaces/{workspaceId}/invitations`, `POST /api/workspaces/{workspaceId}/students`,
  `POST /api/workspaces/{workspaceId}/classes` — yêu cầu actor là OWNER hoặc MANAGER của workspace
  (`requireAdmin()`) và gói của center còn hiệu lực (`requireSubscriptionCurrent()`).
- `PUT /api/workspaces/{workspaceId}/memberships/{membershipId}/roles` — yêu cầu actor là OWNER
  hoặc MANAGER (`requireAdmin()`); chỉ kiểm tra gói (`requireSubscriptionCurrent()`) khi yêu cầu
  thêm role mới, không kiểm tra khi chỉ gỡ role.
- `DELETE /api/workspaces/{workspaceId}/memberships/{membershipId}` — yêu cầu actor là OWNER hoặc
  MANAGER (`requireAdmin()`); không kiểm tra gói.

`requireSubscriptionCurrent()` cho phép khi center chưa từng có kỳ gói nào (`subscription_period`
chưa có row); chỉ từ chối (`SUBSCRIPTION_INACTIVE`) khi đã có kỳ gói nhưng không kỳ nào còn hiệu
lực tại thời điểm gọi.

### Điểm lệch đã biết so với hợp đồng PDP

Với cùng dữ kiện (center chưa từng có kỳ gói), PDP sẽ DENY vì `CenterPolicyProjector` không có
nguồn nào cho scope tương ứng (không nguồn → deny), trong khi các route trên CHO PHÉP. Riêng
`DELETE .../memberships/{membershipId}` (và `PUT .../roles` khi chỉ gỡ role mà không thêm role):
đường SQL không xét gói nên cho phép bất kể trạng thái gói — kể cả khi kỳ gói đã hết hiệu lực —
trong khi PDP từ chối khi không có kỳ gói còn hiệu lực. Hai đường cho hai đáp án trên cùng dữ
kiện — đây là lệch có chủ đích trong phạm vi này, không phải lỗi chưa phát hiện.

### Lý do

Cài PDP + fence cho các route này trong phạm vi T040 là không thể: fence đòi projection READY,
trong khi đặc tả test hợp đồng của #36 seed center thẳng bằng JDBC (không đi qua operation/worker
nên không bao giờ có projection READY cho các center đó), và đặc tả đó chốt hành vi cho phép cho
center chưa có gói. Ép PDP vào sẽ phá vỡ hợp đồng test đã chốt mà không thêm được đảm bảo nào
trong phạm vi này.

### Giới hạn

- Ngoại lệ chỉ áp dụng cho đúng các route liệt kê ở trên. Route khác (kể cả
  `POST /api/invitations/{token}/accept`, vốn quyết theo token chứ không theo role) không thuộc
  phạm vi này và vẫn tuân đầy đủ mục 5–7.
- Không mở rộng ngoại lệ sang route mới mà không ghi thêm quyết định riêng tại đây.
- Bên đánh giá rà lại quyết định này trước khi đóng parent issue; khi nào các route này chuyển
  sang PDP thì gỡ mục này và ghi rõ commit gỡ.

### Eligibility và tính nhất quán của ngoại lệ SQL (#170)

Ngoại lệ chỉ thay cách quyết role/gói của center. Mọi route thành viên, kể cả chấp nhận
lời mời, vẫn đồng bộ profile Keycloak hiện hành và đi qua PDP eligibility trên profile
của chính actor, có ACCOUNT/target fence trước khi đọc hoặc ghi. SELF_REGISTERED chưa
verified bị từ chối; CENTER_ISSUED không email vẫn được dùng nghiệp vụ theo role.

Mutation giữ cùng khóa workspace từ kiểm tra role/gói đến ghi dữ kiện nguồn, dựng bundle
và commit operation. Các writer trực tiếp của center/membership/subscription dùng cùng khóa;
không được dựng descriptor trước rồi mới tuần tự hóa bước ghi hash.

Các endpoint platform tự đi qua pipeline ACCOUNT/target với scope platform tương ứng;
không đòi một lời gọi workspace trước. Actor hoặc projection chưa READY trả
503 AUTHORIZATION_NOT_READY để client thử lại có giới hạn, không trả 403 thiếu quyền
chỉ vì eligibility chưa từng được chiếu.
## 11. Mở rộng ngoại lệ mục 10: route giảng dạy quyết quyền bằng SQL

Quyết định riêng theo đúng điều kiện của mục 10 ("Không mở rộng ngoại lệ sang route mới mà không
ghi thêm quyết định riêng tại đây"), chốt khi cài tầng route của US3.

### Phạm vi

Các route giảng dạy trong `backend/src/main/java/com/giaokhoa/teaching/` (`ClassController`,
`CourseController`, `EnrollmentController`, `AssignmentController`) quyết quyền bằng đúng dữ
kiện nguồn mà `TeachingPolicyProjector` chiếu lên native graph:

- Mọi mutation lớp/khóa/roster/ghi danh/phân công (POST/PATCH/DELETE dưới
  `/api/workspaces/{wid}/classes`, `/courses`, `/course-subjects`, `/enrollments`): actor là
  OWNER hoặc MANAGER hiện hành của center (`center_membership` ACTIVE kèm role), hoặc chủ sở
  hữu của personal workspace; route phân công giáo viên/chủ nhiệm không áp dụng cho personal.
  Cổng gói do service dữ kiện nguồn giữ (mở rộng bị chặn khi gói hết hiệu lực, thu hồi không bị
  chặn).
- `GET /api/workspaces/{wid}/classes`: quản trị thấy mọi lớp; chủ nhiệm hiện hành thấy các lớp
  mình chủ nhiệm; actor không đọc được lớp nào bị từ chối 403. Học sinh không có nguồn
  `class.list` lẫn `read` của lớp (ghi danh chỉ cấp `read` của môn–khóa) nên bị từ chối 403.
- `GET .../classes/{cid}` và `GET .../courses/{kid}`: quản trị hoặc chủ nhiệm hiện hành của
  lớp (chứa khóa); không được đọc trả 404 như không tồn tại.
- `GET .../classes/{cid}/students` và `GET .../classes/{cid}/courses`: quản trị hoặc chủ nhiệm
  hiện hành của lớp; lớp tồn tại nhưng không được đọc trả 403.
- `GET .../course-subjects/{csid}/enrollments`: quản trị hoặc giáo viên đang được phân công
  đúng môn–khóa đó; chủ nhiệm và học sinh không có nguồn `roster.read` của môn–khóa nên bị từ
  chối 403.
- `GET /api/subjects`: danh mục tham chiếu dùng chung, chỉ đòi phiên có tài khoản hợp lệ.

Mutation của các service dữ kiện nguồn không tự ghi operation (lớp/khóa/roster) được route ghi
kèm authorization operation cho bundle của mọi target bị chạm tới trong cùng transaction;
mutation ghi danh/phân công tự ghi operation ở service. Projection vẫn được worker áp như bình
thường cho các bề mặt dùng PDP thật (capabilities, sample action); riêng quyết định cho phép
của các route trên không chờ projection WORKSPACE. Mỗi business request vẫn phải đồng bộ
eligibility hiện hành qua ACCOUNT/target fence và PDP profile của chính actor; không dựa vào
việc trình duyệt đã gọi danh sách workspace trước đó. Mutation lấy khóa workspace trước khi
kiểm tra role, liệt kê target bị ảnh hưởng hoặc đọc/ghi nguồn, giữ tới commit operation.
Chấp nhận lời mời bổ sung membership/role phải ghi lại các bundle giảng dạy bị ảnh hưởng
trong cùng revision như đường thay role và xác nhận gói.

### Lý do

Cùng lý do của mục 10, cộng thêm tính chất vòng khởi đầu của giảng dạy: target lớp/khóa/môn–khóa
chỉ có projection sau mutation giảng dạy đầu tiên, nên route tạo lớp qua PDP+fence không có
resource/policy nào để hỏi ở lần gọi đầu; test hợp đồng của US3 cũng seed center bằng JDBC như
US2. Quyết từ dữ kiện nguồn cho đáp án trùng với graph mà projector dựng từ cùng dữ kiện đó, và
cho hiệu lực thu hồi ngay ở lời gọi tiếp theo (gate 2 của mục 9) thay vì chờ worker áp revision
mới.

### Điểm lệch đã biết so với hợp đồng PDP

Khi projection của target giảng dạy chưa được áp (operation còn PENDING) hoặc đang lệch, PDP sẽ
deny/503 trong khi route vẫn cho phép theo dữ kiện nguồn hiện hành — cùng họ lệch có chủ đích
của mục 10. Route thu hồi lớp/khóa/môn–khóa dùng DELETE chuyển trạng thái (giữ lịch sử, không
xóa vật lý), khớp nguyên tắc không mở DELETE vật lý của hợp đồng HTTP.

### Giới hạn

- Chỉ các route liệt kê ở trên; route capabilities/sample action và mọi route khác vẫn theo
  mục 5–7.
- Catalog quyền chưa có scope revoke riêng cho class/course/course-subject; việc thu hồi ba
  target này đi theo biên quản trị của mục này cho tới khi một đợt riêng bổ sung scope.
- Bên đánh giá rà lại quyết định này cùng mục 10 trước khi đóng parent issue; khi nào các route
  này chuyển sang PDP thì gỡ mục này và ghi rõ commit gỡ.

## 12. Mở rộng ngoại lệ mục 10: route guardian quyết quyền bằng SQL

Quyết định riêng theo đúng điều kiện của mục 10 ("Không mở rộng ngoại lệ sang route mới mà không
ghi thêm quyết định riêng tại đây"), chốt khi cài tầng route của US4 (T060).

### Phạm vi

Các route guardian trong `backend/src/main/java/com/giaokhoa/guardians/` (`GuardianLinkController`,
`StudentResultController`) quyết quyền như sau:

- `POST /api/workspaces/{wid}/guardian-links`, `POST .../guardian-links/{id}/decision`,
  `DELETE .../guardian-links/{id}`: đi qua `executeForActor` trên profile target (PDP
  eligibility + ACCOUNT fence, đúng mục "Eligibility và tính nhất quán của ngoại lệ SQL"), rồi
  kiểm tra role từ dữ kiện nguồn đúng nguồn mà `CenterPolicyProjector` chiếu — request ← PARENT
  ACTIVE (thiếu role → 403 `GUARDIAN_PARENT_NOT_CURRENT`), decision/revoke ← OWNER/MANAGER
  ACTIVE (thiếu role → 403 `PERMISSION_DENIED`). Cổng gói ở decision APPROVE do service kiểm tra
  lại (`SUBSCRIPTION_INACTIVE` 403); revoke không cổng gói, đúng hợp đồng HTTP.
- `GET /api/workspaces/{wid}/guardian-links`: cùng pipeline profile target; OWNER/MANAGER thấy
  danh sách cần duyệt (REQUESTED + APPROVED), PARENT chỉ thấy link của chính mình (kể cả lịch sử
  đã đóng), actor khác bị từ chối 403. Quản trị viên đồng thời là phụ huynh thì thấy danh sách
  cần duyệt.
- `GET /api/workspaces/{wid}/students/{sid}/results`: KHÔNG thuộc ngoại lệ này — ủy thác toàn
  bộ cho `PublishedResultService`, PDP xét scope `read.published` trên từng hàng kết quả, và khi
  tập được phép rỗng thì học sinh tự đọc nhận danh sách rỗng còn actor khác nhận 403 (B56).

### Lý do

Cùng lý do của mục 10: test hợp đồng guardian seed center thẳng bằng JDBC (không đi qua
operation/worker nên không bao giờ có projection cho workspace target), và hợp đồng đó chốt hành
vi cho phép cho POST guardian đầu tiên (202). Hỏi PDP trên workspace target sẽ deny vì không có
Keycloak resource nào cho target đó (`ERROR_NO_RECORDED_PROJECTION` khi reconcile partition
không có projection). Kiểm tra route từ dữ kiện nguồn cho đáp án trùng với graph mà
`CenterPolicyProjector` dựng từ cùng dữ kiện đó; service tự kiểm tra lại mọi tiền đề trước khi
ghi nên kiểm tra route là phòng thủ thêm, không phải nguồn quyền duy nhất.

### Điểm lệch đã biết so với hợp đồng PDP

Với cùng dữ kiện (center seed bằng JDBC, chưa có projection nào cho workspace target), PDP sẽ
DENY mọi scope `guardian.*` trên workspace target trong khi các route trên CHO PHÉP theo role
dữ kiện nguồn — cùng họ lệch có chủ đích của mục 10. Đường đọc kết quả không lệch: nó vẫn hỏi
PDP từng hàng qua projection mà `ResultPolicyProjector` đã ghi.

### Giới hạn

- Chỉ các route liệt kê ở trên; mọi route khác vẫn tuân đầy đủ mục 5–7.
- Bên đánh giá rà lại quyết định này cùng mục 10 trước khi đóng parent issue; khi nào các route
  này chuyển sang PDP thì gỡ mục này và ghi rõ commit gỡ.

## 13. Mở rộng ngoại lệ mục 10: route gửi yêu cầu khôi phục quyết quyền bằng SQL

Quyết định riêng theo đúng điều kiện của mục 10 ("Không mở rộng ngoại lệ sang route mới mà không
ghi thêm quyết định riêng tại đây"), chốt khi rà soát tính nhất quán hợp đồng của luồng khôi phục.

### Phạm vi

Chỉ route `POST /api/me/recovery-requests` (`RecoveryController.submit`): lời gọi không qua
pipeline quyết quyền dùng chung mà quyết thẳng bằng quy tắc SQL trong `RecoveryService` — chỉ tự
gửi cho chính mình, hoặc kèm một workspace CENTER đang ACTIVE mà tài khoản đích là thành viên
hiện hành và người gửi là chính tài khoản đích hoặc OWNER/MANAGER ACTIVE của center đó; mọi
trường hợp ngoài phạm vi (đích không tồn tại, workspace không phải center đang hoạt động, người
gửi không có quyền) đều trả cùng một 404 để không lộ sự tồn tại của tài khoản, đúng hợp đồng HTTP
của route này. Hai route còn lại của cùng controller không thuộc ngoại lệ: `GET
/api/platform/recovery-requests` và `POST /api/platform/recovery-requests/{requestId}/resolve`
vẫn qua pipeline với target platform và scope `account.recover`.

### Lý do

Catalog và template đã sẵn cho đường PDP: scope `recovery.request` có trong `PermissionCatalog`
cho target profile, và `NativePolicyTemplates.requiresEligibility` đã miễn eligibility gate cho
đúng cặp profile/`recovery.request` này. Tầng projection thì chưa có nguồn nào cho cặp đó:
desired projection của profile target chỉ do `IdentityEligibilityService` ghi với scope
danh nghĩa `workspace.list`, `projectedScopes` chỉ gộp `workspace.list` + `history.read`, và
nguồn dựng lại cho profile target chỉ tồn tại khi account đã eligible (record do backend cấp
hoặc email đã xác minh). Đối tượng chính của khôi phục lại là đúng nhóm chưa eligible — tài
khoản tự đăng ký chưa xác minh email vẫn phải gửi được yêu cầu theo mục 2 — nên không tồn tại
root permission native cho (profile, `recovery.request`) và PDP sẽ deny tuyệt đối: đưa route qua
`executeForActor` hôm nay sẽ đổi hành vi đã khóa của hợp đồng HTTP (mọi lời gọi hợp lệ thành
403). Muốn đi đường PDP phải đổi thiết kế checkpoint eligibility để ghi thêm desired state cho
`recovery.request` trên ACCOUNT partition của mọi account — thay đổi ở tầng projection, ngoài
phạm vi một đợt sửa nhất quán hợp đồng, và chạm checkpoint đang khóa hành vi của `workspace.list`
và `history.read`.

### Điểm lệch đã biết so với hợp đồng PDP

Route submit không đồng bộ eligibility hiện hành qua ACCOUNT fence và không hỏi PDP trước khi
ghi, trong khi các route khác của cùng controller có. Rủi ro được chặn ở tầng service: quy tắc
SQL chỉ cho phép tự gửi hoặc quản trị hiện hành của đúng center chứa tài khoản đích, và quyết
định xử lý (resolve) vẫn là của platform operator qua PDP.

### Giới hạn

- Chỉ route submit liệt kê ở trên; không mở rộng sang route khôi phục mới mà không ghi thêm
  quyết định riêng tại đây.
- Bên đánh giá rà lại quyết định này cùng mục 10–12 trước khi đóng parent issue; khi nào tầng
  projection có nguồn cho `recovery.request` trên profile target và route này chuyển sang PDP
  thì gỡ mục này và ghi rõ commit gỡ.
