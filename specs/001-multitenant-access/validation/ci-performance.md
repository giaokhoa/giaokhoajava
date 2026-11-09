# Tối ưu CI và test theo hành vi

Nền: `origin/main` tại `54b44078b645cf1142f127178f2e525fa5fa70b2`.
Đã đọc parent #2 và hướng dẫn tại `bf99c20`; không đổi dependency hay mở story mới.

## Tái dùng PR #207

Đọc cùng cây `04de2c8bbfbcfbb10a34d9a2ceeb77cf5ae14c1a` và cherry-pick có ghi nguồn:

| SHA đầu vào | Phần tái dùng |
| --- | --- |
| `7a8b2f9` | Năm test schema/JDBC bỏ full application context |
| `8b210ee` | Bốn test entity/schema dùng `@DataJpaTest` trên PostgreSQL thật |
| `bc9f687` | Tag và hai profile Failsafe bù nhau; giữ schema riêng khi ghép |

Không lấy phần dọn schema `public` khi scheduler còn sống hoặc shared-center.
Lượt CI #207 `42645751923` bị hủy: `TeachingAuthorizationIT` mất 1421 giây,
`TeachingStructureIT` 355,4 giây. Nền xanh `42633602179` tương ứng 420,5 và 114,9
giây. Đây không phải A/B kiểm soát tải; số đo không đủ để khẳng định các thay đổi
đó làm nhanh hơn. Nhánh này giữ cách ly bằng schema/center riêng.

## Yêu cầu và test chịu trách nhiệm

| Yêu cầu/lỗi cần bắt | Kiểm chứng sau thay đổi |
| --- | --- |
| Cùng phiên phụ huynh mất quyền sau unlink | `PublishedResultContractIT.revokedLinkStopsParentFromReadingResults`; gộp bản trùng trong `GuardianAccessIT` cùng đường HTTP |
| Reload/Back không lộ kết quả cũ | Giữ nguyên E2E thứ tư trong `guardian-results.spec.ts` |
| Trang membership giữ mọi vai trò; cursor REVOKED còn dùng được; cursor lạ không lộ dữ kiện | `ListingPaginationIT.membershipPageKeepsEveryRoleAndAdvancesPastRevokedCursor` |
| Lọc giáo viên/workspace trước khi chọn trang, cursor lớp bị thu hồi còn dùng được | `ListingPaginationIT.classPagesFilterTeacherAndWorkspaceBeforeChoosingThePage` |
| Các trang lớp/roster/khóa/ghi danh/phân công không lặp, không mất hàng khi trùng timestamp; cursor sai lớp bị từ chối | `ListingPaginationIT`, PostgreSQL thật thay các assertion bắt cú pháp SQL |
| Nội dung công khai và link đăng nhập hiển thị được | Giữ `home.spec.ts`, bỏ unit assertion trùng khóa nguyên câu khẩu hiệu |
| Lớp/khóa nhiều môn, phạm vi khác môn/khóa, nguồn độc lập sau thu hồi | Giữ các ca nhiều môn và toàn bộ assertion; giảm số môn chỉ ở ca không cần nhiều môn |

Danh tính phụ huynh dùng một bộ tám user cho mỗi class; center, membership và session
vẫn riêng từng ca. User chỉ được xóa sau khi class kết thúc. Không mock PDP, thay
PostgreSQL bằng H2 hay tắt trigger/rule. Roster và ghi danh dùng API theo lô trong
setup; các ca kiểm tra chính API theo lô vẫn giữ nguyên hành vi/assertion.

## Đường chạy CI

- Hai runner backend chạy `clean verify`, rồi `-Pintegration,it-pure verify` hoặc
  `-Pintegration,e2e-backend verify`; hợp hai tập bằng lượt `-Pintegration verify`.
- Hai shard Playwright vẫn giữ toàn bộ 48 E2E. Mỗi shard có PostgreSQL, Keycloak,
  Mailpit, backend, Next và gateway riêng trên runner riêng, một worker mỗi runner.
- Native Keycloak và signed OIDC logout chạy một lần ở shard 1. Check tổng hợp
  `backend`/`integration` thất bại nếu bất kỳ thành phần tương ứng thất bại/hủy.
- Timeout Playwright dừng trước timeout job để xuất report. XML/JSON/trace được lưu
  cả khi xanh; không coi artifact upload thành công là kiểm chứng test.

Số ca/thời gian và các lệnh đã chạy được ghi trong PR theo đúng SHA kiểm chứng.
Chưa thay đổi đường quét resource/policy của code production trong đợt này.
