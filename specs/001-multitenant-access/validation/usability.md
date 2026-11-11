# Nghiệm thu SC-001/002/009 với người dùng thật theo vai trò (issue #98)

Tài liệu này là **bộ chuẩn bị** cho buổi nghiệm thu với người dùng thật. Tại thời điểm tạo,
chưa có buổi chạy nào diễn ra: mọi ô số liệu trong §7–§9 đang **trống** và chỉ được điền từ
kết quả quan sát thật. Không kết quả nào trong file này được suy ra từ unit test, integration
test hoặc tự đánh giá thay cho người dùng thật.

## 0. Trạng thái và điều kiện kết luận

- Trạng thái: **CHUẨN BỊ — sẵn sàng chạy**, chưa có số liệu.
- Nguồn yêu cầu: `spec.md` mục Success Criteria — SC-001, SC-002, SC-009; AS-010 ghi rõ đây
  là chỉ tiêu nghiệm thu đề xuất về tính sử dụng được, **không phải số liệu đã đo**.
- Issue #98 chỉ được kết luận khi: buổi chạy thật hoàn tất theo đúng §1–§2, số liệu điền vào
  §7–§8, và §9 được người điều phối điền căn cứ trên các số liệu đó.

## 1. Điều kiện môi trường để buổi chạy được tính

Buổi chạy được tính là buổi nghiệm thu chính thức khi đủ mọi điều kiện sau:

1. **Bề mặt**: bản deploy Dokploy công khai — ứng dụng `https://giaokhoa.fuckr.net`, đăng
   nhập tập trung `https://auth.fuckr.net`, hai tenant kiểm chứng
   `https://trung-tam-1.fuckr.net` (Trung tâm 1, chứa dữ kiện phụ huynh ở §6) và
   `https://trung-tam-2.fuckr.net` (dự phòng). Ghi SHA của bản deploy vào phiếu §7.
2. **Thư xác minh hoạt động thật**: realm đã cấu hình gửi thư và điều phối đã gửi thử thành
   công trước buổi chạy (NV-1 không thể chạy nếu thư xác minh không tới hộp thư thật).
3. **Đóng băng trong buổi**: không đổi code, cấu hình realm hoặc dữ kiện chuẩn bị giữa buổi
   chạy. Nếu buộc phải đổi, buổi chạy dừng tính và chạy lại từ đầu trên bản mới.
4. **Người tham gia thật**: 5 người dùng thật, không tham gia triển khai tính năng này; mỗi
   người đúng một vai trò ở §3 và lần đầu thao tác các nhiệm vụ được giao.
5. **Chuẩn bị xong**: toàn bộ tài khoản và dữ kiện ở §6 đã sẵn sàng và được kiểm tra đăng
   nhập bằng tài khoản dự phòng (không dùng tài khoản của người tham gia để chạy thử, để
   giữ nguyên trạng thái "lần đầu").

Chạy trên stack local chỉ dùng để **diễn tập** quy trình điều phối, không tính là buổi
nghiệm thu chính thức.

## 2. Quy tắc đo (áp dụng cho mọi nhiệm vụ)

- **Hướng dẫn ngắn**: điều phối chỉ đọc §4 (khoảng 2 phút) và phát thẻ nhiệm vụ ở §5. Không
  hướng dẫn thao tác, không chỉ vị trí nút/màn hình, không gợi ý cách làm.
- **Bấm giờ**: người quan sát dùng đồng hồ bấm giờ; mốc bắt đầu/kết thúc theo từng nhiệm vụ
  ở §5. Riêng NV-1, **tạm dừng đồng hồ trong lúc chờ thư xác minh tới hộp thư** (SC-001
  không tính thời gian chờ gửi thư); thời gian chờ thư thực tế vẫn ghi riêng vào cột ghi chú.
- **Hoàn thành lần đầu**: đạt mọi tiêu chí hoàn thành quan sát được của nhiệm vụ trong một
  lượt chạy liên tục, không nhận gợi ý hay trợ giúp từ điều phối, không phải bỏ làm lại từ
  đầu. Người tham gia tự nhận ra và tự sửa sai giữa chừng vẫn tính hoàn thành lần đầu.
- **Hỗ trợ = mất lần đầu**: mỗi lần điều phối phải nhắc, chỉ cách hoặc làm hộ, người quan sát
  ghi một lần hỗ trợ; nhiệm vụ đó không còn là hoàn thành lần đầu nhưng vẫn tiếp tục để ghi
  đủ thời gian và vướng mắc.
- **Giới hạn kẹt**: nếu người tham gia kẹt quá 10 phút ở một bước (NV-3: 20 phút) hoặc chủ
  động yêu cầu giúp, điều phối hỗ trợ để buổi chạy tiếp tục, đồng thời đánh dấu nhiệm vụ là
  không hoàn thành lần đầu và ghi nguyên văn vướng mắc.
- **Hai câu hỏi chốt SC-009** sau mỗi nhiệm vụ: (1) "Bạn đang thao tác trong không
  gian/trung tâm nào?" (2) với thao tác thăm dò bị từ chối: "Hệ thống báo gì, và bạn hiểu vì
  sao bị từ chối không?" Người quan sát ghi câu trả lời **nguyên văn**.
- Thứ tự chạy: NV-1 → NV-2 → NV-3 → NV-4 → NV-5 (NV-3 phụ thuộc trung tâm tạo ở NV-2; NV-4
  phụ thuộc phân công tạo ở NV-3).

## 3. Người tham gia và vai trò

| Mã | Vai trò buổi chạy | Nhiệm vụ | Tiêu chí gắn |
|---|---|---|---|
| NT-1 | Người dùng mới, chưa có tài khoản | NV-1 | SC-001 |
| NT-2 | Quản trị viên Giáo Khoa (nền tảng) | NV-2 | SC-002 (phần 1) |
| NT-3 | Người vận hành trung tâm — được chỉ định chủ sở hữu ở NV-2 | NV-3 | SC-002 (phần 2) |
| NT-4 | Giáo viên | NV-4 | SC-009 |
| NT-5 | Phụ huynh của học sinh HS-1 | NV-5 | SC-009 |

SC-009 yêu cầu nhóm nghiệm thu gồm người vận hành trung tâm và giáo viên/phụ huynh — NT-3,
NT-4, NT-5 phủ đúng yêu cầu này. Ngoài 5 người tham gia còn có tài khoản phụ (giáo viên thứ
hai, học sinh) chỉ dùng làm dữ kiện, liệt kê ở §6, không tính vào mẫu số 5.

## 4. Hướng dẫn ngắn (điều phối đọc nguyên văn, khoảng 2 phút)

> "Cảm ơn bạn đã tham gia buổi kiểm tra sử dụng hệ thống Giáo Khoa — hệ thống quản lý học
> tập cho các trung tâm. Mỗi người sẽ nhận một thẻ ghi một việc cần làm, giống việc thật
> hằng ngày ở trung tâm. Bạn cứ thao tác theo cách bạn cho là đúng; không có yêu cầu về
> cách bấm hay thứ tự màn hình. Nếu hệ thống từ chối một thao tác, hãy đọc kỹ thông báo
> trên màn hình — đó cũng là một phần của buổi kiểm tra. Buổi này kiểm tra hệ thống, không
> kiểm tra bạn: vướng ở đâu cứ nói thành tiếng để người ghi chép nắm được. Thời gian làm
> từng việc sẽ được ghi lại. Khi làm xong việc trên thẻ, hãy báo cho người điều phối."

Sau phần chung, điều phối phát tài khoản đăng nhập theo §6 (riêng NT-1 tự đăng ký bằng
email của mình) và thẻ nhiệm vụ tương ứng ở §5. Không giải thích thêm về chức năng.

## 5. Kịch bản nhiệm vụ theo vai trò

### NV-1 — Đăng ký, xác minh email, đăng nhập lần đầu (NT-1 · SC-001)

**Thẻ nhiệm vụ**: "Bạn mới biết đến Giáo Khoa. Hãy tạo tài khoản bằng email của bạn, xác
minh email, đăng nhập, và thử xem tài khoản mới của bạn làm được gì trên hệ thống."

- **Trần thời gian**: 5 phút thao tác, không tính thời gian chờ thư xác minh (xem §2).
- **Bấm giờ**: bắt đầu khi người tham gia mở trang đăng ký; tạm dừng khi đã gửi yêu cầu và
  đang chờ thư; chạy tiếp khi thư tới hộp thư; kết thúc khi hoàn tất tiêu chí cuối.
- **Tiêu chí hoàn thành quan sát được**:
  1. Gửi thành công biểu mẫu đăng ký bằng email thật của người tham gia.
  2. Mở liên kết xác minh từ hộp thư thật và xác minh thành công.
  3. Đăng nhập được và thấy không gian cá nhân của mình.
  4. Mở được chức năng làm đề hệ thống mà tài khoản mới được phép (quyền nền).
  5. Thăm dò bị từ chối: thử mở khu vực soạn học liệu/kho soạn và khu vực quản trị trung
     tâm → không có quyền/không thực hiện được; người tham gia nói được lý do theo thông
     báo của hệ thống (chưa được cấp quyền đó).

### NV-2 — Tạo trung tâm và chỉ định chủ sở hữu (NT-2 · SC-002 phần 1)

**Thẻ nhiệm vụ**: "Một trung tâm mới đã xác nhận mua gói. Hãy tạo trung tâm này trên hệ
thống và chỉ định người vận hành (tài khoản được ghi trên thẻ) làm chủ sở hữu."

- **Trần thời gian**: 5 phút thao tác.
- **Bấm giờ**: bắt đầu khi mở khu vực nền tảng (`/platform`); kết thúc khi trung tâm xuất
  hiện trong danh sách với chủ sở hữu đúng NT-3.
- **Tiêu chí hoàn thành quan sát được**:
  1. Tạo trung tâm với tên do điều phối ghi sẵn trên thẻ; trung tâm có không gian riêng.
  2. Chỉ định đúng tài khoản NT-3 làm chủ sở hữu.
  3. Kiểm chứng chéo: NT-3 đăng nhập và thấy trung tâm trong danh sách không gian của mình.
  4. Thăm dò: thử tìm chức năng tạo vai trò tùy chỉnh cho trung tâm → hệ thống dùng bộ vai
     trò cố định, không có chức năng này; người tham gia nhận biết được điều đó.

### NV-3 — Cấu hình lớp, khóa ba môn, phân công và ghi danh (NT-3 · SC-002 phần 2)

**Thẻ nhiệm vụ**: "Trung tâm của bạn vừa được tạo. Hãy: tạo một lớp; tạo một khóa thuộc
lớp đó gồm ba môn (tên ghi trên thẻ); thêm hai giáo viên (tên đăng nhập ghi trên thẻ) và
phân công cả hai cùng dạy môn thứ nhất; thêm ba học sinh (tên đăng nhập ghi trên thẻ) vào
lớp; ghi danh từng học sinh vào các môn theo bảng phân công trên thẻ."

Bảng phân công ghi danh trên thẻ (điều phối giữ nguyên mẫu này): HS-1 học môn 1 + môn 2;
HS-2 chỉ học môn 1; HS-3 học cả ba môn.

- **Trần thời gian**: 15 phút thao tác.
- **Bấm giờ**: bắt đầu khi người tham gia mở không gian trung tâm mới; kết thúc khi kiểm
  chứng cuối (tiêu chí 5) khớp.
- **Tiêu chí hoàn thành quan sát được**:
  1. Lớp được tạo thuộc đúng trung tâm mới (không nhầm sang không gian cá nhân).
  2. Khóa thuộc lớp đó và chứa đúng ba môn đã ghi trên thẻ.
  3. Hai giáo viên là thành viên trung tâm và **cả hai cùng được phân công môn thứ nhất**.
  4. Ba học sinh có trong danh sách lớp.
  5. Ghi danh khớp bảng phân công: mở khóa thấy HS-1 ở môn 1 + 2, HS-2 chỉ ở môn 1, HS-3 ở
     cả ba môn.
  6. Thăm dò bị từ chối: thử mở kho/tài nguyên cá nhân của NT-4 chỉ bằng quyền chủ sở hữu
     trung tâm → bị từ chối; người tham gia nói được lý do (quyền quản trị trung tâm không
     bao gồm tài nguyên cá nhân của người khác).

### NV-4 — Giáo viên làm việc trong phạm vi được phân công (NT-4 · SC-009)

**Thẻ nhiệm vụ**: "Bạn là giáo viên vừa được thêm vào trung tâm. Hãy đăng nhập, vào trung
tâm, mở môn bạn được phân công trong khóa, xem danh sách học sinh đã ghi danh môn đó."

- **Bấm giờ**: bắt đầu khi mở trang đăng nhập; kết thúc khi mở đúng danh sách học sinh của
  môn được phân công. Không có trần SC riêng; ghi thời gian thực tế.
- **Tiêu chí hoàn thành quan sát được**:
  1. Đăng nhập và chuyển đúng vào không gian trung tâm (không dừng ở không gian cá nhân).
  2. Mở đúng khóa và môn thứ nhất — môn được phân công ở NV-3.
  3. Thấy danh sách học sinh ghi danh môn đó (khớp ghi danh NV-3: cả ba học sinh).
  4. Thăm dò bị từ chối: thử tạo lớp mới trong trung tâm, hoặc thử tự phân công mình thêm
     môn thứ hai → bị từ chối; người tham gia nói được lý do và nói đúng mình đang ở trung
     tâm nào.

### NV-5 — Phụ huynh xem kết quả đã công bố của con (NT-5 · SC-009)

Tiền đề dữ kiện ở Trung tâm 1 (xem §6): NT-5 có liên kết phụ huynh đã duyệt với HS-1, HS-1
có ít nhất một kết quả mẫu **đã công bố** và một kết quả **chưa công bố**.

**Thẻ nhiệm vụ**: "Bạn là phụ huynh có con học tại Trung tâm 1. Hãy đăng nhập, vào Trung
tâm 1 và xem kết quả đã công bố của con bạn."

- **Bấm giờ**: bắt đầu khi mở trang đăng nhập; kết thúc khi mở đúng kết quả đã công bố của
  HS-1 tại khu vực kết quả của con (`/centers/{wid}/children/{sid}/results`). Không có trần
  SC riêng; ghi thời gian thực tế.
- **Tiêu chí hoàn thành quan sát được**:
  1. Đăng nhập và vào đúng không gian Trung tâm 1.
  2. Mở đúng kết quả **đã công bố** của đúng con (HS-1).
  3. Thăm dò bị từ chối: thử xem kết quả của học sinh khác (HS-2), hoặc kết quả chưa công
     bố của HS-1 → bị từ chối/không hiển thị; người tham gia nói được lý do và nói đúng
     mình đang ở trung tâm nào.

## 6. Chuẩn bị trước buổi chạy (điều phối thực hiện và kiểm tra)

### 6.1 Tài khoản cần tạo sẵn

| # | Tài khoản | Vai trò / trạng thái cần chuẩn bị | Dùng cho |
|---|---|---|---|
| 1 | Quản trị viên Giáo Khoa (tài khoản vận hành có sẵn) | Đăng nhập được khu vực nền tảng `/platform` | Phát cho NT-2 lúc chạy |
| 2 | Người vận hành trung tâm | Email đã xác minh; chưa thuộc trung tâm nào; chưa được chỉ định sở hữu ở đâu | NT-3; được chỉ định chủ sở hữu trong NV-2 |
| 3 | Giáo viên 1 | Email đã xác minh; chưa là thành viên trung tâm mới | NT-4; được NT-3 thêm và phân công trong NV-3 |
| 4 | Giáo viên 2 | Email đã xác minh; chưa là thành viên trung tâm mới | Tài khoản phụ — giáo viên thứ hai cùng môn ở NV-3 (không cần người có mặt) |
| 5 | Học sinh HS-1 | Tài khoản có sẵn (được cấp bằng tên đăng nhập, không bắt buộc email); đã là thành viên Trung tâm 1 và có dữ kiện ở mục 6.2 | Được thêm vào trung tâm mới ở NV-3; là "con" trong NV-5 |
| 6 | Học sinh HS-2 | Tài khoản có sẵn; đã là thành viên Trung tâm 1 | Ghi danh ở NV-3; đối tượng thăm dò "học sinh khác" ở NV-5 |
| 7 | Học sinh HS-3 | Tài khoản có sẵn | Thêm vào lớp và ghi danh ở NV-3 |
| 8 | Phụ huynh | Email đã xác minh; là thành viên Trung tâm 1; liên kết phụ huynh với HS-1 đã được duyệt trong Trung tâm 1 | NT-5 |

NT-1 không chuẩn bị tài khoản: người tham gia tự đăng ký bằng email thật của mình trong
NV-1. Mật khẩu/tên đăng nhập của các tài khoản trên được điều phối giữ trong danh sách
riêng ngoài repo và chỉ phát tại buổi chạy; **không ghi thông tin đăng nhập vào file này**.

### 6.2 Dữ kiện cần dựng sẵn

- Trung tâm 1 (tenant `trung-tam-1`): HS-1 và HS-2 là thành viên/học sinh của trung tâm;
  liên kết phụ huynh NT-5 – HS-1 ở trạng thái đã duyệt; HS-1 có tối thiểu một hoạt động mẫu
  đã chấm và **công bố** kết quả, và một kết quả **chưa công bố** (phục vụ thăm dò NV-5).
- Thẻ nhiệm vụ in sẵn các giá trị điều phối điền trước buổi: tên trung tâm mới (NV-2); tên
  lớp, tên khóa, tên ba môn (NV-3); tên đăng nhập của NT-3 (trên thẻ NV-2); tên đăng nhập
  của giáo viên 1, giáo viên 2, HS-1, HS-2, HS-3 (trên thẻ NV-3).
- Thiết bị: một máy/trình duyệt cho mỗi người tham gia, đồng hồ bấm giờ cho người quan
  sát, bản sao của §7–§8 để ghi tại chỗ.

## 7. Phiếu buổi chạy (điền khi chạy thật)

| Mục | Giá trị |
|---|---|
| Ngày, giờ bắt đầu – kết thúc | |
| Bề mặt (URL đã dùng) | |
| SHA của bản deploy | |
| Người điều phối | |
| Người quan sát / ghi chép | |
| Điều kiện §1 đạt đủ (Có/Không, ghi rõ mục thiếu nếu có) | |
| Ghi chú bất thường trong buổi | |

## 8. Bảng kết quả (TRỐNG — chỉ điền từ buổi chạy thật)

| Mã | Vai trò | Nhiệm vụ | Hoàn thành lần đầu (Có/Không) | Thời gian thao tác | Trần SC | Số lần hỗ trợ | Nhận biết không gian & lý do bị từ chối (Đạt/Không) |
|---|---|---|---|---|---|---|---|
| NT-1 | Người dùng mới | NV-1 (SC-001) | | | 5:00 (không tính chờ thư) | | |
| NT-2 | Quản trị viên Giáo Khoa | NV-2 (SC-002 phần 1) | | | 5:00 | | |
| NT-3 | Người vận hành trung tâm | NV-3 (SC-002 phần 2) | | | 15:00 | | |
| NT-4 | Giáo viên | NV-4 | | | — | | |
| NT-5 | Phụ huynh | NV-5 | | | — | | |

Thời gian chờ thư xác minh thực tế của NV-1 (không tính vào thời gian thao tác): ______

### 8.1 Lỗi và vướng mắc ghi nguyên văn

| Nhiệm vụ | Người tham gia | Nguyên văn vướng mắc / thông báo hệ thống | Bước thao tác lúc xảy ra |
|---|---|---|---|
| | | | |
| | | | |
| | | | |

### 8.2 Câu trả lời hai câu hỏi chốt SC-009 (ghi nguyên văn)

| Mã | Đang ở không gian/trung tâm nào? | Hiểu lý do bị từ chối ở thăm dò? (nguyên văn) |
|---|---|---|
| NT-1 | | |
| NT-2 | | |
| NT-3 | | |
| NT-4 | | |
| NT-5 | | |

### 8.3 Tổng kết theo tiêu chí (để trống chờ số thật)

| Tiêu chí | Kết quả đo | Yêu cầu | Đạt / Không đạt |
|---|---|---|---|
| SC-001 — NV-1 hoàn thành trong trần thao tác, có đúng quyền nền, không có quyền chưa cấp | | ≤ 5:00 thao tác (không tính chờ thư) | |
| SC-002 — NV-2 và NV-3 hoàn thành trong trần thao tác | | NV-2 ≤ 5:00; NV-3 ≤ 15:00 | |
| SC-009 — người hoàn thành đúng tác vụ lần đầu sau hướng dẫn ngắn, nhận biết được không gian và lý do không có quyền | ___ / 5 | ≥ 4/5, gồm NT-3 và NT-4 hoặc NT-5 | |

## 9. Kết luận (để trống — điền sau buổi chạy thật)

- Kết luận SC-001: ☐ Đạt ☐ Không đạt — căn cứ: (số liệu §8)
- Kết luận SC-002: ☐ Đạt ☐ Không đạt — căn cứ: (số liệu §8)
- Kết luận SC-009: ☐ Đạt ☐ Không đạt — căn cứ: (số liệu §8, §8.2)
- Việc theo sau nếu có tiêu chí không đạt: (ghi rõ defect/vướng mắc và nơi xử lý tiếp)
- Người điều phối xác nhận số liệu trên là kết quả quan sát buổi chạy thật ngày ______,
  không thay bằng kiểm thử tự động: (tên, ngày ký)
