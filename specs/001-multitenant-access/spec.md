# Feature Specification: Bộ khung xác thực và phân quyền đa trung tâm Giáo Khoa

**Feature Branch**: `main`

**Created**: 2026-11-05

**Status**: Draft — theo các quyết định nghiệp vụ đã được người dùng duyệt.

**Input**: Xây dựng bộ khung Authentication & Authorization cho Giáo Khoa: đăng nhập chung bằng Keycloak, bộ quyền cố định cho các trung tâm, phân quyền theo tài nguyên và phân công lớp–khóa–môn, ghi danh từng môn, liên kết phụ huynh–học sinh và quyền sử dụng theo gói. Bản đầu vận hành phần quản trị; nghiệp vụ học liệu, bài làm và kết quả được kiểm chứng bằng dữ liệu mẫu.

## Clarifications

### Session 2026-11-12

- Q: Sau khi học sinh hoàn thành một đợt học bình thường, em có được xem lại kết quả đã công bố của mình không? → A: Có; tách trạng thái hoàn thành khỏi thu hồi, giữ quyền xem lịch sử trong phạm vi/thời hạn được chốt và chặn bài mới. Khóa tài khoản hoặc thu hồi bảo mật vẫn chặn truy cập (NV-01).
- Q: Quyền xem kết quả sau hoàn thành đợt học kéo dài đến khi nào? → A: Trong thời gian hồ sơ còn được lưu hợp lệ theo chính sách dữ liệu, không tự mất khi hết gói; khóa tài khoản hoặc thu hồi bảo mật vẫn chặn ngay (NV-01).
- Q: Trong bản đầu, việc xác minh người đại diện và căn cứ cho phụ huynh xem dữ liệu học sinh được thực hiện ở đâu? → A: Đơn vị vận hành xác minh ngoài hệ thống; ứng dụng lưu tham chiếu xác nhận tối thiểu, không yêu cầu tải giấy tờ nhạy cảm. Vai trò PARENT hoặc mã học sinh không thay bước xác minh (NV-02).
- Q: Với bài kiểm tra cần bảo đảm học sinh hoàn tất, sản phẩm cho phép dùng nguồn học liệu nào? → A: Chỉ nguồn sở hữu hoặc LONG_TERM; REVOCABLE chỉ dùng cho luyện tập có thể gián đoạn và bị chặn ngay khi thu hồi (NV-04).
- Q: Khi gói trung tâm đã hết hạn và giáo viên duy nhất nghỉ, trung tâm xử lý bài còn chờ chấm theo cách nào? → A: Gia hạn gói trước khi phân công người thay; người cũ mất quyền ngay. Không cấp thêm quyền khi hết hạn và phải nêu giới hạn này trong cam kết hoàn tất bài (NV-05).

## User Scenarios & Testing *(mandatory)*

Các tình huống học liệu, bài làm, chấm bài và kết quả kiểm chứng quyết định quyền bằng dữ liệu mẫu và các thao tác kiểm chứng tối thiểu; không yêu cầu xây dựng công cụ biên soạn, làm bài hoặc sổ điểm hoàn chỉnh. Quản trị tài khoản, trung tâm, thành viên, lớp, khóa, môn, ghi danh và phân công phải chạy được với dữ liệu thực tế. Mỗi câu chuyện có thể được nghiệm thu riêng với dữ kiện tiền đề chuẩn bị sẵn.

### Luồng sử dụng theo vai trò

[Bản đồ luồng theo vai trò](../../docs/luong-nghiep-vu-theo-vai-tro.md) xác định 22 luồng có mã ổn định: ai thực hiện, điều kiện bắt đầu, các bước, kết quả và trường hợp bị chặn. Đây là khung chung cho hướng dẫn sử dụng và E2E, không coi vai trò chỉ là danh sách permission. Hành trình bàn giao giữa quản lý, giáo viên, học sinh và phụ huynh phải giữ đúng danh tính/phạm vi ở từng bước; test dùng tài khoản kiêm mọi vai trò không thay kiểm chứng quyền tối thiểu.

E2E cần nối mã luồng với vai trò và đúng hành vi người dùng. Test UI có API giả, test giao thức và fixture không thay E2E nghiệp vụ với backend/Keycloak thật. Nhánh giao/chấm bài của chủ nhiệm, khôi phục bằng operator và các luồng bổ sung chưa được khóa đủ được ghi rõ trong bản đồ, không suy từ số test đang có.

### User Story 1 - Một tài khoản dùng ở nhiều không gian (Priority: P1)

Người dùng đăng ký, đăng nhập chung và chuyển giữa không gian cá nhân với các trung tâm mình tham gia, giữ quyền riêng ở mỗi nơi.

**Why this priority**: Danh tính thống nhất và cách ly quyền là điều kiện để các nghiệp vụ còn lại hoạt động đúng.

**Independent Test**: Chuẩn bị một tài khoản có phân công khác nhau tại hai trung tâm; kiểm tra đăng nhập, chuyển không gian và khôi phục tài khoản mà không cần học liệu thật.

**Acceptance Scenarios**:

1. **Given** người tự đăng ký chưa xác minh email, **When** sử dụng chức năng nghiệp vụ, **Then** bị từ chối; vẫn được hoàn tất xác minh hoặc khôi phục đăng nhập.
2. **Given** email đã xác minh, **When** đăng nhập lần đầu, **Then** tài khoản trung tính có quyền làm đề hệ thống được phép, chưa có kho soạn học liệu và không bị gán vai trò học sinh hoặc giáo viên toàn hệ thống.
3. **Given** tài khoản là quản lý tại A và học sinh tại B, **When** chuyển từ A sang B, **Then** chỉ quyền hợp lệ tại B được áp dụng; quyền quản lý A không áp sang B.
4. **Given** học sinh chưa có tài khoản và không có email, **When** chủ sở hữu/quản lý cấp tài khoản và thêm vào trung tâm, **Then** học sinh đăng nhập được bằng tên đăng nhập và nhận vai trò học sinh trong đúng phạm vi được cấp.
5. **Given** học sinh đã có tài khoản, **When** được mời vào trung tâm khác, **Then** dùng lại danh tính hiện có thay vì bắt buộc tạo tài khoản khác.
6. **Given** tài khoản không email cần khôi phục, **When** trung tâm yêu cầu hỗ trợ, **Then** chỉ quản trị viên Giáo Khoa được xử lý sau xác minh; quản lý trung tâm không được tự đổi mật khẩu của danh tính dùng chung.
7. **Given** tài khoản có email đã xác minh, **When** quên mật khẩu, **Then** được thực hiện quy trình khôi phục qua email đó.
8. **Given** tài khoản rời A nhưng còn tham gia B, **When** đăng nhập lại, **Then** danh tính, quyền hợp lệ tại B và gói cá nhân độc lập vẫn được giữ.
9. **Given** người gọi chưa đăng nhập hoặc thông tin đăng nhập không hợp lệ/đã hết hạn, **When** truy cập hành động cần xác thực, **Then** bị từ chối và được hướng dẫn đăng nhập; không dùng vai trò tự khai để tiếp tục.

### User Story 2 - Khởi tạo và quản trị trung tâm (Priority: P1)

Quản trị viên Giáo Khoa tạo tenant, chỉ định chủ sở hữu. Chủ sở hữu và quản lý tổ chức thành viên theo bộ quyền chung.

**Why this priority**: Trung tâm cần có người chịu trách nhiệm và vận hành được ngay khi được kích hoạt.

**Independent Test**: Tạo trung tâm, chỉ định chủ sở hữu, bổ nhiệm quản lý và mời thành viên; đối chiếu thao tác được phép của từng người.

**Acceptance Scenarios**:

1. **Given** trung tâm đã được xác nhận mua gói, **When** quản trị viên Giáo Khoa tạo tenant và chỉ định chủ sở hữu, **Then** trung tâm có không gian riêng và chủ sở hữu quản trị được trung tâm đó.
2. **Given** người dùng thông thường, **When** tự tạo tenant hoặc tự kích hoạt gói, **Then** bị từ chối.
3. **Given** chủ sở hữu A, **When** bổ nhiệm/gỡ quản lý A, **Then** quyền quản lý được cập nhật trong A và không đổi quyền tại B.
4. **Given** quản lý A, **When** mời, cấp tài khoản học sinh, gán vai trò vận hành hoặc gỡ thành viên A, **Then** được phép trong thẩm quyền; tự trở thành chủ sở hữu hoặc bổ nhiệm quản lý khác bị từ chối.
5. **Given** giáo viên A, **When** tạo lớp hoặc tự phân công giáo viên trong A, **Then** bị từ chối kể cả có gói cá nhân.
6. **Given** hai trung tâm mới, **When** đối chiếu vai trò và hành động, **Then** cùng dùng bộ quyền cố định và không có chức năng tạo vai trò tùy chỉnh.
7. **Given** quản lý A hoặc quản trị viên nền tảng chưa có nguồn quyền trên tài nguyên cá nhân X, **When** đọc X chỉ dựa trên chức vụ quản trị, **Then** bị từ chối.

### User Story 3 - Phân công và ghi danh trong khóa đa môn (Priority: P1)

Quản lý tạo lớp, khóa đa môn, phân giáo viên từng môn và ghi danh học sinh theo nhu cầu.

**Why this priority**: Phân công và ghi danh xác định quyền giảng dạy, học tập và truy cập kết quả.

**Independent Test**: Dùng một lớp có khóa ba môn, học sinh chỉ học một số môn và nhiều giáo viên; kiểm tra quyền bằng đề/bài nộp mẫu.

**Acceptance Scenarios**:

1. **Given** quản lý A, **When** tạo lớp, tạo khóa và thêm nhiều môn, **Then** các đối tượng thuộc A; mỗi khóa chỉ thuộc một lớp.
2. **Given** khóa ba môn, **When** quản lý ghi danh X chỉ vào môn thứ nhất, **Then** X chỉ nhận bài và xem kết quả của mình ở môn đã ghi danh, không truy cập môn còn lại.
3. **Given** X chưa thuộc danh sách học sinh lớp L, **When** ghi danh X vào môn trong khóa của L, **Then** hệ thống yêu cầu thêm X vào lớp hợp lệ trước.
4. **Given** giáo viên T được phân công môn M trong khóa K, **When** giao đề đúng môn và có quyền dùng, **Then** được phép; đề khác môn hoặc khóa chưa có phân công bị từ chối.
5. **Given** T và U cùng được phân công một môn trong K, **When** từng người giao/chấm bài mẫu của môn đó, **Then** được phép trong cùng phạm vi; T có thể nhận thêm môn khác qua phân công bổ sung.
6. **Given** chủ nhiệm L không có phân công chuyên môn M, **When** giao đề M đã duyệt và được phép sử dụng, **Then** được phép cho người học M trong L; sửa đề, xem đáp án, chấm M cần quyền riêng.
7. **Given** chủ nhiệm L1, **When** thao tác với khóa thuộc L2 mà không có phân công khác, **Then** bị từ chối dù hai lớp cùng trung tâm.
8. **Given** quản lý chọn ghi danh toàn bộ môn hiện có, **When** hoàn tất thao tác, **Then** từng ghi danh được tạo; môn được thêm sau đó không tự có ghi danh hoặc phân công.
9. **Given** học sinh X ghi danh nhiều môn trong cùng khóa K, **When** quản lý hoàn thành đợt học K của X, **Then** mọi ghi danh đang ACTIVE của X thuộc K cùng chuyển COMPLETED (không hoàn thành khóa/lớp khác), X vẫn đọc kết quả PUBLISHED của đợt K khi hồ sơ còn được lưu hợp lệ kể cả khi hết gói, nhưng không nhận/nộp bài mới ở bất kỳ môn nào của K; thu hồi bảo mật hoặc khóa tài khoản vẫn chặn đọc.

### User Story 4 - Phụ huynh xem kết quả của đúng con (Priority: P1)

Phụ huynh đề nghị liên kết và xem kết quả đã công bố sau khi trung tâm xác nhận.

**Why this priority**: Bảo vệ thông tin học sinh, không mở quyền xem cả lớp hoặc kết quả chưa công bố.

**Independent Test**: Dùng hai học sinh có kết quả mẫu công bố/chưa công bố; thử quyền phụ huynh trước, sau duyệt và sau gỡ liên kết.

**Acceptance Scenarios**:

1. **Given** phụ huynh chưa được duyệt liên kết, **When** xem kết quả X, **Then** bị từ chối dù đã gửi yêu cầu liên kết.
2. **Given** chủ sở hữu/quản lý A xác nhận P liên kết với X, **When** P xem kết quả, **Then** chỉ được xem kết quả đã công bố của X tại A; kết quả chưa công bố, bài làm, đáp án và dữ liệu học sinh khác bị từ chối nếu chỉ dựa vào vai trò phụ huynh.
3. **Given** liên kết P–X tại A đã duyệt, **When** xem kết quả X tại B, **Then** liên kết A không tự cấp quyền tại B.
4. **Given** giáo viên chủ nhiệm không có vai trò quản lý/chủ sở hữu, **When** duyệt liên kết, **Then** bị từ chối.
5. **Given** liên kết đã gỡ, **When** P gửi yêu cầu tiếp theo bằng phiên cũ, **Then** không còn quyền xem qua liên kết đó; dữ liệu vẫn được giữ cho người còn thẩm quyền.
6. **Given** yêu cầu liên kết chưa có tham chiếu xác minh người đại diện và căn cứ cho xem dữ liệu, **When** quản lý duyệt, **Then** bị từ chối; duyệt hợp lệ lưu tham chiếu, người xác nhận và thời điểm, không yêu cầu tải giấy tờ nhạy cảm.

### User Story 5 - Thu hồi quyền và ngăn truy cập vượt phạm vi (Priority: P1)

Thu hồi có hiệu lực ngay dù người dùng còn phiên đăng nhập cũ; quyền hợp lệ độc lập không bị ảnh hưởng.

**Why this priority**: Quyền phải bảo vệ được dữ liệu của đúng trung tâm, lớp, môn và học sinh.

**Independent Test**: Giữ nguyên phiên, lần lượt gỡ phân công/membership/ghi danh/liên kết, thử lại cùng hành động và ở không gian khác còn quyền.

**Acceptance Scenarios**:

1. **Given** giáo viên được chấm M tại A, **When** gỡ phân công được ghi nhận thành công, **Then** yêu cầu chấm tiếp theo bị từ chối, không đợi đăng xuất hoặc hết phiên.
2. **Given** thành viên bị gỡ khỏi A, **When** dùng phiên cũ truy cập A, **Then** quyền xuất phát từ membership/phân công A bị từ chối; quyền hợp lệ tại B vẫn dùng được.
3. **Given** học sinh bị gỡ ghi danh M, **When** truy cập bài/kết quả dựa trên ghi danh đó, **Then** yêu cầu tiếp theo bị từ chối, lịch sử được giữ cho người còn thẩm quyền.
4. **Given** người dùng sửa thông tin người thực hiện/trung tâm/lớp/môn/học sinh để vượt quyền, **When** xét quyền, **Then** không được cấp quyền từ thông tin giả đó.
5. **Given** một người có nhiều nguồn quyền, **When** một nguồn bị thu hồi, **Then** chỉ quyền dựa trên nguồn đó mất hiệu lực; nguồn hợp lệ độc lập vẫn được áp dụng đúng phạm vi.
6. **Given** không xác lập được quyền hiện hành hoặc cập nhật quyền thất bại, **When** thực hiện hành động cần quyền, **Then** không cấp thêm quyền và không báo thu hồi thành công khi quyền cũ còn sử dụng được.

### User Story 6 - Tách quyền cá nhân khỏi quyền trung tâm (Priority: P1)

Người mua gói cá nhân có kho soạn học liệu và lớp cá nhân. Giáo viên làm việc trong trung tâm theo phân công mà không cần mua gói cá nhân.

**Why this priority**: Phân biệt quyền lợi trả phí và tránh vai trò trung tâm mở nhầm quyền cá nhân.

**Independent Test**: Đối chiếu tài khoản chỉ có gói cá nhân, chỉ có phân công trung tâm và có cả hai; kiểm tra tạo học liệu/lớp ở từng không gian.

**Acceptance Scenarios**:

1. **Given** chưa có gói cá nhân, **When** tạo tài nguyên hoặc lớp cá nhân, **Then** bị từ chối; lịch sử làm đề hệ thống vẫn được giữ.
2. **Given** quản trị viên xác nhận thanh toán và kích hoạt gói cá nhân, **When** tạo học liệu/lớp cá nhân, **Then** được phép theo gói, không cần xác minh nghề nghiệp.
3. **Given** giáo viên chưa mua gói cá nhân, **When** biên soạn theo phân công hợp lệ tại A, **Then** được phép và tài nguyên thuộc A; tạo kho soạn/lớp cá nhân vẫn bị từ chối.
4. **Given** người có gói cá nhân không phải quản lý A, **When** tạo lớp tại A, **Then** bị từ chối dù tạo được lớp cá nhân.
5. **Given** có cả gói cá nhân và phân công A, **When** rời A, **Then** quyền theo A chấm dứt, quyền cá nhân còn hiệu lực vẫn được giữ.

### User Story 7 - Giữ quyền nguồn khi lưu và chia sẻ học liệu (Priority: P2)

Cá nhân và trung tâm lưu học liệu được phép, nhận cập nhật chủ động và chia sẻ theo điều kiện.

**Why this priority**: Chứng minh quyền tài nguyên trước khi xây công cụ biên soạn.

**Independent Test**: Dùng tài nguyên mẫu có chủ sở hữu, môn, phiên bản, điều kiện chia sẻ; kiểm tra lưu, sửa, chia sẻ, cập nhật, thu hồi mà không cần đề thật.

**Acceptance Scenarios**:

1. **Given** Q/V1 của hệ thống được phép lưu, **When** cá nhân có gói hoặc trung tâm lưu vào kho, **Then** giữ định danh Q và V1; không đổi chủ sở hữu nguồn. Với nguồn chia sẻ giữa hai trung tâm/cá nhân, bản nhận có thể có định danh cục bộ riêng theo AS-012 nhưng phải giữ truy vết Q/V1 và toàn bộ giới hạn nguồn.
2. **Given** bản đã lưu Q/V1, **When** nguồn phát hành V2, **Then** bản đã lưu/bài đã giao vẫn dùng V1; bên nhận chủ động chọn phiên bản mới cho lần dùng sau, được thông báo nếu nguồn đính chính do lỗi.
3. **Given** người có quyền chỉnh sửa Q/V1, **When** tạo bản chỉnh sửa, **Then** có định danh mới, truy vết Q/V1 và giữ giới hạn nguồn; người chỉ có quyền dùng không được sửa.
4. **Given** giáo viên chia sẻ một phiên bản cho A, **When** A nhận vào kho, **Then** giáo viên khác có quyền tại A được lưu/dùng theo điều kiện nguồn; thành viên B không tự có quyền.
5. **Given** bản chỉ được dùng tại A, **When** lưu vào kho cá nhân hoặc tạo bản chỉnh sửa hợp lệ, **Then** vẫn không được dùng/chia sẻ ra B nếu chưa được nguồn cho phép.
6. **Given** chia sẻ có thể thu hồi, **When** thu hồi thành công, **Then** lần sử dụng mới dựa trên quyền đó bị từ chối từ yêu cầu tiếp theo, kể cả qua bản lưu/bản chỉnh sửa; lịch sử bài làm/kết quả không bị xóa.
7. **Given** chia sẻ sử dụng lâu dài cho A, **When** người chia sẻ rời A, **Then** A tiếp tục dùng phiên bản đã nhận theo điều kiện; người đã rời không giữ quyền trung tâm vì từng chia sẻ và không được đơn phương thu hồi chia sẻ lâu dài.
8. **Given** giáo viên chưa có gói cá nhân, **When** lưu học liệu để làm việc hợp lệ trong A, **Then** không phải mua gói cá nhân và cũng không được mở kho soạn cá nhân từ thao tác đó.
9. **Given** một đề mẫu gồm hai câu hỏi có điều kiện nguồn khác nhau, **When** người dùng chia sẻ đề ra ngoài phạm vi được một câu hỏi cho phép, **Then** thao tác bị từ chối; đóng gói thành đề không mở rộng quyền nguồn.
10. **Given** một hoạt động cần bảo đảm hoàn tất có thành phần REVOCABLE hoặc LONG_TERM hết hạn trước khi hoàn tất, **When** giao bài, **Then** bị từ chối dù lúc giao vẫn có quyền; chỉ nguồn sở hữu hoặc LONG_TERM bao phủ toàn bộ thời gian cần dùng mới đủ. Với mẫu chưa có hạn chấm/công bố, LONG_TERM phải không có `expiresAt`; không coi chỉ vượt `dueAt` là bảo đảm chấm/công bố.
11. **Given** luyện tập có thể gián đoạn dùng nguồn REVOCABLE, **When** nguồn bị thu hồi giữa lúc làm hoặc sau nộp/trước chấm, **Then** thao tác tiếp theo cần nguồn bị chặn với lý do rõ ràng, bài nộp/kết quả đã có được giữ; không tự chuyển thành quyền hoàn tất lâu dài.

### User Story 8 - Bảo lưu dữ liệu khi gói hết hạn (Priority: P2)

Cá nhân/trung tâm hết hạn gói vẫn giữ dữ liệu và hoàn tất bài đã giao theo lịch cũ, không được mở rộng hoạt động ngoài quyền còn hiệu lực.

**Why this priority**: Duy trì việc học đang diễn ra, đồng thời kiểm soát quyền lợi trả phí.

**Independent Test**: Dùng gói có mốc hết hạn và hoạt động mẫu đã giao; thử đọc, tạo mới, hoàn tất bài và gia hạn.

**Acceptance Scenarios**:

1. **Given** gói cá nhân hết hạn, **When** đọc tài nguyên còn quyền, **Then** được phép; tạo/sửa học liệu, tạo lớp, giao bài mới bị từ chối.
2. **Given** bài cá nhân đã giao trước hết hạn, **When** học sinh nộp theo lịch cũ và người giao chấm/nhận xét/công bố, **Then** được phép; gia hạn hạn nộp, mở lượt mới, giao lại bị từ chối.
3. **Given** gói A hết hạn, **When** thành viên xem dữ liệu theo quyền hoặc phụ huynh xem kết quả công bố của con, **Then** được phép; tạo lớp/khóa, thêm môn/ghi danh/phân công, tạo/sửa học liệu, giao bài mới bị từ chối.
4. **Given** bài tại A đã giao trước hết hạn, **When** người còn quyền hoàn tất theo lịch cũ, **Then** được phép nộp/chấm/nhận xét/công bố.
5. **Given** gói A hết hạn, **When** chủ sở hữu/quản lý gỡ thành viên, phân công hoặc liên kết, **Then** vẫn được phép, thu hồi có hiệu lực từ yêu cầu tiếp theo.
6. **Given** gói cá nhân hết hạn nhưng A còn hiệu lực hoặc ngược lại, **When** xét quyền từng nơi, **Then** trạng thái gói này không thay đổi quyền hợp lệ ở nơi kia.
7. **Given** gói được kích hoạt lại, **When** người có phân công còn hiệu lực thao tác, **Then** quyền theo gói được mở lại; phân công/liên kết/chia sẻ đã thu hồi không tự phục hồi.
8. **Given** gói trung tâm hết hạn và giáo viên duy nhất bị thu hồi phân công, **When** trung tâm cần người khác chấm bài tồn, **Then** người cũ bị chặn ngay và phải gia hạn trước khi phân công người thay; hết hạn không cấp đường bàn giao tăng quyền riêng.

### Edge Cases

- Cùng người là quản lý tại A, giáo viên tại B và phụ huynh tại A: xét từng nguồn và đối tượng, không cộng thành quyền toàn hệ thống.
- Chủ nhiệm giao nhiều môn nhưng chỉ người ghi danh môn tương ứng nhận bài; không tự có quyền xem đáp án hoặc chấm mọi môn.
- Hai giáo viên cùng môn không mặc nhiên được sửa tài nguyên cá nhân của nhau.
- Thêm môn không tự thêm ghi danh/phân công; gửi lại lời mời hoặc duyệt lại cùng liên kết không mở rộng quyền hay tạo nguồn quyền trùng.
- Không ghép danh tính theo họ tên trùng; mời tài khoản có sẵn hoặc yêu cầu hỗ trợ xác minh nếu không xác định được tài khoản.
- Kho lưu, tác giả, chủ sở hữu nguồn và nơi được dùng có thể khác nhau; quyền dạy học không chuyển quyền sở hữu.
- Tài nguyên xuất hiện trong nhiều đề/bản chỉnh sửa vẫn giữ phiên bản và điều kiện nguồn; sao chép không được vượt quyền thu hồi.
- Tài nguyên tổng hợp nhiều nguồn phải thỏa quyền của các thành phần mà hành động cần dùng, gồm đáp án khi hành động cần đáp án.
- Thu hồi chia sẻ trong lúc đang làm bài: giữ lịch sử đã phát sinh; không tự hưởng ngoại lệ hoàn tất do hết hạn gói. Hành động tiếp theo cần nguồn quyền hiện hành hợp lệ.
- Ngoại lệ hết hạn gói không vượt việc gỡ membership, phân công, ghi danh, liên kết hoặc quyền học liệu.
- Không xóa trực tiếp môn/lớp/khóa làm mất lịch sử phụ thuộc; khi chưa có chức năng lưu trữ riêng, từ chối xóa đối tượng đã phát sinh dữ liệu đó.
- Kết quả chưa công bố không được phụ huynh xem, kể cả khi gói trung tâm đã hết hạn.
- Gói cá nhân của người chia sẻ hết hạn không tự hủy quyền đã cấp cho trung tâm; bên nhận vẫn chịu điều kiện tài nguyên và trạng thái gói của chính mình.
- Học sinh được trung tâm cấp tài khoản vẫn sở hữu danh tính dùng chung; trung tâm không được đặt lại mật khẩu ảnh hưởng nơi khác.

## Requirements *(mandatory)*

### Functional Requirements

#### Danh tính và tài khoản

- **FR-001**: Hệ thống PHẢI cung cấp đăng nhập chung tại `auth.giaokhoa.com`, giữ một danh tính dùng ở không gian cá nhân và nhiều trung tâm.
- **FR-002**: Người tự đăng ký PHẢI xác minh email trước khi dùng nghiệp vụ; các bước xác minh/khôi phục đăng nhập vẫn phải truy cập được.
- **FR-003**: Tài khoản mới PHẢI trung tính và có quyền nền làm đề hệ thống được phép; KHÔNG tự nhận vai trò nghề nghiệp/học sinh toàn hệ thống, quyền soạn học liệu hoặc tạo lớp cá nhân.
- **FR-004**: Chủ sở hữu/quản lý PHẢI mời được danh tính có sẵn hoặc cấp tài khoản học sinh có tên đăng nhập duy nhất, không bắt buộc email; rời trung tâm không xóa danh tính dùng chung.
- **FR-005**: Tài khoản có email xác minh PHẢI khôi phục được qua email; tài khoản không email chỉ được quản trị viên Giáo Khoa khôi phục sau xác minh, trung tâm được gửi yêu cầu hỗ trợ.
- **FR-006**: Người dùng PHẢI chuyển được giữa các không gian hợp lệ, thấy rõ nơi đang hoạt động; quyền tại một nơi KHÔNG được dùng để cấp quyền ở nơi khác.

#### Trung tâm và bộ quyền chung

- **FR-007**: Chỉ quản trị viên Giáo Khoa được tạo tenant và chỉ định chủ sở hữu ban đầu; bản đầu PHẢI hỗ trợ xác nhận thanh toán và kích hoạt/gia hạn gói cá nhân/trung tâm thủ công.
- **FR-008**: Tất cả trung tâm PHẢI dùng bộ vai trò chung: chủ sở hữu, quản lý, giáo viên, học sinh, phụ huynh. Chủ nhiệm và giáo viên bộ môn PHẢI là phân công có phạm vi; KHÔNG có tính năng tự định nghĩa vai trò/quyền mới.
- **FR-009**: Chủ sở hữu PHẢI có quyền vận hành của quản lý và bổ nhiệm/gỡ quản lý tại trung tâm mình; quản lý KHÔNG tự bổ nhiệm chủ sở hữu hoặc quản lý khác.
- **FR-010**: Chủ sở hữu/quản lý PHẢI quản lý được thành viên vận hành, lớp, khóa, môn trong khóa, ghi danh, phân công và liên kết phụ huynh trong đúng trung tâm.
- **FR-011**: Giáo viên trung tâm KHÔNG được tạo lớp, tự phân công hoặc mở rộng quyền của mình; gói cá nhân không thay đổi giới hạn này.
- **FR-012**: Vai trò quản trị trung tâm KHÔNG tự cấp quyền trên kho cá nhân hoặc tài nguyên trung tâm khác; quyền quản trị nền tảng KHÔNG mặc nhiên là quyền đọc mọi học liệu/kết quả.

#### Lớp, khóa, môn và phân công

- **FR-013**: Trung tâm PHẢI có thể có nhiều lớp; mỗi khóa PHẢI thuộc một lớp và có một hoặc nhiều môn do chủ sở hữu/quản lý thêm. KHÔNG cho phân công/ghi danh tham chiếu chéo trung tâm hoặc sai lớp–khóa–môn.
- **FR-014**: Hệ thống PHẢI quản lý học sinh của lớp và ghi danh độc lập từng môn trong khóa; chỉ học sinh hợp lệ của lớp được ghi danh các khóa thuộc lớp.
- **FR-015**: Quản lý PHẢI chọn được một, nhiều hoặc toàn bộ môn hiện có khi ghi danh; thêm môn sau đó KHÔNG tự tạo ghi danh/phân công.
- **FR-016**: Phân công giảng dạy PHẢI chỉ rõ giáo viên, trung tâm, lớp, khóa, môn; một giáo viên nhận được nhiều phân công và một môn trong khóa có được nhiều giáo viên.
- **FR-017**: Giáo viên bộ môn PHẢI chỉ giao bài, chấm và quản lý kết quả trong phân công còn hiệu lực; không tự có quyền trên môn/khóa khác trong cùng lớp.
- **FR-018**: Giao đề PHẢI đồng thời đúng phân công, môn và quyền dùng tài nguyên; quyền giao không tự gồm quyền sửa/chia sẻ tiếp.
- **FR-019**: Chủ nhiệm PHẢI được phân công theo lớp, được giao đề nhiều môn đã duyệt và được phép sử dụng, theo dõi hoàn thành; chỉ học sinh ghi danh môn tương ứng mới nhận bài.
- **FR-020**: Chủ nhiệm KHÔNG tự có quyền sửa đề, xem đáp án hoặc chấm mọi môn; các hành động đó PHẢI có nguồn quyền riêng.
- **FR-021**: Học sinh PHẢI chỉ nhận/nộp bài theo ghi danh học tập còn hiệu lực; chỉ xem kết quả đã công bố của mình theo ghi danh hiện hành hoặc quyền đọc lịch sử sau hoàn thành tại FR-055; không truy cập dữ liệu học sinh khác.

#### Phụ huynh

- **FR-022**: Phụ huynh PHẢI gửi được yêu cầu liên kết; chỉ chủ sở hữu/quản lý được xác nhận/gỡ liên kết trong trung tâm đó. Yêu cầu chưa duyệt không tạo quyền.
- **FR-023**: Quyền phụ huynh PHẢI gắn đúng tài khoản phụ huynh, học sinh, trung tâm; liên kết tại A KHÔNG tự cấp quyền tại B.
- **FR-024**: Phụ huynh PHẢI chỉ xem kết quả đã công bố của con còn liên kết hợp lệ; KHÔNG xem kết quả chưa công bố, học sinh khác, bài làm/đáp án chỉ dựa vào vai trò phụ huynh.

#### Gói và quyền cá nhân

- **FR-025**: Gói cá nhân đang hiệu lực PHẢI mở quyền kho soạn học liệu, soạn tài nguyên, tạo lớp/giao bài cá nhân theo gói; không cần xác minh nghề nghiệp và KHÔNG tự tạo phân công trung tâm.
- **FR-026**: Membership/phân công trung tâm KHÔNG mở kho soạn cá nhân; giáo viên PHẢI làm việc hợp lệ trong trung tâm theo gói trung tâm mà không cần mua thêm gói cá nhân.
- **FR-027**: Gói PHẢI xác định chủ thể hưởng, trạng thái, thời gian hiệu lực; xét đúng gói của không gian được yêu cầu, không mượn gói cá nhân để vượt hạn chế gói trung tâm hoặc ngược lại.
- **FR-028**: Kích hoạt/gia hạn thành công PHẢI có hiệu lực từ yêu cầu tiếp theo với nguồn quyền còn hợp lệ; không tự phục hồi quyền/quan hệ đã thu hồi.

#### Kho, sở hữu, phiên bản và chia sẻ

- **FR-029**: Hệ thống PHẢI phân biệt không gian tài nguyên cá nhân, từng trung tâm và hệ thống; phân biệt tài nguyên sở hữu với tài nguyên được cấp quyền dùng trong kho.
- **FR-030**: Tự soạn trong không gian cá nhân PHẢI thuộc cá nhân; tạo theo phân công trung tâm PHẢI thuộc trung tâm và ghi nhận người soạn. Tạo tài nguyên trung tâm cần phân công biên soạn phù hợp, không chỉ tư cách thành viên.
- **FR-031**: Lưu tài nguyên nguyên bản PHẢI giữ định danh và phiên bản nguồn trực tiếp hoặc qua truy vết bất biến của bản nhận có định danh cục bộ riêng theo AS-012; thêm vào kho KHÔNG đổi chủ sở hữu nguồn hoặc tự cấp quyền sửa/chia sẻ tiếp/dùng ngoài phạm vi nguồn. Định danh bản nhận KHÔNG được coi là căn cứ sở hữu độc lập để vượt license.
- **FR-032**: Chỉnh sửa tài nguyên lấy từ nguồn khác PHẢI có quyền sửa và tạo tài nguyên định danh riêng, truy vết nguồn/phiên bản và giữ giới hạn nguồn áp dụng.
- **FR-033**: Phiên bản đã lưu hoặc gắn bài đã giao PHẢI giữ ổn định; nguồn có phiên bản mới KHÔNG thay nội dung bản đã lưu/đề đã soạn/bài đã giao. Nhận cập nhật áp dụng lần dùng sau, không ghi đè lịch sử.
- **FR-034**: Hệ thống PHẢI thông báo đính chính do lỗi cho cá nhân/trung tâm đã nhận phiên bản bị ảnh hưởng; bên nhận chủ động nhận cập nhật khi còn quyền.
- **FR-035**: Người có thẩm quyền PHẢI chọn được chia sẻ có thể thu hồi hoặc sử dụng lâu dài và xác định bên nhận, phiên bản, hành động, phạm vi; KHÔNG được cấp tiếp rộng hơn quyền nguồn.
- **FR-036**: Tài nguyên cá nhân chia sẻ cho trung tâm PHẢI nhận được vào kho trung tâm; giáo viên khác chỉ lưu/dùng theo phân công và điều kiện nguồn, không mặc nhiên cấp cho mọi thành viên.
- **FR-037**: Chia sẻ lâu dài PHẢI cho trung tâm tiếp tục dùng phiên bản đã nhận khi người chia sẻ rời đi; việc rời hoặc hết hạn gói cá nhân của người chia sẻ không tự hủy chia sẻ hay đổi chủ sở hữu nguồn. Bản đầu KHÔNG cho người chia sẻ đơn phương thu hồi hình thức lâu dài.
- **FR-038**: Thu hồi chia sẻ có thể thu hồi PHẢI chặn hành động dựa vào quyền đó từ yêu cầu tiếp theo, kể cả qua bản lưu/bản chỉnh sửa; không xóa lịch sử bài làm/kết quả.
- **FR-039**: Lưu vào kho cá nhân PHẢI có quyền kho cá nhân phù hợp và tuân điều kiện nguồn; giáo viên chưa có gói cá nhân PHẢI dùng được tài nguyên hợp lệ trong trung tâm mà không cần mở kho cá nhân.
- **FR-040**: Quyền xem nội dung, xem đáp án, dùng giao bài, tạo bản chỉnh sửa, chia sẻ tiếp PHẢI xét riêng; không tự suy hành động này từ hành động khác. Quyền dạy không tự cấp quyền sửa tài nguyên người khác.
- **FR-041**: Hành động trên tài nguyên tổng hợp PHẢI thỏa quyền của các thành phần cần dùng; nội dung có nguồn giới hạn không được phân phối rộng hơn qua đề/tài nguyên tổng hợp.

#### Hết hạn và bảo lưu

- **FR-042**: Gói cá nhân hết hạn PHẢI bảo lưu kho, cho đọc tài nguyên còn quyền; PHẢI từ chối tạo/sửa học liệu, tạo lớp, giao bài cá nhân mới.
- **FR-043**: Gói trung tâm hết hạn PHẢI bảo lưu dữ liệu/quyền xem hiện hành, gồm kết quả công bố của con cho phụ huynh còn liên kết; PHẢI từ chối tạo lớp/khóa, thêm môn/ghi danh/phân công, tạo/sửa học liệu, giao bài mới.
- **FR-044**: Bài đã giao trước hết hạn PHẢI được nộp theo lịch cũ; người còn thẩm quyền được chấm, nhận xét, công bố. Ngoại lệ KHÔNG gồm gia hạn hạn nộp, mở lượt mới hoặc giao lại bài. Nếu không còn giáo viên có phân công hợp lệ, trung tâm phải gia hạn trước khi phân công người thay; không giữ quyền của người đã nghỉ hoặc cấp thêm quyền bằng ngoại lệ hoàn tất (NV-05).
- **FR-045**: Ngoại lệ hoàn tất bài PHẢI còn các nguồn quyền cần thiết; KHÔNG vượt thu hồi membership, phân công, ghi danh, liên kết hoặc quyền tài nguyên.
- **FR-046**: Trung tâm hết hạn gói PHẢI vẫn cho chủ sở hữu/quản lý gỡ thành viên, phân công, ghi danh, liên kết và quyền trong thẩm quyền; KHÔNG dùng quyền bảo vệ dữ liệu để cấp thêm quyền.
- **FR-047**: Bảo lưu PHẢI giữ nội dung/phiên bản và kết quả lịch sử; người bị gỡ quyền KHÔNG được đọc chỉ vì dữ liệu vẫn tồn tại. Xóa đối tượng không được làm mất lịch sử phụ thuộc.

#### Quyết định và kiểm chứng quyền

- **FR-048**: Mọi hành động cần quyền PHẢI xét danh tính đã xác thực, đúng không gian, đối tượng, hành động, nguồn quyền hiện hành; mặc định từ chối khi thiếu căn cứ hoặc không xác minh được quyền.
- **FR-049**: Gỡ membership, phân công, ghi danh, liên kết, quyền chia sẻ PHẢI có hiệu lực từ yêu cầu tiếp theo sau khi ghi nhận thành công, kể cả phiên/thông tin đăng nhập đã cấp trước.
- **FR-050**: Một hành động PHẢI có ít nhất một nguồn quyền hợp lệ và thỏa giới hạn bắt buộc của đối tượng; thu hồi một nguồn KHÔNG làm mất nguồn độc lập còn hợp lệ ở nơi khác.
- **FR-051**: KHÔNG tin thông tin người dùng tự khai về người thực hiện, vai trò, sở hữu, trung tâm, quan hệ phụ huynh để mở quyền; chọn không gian hoặc cung cấp mã đối tượng không thay việc xác minh.
- **FR-052**: Chỉ được ghi nhận hoàn tất cấp/thu hồi quyền khi thay đổi có hiệu lực; nếu chưa hoàn tất PHẢI báo chưa thành công và không cấp theo quyền mới chưa xác lập.
- **FR-053**: Bản đầu PHẢI kiểm chứng cho phép/từ chối bằng cùng cơ chế quyền dùng trong vận hành; KHÔNG có cơ chế cấp quyền riêng chỉ cho dữ liệu mẫu.
- **FR-054**: Bản đầu PHẢI có luồng quản trị sử dụng được cho tài khoản, tenant, gói thủ công, thành viên, lớp, khóa, môn, ghi danh, phân công, liên kết phụ huynh; học liệu/bài làm/kết quả PHẢI nghiệm thu ở mức dữ liệu và thao tác kiểm chứng tối thiểu.
- **FR-055**: Phạm vi hoàn thành một đợt học của bản đầu là **một học sinh trong một khóa** (không phải một ghi danh môn đơn lẻ). Thao tác hoàn thành PHẢI chuyển đồng bộ toàn bộ ghi danh ACTIVE của học sinh trong khóa sang COMPLETED; không cho hoàn thành một phần hoặc làm sống lại ghi danh đã thu hồi. Hoàn thành bình thường PHẢI được phân biệt với thu hồi truy cập. Sau hoàn thành, học sinh chỉ giữ quyền đọc kết quả đã công bố của chính mình trong đúng đợt học, trong thời gian hồ sơ còn được lưu hợp lệ theo chính sách dữ liệu, kể cả khi hết gói; không nhận/nộp bài mới hoặc nhận quyền ở đợt/lớp khác. Quyền lịch sử KHÔNG vượt khóa tài khoản hoặc thu hồi truy cập có chủ đích; dữ liệu vẫn được bảo lưu cho người còn thẩm quyền. Đây là yêu cầu bổ sung NV-01, chưa được coi là đã triển khai.
- **FR-056**: Trước khi duyệt liên kết phụ huynh, đơn vị vận hành PHẢI xác minh ngoài hệ thống người đại diện và căn cứ cho phép xem dữ liệu. Ứng dụng PHẢI yêu cầu tham chiếu xác nhận tối thiểu khi duyệt, lưu người xác nhận và thời điểm; chỉ có vai trò PARENT hoặc mã học sinh KHÔNG đủ. KHÔNG yêu cầu tải giấy tờ nhạy cảm. Khi cần ngừng chia sẻ do tranh chấp, dùng thu hồi hiện có; xác minh lại ngoài hệ thống trước khi cấp lại. Đây là yêu cầu bổ sung NV-02, chưa được coi là đã triển khai.
- **FR-057**: Khi giao bài, PHẢI phân biệt hoạt động cần bảo đảm hoàn tất với luyện tập có thể gián đoạn. Loại cần bảo đảm chỉ dùng nguồn sở hữu hoặc LONG_TERM trên toàn bộ thành phần cần dùng, với điều kiện/thời hạn nguồn bao phủ cả nộp, chấm và công bố. Vì mẫu hiện chỉ có hạn nộp `dueAt` mà không có hạn chấm/công bố, LONG_TERM có `expiresAt` hữu hạn không đủ điều kiện GUARANTEED (kể cả `expiresAt > dueAt`); chỉ nguồn sở hữu hoặc LONG_TERM không có mốc hết hạn được dùng đến khi có hợp đồng xác định toàn bộ thời hạn hoàn tất. Có nguồn REVOCABLE thì từ chối giao loại này. Luyện tập dùng REVOCABLE PHẢI báo rõ khi thu hồi làm gián đoạn nộp/chấm/công bố và giữ lịch sử đã có. Bảo đảm về nguồn học liệu KHÔNG vượt khóa tài khoản, thu hồi quan hệ học tập hoặc điều kiện lịch/lượt làm. Đây là yêu cầu bổ sung NV-04, chưa được coi là đã triển khai.

### Ma trận quyền cố định

Mọi quyền chịu giới hạn không gian, gói, đối tượng và quyền nguồn. Tên vai trò không là quyền toàn hệ thống.

| Chủ thể | Phạm vi | Quyền chính | Giới hạn |
| --- | --- | --- | --- |
| Quản trị viên Giáo Khoa | Nền tảng | Tạo tenant, chỉ định chủ sở hữu, kích hoạt/gia hạn gói, khôi phục danh tính không email | Không tự có quyền đọc mọi học liệu/kết quả |
| Chủ sở hữu | Một trung tâm | Quyền quản lý, bổ nhiệm/gỡ quản lý | Không mở quyền sang trung tâm khác/kho cá nhân |
| Quản lý | Trung tâm được giao | Thành viên vận hành, lớp, khóa, môn, ghi danh, phân công, liên kết | Không tự trở thành chủ sở hữu hoặc bổ nhiệm quản lý |
| Giáo viên | Nguồn quyền/phân công cụ thể | Soạn/dùng học liệu được phép, thực hiện giảng dạy | Không tạo lớp trung tâm hoặc tự mở kho cá nhân trả phí |
| Chủ nhiệm | Lớp được phân công | Giao đề nhiều môn đã duyệt/được dùng, theo dõi hoàn thành | Không tự có quyền sửa, xem đáp án, chấm mọi môn |
| Giáo viên bộ môn | Môn–khóa trong lớp cụ thể | Giao/chấm bài, quản lý kết quả | Đúng môn, người học và quyền học liệu |
| Học sinh | Môn–khóa đã ghi danh | Nhận/nộp bài, xem kết quả công bố của mình | Không xem dữ liệu người khác/môn chưa ghi danh |
| Phụ huynh | Con và trung tâm đã xác nhận | Xem kết quả đã công bố của con | Không xem bài làm/đáp án hoặc kết quả chưa công bố chỉ bằng vai trò phụ huynh |
| Người có gói cá nhân | Không gian cá nhân | Kho soạn học liệu, lớp/giao bài theo gói | Không cấp quyền trung tâm; tuân điều kiện tài nguyên nhận từ nguồn khác |

### Phạm vi bản đầu và giới hạn

**Vận hành thực tế**: Xác thực, chuyển không gian; quản trị tenant/gói thủ công/thành viên; bộ quyền cố định; lớp, khóa đa môn, ghi danh từng môn, nhiều giáo viên và chủ nhiệm; yêu cầu/duyệt/gỡ liên kết phụ huynh; cơ chế quyền và hiệu lực thu hồi.

**Dữ liệu mẫu để nghiệm thu**: Tài nguyên ba không gian, sở hữu/quyền dùng, hành động học liệu, phiên bản, nhận cập nhật/thông báo đính chính, hai hình thức chia sẻ, bài giao/bài nộp/kết quả, công bố và ngoại lệ hoàn tất khi hết hạn. Mẫu phải thể hiện đúng quy tắc, không cần nội dung sư phạm hoàn chỉnh.

**Chưa triển khai**: Công cụ soạn câu hỏi/đề/ma trận, làm bài/chấm bài/sổ điểm hoàn chỉnh; luồng duyệt học liệu nhiều bước; thanh toán tự động; trung tâm tự tạo tenant; vai trò tùy chỉnh; khóa gom nhiều lớp; giá/học phí chi tiết; quy trình bàn giao sở hữu riêng và ngoại lệ thu hồi chia sẻ lâu dài. Không đặt yêu cầu MFA hoặc ép hai bước theo vai trò; vận hành có thể cấu hình khi cần. Chủ nhiệm trong bản này là người điều phối lớp trung tâm theo FR-019–020, chưa là vai trò tổng hợp học bạ/rèn luyện của trường phổ thông (NV-03). Đính chính nguồn chưa bao gồm điều chỉnh kết quả đã công bố; cần đặc tả riêng trước chức năng chấm bài thật (NV-06).

### Ràng buộc tích hợp do sản phẩm yêu cầu

- Keycloak cung cấp đăng nhập tập trung/danh tính; Keycloak Authorization Services là nơi quyết định quyền. Giáo Khoa cung cấp dữ kiện nghiệp vụ cần thiết, cập nhật quan hệ quyền và thực thi quyết định trên mọi hành động được bảo vệ.
- Bộ vai trò/hành động/quy tắc dùng chung định nghĩa một lần; quyền theo tenant, tài nguyên, lớp, khóa, môn, ghi danh, quan hệ phụ huynh cập nhật theo vận hành.
- Phải kiểm chứng cách ly vai trò theo tenant, quyền theo đối tượng và thu hồi với phiên cũ trước khi nghiệm thu. Tổ chức nội bộ chính sách và cách đồng bộ thuộc bước lập kế hoạch.
- Đây là ràng buộc sản phẩm đã thống nhất; spec không quy định cấu trúc lưu trữ, giao diện lập trình, thư viện hoặc cách triển khai chính sách.

### Key Entities *(include if feature involves data)*

- **Danh tính người dùng**: Tài khoản chung, tên đăng nhập, email nếu có, xác minh, khôi phục; một người có nhiều nguồn quyền độc lập.
- **Không gian**: Cá nhân, một trung tâm hoặc hệ thống; phạm vi hoạt động và nơi quản lý tài nguyên.
- **Trung tâm**: Tenant có chủ sở hữu/gói riêng; chứa thành viên, lớp, kho, dữ liệu học tập cách ly.
- **Membership và vai trò**: Quan hệ danh tính–trung tâm, vai trò cố định, hiệu lực; không là nghề nghiệp toàn hệ thống.
- **Gói**: Chủ thể cá nhân/trung tâm, quyền lợi, trạng thái, thời gian hiệu lực, xác nhận kích hoạt/gia hạn thủ công.
- **Lớp**: Thuộc không gian quản lý, có học sinh và chủ nhiệm; lớp trung tâm chứa các khóa của chính lớp đó.
- **Khóa**: Thuộc một lớp, có nhiều môn; không mặc định gồm mọi học sinh của lớp.
- **Môn trong khóa**: Phạm vi phân công, ghi danh, giao bài, kết quả; độc lập với cùng môn ở khóa khác.
- **Ghi danh**: Học sinh–môn–khóa và hiệu lực; cần thuộc đúng lớp.
- **Phân công**: Chủ nhiệm theo lớp, giảng dạy theo môn–khóa, biên soạn theo phạm vi tài nguyên; nhiều phân công độc lập.
- **Liên kết phụ huynh–học sinh**: Trong một trung tâm, người đề nghị, người xác nhận, trạng thái chờ duyệt/hiệu lực/đã gỡ.
- **Tài nguyên và phiên bản**: Loại câu hỏi/đề/ma trận hoặc mẫu, môn, sở hữu, tác giả, định danh, nguồn/phiên bản, trạng thái được duyệt/sử dụng; bản lưu và lịch sử gắn đúng phiên bản.
- **Mục lưu trong kho**: Một không gian lưu tài nguyên/phiên bản để dùng; không đổi định danh/sở hữu nguồn.
- **Quyền chia sẻ**: Bên cấp/nhận, phiên bản, hành động, phạm vi, hình thức có thể thu hồi/lâu dài, hiệu lực; ràng buộc cả bản chỉnh sửa/phân phối tiếp.
- **Hoạt động học tập mẫu**: Bài giao cho học sinh ghi danh, môn–khóa, phiên bản học liệu, lịch giao/nộp và lượt làm đã xác lập; kiểm chứng quyền/ngoại lệ hết hạn.
- **Bài làm/kết quả mẫu**: Người học, hoạt động, nội dung/phiên bản, nhận xét, công bố; bảo lưu lịch sử, kiểm soát xem/chấm/công bố.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Hoàn thành đăng ký/xác minh email trong tối đa 5 phút thao tác, không tính chờ gửi thư; sau xác minh có đúng quyền nền, không có quyền soạn/quản trị chưa cấp.
- **SC-002**: Với tài khoản có sẵn, quản trị viên tạo trung tâm/chỉ định chủ sở hữu trong tối đa 5 phút thao tác; chủ sở hữu/quản lý cấu hình một lớp, khóa ba môn, hai giáo viên cùng môn và ghi danh chọn môn cho ba học sinh trong tối đa 15 phút.
- **SC-003**: 100% tình huống US1–US8 và các hàng ma trận quyền có bằng chứng nghiệm thu; không có hành động ngoài phạm vi được cấp phép trong bộ kiểm chứng.
- **SC-004**: Bộ kiểm chứng có ít nhất hai trung tâm, hai lớp trong một trung tâm, một khóa ba môn, hai giáo viên cùng môn, ba học sinh ghi danh khác nhau, hai phụ huynh; không có truy cập trái phép chéo trung tâm/lớp/môn/học sinh.
- **SC-005**: Trong 100% phép thử gỡ membership/phân công/ghi danh/liên kết/chia sẻ có thể thu hồi, yêu cầu tiếp theo sau xác nhận thành công bị từ chối nếu không còn nguồn quyền; phiên cũ không làm đổi kết quả, quyền độc lập còn hợp lệ vẫn hoạt động.
- **SC-006**: 100% phép thử phụ huynh chỉ trả kết quả công bố của đúng con/đúng trung tâm; yêu cầu chưa duyệt, kết quả chưa công bố và dữ liệu học sinh khác bị từ chối.
- **SC-007**: Với cả hai loại gói, 100% thao tác mở rộng hoạt động sau hết hạn bị từ chối; hoạt động mẫu đã giao được hoàn tất theo lịch cũ khi còn quyền, không mất lịch sử, không tự phục hồi quyền đã thu hồi sau gia hạn.
- **SC-008**: 100% phép thử lưu/cập nhật/chỉnh sửa/thu hồi tài nguyên mẫu giữ/tạo định danh và phiên bản đúng quy tắc, không thay lịch sử, không mở rộng quyền nguồn qua sao chép.
- **SC-009**: Ít nhất 4/5 người nghiệm thu, gồm người vận hành trung tâm và giáo viên/phụ huynh, hoàn thành đúng tác vụ lần đầu sau hướng dẫn ngắn, nhận biết được không gian và lý do không có quyền. Đây là mục tiêu nghiệm thu đề xuất, không phải số liệu đã đo.

### Quyết định phạm vi nghiệm thu 2026-11-12 — bỏ gate người dùng thật

Chủ repository đã xác nhận **không yêu cầu buổi nghiệm thu với người dùng thật**
trong đợt hoàn thành parent #2 này. SC-001, SC-002 (giới hạn thời gian thực tế)
và SC-009 (tỷ lệ 4/5) được giữ nguyên ở trên để truy vết yêu cầu ban đầu,
nhưng **được miễn áp dụng, không phải đã đạt** trong bản phát hành hiện tại.
Không ghi số liệu hoặc suy kết quả người dùng từ unit/E2E; flow nghiệp vụ, bảo mật,
đồng bộ quyền và ma trận acceptance tự động vẫn là các gate bắt buộc.
T096/#98 được kết thúc theo trạng thái *not planned / waived*, không phải completed.
Khi cần pilot/nghiệm thu người thật sau này sẽ mở đợt riêng và đo lại trên build thật.
Việc không yêu cầu chạy người dùng không có nghĩa bỏ trách nhiệm chốt chính sách lưu/xóa
và căn cứ xử lý dữ liệu học sinh trước khi vận hành thật (AS-013).

## Assumptions

- **AS-001 — Đăng nhập**: Ban đầu dùng tên đăng nhập hoặc email nếu có cùng mật khẩu; MFA/nhà cung cấp bổ sung do vận hành cấu hình, không là tính năng riêng của bản đầu. Tên đăng nhập phân biệt trong hệ thống danh tính chung.
- **AS-002 — Không gian cá nhân**: Danh tính luôn có hồ sơ/lịch sử riêng, kho soạn chỉ mở theo gói. Chủ lớp cá nhân thực hiện quyền tổ chức/giao bài theo gói; chưa bổ sung cơ chế mời đồng giáo viên riêng cho lớp cá nhân.
- **AS-003 — Vai trò**: Một người có nhiều vai trò/phân công có phạm vi riêng. Mỗi trung tâm có một chủ sở hữu ban đầu; chuyển sở hữu chưa thuộc bản đầu. Chủ sở hữu bổ nhiệm/gỡ quản lý; quản lý phụ trách các vai trò vận hành còn lại.
- **AS-004 — Lịch hoạt động**: Mẫu kiểm chứng ngoại lệ hết hạn có lịch giao, hạn nộp, lượt làm xác lập trước khi hết hạn. Chưa xây quy tắc chuyển đổi bài không có hạn; khi phát triển giao bài phải xác định riêng loại bài đó.
- **AS-005 — Quyền mẫu**: Mẫu có nguồn quyền tường minh cho biên soạn, duyệt, dùng, xem đáp án, chia sẻ. Trạng thái đã duyệt chuẩn bị sẵn để kiểm chứng chủ nhiệm; quy trình duyệt nhiều bước và hành động chuyên biệt từng loại câu hỏi thuộc tính năng sau.
- **AS-006 — Chia sẻ**: Nhận tài nguyên không chuyển sở hữu. Giới hạn nguồn áp dụng bản nguyên và chỉnh sửa trong Giáo Khoa; không hứa thu hồi bản sao ngoài hệ thống, không triển khai xuất/tải học liệu hoặc giải quyết tranh chấp giấy phép. Ngoại lệ thu hồi chia sẻ lâu dài chưa thuộc bản đầu.
- **AS-007 — Bảo lưu**: Thu hồi chỉ mất nguồn quyền bị tác động; lịch sử giữ cho người còn thẩm quyền. Không xóa tự động tài nguyên/hồ sơ khi gói hết hạn; thời hạn lưu trữ dài hạn và xóa danh tính theo yêu cầu cần chính sách dữ liệu riêng.
- **AS-008 — Nguồn hệ thống**: Quyền nền chỉ làm đề hệ thống được phát hành cho nhóm người dùng tương ứng; không mặc định xem mọi đáp án hoặc nội dung chưa phát hành. Lưu vào kho cá nhân cần gói; lưu vào kho trung tâm cần quyền trung tâm và điều kiện nguồn.
- **AS-009 — Bảo vệ quyền**: Khi gói trung tâm hết hạn, giữ quyền thu hồi và gửi yêu cầu hỗ trợ khôi phục danh tính, không cấp thêm membership/phân công/ghi danh. Khôi phục danh tính toàn hệ thống do Giáo Khoa xử lý độc lập gói trung tâm.
- **AS-010 — Chỉ tiêu nghiệm thu**: SC-001, SC-002, SC-009 là mục tiêu mặc định đề xuất về tính sử dụng được, không mở rộng nghiệp vụ hoặc cam kết quy mô tải chưa xác định.
- **AS-011 — Đợt học và chuyển lớp**: Một học sinh × một khóa trong đúng lớp là phạm vi hoàn thành đợt học của bản đầu. Nếu khóa có nhiều môn thì hoàn thành là thao tác nguyên tử trên mọi ghi danh ACTIVE của học sinh tại khóa, không chỉ môn đầu tiên. Đợt mới tạo khóa/phạm vi mới; chuyển lớp không đổi lớp của khóa cũ hoặc mang theo nguồn quyền học tập cũ. Hoàn thành và thu hồi là hai thao tác khác nhau; không dùng REVOKED để ghi nhận tốt nghiệp bình thường. Quyền lịch sử của FR-055 chỉ cho chính học sinh; không tự tạo liên kết phụ huynh ở personal workspace.
- **AS-012 — Bản nhận từ nguồn chia sẻ**: Theo quyết định đã có ở issue #261 và Sổ B101, chia sẻ giữa các workspace cá nhân/trung tâm tạo bản nhận có định danh cục bộ, giữ truy vết và điều kiện nguồn; lưu từ nguồn hệ thống giữ định danh nguồn. Đây là cách lưu bản nhận, không chuyển sở hữu nguồn và không mở quyền sửa/chia sẻ rộng hơn.
- **AS-013 — Dữ liệu học sinh và loại hình hoạt động**: Trước khi dùng dữ liệu học sinh thật, đơn vị vận hành phải chốt chính sách lưu trữ/xóa, căn cứ xử lý và xác minh người đại diện ngoài hệ thống; tiền đề này áp dụng cả center và personal workspace có học sinh trẻ em. Loại hình lớp và điều kiện tổ chức do đơn vị vận hành xác nhận, mua gói phần mềm không thay xác nhận đó (NV-02/NV-07). Bản này không xây hệ thống thẩm định pháp lý, quản lý giấy tờ hoặc sổ điểm chính thức; chưa xác nhận điều kiện vận hành thực tế đã đạt.
- **DEP-001 — Danh tính và quyền**: Cần Keycloak và Authorization Services, tên miền đăng nhập, kênh email xác minh/khôi phục. Lập kế hoạch phải xác định cấu hình và kiểm chứng khả thi; không thay yêu cầu thu hồi từ thao tác tiếp theo bằng việc chờ hết phiên.
- **DEP-002 — Vận hành**: Cần người có thẩm quyền xác nhận thanh toán, tạo trung tâm, xác minh khôi phục danh tính không email; không cần thanh toán tự động.
- **DEP-003 — Quản trị dự án**: Tài liệu nguyên tắc dự án hiện chưa được điền; spec không suy diễn nguyên tắc từ chỗ trống hoặc sửa tài liệu quản trị đó.
