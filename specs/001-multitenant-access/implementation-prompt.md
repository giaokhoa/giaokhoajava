# Prompt triển khai với điều phối song song

Sao chép phần dưới cho bên triển khai. Đây là chỉ dẫn thực thi; trạng thái PR/SHA phải đọc lại bằng `gh`, không lấy thời điểm ghi tài liệu làm bằng chứng hoàn tất.

```text
Tiếp tục triển khai giaokhoa/giaokhoa trong phạm vi US4 hiện tại. Dùng tối đa khả năng phân công agent thực tế cho các phần độc lập, giữ đầy đủ nghiệp vụ, bảo mật, review và kiểm thử. Không chuyển US5 trước khi gate US4 #64 đạt.

1. Đọc AGENTS.md, áp dụng ponytail mức full, đọc parent #2, tasks.md và github-issues.json. Dùng gh kiểm tra lại issue/PR và origin/main. Báo số agent thực sự có thể dùng; phải gọi công cụ tạo/phân công agent khi có công cụ và công việc độc lập. Không thay việc phân công bằng một danh sách dự định.

2. Tái dùng PR #181, #182, #183, #185, #187, #189 hoặc nhánh kế nhiệm; không viết lại phần đã có. Agent điều phối chuẩn bị nhánh tích hợp có origin/main hiện hành và ghép các commit đang triển khai, giữ các bản sửa #179/#184/#186/#188 và test hồi quy. Không force-push nhánh của người khác. Ghi SHA nền và artifact đầu vào của từng phần.

3. Phân công ngay theo số slot thực tế. Nếu có ít slot hơn thì ghép vai trò; nếu có thêm slot và việc độc lập thì giao thêm review/test. Mỗi agent dùng worktree/nhánh riêng:
- Guardian HTTP: hoàn thiện phần guardian của #62 từ service/projector #60 ở PR #187; sở hữu GuardianLinkController và test riêng cho phần này.
- Result HTTP: hoàn thiện phần kết quả của #62 từ #59/#61 ở PR #185/#189; sở hữu StudentResultController và test riêng. Kiểm tra mỗi row qua PDP, published-only và DTO không lộ nội dung.
- Frontend: thực hiện #63 theo HTTP contract và artifact E2E #57/PR #182; chạy song song backend, sau đó kiểm chứng với backend thật.
- Agent điều phối: ghép origin/main và patch vào nhánh tích hợp; giữ một owner cho contract, migration, CenterService, ProjectionReconciler và fixture dùng chung; review #56/#57 với bằng chứng red phù hợp, chuẩn bị gate #64. Khi agent xong hoặc chờ, giao ngay phần kiểm thử/review/hồi quy độc lập còn thiếu trong US4.

4. Chốt DTO, ID, expectedVersion, error code và định dạng GUARDIAN_GROUP theo spec/contracts cùng các mục B51–B56 trước khi các bên ghép. Quyết định chưa được review vẫn phải được kiểm tra, không coi ghi vào sổ là đã phê duyệt. Native Blocked by là tiền đề khởi động; task #61 hoàn tất vẫn cần guardian group thật của #60, #62 cần đủ cả hai nhóm endpoint, #63 cần backend thật. Không đóng task chỉ để gỡ blocker.

5. Bàn giao từng phần chạy được gồm service, nguồn quyền/projector, HTTP và test. Test-first có thể được đóng sau review contract và red do thiếu hành vi, không đợi gate toàn story. Test đỏ không merge riêng vào main; tiếp tục ghép code làm xanh trên nhánh tích hợp. Mỗi PR nói rõ phạm vi thực sự chạy được và phần còn thiếu.

6. Tối đa agent viết/review code không đồng nghĩa chạy đồng thời nhiều JVM/test stack nặng. Điều phối một hàng đợi kiểm thử nặng trên máy dùng chung cho đến khi có bằng chứng đủ tài nguyên; dùng stack TEST riêng theo README, không reset môi trường khác. Trong khi chờ kiểm thử, tiếp tục phần độc lập.
- Format: ./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply
- JDK 25: ./backend/mvnw -B -ntp -f backend/pom.xml clean verify
- DB/security/session/OIDC: ./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify trên stack TEST riêng theo backend/README.md.
- Client/bootstrap Keycloak: bash infra/keycloak/verify-native.sh
- Frontend: chạy các lệnh lint, typecheck, test/build và E2E thuộc phạm vi theo README/package.json hiện hành.
Báo đúng kết quả và test bị skip. Không giảm rule, mock PDP, skip kiểm thử hoặc dùng compile/list-tests để báo acceptance đạt. Chạy chọn lọc để phản hồi sớm không thay thế các lệnh bàn giao bắt buộc.

7. Push lên PR hiện có hoặc PR tích hợp để bên khác review; không dừng ở patch local. Trước mọi merge main, dùng ponytail-review đọc diff và code liên quan, sửa hết Must fix, review lại và báo kết luận. Gate #64 chỉ đạt khi đủ #56–#63 cùng US4.1–US4.5/T6, cùng session sau unlink, RLS/FK, published-only và E2E backend thật.

8. Báo tiến độ theo bảng agent | issue/phần việc | file sở hữu | nhánh/PR/SHA | đang làm/chờ | blocker. Khi không dùng hết slot dù còn việc sẵn sàng, giải thích lý do cụ thể và phân công lại. Đồng bộ trạng thái có bằng chứng và dependency vào GitHub/tasks.md/github-issues.json; không ghi hoàn tất vì đã có file, PR hoặc CI xanh ở tầng chưa đủ.

9. Trước mỗi story tiếp theo, kể cả khi đổi phiên làm việc: đọc lại origin/main, parent #2 và đúng commit hướng dẫn được liên kết nếu PR điều phối chưa merge. Chỉ sau khi gate trước đạt mới rà dependency của story sắp mở theo code/contract thật, tách tiền đề bắt đầu/điều kiện hoàn tất, giữ đủ đầu vào gate và đồng bộ GitHub/tasks.md/github-issues.json. Ghi bảng phân công agent/file/nhánh/artifact đầu vào tại parent rồi mới triển khai. US5–US8 còn lịch phân rã gốc, không được tự dùng lại làm chuỗi tuần tự mặc định. Không quay lại chờ cả tầng hoặc issue đóng khi artifact đã sẵn sàng; không mở code story sau khi gate trước chưa đạt.
```
