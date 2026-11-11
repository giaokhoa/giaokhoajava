# Quy tắc dành cho AI Agent

## Ngôn ngữ
- Sử dụng **tiếng Việt** khi trao đổi, viết đặc tả, kế hoạch, task, tài liệu và báo cáo review/audit.
- Giữ nguyên tên kỹ thuật, lệnh, đường dẫn, định danh trong code và các thuật ngữ không nên dịch.

## Trước khi viết code
- Trước mọi tác vụ viết, sửa lỗi hoặc tái cấu trúc code, phải đọc `.agents/skills/ponytail/SKILL.md` và áp dụng skill `ponytail` ở mức **full** trong suốt tác vụ.
- Tuân thủ các nguyên tắc về phạm vi, sự tối giản, an toàn và kiểm thử của skill. Không tự ý hạ mức.
- Khi viết code, phải bám sát các pattern, convention và API chính thống của framework đang sử dụng (Spring Boot, Spring Security, Next.js, v.v.) và cách triển khai nhất quán trong project. Ưu tiên giải pháp được framework hỗ trợ sẵn; không tự tạo abstraction hoặc cách làm riêng khi framework đã có pattern phù hợp.

## Trước khi merge
- Trước khi merge **bất kỳ** PR hoặc nhánh nào vào `main`, phải đọc `.agents/skills/ponytail-review/SKILL.md` và dùng `ponytail-review` để review thay đổi so với nhánh đích, bao gồm cả code liên quan.
- Phải xử lý toàn bộ lỗi **Must fix** trước khi merge và review lại phần đã sửa. Không merge nếu review chưa hoàn tất hoặc kết luận chưa đủ điều kiện merge.
- Báo cáo kết luận review trước khi thực hiện merge. CI chạy thành công không thay thế cho bước review này.

## Thiết kế kiểm thử
- Khóa kết quả nghiệp vụ, hành vi quan sát được và hợp đồng công khai. Refactor giữ nguyên hành vi không được buộc đổi assertion chỉ vì đổi helper, thứ tự gọi nội bộ hoặc cú pháp SQL.
- Test SQL phải chứng minh dữ kiện trả về và các ràng buộc trên PostgreSQL thật; kiểm tra chuỗi SQL không thay thế kiểm chứng phân trang, scope hoặc RLS.
- Gộp test trùng theo yêu cầu và lỗi mà test bắt được, cập nhật bảng nghiệm thu; giữ những assertion riêng của transaction, giao thức, session và browser. Số test hay coverage không thay thế bằng chứng hành vi.
- Fixture chỉ dựng tiền đề cần cho ca kiểm thử. Tái dùng danh tính phải giữ cô lập dữ kiện và phiên; thao tác đang được kiểm thử luôn đi qua đường chạy thật của tầng đó.
- Đặt tên test theo cơ chế của JUnit 5: tên phương thức là định danh ngắn gọn chỉ nêu hành vi chính; câu mô tả đầy đủ (kịch bản + kết quả kỳ vọng) viết ở `@DisplayName` bằng tiếng Việt để báo cáo test đọc như đặc tả, không nhồi toàn bộ mô tả vào tên phương thức. Khi một nhóm test trong cùng class dùng chung một tiền tố ngữ cảnh dài, gom nhóm bằng `@Nested` (class con mang `@DisplayName` riêng cho ngữ cảnh đó) thay vì lặp tiền tố trong từng tên phương thức.
- Ba tầng test backend phân biệt bằng hậu tố tên class, plugin chạy và cơ chế (căn cứ: tài liệu Maven Failsafe/Surefire về mẫu tên mặc định và Spring Boot Reference mục Testing):
  - **Unit** — hậu tố `Test`: Surefire chạy ở phase `test`; không dựng Spring context.
  - **Integration (it-pure)** — hậu tố `IT`: Failsafe chạy ở các phase `integration-test`/`verify` (profile `integration`; profile `it-pure` loại nhóm `e2e-backend`). `@SpringBootTest` môi trường MOCK: khẳng định qua service/repository/JDBC và MockMvc trong tiến trình.
  - **E2E backend** — hậu tố `E2EIT` kèm nhãn `@Tag("e2e-backend")`: Failsafe chạy qua profile `e2e-backend` (chỉ đúng nhóm nhãn này). Tên vẫn khớp mẫu mặc định `**/*IT.java` của Failsafe và không khớp mẫu nào của Surefire nên không cần cấu hình includes riêng. `@SpringBootTest(webEnvironment = RANDOM_PORT)` chạy server thật trên cổng ngẫu nhiên; mọi request vào ứng dụng đi qua HTTP thật bằng `RestTestClient.bindToServer()` — client fluent của Spring Framework 7 cho stack servlet (`WebTestClient` là client của stack WebFlux). Cookie phiên và token CSRF lấy qua hợp đồng thật của ứng dụng (phiên JDBC thật; token CSRF đọc từ `GET /api/session` như frontend), không dùng post-processor mô phỏng của spring-security-test.

## Điều phối triển khai

- Ở đầu mỗi phiên và trước mỗi story mới, đọc lại `origin/main`, parent issue và hướng dẫn điều phối được liên kết. Nếu thay đổi hướng dẫn còn ở PR chưa merge, đọc đúng commit tài liệu được parent liên kết; không giả định `main` đã chứa quy tắc mới.
- Trước khi triển khai story kế tiếp, xác minh gate trước đã đạt, rồi rà từng dependency với code/contract hiện có: tách tiền đề bắt đầu và điều kiện hoàn tất, bỏ cạnh chờ thừa có bằng chứng, kiểm tra tên migration/file thật và đồng bộ GitHub/`tasks.md`/`github-issues.json`. Ghi bảng phân công agent, file, artifact đầu vào và gate hội tụ tại parent issue trước khi giao implementation; không tự dùng lại chuỗi tuần tự của bản phân rã gốc. Khi nới cạnh khởi động, phải giữ điều kiện hoàn tất và mọi đầu vào nghiệm thu ở gate của story.
- Khi người dùng yêu cầu làm song song hoặc tối đa công suất, phải phân công agent bằng công cụ thực tế nếu môi trường hỗ trợ; không chỉ ghi `[P]` hoặc hứa làm đồng thời. Dùng các slot có sẵn khi còn công việc độc lập; nếu không có công cụ hoặc bị giới hạn, báo đúng giới hạn, không giả có nhiều agent.
- Agent điều phối chọn việc trong story đã mở, giao rõ issue, file sở hữu, nhánh/worktree và đầu ra cho từng agent. Khi một phần bị chặn hoặc một agent xong việc, giao ngay phần độc lập tiếp theo trong phạm vi hiện tại. Không vượt gate sang story chưa được phép triển khai.
- Mỗi agent sửa code trên nhánh/worktree riêng. Một owner ghép các file chung như migration, contract, `CenterService` và `ProjectionReconciler`; các agent khác bàn giao thay đổi cần ghép, không cùng sửa file đó.
- Dependency schema/service/test là tiền đề artifact: được dùng PR/SHA đã có hợp đồng ổn định và kiểm chứng phù hợp, không phải chờ issue đóng hoặc PR merge. Phân biệt điều kiện bắt đầu với điều kiện hoàn tất; gate vẫn cần toàn bộ hành vi, review và kiểm thử thật đạt.
- Test-first được hoàn tất khi contract được review và có bằng chứng red do thiếu hành vi; không giữ task test đến cuối story chỉ để đợi implementation green. Không merge test đỏ vào `main`; ghép test và code làm xanh trong PR tích hợp đủ điều kiện.
- Trước kiểm chứng cuối và bàn giao PR, ghép `origin/main` hiện hành vào nhánh tích hợp, giải quyết xung đột và giữ các bản sửa/test hồi quy đã merge. Không tự rebase hoặc force-push nhánh của người khác.
- Khi đổi dependency, đồng bộ native dependency trên GitHub, `tasks.md` và `github-issues.json` trong cùng đợt; quy tắc điều phối mới phải được ghi vào repo, không chỉ tồn tại trong prompt trò chuyện. Đọc hướng dẫn tại parent issue của feature trước khi chọn việc. Nếu snapshot repo lệch lịch GitHub đã được parent xác nhận, sửa tài liệu theo lịch hiện hành; không tái tạo cạnh cũ từ snapshot. Đọc các file điều phối ở cùng commit và ghi SHA đã dùng.
- Báo tiến độ bằng agent → issue/phần việc → nhánh/PR/SHA → đang làm/chờ → blocker cụ thể. Nhiều PR mở hoặc nhiều test được liệt kê không chứng minh chạy song song hay nghiệm thu đạt.
- Tách song song viết/review code khỏi số lượt kiểm thử nặng. Trên máy dùng chung, chỉ chạy một lượt Maven integration/native/Playwright nặng tại một thời điểm nếu chưa chứng minh đủ tài nguyên; các agent khác tiếp tục code/review. Mỗi lượt dùng stack TEST riêng theo README, không dùng chung dữ liệu hoặc reset stack của agent khác.

## Chất lượng backend Java
- Trước khi bàn giao thay đổi backend, chạy `./backend/mvnw -B -ntp -f backend/pom.xml clean verify` bằng JDK 25. Lệnh này kiểm tra compiler, unit test, Spotless, PMD và SpotBugs cho cả code chính và test.
- Tự format bằng `./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply`. Không format tay theo một convention khác.
- Sửa nguyên nhân warning và vi phạm. Không giảm rule, bỏ qua source/test, thêm suppression hoặc dùng cờ skip chỉ để làm build xanh. Ngoại lệ đã xác minh do framework hoặc test cố ý kiểm tra đầu vào sai phải hẹp và có giải thích cụ thể.
- Với thay đổi DB, session, OIDC hoặc security, chạy thêm `./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify` trên stack TEST riêng theo `backend/README.md`. Profile này tự tìm integration test của stack TEST theo convention `*IT`; native Keycloak chạy qua script riêng bên dưới.
- Báo cáo rõ lệnh đã chạy và kết quả. Không báo integration test thành công nếu chỉ chạy unit test hoặc compile test.
- Với thay đổi client Keycloak hoặc bootstrap realm, chạy thêm `bash infra/keycloak/verify-native.sh`; script tạo stack native riêng, chạy acceptance test và tự dọn container.

## Quy ước cấu trúc và đặt tên backend

- Package tầng 1 theo nghiệp vụ (9 domain đã chốt trong plan). Package con hoặc chia theo chức năng khi domain đủ lớn (mẫu `authorization`: `decision`, `fence`, `operation`, `permission`, `policy`, `projection`, `target`), hoặc theo tầng kỹ thuật (`model` cho type của domain, `data` cho truy cập dữ liệu và transaction); domain nhỏ giữ phẳng. Một nhãn package con chỉ mang một nghĩa trong toàn repo.
- Stereotype khớp hậu tố vai trò: `@RestController`/`Controller`, `@Service`/`Service`, `@Repository`/`Repository`; `@Component` chỉ cho bean không thuộc ba tầng trên. Class truy cập dữ liệu do class khác tự dựng thay vì container vẫn mang hậu tố `Repository` nhưng không gắn annotation.
- Hậu tố type lồng: `Row` cho dòng đọc từ cơ sở dữ liệu, `View` cho dữ kiện trả ra API, `Request` cho dữ kiện nhận vào; cùng họ còn có `Page`, `Receipt`, `Scope`. Tên `State`/`Kind` được tái dùng theo domain khi đặt trong type sở hữu.
- Họ projection: `*Projector` là class chiếu policy của một domain; `Projection*` là hạ tầng pipeline projection dùng chung ở `authorization.projection`.
- Tên phương thức mở đầu bằng động từ hoặc cụm động từ (Google Java Style Guide §5.2.3), gồm cả handler của controller. Helper private trả về giá trị tính được có thể đặt tên theo giá trị đó, nhưng code mới ưu tiên động từ.
- Hằng `UPPER_SNAKE_CASE` chỉ dành cho giá trị bất biến sâu (Google Java Style Guide §5.2.4). Field `static final` chứa đối tượng có trạng thái thay đổi được (ví dụ `SecureRandom`) hoặc mảng dùng lowerCamelCase như field thường.
