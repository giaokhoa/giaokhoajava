# Đối chiếu spec với nghiệp vụ sư phạm tại Việt Nam

Ngày rà soát: 10/11/2026.

**Cập nhật 12/11/2026**: Đã xử lý phần làm rõ bằng Spec Kit tại [spec](../specs/001-multitenant-access/spec.md), gồm 5 câu trả lời trực tiếp của chủ spec: NV-01 tách hoàn thành/thu hồi và giữ lịch sử theo thời gian hồ sơ được lưu hợp lệ; NV-02 xác minh ngoài hệ thống, ứng dụng lưu tham chiếu tối thiểu; NV-04 bài cần bảo đảm hoàn tất dùng nguồn sở hữu/LONG_TERM; NV-05 gia hạn trước khi phân công giáo viên thay. FR-055–057 và 5 scenario bổ sung còn cần triển khai/kiểm chứng. NV-03 giữ phạm vi điều phối trung tâm; NV-06 để đặc tả chấm bài thật; NV-07 giữ là tiền đề vận hành chưa chốt cho đơn vị cụ thể. Xem [kết quả spec → code và task hội tụ](doi-chieu-spec-code-20261112.md), [luồng theo vai trò để viết hướng dẫn/E2E](luong-nghiep-vu-theo-vai-tro.md). Phần dưới giữ nguyên báo cáo ngày 10/11, gồm căn cứ pháp luật tại thời điểm đó; đợt cập nhật này không xác nhận lại các văn bản hoặc chứng nhận tuân thủ.

Nền đối chiếu: `origin/main` tại `efcb781cd4f4964bca7cf436d4aef002205ca74a`; đã fetch lại và xác nhận spec, data-model, contracts trong workspace khớp nền này. Đã đọc parent issue [#2](https://github.com/giaokhoa/giaokhoa/issues/2), bao gồm hướng dẫn điều phối liên kết commit `bf99c20efa1aad2740f772bcc958205c7868004e`. Đây là rà soát nghiệp vụ, không triển khai story hoặc xác nhận gate kỹ thuật.

## Kết luận và phạm vi

Spec phù hợp làm bộ khung phân quyền cho trung tâm: một danh tính ở nhiều nơi, ghi danh từng môn, nhiều giáo viên, quyền phụ huynh có xác nhận và bảo lưu dữ liệu. Tuy nhiên, vòng đời học tập và một số tình huống vận hành chưa được phân biệt đủ rõ. Nếu dùng cho trường phổ thông, quyền của chủ nhiệm và mô hình kết quả hiện chưa đủ.

Đã đối chiếu `spec.md`, `data-model.md`, `contracts/authorization.md` và `contracts/http-api.md`. Các phát hiện dưới đây là kết luận về hợp đồng đã viết; chưa tái hiện lỗi của implementation. Phân biệt ba loại: thiếu quyết định để vận hành, rủi ro của chính sách đã chọn, và thiếu nghiệp vụ khi mở rộng sang trường phổ thông.

`P1`: cần chốt trước khi dùng cho đối tượng thực tế liên quan. `P2`: cần làm rõ trước khi triển khai luồng chịu ảnh hưởng. Các mức này không có nghĩa mọi nội dung đều phải được lập trình trong bản Authentication & Authorization hiện tại.

## Các điểm cần xem lại

### NV-01 — P1: Chưa tách kết thúc học bình thường khỏi thu hồi quyền

**Vị trí:** `spec.md:100`, FR-021 (`spec.md:208`), FR-047 (`spec.md:246`); `data-model.md:93`; `authorization.md:86`.

Ghi danh chỉ có vòng đời `PENDING → ACTIVE → REVOKED`. Quyền học sinh xem kết quả cần enrollment hiện hành. Spec chưa định nghĩa tốt nghiệp khóa, kết thúc đợt học, chuyển lớp hoặc bảo lưu học tập.

**Tình huống:** X học xong Toán đợt 1 và chuyển sang đợt 2. Nếu vận hành gỡ ghi danh cũ để kết thúc học, X mất quyền xem kết quả đợt 1 theo chính hợp đồng hiện tại. Nếu giữ ACTIVE để xem lại, quan hệ cũ vẫn là nguồn quyền học tập. Phụ huynh lại có nhánh xem dựa trên liên kết riêng, nên có thể còn xem được trong khi chính học sinh không xem được.

**Đề xuất tối thiểu:** Chốt riêng việc kết thúc học bình thường và thu hồi vì lý do bảo mật; xác định quyền đọc kết quả đã công bố của chính người học sau hoàn thành. Quyền này phải có phạm vi, thời hạn và chính sách riêng, không tự vượt việc khóa tài khoản hoặc thu hồi truy cập có chủ đích. Ghi rõ kỳ/đợt học và nguyên tắc chuyển lớp để không dùng lại cùng phạm vi cho các lứa học sinh khác nhau.

**Nghiệm thu cần bổ sung:** Hoàn thành đợt học vẫn xem được lịch sử được phép nhưng không nhận/nộp bài mới; chuyển lớp không mang quyền sang lớp cũ; thu hồi bảo mật vẫn chặn đúng phạm vi. Đây là khuyến nghị nghiệp vụ, không phải kết luận pháp luật bắt buộc mở quyền đọc vĩnh viễn.

### NV-02 — P1: Duyệt liên kết phụ huynh chưa đủ mô tả xác minh người đại diện và xử lý dữ liệu trẻ em

**Vị trí:** US1.4 (`spec.md:28`), FR-004 (`spec.md:185`), FR-022–024 (`spec.md:212`), AS-007 (`spec.md:330`); `data-model.md:18`, `data-model.md:45`.

Spec cho phép cấp tài khoản học sinh không email và quản lý xác nhận liên kết, nhưng chưa quy định căn cứ xác minh quan hệ, người giám hộ hợp pháp, thông tin tuổi cần thiết, căn cứ xử lý dữ liệu hoặc xử lý khi có tranh chấp đại diện. Liên kết phụ huynh chỉ có trong trung tâm, trong khi lớp cá nhân cũng có thể chứa học sinh trẻ em.

Luật Bảo vệ dữ liệu cá nhân hiện quy định vai trò của người đại diện; việc công bố, tiết lộ thông tin riêng tư của trẻ từ đủ 7 tuổi có yêu cầu đồng ý riêng. Không được suy thành mọi thao tác học tập đều phải dùng cùng một cơ chế xin đồng ý. Xem [Điều 24 được Cổng TTĐT Chính phủ giới thiệu](https://xaydungchinhsach.chinhphu.vn/quy-dinh-bao-ve-du-lieu-ca-nhan-cua-tre-em-nguoi-bi-mat-hoac-han-che-nang-luc-hanh-vi-dan-su-119250725165056743.htm).

**Đề xuất tối thiểu:** Bổ sung quy trình vận hành xác minh người đại diện, căn cứ xử lý/chia sẻ dữ liệu và dấu vết xác nhận tối thiểu; cho phép ghi nhận chứng cứ được xác minh ngoài hệ thống. Bao phủ cả center và personal workspace. Không mặc định bắt tải giấy tờ nhạy cảm lên hệ thống. Chốt chính sách bảo lưu, yêu cầu truy cập/xóa và các trường hợp phải giữ hồ sơ trước khi đưa dữ liệu học sinh thật vào.

**Nghiệm thu cần bổ sung:** Chỉ biết mã học sinh hoặc có vai trò PARENT không đủ tạo liên kết; xử lý được người giám hộ hợp pháp và tranh chấp/yêu cầu gỡ; việc duyệt quyền xem không được coi là thay thế mọi căn cứ pháp lý về dữ liệu. Thiếu đặc tả chưa đủ để kết luận đơn vị vận hành đang vi phạm pháp luật.

### NV-03 — P2, có điều kiện: Quyền chủ nhiệm thiếu phần tổng hợp kết quả nếu phục vụ trường phổ thông

**Vị trí:** FR-019–020 (`spec.md:206`); ma trận chủ nhiệm (`spec.md:268`); `authorization.md:85–87`.

Chủ nhiệm được giao đề đã duyệt và theo dõi hoàn thành, nhưng nhánh đọc kết quả không có chủ nhiệm đứng riêng. Người chỉ có phân công chủ nhiệm không có đủ quyền theo dõi kết quả các môn của lớp.

Trong trường THCS/THPT, chủ nhiệm còn có trách nhiệm tổng hợp kết quả học tập, rèn luyện và trao đổi với gia đình theo Điều 20 [Thông tư 22/2021/TT-BGDĐT](https://chinhphu.vn/?docid=203926&pageid=27160); có thể đọc [nội dung Điều 18–20](https://thuvienphapluat.vn/van-ban/Giao-duc/Thong-tu-22-2021-TT-BGDDT-danh-gia-hoc-sinh-trung-hoc-co-so-485242.aspx). Quy định trường phổ thông không tự áp nguyên bộ cho mọi trung tâm.

**Đề xuất tối thiểu:** Nếu “chủ nhiệm” là chủ nhiệm theo nghiệp vụ trường học, bổ sung quyền đọc kết quả/nhận xét cần thiết của đúng lớp. Quyền tổng hợp không tự gồm quyền sửa điểm, đọc đáp án hoặc chấm mọi môn. Nếu chỉ là người điều phối nhóm học trung tâm, ghi rõ nghĩa đó và giữ giới hạn hiện tại.

**Nghiệm thu cần bổ sung khi chọn hỗ trợ trường học:** Chủ nhiệm đọc được kết quả các môn của đúng lớp để tổng hợp, không đọc lớp khác và không tự sửa điểm chuyên môn.

### NV-04 — P2: Thu hồi quyền học liệu có thể làm gián đoạn bài đang làm và bài chờ chấm

**Vị trí:** US7.6 (`spec.md:136`), edge case (`spec.md:169`), FR-038 (`spec.md:234`), FR-045 (`spec.md:244`).

Đây là chính sách được viết rõ, không phải mâu thuẫn nội bộ: học sinh đang làm đề dùng nguồn REVOCABLE có thể bị chặn thao tác tiếp theo khi nguồn bị thu hồi. Giữ phiên bản và dữ liệu đã lưu không bảo đảm tiếp tục được nộp, chấm hoặc công bố. Thu hồi một thành phần trong đề tổng hợp cũng có thể gây tình huống này.

**Ảnh hưởng sư phạm:** Một bài kiểm tra có thời gian cố định có thể không hoàn tất dù học sinh thực hiện đúng lịch; dữ liệu còn nhưng chưa có kết quả sử dụng được.

**Đề xuất tối thiểu:** Phân biệt luyện tập có thể gián đoạn với bài kiểm tra cần bảo đảm hoàn tất. Loại thứ hai chỉ dùng nguồn có điều kiện cấp phép phù hợp, chẳng hạn LONG_TERM hoặc thỏa thuận cho phép hoàn tất bài đã giao. Nếu vẫn cho phép REVOCABLE, phải nêu cách xử lý bài bị gián đoạn. Không tự tạo ngoại lệ vượt quyền nguồn đã thỏa thuận.

**Nghiệm thu cần bổ sung:** Thu hồi nguồn trong lúc làm bài và sau nộp/trước chấm đều có kết quả nghiệp vụ được xác định; không chỉ kiểm tra deny và giữ nguyên hàng dữ liệu.

### NV-05 — P2: Cam kết hoàn tất bài khi hết hạn gói chưa bao phủ thay giáo viên

**Vị trí:** US8.3–5 (`spec.md:153`), FR-043–046 (`spec.md:242`), AS-009 (`spec.md:332`).

**Tình huống:** Gói hết hạn; T nghỉ dạy nên trung tâm thu hồi phân công của T; bài đã giao còn chờ chấm. Hệ thống cho thu hồi nhưng cấm phân công thêm, trong khi người chấm phải còn phân công hiện hành. Nếu không có đồng giáo viên còn quyền, không ai có thể hoàn tất bài theo ngoại lệ đã hứa.

**Đề xuất tối thiểu:** Chốt một trong hai chính sách: cho bàn giao giới hạn vào bài đã giao, hoặc yêu cầu gia hạn gói khi đổi người chấm và nói rõ giới hạn của cam kết hoàn tất. Cách bàn giao phải giữ thu hồi ngay đối với người nghỉ và không mở quyền giao bài/lớp mới.

**Nghiệm thu cần bổ sung:** Giáo viên duy nhất nghỉ khi gói đã hết hạn; người cũ mất quyền ngay và trung tâm có đường xử lý các bài tồn theo chính sách đã chọn. Không giữ tài khoản/phân công của người đã nghỉ chỉ để chấm nốt.

### NV-06 — P2 trước khi có chấm bài thật: Đính chính nguồn chưa có hợp đồng điều chỉnh kết quả đã công bố

**Vị trí:** FR-033–034 (`spec.md:229`); `data-model.md:59`, `data-model.md:72`; action grade/publish tại `http-api.md:166`.

Spec giữ nguyên V1 và thông báo V2 do lỗi là đúng cho truy vết. Tuy nhiên, chưa xác định khi đáp án V1 sai thì ai được chấm lại, kết quả công bố được thay bằng bản nào, ai được biết và lịch sử điểm cũ được giữ ra sao. Việc giữ nguyên đề đã giao không đồng nghĩa phải giữ nguyên một kết quả chấm sai.

**Đề xuất tối thiểu:** Trước khi làm chức năng chấm bài thật, chốt quyền điều chỉnh, lý do, lịch sử trước/sau và thông báo kết quả thay thế. Bản đầu có thể chỉ kiểm chứng quyền bằng mẫu; chưa cần xây cả quy trình phúc khảo. Nếu phục vụ sổ điểm chính thức, cần phân biệt sửa kết quả bài luyện tập với sửa hồ sơ đánh giá có bước xác nhận/phê duyệt theo [Điều 18–20 Thông tư 22/2021](https://chinhphu.vn/?docid=203926&pageid=27160).

**Nghiệm thu tương lai:** Đề được đính chính sau công bố; người có thẩm quyền sửa kết quả có dấu vết, người không có quyền bị chặn, bài làm/phiên bản gốc không bị ghi đè.

### NV-07 — P1 nếu vận hành dạy thêm: Chưa chốt loại hoạt động và trách nhiệm tuân thủ

**Vị trí:** FR-007 (`spec.md:191`), FR-013–016 (`spec.md:200`), FR-025 (`spec.md:218`).

Tenant và gói chưa phân biệt hoạt động dạy thêm theo chương trình phổ thông với ngoại ngữ, kỹ năng hoặc hoạt động bổ trợ ngoài chương trình. Điều 3–6 quy định tính tự nguyện, sự đồng ý của cha mẹ/người giám hộ, những trường hợp không được dạy thêm và điều kiện tổ chức ngoài nhà trường. Xem [bản hợp nhất Thông tư 29/2024 và sửa đổi 19/2026](https://congbaocdn.chinhphu.vn/180507251028987904/2026/5/8/469438-1778212759_v1_1778233175_signed.pdf).

Sửa đổi năm 2026 phân định rõ hơn hoạt động bổ trợ ngoài chương trình với dạy thêm thuộc phạm vi Thông tư; không thể dùng một quy tắc cấm chung cho mọi lớp ngoại ngữ/STEM của học sinh tiểu học. Xem [giải thích của Bộ GDĐT trên Báo Chính phủ](https://baochinhphu.vn/quy-dinh-moi-ve-day-them-hoc-them-102260408162857714.htm).

**Đề xuất tối thiểu:** Ghi rõ loại hình mà sản phẩm hỗ trợ, ai xác nhận điều kiện tổ chức và bằng chứng đăng ký học tự nguyện được lưu ở đâu. Nếu xác minh ngoài hệ thống, ghi đó là tiền đề vận hành. Chỉ thêm dữ kiện và kiểm soát trong ứng dụng khi chức năng thực sự cần.

Mua gói phần mềm không phải thu học phí hoặc được cấp phép dạy thêm. Quyết định không xác minh nghề nghiệp ở FR-025 tự nó không sai; không có căn cứ buộc nền tảng phải kiểm tra bằng cấp mọi người mua gói. Phát hiện này là thiếu ranh giới và trách nhiệm nghiệp vụ, không phải kết luận phần mềm đã tổ chức dạy thêm trái phép.

## Những lựa chọn hiện tại không nên coi là lỗi

| Lựa chọn | Nhận định |
| --- | --- |
| Một khóa chỉ thuộc một lớp | Giới hạn đã công bố tại `spec.md:280`. Có thể dùng nếu khóa được hiểu là một đợt mở của nhóm học; không đại diện được khóa dùng chung nhiều lớp. Chưa cần đổi mô hình khi chưa có nhu cầu đó. |
| Phải vào danh sách lớp trước khi ghi danh môn | Có thể là roster của nhóm học trung tâm. Không được suy “lớp” này là lớp chính khóa ở trường. Data-model không cấm một học sinh thuộc nhiều lớp trung tâm. |
| Chỉ quản lý/chủ sở hữu duyệt liên kết phụ huynh | Là lựa chọn kiểm soát; không có căn cứ từ các văn bản đã đối chiếu để khẳng định GVCN bắt buộc phải là người duyệt liên kết tài khoản. Có thể bổ sung vai trò hỗ trợ xác minh nếu vận hành cần. |
| Phụ huynh chỉ xem kết quả công bố, không tự có bài làm/đáp án | Là mức quyền mặc định hợp lý. Nếu cần phối hợp chữa bài, nên chia sẻ phản hồi/bài đã trả theo hành động riêng; không mặc định mở toàn bộ đáp án. |
| Chưa có sổ điểm, học bạ, rèn luyện, ma trận đầy đủ | Đã loại khỏi bản đầu. Không đánh giá các chức năng chưa xây như lỗi của bộ khung Authentication & Authorization. |

## Lưu ý về văn bản hiện hành và phạm vi trường phổ thông

Tại ngày rà soát, [Thông tư 15/2026/TT-BGDĐT](https://chinhphu.vn/?classid=1&docid=217678&orggroupid=4&pageid=27160) đã có hiệu lực từ 10/05/2026; không dùng Điều lệ trường trung học năm 2020 như căn cứ hiện hành duy nhất. [Thông tin thay thế các Điều lệ trước](https://xaydungchinhsach.chinhphu.vn/toan-van-thong-tu-so-15-2026-tt-bgddt-dieu-le-truong-tieu-hoc-truong-trung-hoc-co-so-truong-trung-hoc-pho-thong-va-truong-pho-thong-co-nhieu-cap-hoc-119260504162539719.htm) được Cổng TTĐT Chính phủ công bố.

[Thông tư 89/2026/TT-BGDĐT](https://chinhphu.vn/?classid=1&docid=220070&orggroupid=4&pageid=27160) đã ban hành nhưng đến **01/12/2026** mới có hiệu lực. Các nội dung về học bạ số, tổ chức kiểm tra trên máy tính và bảo đảm tính toàn vẹn/truy xuất cần được đưa vào thiết kế nếu phát triển hồ sơ đánh giá chính thức; không dùng như tiêu chí bắt buộc đã có hiệu lực vào 10/11/2026. Xem [nội dung được Chính phủ giới thiệu](https://xaydungchinhsach.chinhphu.vn/thong-tu-89-2026-tt-bgddt-sua-doi-bo-sung-quy-dinh-ve-danh-gia-hoc-sinh-cac-cap-hoc-119261020155918241.htm).

Không có cơ sở áp mọi quy định về học bạ và đánh giá chính thức của trường phổ thông vào điểm luyện tập của trung tâm. Trước khi mở rộng, phải chốt “kết quả” là phản hồi luyện tập hay hồ sơ đánh giá chính thức, cấp học và loại cơ sở phục vụ.

## Thứ tự xử lý đề xuất

1. Chốt NV-01 và NV-02 cho vận hành học sinh thật; chốt NV-07 nếu khách hàng tổ chức dạy thêm.
2. Chốt NV-04 và NV-05 để lời hứa bảo lưu/hoàn tất bài có ý nghĩa trong vận hành.
3. Chỉ mở NV-03 khi hỗ trợ nghiệp vụ chủ nhiệm tương ứng; đưa NV-06 vào thiết kế trước chức năng chấm bài thật.

Không sửa spec/contract/code hoặc cập nhật GitHub trong đợt rà soát này. Chỉ thêm báo cáo; không chạy build/test vì không thay đổi phần mềm. Chưa phỏng vấn giáo viên/quản lý trung tâm, chưa rà quy định riêng từng địa phương hoặc chứng nhận tuân thủ pháp luật cho đơn vị vận hành cụ thể.
