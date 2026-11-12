# Specification Quality Checklist: Bộ khung xác thực và phân quyền đa trung tâm Giáo Khoa

**Purpose**: Kiểm tra mức đầy đủ và chất lượng của spec trước khi lập kế hoạch.
**Created**: 2026-11-05
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Không có chi tiết triển khai về ngôn ngữ, framework, cấu trúc lưu trữ hoặc giao diện lập trình.
- [x] Tập trung vào giá trị người dùng và nhu cầu nghiệp vụ.
- [x] Nội dung được viết để bên nghiệp vụ có thể xem xét.
- [x] Các phần bắt buộc được điền đầy đủ theo template đã phân giải.

## Requirement Completeness

- [x] Không còn dấu yêu cầu làm rõ chưa xử lý.
- [x] Yêu cầu kiểm chứng được, có phạm vi và kết quả cho phép/từ chối rõ.
- [x] Tiêu chí thành công đo lường được.
- [x] Tiêu chí thành công độc lập công nghệ triển khai.
- [x] Các kịch bản nghiệm thu chính được xác định.
- [x] Có trường hợp biên và ngoại lệ.
- [x] Phạm vi bản đầu, phần dữ liệu mẫu và phần chưa triển khai được phân định.
- [x] Phụ thuộc và giả định được ghi nhận.

## Feature Readiness

- [x] Mọi yêu cầu chức năng có căn cứ nghiệm thu trong câu chuyện, trường hợp biên hoặc ma trận quyền tương ứng.
- [x] Các câu chuyện bao phủ luồng chính của từng nhóm người dùng.
- [x] Có tiêu chí kiểm chứng các kết quả mong muốn.
- [x] Không đưa thiết kế triển khai vào yêu cầu nghiệp vụ.

## Notes

- Kết quả rà soát: **16/16 mục đạt**; đây là kiểm tra chất lượng tài liệu, không phải kết quả kiểm thử sản phẩm.
- Đối chiếu lại ngày 12/11/2026: 8 câu chuyện, 57 yêu cầu chức năng và 9 tiêu chí thành công; 61 acceptance scenarios (56 gốc + 5 bổ sung), định danh không trùng. 5 câu làm rõ đã được trả lời và ghi ở spec; checklist vẫn 16/16 về chất lượng tài liệu, không chứng nhận triển khai FR-055–057.
- Keycloak, Authorization Services và `auth.giaokhoa.com` là ràng buộc sản phẩm người dùng yêu cầu, được tách riêng; spec không chọn cách tổ chức chính sách hoặc cơ chế đồng bộ. Đây không phải thiết kế kỹ thuật tự bổ sung.
- Đã đối chiếu thay đổi cuối: khóa đa môn, nhiều giáo viên cùng môn, ghi danh theo từng môn, không mở vai trò tùy chỉnh và không bắt buộc MFA.
- Đã tách rõ bảo lưu lịch sử với quyền được đọc; ngoại lệ hết hạn gói không vượt quyền đã thu hồi. Chia sẻ lâu dài không bị xử lý như chia sẻ có thể thu hồi.
- Các mốc thời gian/tỷ lệ nghiệm thu là mục tiêu mặc định có ghi trong AS-010; không phải số liệu đã đo hoặc cam kết tải.
- Phụ thuộc môi trường và vận hành ở DEP-001–DEP-003. Tài liệu nguyên tắc dự án hiện còn chỗ trống; không được suy diễn thành quy định đã ban hành.
- Không có extension hooks được đăng ký tại thời điểm thực hiện.
- Đã có plan/tasks/code. Tiếp tục các task hội tụ và gate hiện hành; không tạo lại feature hoặc bỏ qua kiểm chứng cách ly/thu hồi. Bản đồ luồng theo vai trò liên kết trong spec là khung hướng dẫn sử dụng và nghiệm thu E2E; những phần chưa có code/test được ghi rõ.

### Đối chiếu phạm vi yêu cầu và căn cứ nghiệm thu

| Yêu cầu | Căn cứ chính | Kết quả cần đối chiếu |
| --- | --- | --- |
| FR-001–FR-006 | US1; SC-001, SC-004 | Danh tính chung, xác minh, tài khoản không email, chuyển không gian, khôi phục |
| FR-007–FR-012 | US2; ma trận quyền; SC-002–SC-004 | Tenant/gói thủ công, bộ quyền cố định, giới hạn chủ sở hữu/quản lý/giáo viên |
| FR-013–FR-021 | US3; trường hợp biên; SC-002–SC-004 | Một lớp mỗi khóa, nhiều môn/giáo viên, ghi danh từng môn, quyền chủ nhiệm |
| FR-022–FR-024 | US4; SC-005–SC-006 | Duyệt liên kết, đúng con/trung tâm, chỉ kết quả đã công bố |
| FR-025–FR-028 | US6; US8.7; SC-007 | Gói cá nhân tách trung tâm, kích hoạt/gia hạn và quyền còn hiệu lực |
| FR-029–FR-041 | US7; US6.3; trường hợp biên; SC-008 | Kho/sở hữu, định danh/phiên bản, chia sẻ, giới hạn nguồn và tài nguyên tổng hợp |
| FR-042–FR-047 | US8; trường hợp biên; SC-007 | Chỉ đọc, hoàn tất theo lịch cũ, quyền thu hồi và bảo lưu dữ liệu |
| FR-048–FR-053 | US5; US1.9; SC-003–SC-005 | Mặc định từ chối, không tin dữ kiện giả, thu hồi với phiên cũ và cập nhật thất bại |
| FR-054 | Tám câu chuyện; phạm vi bản đầu; SC-002, SC-009 | Luồng quản trị vận hành thật, quyền nghiệp vụ bằng dữ liệu mẫu |
