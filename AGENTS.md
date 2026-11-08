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

## Điều phối triển khai

- Khi người dùng yêu cầu làm song song hoặc tối đa công suất, phải phân công agent bằng công cụ thực tế nếu môi trường hỗ trợ; không chỉ ghi `[P]` hoặc hứa làm đồng thời. Dùng các slot có sẵn khi còn công việc độc lập; nếu không có công cụ hoặc bị giới hạn, báo đúng giới hạn, không giả có nhiều agent.
- Agent điều phối chọn việc trong story đã mở, giao rõ issue, file sở hữu, nhánh/worktree và đầu ra cho từng agent. Khi một phần bị chặn hoặc một agent xong việc, giao ngay phần độc lập tiếp theo trong phạm vi hiện tại. Không vượt gate sang story chưa được phép triển khai.
- Mỗi agent sửa code trên nhánh/worktree riêng. Một owner ghép các file chung như migration, contract, `CenterService` và `ProjectionReconciler`; các agent khác bàn giao thay đổi cần ghép, không cùng sửa file đó.
- Dependency schema/service/test là tiền đề artifact: được dùng PR/SHA đã có hợp đồng ổn định và kiểm chứng phù hợp, không phải chờ issue đóng hoặc PR merge. Phân biệt điều kiện bắt đầu với điều kiện hoàn tất; gate vẫn cần toàn bộ hành vi, review và kiểm thử thật đạt.
- Test-first được hoàn tất khi contract được review và có bằng chứng red do thiếu hành vi; không giữ task test đến cuối story chỉ để đợi implementation green. Không merge test đỏ vào `main`; ghép test và code làm xanh trong PR tích hợp đủ điều kiện.
- Trước kiểm chứng cuối và bàn giao PR, ghép `origin/main` hiện hành vào nhánh tích hợp, giải quyết xung đột và giữ các bản sửa/test hồi quy đã merge. Không tự rebase hoặc force-push nhánh của người khác.
- Khi đổi dependency, đồng bộ native dependency trên GitHub, `tasks.md` và `github-issues.json` trong cùng đợt; quy tắc điều phối mới phải được ghi vào repo, không chỉ tồn tại trong prompt trò chuyện. Đọc hướng dẫn tại parent issue của feature trước khi chọn việc.
- Báo tiến độ bằng agent → issue/phần việc → nhánh/PR/SHA → đang làm/chờ → blocker cụ thể. Nhiều PR mở hoặc nhiều test được liệt kê không chứng minh chạy song song hay nghiệm thu đạt.
- Tách song song viết/review code khỏi số lượt kiểm thử nặng. Trên máy dùng chung, chỉ chạy một lượt Maven integration/native/Playwright nặng tại một thời điểm nếu chưa chứng minh đủ tài nguyên; các agent khác tiếp tục code/review. Mỗi lượt dùng stack TEST riêng theo README, không dùng chung dữ liệu hoặc reset stack của agent khác.

## Chất lượng backend Java
- Trước khi bàn giao thay đổi backend, chạy `./backend/mvnw -B -ntp -f backend/pom.xml clean verify` bằng JDK 25. Lệnh này kiểm tra compiler, unit test, Spotless, PMD và SpotBugs cho cả code chính và test.
- Tự format bằng `./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply`. Không format tay theo một convention khác.
- Sửa nguyên nhân warning và vi phạm. Không giảm rule, bỏ qua source/test, thêm suppression hoặc dùng cờ skip chỉ để làm build xanh. Ngoại lệ đã xác minh do framework hoặc test cố ý kiểm tra đầu vào sai phải hẹp và có giải thích cụ thể.
- Với thay đổi DB, session, OIDC hoặc security, chạy thêm `./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify` trên stack TEST riêng theo `backend/README.md`. Profile này tự tìm integration test của stack TEST theo convention `*IT`; native Keycloak chạy qua script riêng bên dưới.
- Báo cáo rõ lệnh đã chạy và kết quả. Không báo integration test thành công nếu chỉ chạy unit test hoặc compile test.
- Với thay đổi client Keycloak hoặc bootstrap realm, chạy thêm `bash infra/keycloak/verify-native.sh`; script tạo stack native riêng, chạy acceptance test và tự dọn container.
