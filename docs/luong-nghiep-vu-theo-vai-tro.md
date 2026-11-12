# Luồng nghiệp vụ theo vai trò và hợp đồng nghiệm thu

Ngày đối chiếu: 12/11/2026, code nền `f3d2e618be74f574c03d8301a04d9a0427bdbc98`. Phạm vi và quyền gốc ở [spec](../specs/001-multitenant-access/spec.md); các lệch còn lại ở [báo cáo hội tụ](doi-chieu-spec-code-20261112.md).

Đây là bản đồ để viết hướng dẫn sử dụng và khóa hành vi bằng E2E. Mỗi mã luồng giữ nguyên khi tách thành hướng dẫn riêng. Cột “có test” chỉ chỉ ra ca đang tồn tại; kết luận đạt phải có kết quả chạy tại đúng revision và đúng phạm vi.

## Ai làm việc gì

| Vai trò trong không gian đang chọn | Công việc | Giới hạn cần nói rõ trong hướng dẫn |
| --- | --- | --- |
| Quản trị viên Giáo Khoa | Tạo trung tâm, chỉ định chủ sở hữu, xác nhận gói, hỗ trợ khôi phục danh tính | Chức vụ nền tảng không tự cho đọc học liệu, bài làm hoặc kết quả |
| Chủ sở hữu trung tâm | Mọi thao tác của quản lý; bổ nhiệm/gỡ quản lý | Không chuyển quyền sang trung tâm khác hoặc kho cá nhân |
| Quản lý trung tâm | Thành viên, lớp/khóa/môn, ghi danh, phân công, xác nhận/gỡ liên kết phụ huynh | Không tự làm chủ sở hữu, không bổ nhiệm quản lý khác; không tự có quyền chấm mọi môn |
| Giáo viên bộ môn | Soạn/dùng học liệu được cấp, giao bài đúng môn, chấm/công bố đúng phân công | Không tạo lớp trung tâm, tự phân công hoặc xem dữ liệu môn/lớp khác |
| Chủ nhiệm | Điều phối đúng lớp, giao đề các môn đã duyệt và được phép dùng, theo dõi hoàn thành | Không tự được sửa đề, xem đáp án, chấm/công bố mọi môn hoặc duyệt phụ huynh |
| Học sinh | Nhận/nộp bài môn đã ghi danh, xem kết quả công bố của mình | Không xem bạn khác; sau hoàn thành chỉ giữ lịch sử được phép, không nộp bài mới |
| Phụ huynh | Đề nghị liên kết, xem kết quả công bố của đúng con sau xác nhận | Có vai trò PARENT chưa đủ; không có bài làm/đáp án, kết quả nháp hoặc quyền ở trung tâm khác |
| Chủ không gian cá nhân có gói | Tổ chức lớp và kho của mình theo gói | Gói cá nhân không mở quyền quản trị trung tâm; giáo viên trung tâm không cần mua gói này để làm việc ở trung tâm |
| Tài khoản đã xác minh chưa có phân công/gói | Đăng nhập, chọn không gian hợp lệ, luyện tập đề hệ thống được phát hành | Không tự thành giáo viên/học sinh toàn nền tảng hoặc có kho soạn trả phí |

Một tài khoản có thể mang nhiều vai trò nhưng từng hướng dẫn phải chỉ rõ không gian và vai trò đang thực hiện. “Giáo viên”, “chủ nhiệm”, “chủ lớp cá nhân” là ba nguồn quyền khác nhau; không dùng một tài khoản kiêm tất cả vai trò làm đối chứng cho quyền tối thiểu.

## Thứ tự một trung tâm bắt đầu sử dụng

1. Quản trị viên tạo trung tâm, chỉ định chủ sở hữu và xác nhận gói theo quy trình vận hành.
2. Chủ sở hữu bổ nhiệm quản lý nếu cần. Quản lý mời tài khoản có sẵn hoặc cấp tài khoản học sinh mới; tài khoản không email phải đổi mật khẩu tạm lần đầu.
3. Chủ sở hữu/quản lý tạo lớp, khóa và các môn; thêm học sinh vào lớp rồi ghi danh từng môn; phân công giáo viên bộ môn và chủ nhiệm riêng.
4. Giáo viên được phân công chuẩn bị học liệu có quyền sử dụng, giao bài đúng môn. Học sinh làm/nộp; giáo viên có phân công chấm/công bố. Bản đầu chỉ có thao tác mẫu để kiểm chứng quyền, chưa có công cụ làm/chấm bài hoàn chỉnh.
5. Phụ huynh được mời vào trung tâm rồi đề nghị liên kết. Quản lý xác minh ngoài hệ thống, lưu tham chiếu xác nhận và duyệt; phụ huynh chỉ thấy kết quả đã công bố của con.
6. Kết thúc đợt học của từng học sinh trong một khóa dùng thao tác hoàn thành toàn bộ các môn cùng lúc; ngừng truy cập có chủ đích dùng thu hồi. Khi hết gói, vẫn đọc/hoàn tất bài cũ nếu còn quyền và đúng lịch, vẫn thu hồi được; muốn thay giáo viên phải gia hạn trước.

Các phần bổ sung ở bước 5–6 chưa được triển khai đầy đủ; không dùng mô tả đích này như hướng dẫn tính năng đã phát hành.

## Danh mục luồng để viết hướng dẫn

| Mã | Người thực hiện chính | Điều kiện bắt đầu và các bước | Kết quả, nhánh từ chối bắt buộc | Căn cứ |
| --- | --- | --- | --- | --- |
| TK-01 | Mọi tài khoản | Đăng ký/đăng nhập; tự đăng ký thì xác minh email, tài khoản được cấp thì đổi mật khẩu tạm | Đúng danh tính, không lộ token; chưa xác minh không dùng nghiệp vụ | US1.1/2/4/9; FR-001–004 |
| TK-02 | Mọi tài khoản | Chọn không gian mình tham gia; làm việc; đăng xuất hoặc quay lại trình duyệt | Quyền theo đúng nơi đang chọn; cookie cũ không hồi sinh phiên đã logout | US1.3/8; FR-006/012 |
| QT-01 | Quản trị viên Giáo Khoa | Chọn chủ sở hữu đã có tài khoản; tạo trung tâm; đợi báo hoàn tất | Chủ sở hữu quản trị đúng trung tâm; tài khoản thường không tự tạo tenant | US2.1/2; FR-007 |
| QT-02 | Quản trị viên Giáo Khoa | Xác nhận thanh toán ngoài hệ thống; chọn workspace và kỳ gói; xác nhận; đợi hoàn tất | Gói đúng nơi hiệu lực; người dùng không tự kích hoạt; gia hạn không hồi sinh quyền đã thu hồi | US6.2; US8.6/7; FR-027/028 |
| QT-03 | Quản trị viên Giáo Khoa | Tiếp nhận yêu cầu không email; xác minh; thực hiện khôi phục; xác nhận đã bàn giao | Chưa bàn giao không báo xong; quản lý trung tâm không đổi mật khẩu danh tính dùng chung | US1.6/7; FR-005 |
| CSH-01 | Chủ sở hữu | Chọn thành viên đúng trung tâm; bổ nhiệm/gỡ quản lý | Quản lý mới có quyền tại đúng nơi; quản lý thường không thực hiện được | US2.3/4; FR-009 |
| QL-01 | Chủ sở hữu/quản lý | Mời tài khoản có sẵn hoặc cấp học sinh mới; đặt vai trò vận hành; đợi hoàn tất | Không chiếm username cũ; mật khẩu tạm chỉ bàn giao lần tạo đầu; quản lý không cấp OWNER/MANAGER | US1.4/5; US2.4; FR-004/010 |
| QL-02 | Chủ sở hữu/quản lý | Tạo lớp; tạo khóa thuộc lớp với các môn; thêm môn khi cần | Khóa có ít nhất một môn khi tạo; thêm môn không tự ghi danh/phân công | US3.1/8; FR-013/015 |
| QL-03 | Chủ sở hữu/quản lý | Thêm học sinh vào lớp; chọn môn ghi danh; phân công từng giáo viên/môn và chủ nhiệm/lớp | Không gắn chéo lớp/trung tâm; nhiều giáo viên cùng môn hợp lệ; giáo viên không tự phân công | US3.2–7; FR-014–020 |
| QL-04 | Chủ sở hữu/quản lý | Xem yêu cầu liên kết; xác minh người đại diện/căn cứ ngoài hệ thống; ghi tham chiếu; duyệt/từ chối/gỡ | Thiếu xác minh không duyệt; chủ nhiệm không duyệt; gỡ vẫn được khi hết gói | US4.1/2/4/5/6; FR-022–024/056 |
| QL-05 | Chủ sở hữu/quản lý | Gỡ đúng membership, vai trò, ghi danh, phân công hoặc liên kết; đợi hoàn tất | Người bị gỡ bị chặn ở yêu cầu kế tiếp bằng phiên cũ; nguồn độc lập tại B còn nguyên | US5; FR-049/050/052 |
| QL-06 | Chủ sở hữu/quản lý hoặc chủ lớp cá nhân | Chọn học sinh và khóa có các môn đang ghi danh; hoàn thành tất cả ghi danh ACTIVE của học sinh trong khóa cùng lúc; đợt mới có phạm vi mới | Không dùng thu hồi thay hoàn thành; mất quyền nhận/nộp mới nhưng giữ lịch sử; chuyển lớp không mang quyền học tập cũ | US3.9; FR-055; **chưa có implementation** |
| GV-01 | Giáo viên có phân công biên soạn/dùng nguồn | Chọn đúng kho; soạn/lưu/xem nội dung hoặc đáp án; fork/chia sẻ theo từng quyền; nơi nhận do owner/manager chấp nhận | Dùng không tự gồm sửa/đáp án/chia sẻ; giữ giới hạn nguồn, bản nhận và phiên bản; cấp gói cá nhân không bắt buộc khi làm cho trung tâm | US6.3; US7.1–9; FR-029–041 |
| GV-02 | Giáo viên đúng môn | Chọn khóa–môn được phân công; chọn đề đúng môn và được dùng; đặt lịch/lượt và loại bảo đảm; giao | Sai môn/thiếu quyền nguồn bị chặn; GUARANTEED không dùng REVOCABLE hay LONG_TERM có hạn hết trước khi kết thúc chấm/công bố (bản mẫu chưa có hạn đó nên yêu cầu LONG_TERM không hết hạn) kể cả thành phần | US3.4/5; US7.10; FR-018/057 |
| GV-03 | Giáo viên đúng môn | Mở bài đã nộp; chấm/nhận xét; công bố | Chỉ phân công còn hiệu lực; kết quả nháp chưa tới phụ huynh; hết gói vẫn hoàn tất đúng điều kiện; nghỉ dạy mất quyền ngay | US3.5; US8.4/8; FR-017/044/045 |
| CN-01 | Chủ nhiệm đúng lớp | Xem lớp/tiến độ; giao đề đã duyệt của môn mà học sinh đã ghi danh | Không tự chấm/xem đáp án; không duyệt phụ huynh hoặc đọc lớp khác | US3.6/7; FR-019/020; NV-03 |
| HS-01 | Người học đã đủ điều kiện danh tính | Chọn đề hệ thống được phát hành; luyện tập; xem lịch sử cá nhân | Không cần gói soạn hoặc vai trò học sinh toàn cục; không mở kho soạn | US1.2; US6.1; FR-003 |
| HS-02 | Học sinh có ghi danh đúng môn | Nhận bài; nộp theo hạn/lượt; đọc kết quả của mình sau công bố | Không nộp quá hạn/thêm lượt; không xem bạn khác; revoke enrollment/license chặn thao tác cần nguồn | US3.2; US5.3; US8.2/4; FR-021/044/045 |
| HS-03 | Học sinh đã hoàn thành | Mở lịch sử đợt cũ; đọc kết quả công bố của mình | Còn đọc khi hồ sơ lưu hợp lệ dù hết gói; không nhận/nộp mới; khóa/thu hồi bảo mật vẫn chặn | US3.9; FR-055; **chưa có implementation** |
| PH-01 | Phụ huynh đã là thành viên có vai trò PARENT | Chọn con trong phạm vi; gửi yêu cầu liên kết; chờ quản lý xác nhận | REQUESTED chưa cấp quyền; không khai parent khác hoặc chỉ dùng mã học sinh để mở quyền | US4.1/2/6; FR-022/056 |
| PH-02 | Phụ huynh có liên kết đã duyệt | Mở kết quả của con tại đúng trung tâm; đọc các kết quả công bố | Con khác/trung tâm khác/nháp/bài làm/đáp án đều bị chặn; gỡ liên kết chặn cả phiên cũ và Back | US4.2/3/5; FR-023/024 |
| CNH-01 | Chủ không gian cá nhân có gói | Mở lớp/kho cá nhân; tổ chức lớp, môn, học sinh; dùng học liệu được cấp | Gói personal không mở quyền center; rời center không mất personal; hết gói chặn tạo mới, giữ đọc/thu hồi và completion hợp lệ | US6.1–5; US8.1/2/6; FR-025–028/042 |

## Khóa E2E theo vai trò và luồng

Mỗi test có `@flow-<mã>` và `@role-<vai>` theo người thực hiện hành vi được khẳng định, không theo tài khoản chỉ dùng để dựng tiền đề. Một hành trình có bàn giao giữa vai trò mang nhiều tag vai; vẫn giữ phiên tách biệt và kiểm tra danh tính trước mỗi phần việc. Tài khoản chỉ có đúng role/phân công tối thiểu; ca nhiều vai là phép thử cộng nguồn riêng.

Các tầng trong Playwright được gắn rõ:

- `@layer-e2e`: ứng dụng thật, HTTP và Keycloak thật; có thể dùng API để dựng tiền đề, nhưng thao tác được nghiệm thu đi qua UI/HTTP thật đúng tầng. Một ca chỉ gọi API chưa chứng minh thao tác UI tương ứng đã có.
- `@layer-ui`: trang/component thật với API giả hoặc trang tĩnh; khóa trình bày, retry và trạng thái, không chứng minh quyền thật.
- `@layer-protocol`: Authorization Code/PKCE hoặc Mailpit; không thay hành trình người dùng trong ứng dụng.
- `@layer-fixture`: chứng minh bộ dựng dữ liệu test, không tính là người dùng hoàn tất nghiệp vụ.

Theo [API tag của Playwright](https://playwright.dev/docs/test-annotations#tag-tests), có thể lọc các ca đã gắn tag mà không đổi cách chia project hay bỏ test khỏi CI:

```bash
cd frontend
npx playwright test --grep '(?=.*@layer-e2e)(?=.*@role-parent)' --list
npx playwright test --grep '(?=.*@layer-e2e)(?=.*@flow-QL-04)'
npx playwright test --grep '(?=.*@layer-e2e)(?=.*@role-subject-teacher)'
```

Lệnh có `--list` chỉ liệt kê, không phải kết quả pass. Các lượt chạy thật cần stack TEST và biến môi trường theo README. Giữ một lượt nặng tại một thời điểm; không tạo lại các project theo từng vai làm nhân số lần chạy cùng test.

| Luồng | E2E hiện có | Phần chưa được khóa đủ |
| --- | --- | --- |
| TK-01/02 | `identity.spec.ts`, `session-security.spec.ts`, `native-gate.spec.ts` | Hướng dẫn phải tách tài khoản tự đăng ký khỏi tài khoản được cấp, không gọi identity-first là MFA |
| QT-01/02, CSH-01, QL-01 | `center-administration.spec.ts`; nhánh gói personal ở `personal-entitlements.spec.ts` | QT-03 hiện chỉ có `recovery-ui.spec.ts` dùng API giả ở browser; cần hành trình operator/người yêu cầu thật, backend HTTP không thay browser |
| QL-02/03, CN-01 | `teaching-administration.spec.ts` | CN-01 mới khóa roster/không tự phân công; còn thiếu giao đề đã duyệt, theo dõi hoàn thành và phủ định chấm/đáp án qua flow chính của chủ nhiệm |
| QL-04, PH-01/02 | `guardian-results.spec.ts` | Thiếu tham chiếu xác minh FR-056; phải bổ sung ca reject/approve thiếu tham chiếu, sai vai và giữ phiên phụ huynh sau revoke |
| QL-05 | `revocation.spec.ts`, guardian unlink, resource revoke | Một số ca đang quan sát roster thay hành động chấm; phải khóa hành động thực tế tại GV-03/HS-02, không suy quyền chấm chỉ vì đọc roster đổi |
| GV-01/02 | `resource-permissions.spec.ts`, `personal-entitlements.spec.ts` | Thiếu người nhận mở thông báo đính chính rồi chủ động nhận cập nhật; chưa có GUARANTEED/INTERRUPTIBLE |
| GV-03, HS-02 | `subscription-expiry.spec.ts` có nộp/chấm/công bố bằng các actor thật | Thiếu bộ flow tối thiểu độc lập trạng thái gói: trước/đúng/sau hạn nộp, license revoke giữa làm và sau nộp/trước chấm; giữ hai học sinh, hai môn và teacher sai môn làm đối chứng |
| HS-01, CNH-01 | `personal-entitlements.spec.ts` | UI chỉ ở mức quản trị/mẫu; không diễn đạt thành công cụ soạn/làm/chấm bài hoàn chỉnh |
| QL-06, HS-03 | Chưa có | Test-first từ FR-055; sau hoàn thành đọc lịch sử, chặn bài mới, chuyển lớp và revoke bảo mật; kiểm cả center/personal |

Mỗi flow chỉ hoàn tất khi có đối chứng được phép và bị chặn, trạng thái dữ liệu sau thao tác, điều kiện pending/success, đúng actor/workspace, và trường hợp revoke/expiry liên quan. Giữ assertion riêng của transaction, HTTP/session và browser; không chép toàn bộ ma trận vào mọi tầng.

## Cách tách thành hướng dẫn sử dụng sau này

Mỗi vai có một trang mục lục dẫn tới các mã luồng mình thực hiện. Mỗi hướng dẫn dùng cùng mẫu: mục đích → điều kiện bắt đầu → thao tác trên màn hình → dấu hiệu hoàn tất → lý do thường bị chặn → bước xử lý tiếp theo. Tránh đưa scope, partition, SQL, policy hoặc thao tác fixture vào hướng dẫn người dùng. Chỉ viết hướng dẫn tính năng đã có UI; luồng đang ở mức mẫu hoặc chưa triển khai phải ghi rõ trạng thái đó.

NV-03 giữ chủ nhiệm ở phạm vi điều phối trung tâm; NV-06 điều chỉnh điểm sau công bố là thiết kế sau; NV-07 cần chốt loại hình hoạt động và trách nhiệm vận hành trước pilot. Những nội dung này không tự biến thành tính năng sổ điểm, xác minh giấy tờ hoặc module pháp lý.
