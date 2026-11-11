# Nghiên cứu kỹ thuật: xác thực và phân quyền đa trung tâm

**Ngày đối chiếu**: 2026-11-05. **Căn cứ**: [spec.md](spec.md) và yêu cầu backend Spring Boot, frontend Next.js. Đây là quyết định thiết kế dựa trên tài liệu/source chính thức; chưa có kết quả kiểm thử runtime của sản phẩm.

## R1. Baseline và phiên bản

**Decision**: Java 25, Spring Boot 4.1.2 và các dependency do BOM quản lý; Next.js 16.4.5 App Router, TypeScript và Node.js 24; PostgreSQL 18.6; Keycloak 26.8.1. Maven Wrapper cho backend, npm với lockfile cho frontend.

**Rationale**: Java 25, Node 24, hai database PostgreSQL riêng và Keycloak 26.8.1 đã có trong `.devcontainer/`. Spring Boot 4.1.2 hỗ trợ Java 25. Next.js 16.4.5 là bản vá ổn định được tài liệu hiện hành và thông báo bảo mật xác nhận. Không cần thay môi trường đang có để lập kế hoạch. Spring Security/Spring Session/Testcontainers dùng phiên bản BOM, không nâng riêng.

**Alternatives considered**: Java 21/Node 26 không cần thiết khi baseline hiện tại tương thích; microservices và một bộ quản lý workspace JavaScript phức tạp chưa có nhu cầu.

**Sources**: [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Boot managed dependencies](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html), [Next installation](https://nextjs.org/docs/app/getting-started/installation), [Next November 2026 security release](https://nextjs.org/blog/november-2026-security-release), [Node releases](https://nodejs.org/en/about/previous-releases).

## R2. Spring giữ phiên, Next phụ trách giao diện

**Decision**: Spring Boot là modular monolith, đồng thời là BFF. OIDC Authorization Code với confidential client `giaokhoa-web`; dùng rõ `HttpSessionOAuth2AuthorizedClientRepository` để access/refresh token nằm trong phiên Spring Session JDBC, không để authorized-client service in-memory mặc định. Next không giữ token, không có session/auth provider thứ hai và không truy cập database. Browser dùng cookie host-only qua cùng origin ứng dụng.

**Rationale**: Giảm số nơi giữ credential, xử lý refresh, đăng xuất và xét quyền. Spring lấy danh tính từ phiên đã xác thực và token cùng người dùng để gọi dịch vụ quyền. Cookie khác với cookie SSO của `auth.giaokhoa.com`.

**Alternatives considered**: Next BFF là một lựa chọn hợp lệ nhưng tạo thêm ranh giới token/session; SPA giữ token tại browser làm tăng việc quản lý credential. Không mở bearer public API chỉ để có một resource-server; có thể bổ sung sau với cùng bộ quyền.

**Sources**: [Spring OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html), [Authorized client persistence](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html), [Spring Session JDBC](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html), [Next rewrites](https://nextjs.org/docs/app/api-reference/config/next-config-js/rewrites).

## R3. Native Authorization Services là PDP duy nhất

**Decision**: Spring là PEP (thực thi quyết định) và PAP/provisioner (tạo graph chính sách); Keycloak là PDP ở thời điểm truy cập. Dùng native user, realm-group, time và aggregated policies. Không chọn JS/SPI hoặc dịch vụ phân quyền thứ hai cho bản đầu.

**Rationale**: Giữ mỗi nguồn ownership, membership, assignment, enrollment, guardian và share thành nhánh riêng. Nhánh dùng AND cho các điều kiện bắt buộc; các nguồn độc lập hợp lệ dùng OR. Policy không materialize thành một danh sách effective-allow đã tính sẵn bởi một bộ quyết định khác. Spring vẫn phải ánh xạ nghiệp vụ thành policy instances và kiểm tra invariant lớp/khóa/môn; đó là phần tích hợp cần xây dựng.

**Alternatives considered**: Chỉ dùng role không biểu diễn đủ quan hệ đối tượng. Viết một PDP trong ứng dụng trái ràng buộc đã chốt. Custom SPI gọi ngược ứng dụng mỗi evaluation tạo vòng phụ thuộc chưa cần thiết. Chỉ cân nhắc thay thiết kế qua một quyết định riêng nếu gate runtime chứng minh native graph không đáp ứng.

**Sources**: [Authorization Services architecture and policies](https://www.keycloak.org/docs/26.8.0/authorization_services/), [UserPolicyProvider 26.8.1](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/authz/policy/common/src/main/java/org/keycloak/authorization/policy/provider/user/UserPolicyProvider.java).

## R4. Organization là directory; quyền theo nhóm realm có phạm vi

**Decision**: Một realm `giaokhoa`, một Organization mỗi trung tâm; tài khoản tồn tại độc lập với Organization. Membership nghiệp vụ được điều phối qua ứng dụng. Các nguồn vai trò/phân công được chiếu sang realm groups có định danh workspace/lớp/môn–khóa và native user policies cho người nhận cụ thể. Không dùng Organization groups trong group policy.

**Rationale**: Organization groups chưa được hỗ trợ trực tiếp bởi group-based authorization policies. Không dùng `realm_access`/`resource_access` tổng hợp để kết luận quyền tenant. Group policy đọc membership hiện hành ở realm khi token không mang group claim; không phát group claim cho client dùng quyết định quyền. Tên nhóm là biểu diễn của bộ vai trò/phân công cố định, không mở trình tạo vai trò cho trung tâm.

**Alternatives considered**: Một realm mỗi tenant làm phức tạp danh tính chung/chuyển trung tâm. Gán giáo viên thành role toàn realm dễ làm mở nhầm quyền cá nhân. Dùng một user list hiện hành mỗi nguồn vẫn khả thi nhưng nhiều cập nhật hơn; nhóm realm giảm fanout cho membership/phân công lặp lại.

**Sources**: [Organizations groups and restrictions](https://www.keycloak.org/docs/26.8.0/server_admin/#_managing_groups_), [GroupPolicyProvider 26.8.1](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/authz/policy/common/src/main/java/org/keycloak/authorization/policy/provider/group/GroupPolicyProvider.java).

## R5. Quyết định mới cho từng cặp đối tượng–hành động

**Decision**: Mỗi hành động bảo vệ gọi token endpoint với access token của đúng người dùng, UMA grant, audience `giaokhoa-api`, đúng một `permission=<resource>#<scope>` và `response_mode=decision`. Không dùng service-account token để quyết định thay người dùng. Không gửi RPT cũ, không nhận `claim_token`/actor/tenant claims do browser tự cung cấp. Không cache allow qua request.

**Rationale**: Quyền trong phiên/token cũ không chứng minh quyền hiện hành. Source 26.8.1 cho thấy `isGranted()` khi không gửi RPT kiểm tra có ít nhất một permission; một `decision=true` cho batch không chứng minh tất cả cặp đều được cấp. MVP gọi riêng từng cặp cần thiết và yêu cầu tất cả thành công. Nếu tối ưu batch sau này phải dùng danh sách permissions trả về và so khớp đầy đủ.

**Alternatives considered**: Offline RPT/JWT permission hoặc introspection RPT không thay thế một lần đánh giá policy mới. SDK/enforcer cache mặc định không được chọn trước khi chứng minh không vi phạm thu hồi tức thời.

**Sources**: [Obtaining permissions](https://www.keycloak.org/docs/26.8.0/authorization_services/#_service_obtaining_permissions), [AuthorizationTokenService 26.8.1](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/services/src/main/java/org/keycloak/authorization/authorization/AuthorizationTokenService.java).

## R6. Projection và hàng rào độ tươi

**Decision**: Lưu revision và thao tác đồng bộ bền trong PostgreSQL; worker chạy trong Spring, không thêm broker. Mutation đặt các partition bị ảnh hưởng thành PENDING, provision idempotent graph Keycloak, đối chiếu revision rồi chuyển READY. Chỉ báo hoàn tất khi quyền đã có hiệu lực. PEP không cho hành động đi qua partition chưa READY hoặc revision thay đổi trong quá trình xét quyền.

**Rationale**: PostgreSQL và Keycloak không có transaction chung. Pending có thể tạm chặn hành động vẫn còn quyền, nhưng tránh báo thu hồi thành công trong khi quyền cũ còn dùng được. Khóa chia sẻ theo partition cho request và khóa cập nhật cho mutation xác lập thứ tự trước/sau; nhiều khóa lấy theo thứ tự ổn định. Chi tiết ở [authorization contract](contracts/authorization.md).

**Alternatives considered**: Outbox eventual-consistency không có fence không đạt FR-049/FR-052; gọi Keycloak trước rồi bỏ qua lỗi database cũng có thể để lại quyền lẻ. Không cần Redis hoặc queue service cho mức đầu.

**Sources**: [PostgreSQL explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html). Đây là giao thức ứng dụng được suy ra để đáp ứng spec, không phải bảo đảm giao dịch sẵn có của Keycloak.

## R7. Phạm vi vận hành console

**Decision**: Tạo/gỡ membership, phân công và quyền học liệu qua Giáo Khoa để cập nhật cả directory lẫn projection. Thay đổi bằng Keycloak Admin Console là thao tác khẩn cấp: đưa phạm vi vào trạng thái bảo trì/fenced, reconcile và xác nhận trước khi mở lại. Vẫn cho quản trị viên tự cấu hình MFA và chính sách đăng nhập.

**Rationale**: Sửa Organization trực tiếp không tự sửa realm-group/user-policy projection. Không hứa thu hồi tức thời cho thao tác ngoài luồng khi chưa có listener. Tài khoản học sinh dùng membership độc lập để việc gỡ một trung tâm không xóa danh tính.

**Alternatives considered**: Listener sự kiện tùy chỉnh có thể tự đồng bộ ngoài luồng nhưng thêm extension/đường vận hành không cần cho bản đầu. Không âm thầm thay yêu cầu thu hồi bằng thời gian chờ token hết hạn.

**Sources**: [Managed and unmanaged members](https://www.keycloak.org/docs/26.8.0/server_admin/), [Organizations group source](https://github.com/keycloak/keycloak/blob/26.8.1/docs/documentation/server_admin/topics/organizations/managing-groups.adoc).

## R8. Hết hạn, hoàn tất bài cũ và chia sẻ

**Decision**: Native time policies kiểm tra thời gian trực tiếp, không chờ scheduler để đóng gói. Read tách với create/edit/assign. Completion branch của hoạt động đã giao không đòi gói hiện còn hiệu lực nhưng vẫn cần membership/phân công/ghi danh/license hiện hành. Chia sẻ lâu dài không phụ thuộc gói hoặc membership hiện tại của người đã chia sẻ.

**Rationale**: Giữ lịch sử và hoạt động đã giao, đồng thời không mở đường tạo hoạt động mới. Bản chỉnh sửa phải tham chiếu các gate nguồn còn áp dụng; thu hồi gate nguồn không được làm quyền bản nhận trở thành độc lập. Native policy graph biểu diễn quyền, invariant thời điểm giao và môn được xác lập khi tạo dữ kiện.

**Alternatives considered**: Chỉ đổi một role active/expired qua cron có cửa sổ cấp sai. Đổi ID tài nguyên khi chỉ lưu vào kho vi phạm giữ ID nguồn. ID nghiệp vụ Q/V1 và ID authorization projection theo workspace là hai định danh khác mục đích.

**Recipe biên thời gian**: Source 26.8.1 dùng so sánh `after(end)` cho `noa`, nên tại đúng end còn có thể grant. Để thể hiện `[start,end)` bằng native policies: AND `time(nbf=start, POSITIVE)` với `time(nbf=end, NEGATIVE)`. Nhánh thứ hai deny từ đúng end trở đi. Không dùng `noa=end` một mình làm gate half-open; gate runtime phải xác nhận recipe và timezone UTC.

**Sources**: [TimePolicyProvider 26.8.1](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/authz/policy/common/src/main/java/org/keycloak/authorization/policy/provider/time/TimePolicyProvider.java), [Native policy types](https://www.keycloak.org/docs/26.8.0/authorization_services/). Precision/timezone của time policy phải được kiểm chứng tại gate, với giờ UTC và mốc giây thống nhất.

## R9. Dữ liệu và cách ly

**Decision**: Một app database PostgreSQL; mỗi tenant (workspace) một schema riêng khi đã kích hoạt, các bảng tenant trong schema hệ thống (public) là mặt phẳng dùng chung cho workspace chưa kích hoạt. `workspace_id` và khóa ngoại tổng hợp trên các quan hệ dạy học. Spring Data JPA/Hibernate, Flyway là nguồn duy nhất đổi schema (hai dòng migration: hệ thống và tenant); Hibernate không validate toàn cục vì validate không làm việc với multitenancy theo schema. Cách ly workspace dựa vào ranh giới schema với tenant đã kích hoạt và vào mệnh đề `workspace_id` trong truy vấn ứng dụng với tenant chưa kích hoạt — không dùng RLS (đã tháo ở bước chốt của đợt di trú JPA; lịch sử quyết định và bằng chứng ở Sổ B103 trong `docs/diem-chua-chac-chan-va-lech-spec.md`). Canonical tài nguyên nguồn được truy cập qua repository chuyên biệt sau quyết định quyền trong ngữ cảnh nhận, không qua endpoint danh sách toàn cục.

**Rationale**: Không cần database mỗi trung tâm; schema mỗi tenant cho ranh giới cách ly cứng ở tầng DB mà không nhân chi phí vận hành của database riêng. Runtime role không là owner, không BYPASSRLS, chỉ có DML theo grants gắn với từng schema (trừ bảng lịch sử migration chỉ được đọc); migration role tách riêng. Directory danh tính/trung tâm và control metadata projection được quản lý bằng service chuyên biệt, không trả dữ liệu nghiệp vụ từ lookup chưa xét quyền.

**Alternatives considered**: Database mỗi tenant tăng vận hành. Schema dùng chung + RLS là mô hình chuyển tiếp trong lúc di trú, đã nghỉ hưu khi schema-per-tenant land vì hai cơ chế cách ly cùng lúc làm mọi đường truy cập phải mang cả hai kỷ luật. Chỉ lọc tenant ở UI không bảo vệ API.

**Sources**: [PostgreSQL row security](https://www.postgresql.org/docs/18/ddl-rowsecurity.html), [Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## R10. Web security và cache

**Decision**: Cookie phiên HttpOnly, Secure ở HTTPS, SameSite=Lax, host-only; CSRF cho mọi mutation từ browser. Chỉ endpoint giao thức OIDC back-channel được miễn browser CSRF/session authentication và phải xác minh signed logout_token bằng Spring handler. Next không cache session, capabilities hoặc dữ liệu được bảo vệ; protected responses `Cache-Control: private, no-store`. Logout người dùng là POST có CSRF và RP-Initiated Logout. Back-channel dùng đúng tên cookie `SESSION`; triển khai OidcSessionRegistry correlation bền trên JDBC thay vì giữ registry chỉ in-memory.

**Rationale**: Chuyển workspace hoặc thu hồi phải không trả lại dữ liệu từ cache. Next chỉ dùng capability để hiển thị, backend luôn xét lại. Một backend instance là baseline; kiểm chứng restart giữ session/token và correlation riêng, không suy luận rằng JDBC session tự persist mọi dữ kiện OAuth2 hoặc registry.

**Alternatives considered**: Cookie chia sẻ `.giaokhoa.com`, token trong localStorage, bỏ CSRF hoặc cache quyết định allow không đáp ứng ranh giới đã chốt.

**Sources**: [Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [OIDC logout](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html), [Next fetching](https://nextjs.org/docs/app/getting-started/fetching-data).

## R11. Gate bắt buộc trước xây rộng

**Decision**: Việc đầu của implementation là một lát cắt tích hợp Spring–Next–Keycloak thật–PostgreSQL thật; qua gate mới mở rộng các màn hình quản trị. Không tạo spike code hoặc chạy test sản phẩm trong lệnh lập kế hoạch này.

**Gate criteria**: Một resource/scope mới, cùng session trước/sau revoke, vai trò trái ngược A/B, phụ huynh/con/kết quả chưa công bố, batch chỉ được một phần, gói hết hạn/completion, grant nguồn bị thu hồi qua bản chỉnh sửa, cập nhật thất bại/crash và cạnh tranh revoke/write. Chứng minh hai backend request sau cùng không dựa vào quyền cũ; không mock PDP/JWT cho các tiêu chí này.

**Alternatives considered**: Làm mọi CRUD rồi mới thử quyền có rủi ro thiết kế lại nhiều module. Unit test policy giả không xác nhận hành vi native Keycloak.

**Sources**: [Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), [Playwright web server](https://playwright.dev/docs/test-webserver).

## R12. Một realm cho self-register xác minh email và học sinh không email

**Decision**: Realm bật registration, loginWithEmail và verifyEmail, nhưng không dùng email-as-username hoặc bắt buộc email toàn User Profile. Không đặt VERIFY_EMAIL/UPDATE_PROFILE thành default required action cho mọi user; tài khoản cấp mới có temporary password và UPDATE_PASSWORD. Backend lưu nguồn tạo tài khoản; unknown mặc định SELF_REGISTERED. Chỉ CENTER_ISSUED do chính backend tạo mới được miễn điều kiện email. Invite tài khoản có sẵn không đổi nguồn.

**Rationale**: Source 26.8.1 cho thấy VerifyEmail bỏ qua email trống, trong khi User Profile/VERIFY_PROFILE có thể ép người không email bổ sung email. Default required actions đã thêm lúc create không bị xóa chỉ bằng payload requiredActions khác. Người self-register bỏ email có thể có danh tính nhưng không có nghiệp vụ đến khi thêm và xác minh email; onboarding/status/recovery vẫn truy cập được. Không cần custom SPI hoặc theme riêng để bảo vệ điều kiện này.

Trước protected business request, Spring lấy profile hiện hành qua Admin API bằng credential nội bộ chỉ để xác thực trạng thái identity; đối chiếu sub và cập nhật eligibility source nếu đổi, đi qua account partition/fence trước gọi PDP. Không dùng email_verified claim cũ hoặc field account_origin frontend tự khai. Nếu không lấy được thông tin hiện hành thì fail-closed. Đường này cung cấp input danh tính đáng tin cậy, không dùng service account đánh giá quyền thay user; native eligibility policy vẫn là gate của mọi business permission. Thay đổi email/xóa email sau xác minh phải làm mất eligibility của SELF_REGISTERED cho đến khi verified lại; không tin một cờ đã xác minh vĩnh viễn.

**Alternatives considered**: Email bắt buộc toàn realm phá ngoại lệ học sinh không email. Tắt verifyEmail rồi tin role/attribute tự khai mở đường bỏ qua xác minh. Đánh dấu email giả là verified không được chấp nhận. Listener đồng bộ identity có thể tối ưu sau, chưa cần cho mức đầu.

**Sources**: [VerifyEmail 26.8.1](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/services/src/main/java/org/keycloak/authentication/requiredactions/VerifyEmail.java), [VerifyUserProfile](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/services/src/main/java/org/keycloak/authentication/requiredactions/VerifyUserProfile.java), [UserResource](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java), [JpaUserProvider](https://raw.githubusercontent.com/keycloak/keycloak/26.8.1/model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserProvider.java), [User Profile](https://www.keycloak.org/docs/26.8.0/server_admin/#_user-profile).

## Kết luận Phase 0

Các lựa chọn kỹ thuật chưa rõ đã có quyết định và phương án thay thế. Không có yêu cầu nghiệp vụ mới cần hỏi người dùng trong bước này. Tính khả thi runtime của native graph, thời gian policy và fence là gate của implementation, chưa được báo là đã đạt. Nếu gate thất bại, dừng mở rộng và ghi nhận bằng chứng, không tự đổi spec sang PDP khác.
