# Đối chiếu spec, luồng theo vai trò và code — 12/11/2026

## Kết luận và căn cứ

**Chưa hội tụ, chưa đủ điều kiện đóng parent #2.** Có code và bằng chứng lịch sử cho phần lớn phạm vi gốc, nhưng còn khoảng hở completion, schema tenant, thông báo, E2E theo vai và các yêu cầu vừa làm rõ. Đợt này sửa tài liệu/hợp đồng, phân loại test hiện có và lập task hội tụ; không tuyên bố đã sửa các defect backend.

- Code nền: `origin/main` tại `f3d2e618be74f574c03d8301a04d9a0427bdbc98`, gồm PR #288. Đã đọc [parent #2](https://github.com/giaokhoa/giaokhoa/issues/2), bốn comment điều phối và hướng dẫn tại commit `bf99c20efa1aad2740f772bcc958205c7868004e`.
- Quyết định kiến trúc: [#261](https://github.com/giaokhoa/giaokhoa/issues/261) chốt JPA, schema mỗi workspace, hai dòng Flyway và copy-on-share giữa tenant. Được dùng làm căn cứ cập nhật thiết kế; không suy mọi tiêu chí #261 đã đạt chỉ vì issue CLOSED.
- Snapshot GitHub ban đầu (02:51 UTC ngày 12/11): 99 sub-issue, 136 dependency, 93 CLOSED và 6 OPEN (#94/#95/#96/#97/#98/#101). Sau khi #94 đóng: 94 CLOSED, 5 OPEN (#95/#96/#97/#98/#101); các native edge không đổi. `github-issues.json` ghi rõ mốc snapshot và trạng thái cập nhật. Không sửa dependency hoặc đăng thông điệp lên GitHub trong đợt này.
- #94 đã CLOSED lúc 03:07 UTC ngày 12/11 và PR #302 đã MERGED lúc 03:06 UTC (`6be8371` trên `origin/main`); #95 còn OPEN, chưa có kết luận đạt. [Comment điều phối trước thời điểm merge](https://github.com/giaokhoa/giaokhoa/issues/2#issuecomment-6812494685) từng yêu cầu chờ PR #302; điều kiện chờ đó nay đã được gỡ, nhưng vẫn phải nghiệm thu lại. Không suy #96–#101 đã sẵn sàng từ một cạnh native riêng lẻ.
- Đầu vào: hai sổ trong `docs`, spec/plan/tasks/research/data-model/contracts/quickstart, ma trận nghiệm thu và code liên quan. `.specify/memory/constitution.md` còn là template; bỏ kiểm tra nguyên tắc chưa ban hành, không tự điền constitution.

Spec Kit được dùng theo ba bước: `speckit-analyze` đối chiếu artifact, `speckit-clarify` ghi 5 câu trả lời, `speckit-converge` bổ sung công việc còn thiếu vào cuối tasks. Việc cập nhật tài liệu trước converge thực hiện theo yêu cầu xử lý lệch của chủ dự án; converge chỉ thêm phase mới, không viết code để làm xanh task.

## Quyết định nghiệp vụ đã chốt

| Điểm | Quyết định | Yêu cầu và nghiệm thu |
| --- | --- | --- |
| NV-01 | Tách hoàn thành đợt học khỏi thu hồi. Đọc kết quả công bố của chính mình trong thời gian hồ sơ còn được lưu hợp lệ, không theo gói; khóa/revoke bảo mật vẫn chặn | FR-021/055, AS-011, US3.9; COMPLETED chưa có trong code |
| NV-02 | Xác minh người đại diện/căn cứ ngoài hệ thống; lưu tham chiếu tối thiểu và người/thời điểm, không yêu cầu tải giấy tờ | FR-056, US4.6; API/UI/migration chưa có `verificationRef` |
| NV-03 | Giữ chủ nhiệm là người điều phối lớp trung tâm theo phạm vi hiện có | Không mở quyền tổng hợp học bạ hoặc chấm mọi môn; khung hướng dẫn CN-01 |
| NV-04 | GUARANTEED dùng nguồn sở hữu/LONG_TERM trên mọi thành phần; REVOCABLE chỉ cho INTERRUPTIBLE | FR-057, US7.10/11; chưa có loại hoạt động/gate tương ứng |
| NV-05 | Hết gói mà giáo viên duy nhất nghỉ: thu hồi ngay; phải gia hạn trước phân công người thay | FR-044, US8.8; không thêm đường bàn giao tăng quyền khi hết gói |
| NV-06 | Điều chỉnh điểm sau công bố nằm ngoài luồng mẫu hiện tại | Giữ đề/bài/kết quả gốc; đặc tả riêng trước chức năng chấm bài thật |
| NV-07 | Loại hình lớp và trách nhiệm tổ chức là tiền đề vận hành cần chốt trước pilot | AS-013/T114; chưa có thông tin của đơn vị vận hành để kết luận đã đáp ứng |

NV-01/NV-02/NV-04/NV-05 có câu trả lời trực tiếp tại `spec.md/Clarifications`; NV-03/NV-06 là giới hạn sẵn có được diễn đạt rõ, không là yêu cầu mở tính năng trường phổ thông. Báo cáo này không đánh giá lại tính hiện hành của các văn bản pháp luật trong báo cáo nghiệp vụ hoặc chứng nhận tuân thủ cho một đơn vị cụ thể.

## Phát hiện còn phải xử lý

Các kết luận code dưới đây dựa trên đường chạy đã đọc; chưa tái hiện bằng lượt integration mới trong phiên này. `missing` = chưa có; `partial` = có nhưng thiếu điều kiện; `contradicts` = khác hợp đồng. Mức HIGH phải xử lý trước nghiệm thu phạm vi liên quan. Không có phát hiện CRITICAL theo constitution vì constitution chưa ban hành.

| ID | Loại / mức | Nguồn | Bằng chứng trong code hiện tại | Công việc còn lại |
| --- | --- | --- | --- | --- |
| F01 | partial / HIGH | FR-044; US8.2/4; B54/B105 | `verification/service/SampleActionService.insertSubmission` kiểm state, enrollment và lượt rồi ghi `Instant.now()`, không so `due_at`; trigger `validate_sample_submission` trong hai baseline cũng không kiểm hạn | T100: chặn nộp quá hạn, khóa kết quả nghiệp vụ trước/đúng/sau hạn trên đường thật |
| F02 | partial / HIGH | FR-045; US7.11; B88/B105 | `submitActivity`, `gradeResult`, `publishResult` chỉ đưa target activity/result vào executor; `ActivityPolicyProjector` chỉ có actor/period, `ResultPolicyProjector` chỉ actor/group; không xét license của version/thành phần cho các hành động này | T101: gate nguồn cần dùng, closure/fence/revoke bằng phiên cũ; READY không thay quyết định license |
| F03 | partial / HIGH | plan Storage/R9; #261; B92/B95/B96/B102/B103 | Các hook tạo workspace chỉ gọi `provisionAfterCommit` → prepare; `TenantMigrationRunner` ghi registry qua `provisionSchema` nhưng không chuyển dữ kiện. Nhiều service nguồn vẫn chạy trên mặt phẳng hệ thống ngoài workspace context | T102: chốt chuyển dữ kiện/kích hoạt an toàn và kiểm chứng toàn luồng nghiệp vụ với tenant đã kích hoạt; không chỉ test plumbing |
| F04 | partial / HIGH | FR-034; US7.2; B80/B81/B87/B101/B103 | `CorrectionNotificationService` ghi receipt bằng SQL không định tuyến từng schema; `SampleActionsPanel` chỉ nhận notice từ response CORRECT_SOURCE của người phát hành. Không có route/UI đọc thông báo của bên nhận | T103: contract/đọc/hiển thị nhận thông báo và fan-out đúng tenant; giữ nguồn/license khi RECEIVE_UPDATE |
| F05 | missing / HIGH | FR-055; US3.9; NV-01 | Enrollment chưa có COMPLETED/`completed_at`; nguồn student của `ResultPolicyProjector` còn dựa vào enrollment hiện hành; không có route/UI hoàn thành theo học sinh × khóa đa môn nguyên tử | T104 test-first contract; T105 dữ kiện, nguồn quyền, API/UI và E2E cho QL-06/HS-03 |
| F06 | missing / HIGH | FR-056; US4.6; NV-02 | `GuardianDecision`, `GuardianLink` và màn duyệt chưa có tham chiếu xác minh; audit duyệt hiện có không thay căn cứ xác minh mới | T106: xác nhận tối thiểu khi APPROVE; đối chứng sai vai/thiếu tham chiếu, không lộ dữ liệu xác minh |
| F07 | missing / HIGH | FR-057; US7.10/11; NV-04 | `SampleActionRequest`/activity chưa có `completionPolicy`; giao bài chỉ xét quyền dùng hiện hành, chưa phân biệt bảo đảm với có thể gián đoạn | T107: phân loại lúc giao, kiểm toàn closure, báo lý do gián đoạn và giữ lịch sử |
| F08 | partial / HIGH | FR-017–024/053/054; SC-003; B16/B21/B63/B68/B90 | E2E homeroom chủ yếu roster/không tự phân công; US5.1 dùng roster thay chấm. Recovery browser chỉ có API giả. GV-03/HS-02 có flow expiry nhưng thiếu bộ đối chứng độc lập gói | T108: E2E theo đúng vai/flow, hành động thật và phiên riêng; không coi tag là coverage đạt |
| F09 | partial / HIGH | ràng buộc PDP; authorization §10–13; A1/B22/B57/B83 | Membership/teaching/guardian xét role/gói từ DB sau PDP profile; submit recovery không đi PDP. Các ngoại lệ đã ghi nhưng chưa là mọi hành động được quyết tại Keycloak | T109: quyết định đích và test đường thật trước đóng parent; không lấy fixture chưa READY làm lý do duy nhất giữ ngoại lệ |
| F10 | partial / MEDIUM | HTTP quy tắc cursor; A13 | `MembershipService.pageMemberships` dùng UUID anchor + workspace, không có actor trong API/cursor; không thể đáp ứng ràng buộc actor đã viết. Một số danh sách teaching cũng dùng anchor | T110: thống nhất binding actor/workspace/query, thử đổi actor và revoke mốc trên PostgreSQL thật; không quy kết có rò dữ liệu chỉ từ format cursor |
| F11 | contradicts / MEDIUM | T085; HTTP màn `/review`; B80 | `app/(session)/review/page.tsx` render panel khi có session, không kiểm profile review; fixture backend có @Profile nhưng không gate chính trang | T111: gate môi trường cho review theo contract, giữ các đường nghiệp vụ cần thiết; test production/review |
| F12 | partial / MEDIUM | plan quyền tối thiểu; A6/A7/B91 | Writer/provisioner dùng Admin REST; bootstrap còn cấp `uma_protection`. Secret tĩnh trong realm TEST là dữ kiện test, không phải bằng chứng có secret production bị lộ | T112: kiểm quyền service account thật và thu hẹp quyền không dùng; không bắt đổi toàn bộ sang Protection API khi không có căn cứ |
| F13 | partial / MEDIUM | FR-044; US8.8; NV-05 | Backend đã chặn thêm assignment khi expired, nhưng flow/giải thích cho trường hợp giáo viên cuối nghỉ chưa được khóa | T113: hướng dẫn xử lý, lý do bị chặn và E2E gia hạn rồi thay người; người cũ không hồi quyền |
| F14 | partial / MEDIUM | AS-007/013; FR-054; NV-02/07 | Chưa có quyết định thời hạn lưu trữ/xóa cụ thể của đơn vị vận hành, xác nhận loại hình hoạt động và hướng dẫn theo vai đã được người dùng thật nghiệm thu | T114: tiền đề pilot/hướng dẫn từ 22 flow; giữ T096/#98 cho đo với người thật, không tạo số liệu thay |

Ngoài các phát hiện còn mở, đã sửa lệch tài liệu về receipt/status/sample DTO, precondition của PATCH/guardian/DELETE, state domain so với READY, scope đính chính mẫu, copy-on-share và RLS đã nghỉ hưu. Không tạo task code chỉ để đổi tên class/migration cho giống snapshot cũ.

## Bao phủ spec và nhiệm vụ

| Nguồn yêu cầu | Task gốc | Hội tụ bổ sung | Trạng thái bằng chứng |
| --- | --- | --- | --- |
| FR-001–006; US1 | T023–033 | T108/T109 | Giữ bằng chứng gốc; thiếu flow operator recovery thật ở browser |
| FR-007–012; US2 | T034–042 | T102/T109/T112 | Ngoại lệ role/gói và activation schema chưa thể coi đạt mọi ràng buộc đích |
| FR-013–021; US3 | T043–053 | T104/T105/T108 | US3.9 bổ sung; không đổi bằng chứng US3.1–8 thành pass cho COMPLETED |
| FR-022–024; US4 | T054–062 | T106/T108 | US4.6 thiếu xác minh trong code; published-only vẫn giữ |
| FR-025–028; US6 | T069–074 | T102/T108 | Gói personal/center độc lập; schema đã kích hoạt cần chứng minh thêm |
| FR-029–041; US7 | T075–086 | T103/T107/T108/T110 | Copy-on-share đã có quyết định; notification tới người nhận và loại hoạt động còn thiếu |
| FR-042–047; US8 | T087–093 | T100/T101/T113 | #95 OPEN; test mới phải bắt deadline/license, không chỉ gói hết hạn |
| FR-048–054; US5/xuyên suốt | T013–022/T063–068/T094–099 | T102/T108–112/T114 | SC-003/005 cần đủ flow và source; chưa đủ điều kiện nghiệm thu chung |
| FR-055 | Không có trong 99 task gốc | T104/T105 | Chưa triển khai |
| FR-056 | Không có trong 99 task gốc | T106 | Chưa triển khai |
| FR-057 | Không có trong 99 task gốc | T107 | Chưa triển khai |
| SC-001/002/009 | T096 | T114 chỉ chuẩn bị hướng dẫn/tiền đề | Vẫn cần thời gian/số liệu của người thật; #98 OPEN |
| SC-003–008 | Các gate story và T094/T095/T099 | T100–113 theo nhánh | Coverage tài liệu không thay bằng chứng runtime |

Kiểm kê: 57 FR, 9 SC, 61 acceptance scenarios (56 gốc + 5 bổ sung), 22 flow theo vai; 99 task GitHub gốc + 15 task hội tụ chưa phát hành. Sau bổ sung, 57/57 FR có task tham chiếu (100% **bao phủ tài liệu**, không phải tỷ lệ hành vi đạt). 8 nhóm quyết định kỹ thuật được rà: BFF/session, PAP/PDP, fence/revision, tenant/Flyway, JPA/directory, resource/provenance, expiry/completion và tầng kiểm thử. 14 finding: 3 missing, 10 partial, 1 contradicts; 9 HIGH, 5 MEDIUM; không có phát hiện unrequested. Không có task mới không gắn yêu cầu hoặc luồng.

Phân tích artifact sau sửa: 66 định danh FR/SC, 114 task; 5 câu làm rõ đã trả lời, không còn câu hỏi nghiệp vụ chờ trong đợt này. Không có ID yêu cầu/task trùng hoặc task mới không có nguồn; Sổ B106 trùng số được giữ nguyên lịch sử và phân biệt bằng tiêu đề. Các khác biệt cố ý/đang thiếu đều có trạng thái và task, không được coi là đã hội tụ chỉ vì đã mô tả rõ.

## Xử lý các mục trong hai sổ cũ

“Có sửa trong code” dưới đây là kết quả đọc code/tài liệu, chưa phải rerun acceptance. Giữ nguyên nội dung lịch sử trong sổ; bảng này cho biết cần đọc nó theo quyết định nào hiện nay. Những note về giả định của test phải quay lại kiểm chứng hành vi, không lấy chính giả định làm authority.

| Mục sổ | Kết luận hiện hành / nơi xử lý |
| --- | --- |
| A1; B22/B57/B83 | Ngoại lệ SQL vẫn tồn tại, đúng phạm vi §10–13; F09/T109. Eligibility không nằm trong ngoại lệ role/gói |
| A2/A5/A8/A9/A10/A11/A14; B15/B17/B18/B25/B26 | Các nhận xét cũ đã được thay bởi page centers, exact+paging Organization, CI/browser production build, verifier hiện hành, comment Việt, hiển thị identity/lỗi, pipeline platform và login hai bước. Không mở lại theo mô tả cũ; giữ yêu cầu CI/test đúng SHA |
| A3; B21/B63/B68/B76 | CRUD/action mẫu đã có; vẫn thiếu flow và điều kiện completion thực tế, F01/F02/F08. Roster read không chứng minh grade/submit |
| A4/B91 | Baseline hiện `sslRequired=external`; bootstrap đặt logout URLs từ APP_ORIGIN; realm TEST đã có backchannel. Không dùng import template thô thay bootstrap. Chưa chứng nhận triển khai production thật |
| A6/A7 | F12/T112; giữ ranh giới TEST và production, không diễn giải secret TEST là secret production bị lộ |
| A12 | Lịch sử test-first không thể sửa ngược bằng commit mới. Task bổ sung cần contract được review và red do thiếu hành vi trước implementation |
| A13 | Keyset DB đã thay cắt trong bộ nhớ; actor binding vẫn thiếu, F10/T110 |
| B1–B5/B10–B14/B23/B24 | Lịch sử realm/helper/stack/test teardown, không dùng log cũ kết luận main hiện đỏ. Đã có helper identity-first và fixture/runtime convention mới; cổng hiện hành vẫn phải đọc kết quả riêng |
| B6 | Nhánh bundle dùng rootPolicyNames; nhánh Apply đơn còn phải giữ bất biến hình dạng descriptor. Không khẳng định defect mới khi chưa có descriptor phản chứng; rà lại trong T109 nếu đổi graph |
| B7/B8 | Accept giữ khóa workspace; confirm giữ khóa workspace + actor/key; InitialCredentialService có bù trừ delete user khi registrar lỗi. Expiry invitation được ép theo thời gian dù state còn PENDING. Những chữ ký 500 cũ chưa được tái hiện lại; crash/cleanup-failure không được coi tự đạt từ happy path |
| B9/B19/B65 | Migration cũ đã gộp baseline; raw constraints/recovery tests có tên và vị trí mới, xem ánh xạ dưới. Không tạo lại verifier/migration cũ |
| B16/B62/B64/B66/B67/B69/B70/B71/B72/B73 | Các đường thu hồi đã có contributor/fence/fault-injection; giữ các giới hạn quan sát từng tầng. F02/F08 vẫn yêu cầu hành động thật, share/license và đúng kết quả 200 rỗng/403 theo actor |
| B20/B27–B40/B42–B45/B48–B54/B56 | Giữ identity roster/assignment, mã lỗi, set semantics và tiền đề role theo code/contract hiện hành. Domain ACTIVE không đồng nghĩa READY; guardian REQUESTED/REJECTED không được suy chuyển REVOKED. B49 precondition và B51 view đã rõ; riêng B54 không ép deadline là thiếu FR-044, xử lý F01 |
| B41/B46/B47/B55/B58/B75/B77 | Bundle/coordinator/cascade cùng revision vẫn là thiết kế hiện hành; baseline tạo partition, không lấy seed JDBC thiếu partition làm tiền đề để nới quyền. F03/T102 cần chứng minh trên schema đã kích hoạt |
| B59/B74 | Đã phân biệt OperationReceipt, OperationView, StudentIssueView và SampleActionResult trong HTTP contract; không sửa sản phẩm để ép mọi DTO cùng hình dạng |
| B60/B61 | Fixture chỉ dựng tiền đề read side; việc đọc trạng thái REVOKED/không thấy link phụ thuộc actor. E2E duyệt/thu hồi và nhận/nộp/chấm phải đi đường thật, xem bản đồ flow |
| B78/B79/B82/B84/B85/B86/B87/B88/B89 | Catalog/action/provenance và thu hồi nguồn được đọc cùng quyết định #261 và C7; không tiếp tục yêu cầu RLS/FK xuyên schema. Scope CORRECT_SOURCE mẫu hiện là review tại nguồn, chưa có resource version.create. F01/F02/F03/F04/F07/F10 còn mở |
| B80/B81/B90/B104/B110 | Managers group đã được ensure trước áp graph; panel đã có controls/action. Chưa có nguồn notification bên nhận và gate review environment; capabilities chưa nối toàn UI không thay việc xét lại ở backend. F04/F08/F11 |
| B92–B103 | Quyết định JPA/schema/two-stream/copy-on-share đã ghi lại trong plan/research/data-model. F03/F04 là phần readiness còn thiếu; CLOSED #261 không thay test cutover |
| B105 | CourseScope.UPDATE và renewal bundle đã có sửa ở #295/#298; ba class đích tưởng tượng không được tạo lại. Khẳng định completion đã đủ license/deadline chưa đúng, F01/F02 |
| B106 (package)/B99/B100/B101 | Tra package web/service/repository và infrastructure hiện hành; không thêm wrapper để khớp tên task. B106 trùng số với mục US8 cuối sổ, dùng tiêu đề để phân biệt |
| B106 (US8 guard/UI) | T091 đã có guard; lỗi UI personal/rename đã vào `origin/main` qua PR #302 (`6be8371`), #94 CLOSED; không làm lại patch, cần kiểm chứng trên revision tích hợp |
| B107/B108/B109 | Cooldown và phân biệt EMAIL_TAKEN đã có; backend E2E đã chuyển E2EIT/RANDOM_PORT/RestTestClient. Cập nhật kiểu DTO/Testing trong thiết kế, không coi 429 hay fallback message là quyền mới |
| C1–C7 | Quyết định sửa lịch sử giữ để truy vết. C7 giữ license khi nhận đính chính; không chứng minh submit/grade/publish đã xét license ở F02 |
| NV-01–NV-07 | Bảng quyết định phía trên và 5 câu trả lời trong spec; nội dung chưa có implementation giữ task mở |

## Ánh xạ tên lịch sử sang artifact thật

| Tham chiếu cũ | Artifact hiện hành |
| --- | --- |
| Migration riêng V009–V035 cho feature, V018/V019/V020… | `backend/src/main/resources/db/migration/V001__system_baseline.sql` và `db/migration-tenant/V001__tenant_baseline.sql`; migration sau baseline không được sửa checksum |
| `CompletionPolicyProjector`, `SampleLearningActionService`, `SubscriptionPolicyProjector` | `verification/service/SampleActionService`, `ActivityPolicyProjector`, `ResultPolicyProjector`, hai teaching projector, `resources/ResourcePolicyProjector`, `subscriptions/SubscriptionPolicy` và `subscriptions/service/SubscriptionService` |
| `JdbcOidcSessionRegistry` | `identity/security/JpaOidcSessionRegistry` và repository correlation |
| Controller/service package gốc, `TeachingRepository` chung | `web/`, `service/`, Spring Data repositories/fragment theo module; giữ functional packages của authorization |
| `CapabilitiesController` dưới permission | `authorization/decision/CapabilitiesController` |
| Trang ở `frontend/src/app/centers`, `/personal`, `/platform`, `/review` | Các trang trong `frontend/src/app/(session)/…`, URL public không đổi |
| `subscription-state.tsx`, operation-status dưới features | `features/subscriptions/subscription-state.ts`, `lib/operation-status.ts`; không tạo bản sao `.tsx` |
| 11 backend E2E mang hậu tố IT | Ánh xạ E2EIT ở Sổ B109; test HTTP thật và JUnit tag xác định tầng, không đổi lại chỉ để khớp báo cáo cũ |

## Kiểm chứng của đợt này và bàn giao

- Đã đọc prerequisites của Spec Kit; feature hợp lệ tại `specs/001-multitenant-access`, không có extension hook; constitution template được bỏ qua đúng phạm vi.
- 64 đăng ký Playwright được gắn layer/flow/role, giữ nguyên tên, thứ tự, thân test và assertion: 56 `layer-e2e`, 5 `layer-ui`, 2 `layer-protocol`, 1 `layer-fixture`. 20/22 flow có tag; QL-06 và HS-03 chưa có test, QT-03 chỉ có UI giả. Có tag không có nghĩa flow đã đủ ca hoặc pass.
- TypeScript `npx tsc --noEmit` và `npm run lint`: đạt. `playwright test --list` chỉ xác minh đăng ký/phân loại, không được báo E2E thành công. Không chạy Maven/integration/native/browser nặng; đợt này không sửa backend hoặc thân test.
- Sau sửa tài liệu: đối chiếu định danh yêu cầu/task/flow, tham chiếu, trạng thái snapshot, SHA-256 nguồn và điều kiện append-only của converge. Báo kết quả thực ở bàn giao; không dựng số liệu SC-009.

Các kiểm tra cấu trúc trên đã đạt: FR-001–057 và T001–T114 liên tiếp/duy nhất, 61 scenarios, 5 câu trả lời, 22 mã flow; snapshot/hash khớp, prefix trước Phase 12 giữ nguyên từng byte, liên kết tương đối và code fence hợp lệ, bảng xử lý bao phủ B1–B110. Lệnh lọc `layer-e2e` + `role-parent` liệt kê đúng 5 ca trong 3 file; chưa chạy các ca đó trong phiên này. `git diff --check` đạt.

Task T100–T114 là backlog cục bộ **chưa có GitHub issue/native edge**, ghi riêng trong snapshot thay vì giả đã đồng bộ thành sub-issue. Khi được giao implementation, owner phải công bố phân công/đầu vào/gate tại parent và đồng bộ DAG theo AGENTS.md. Không tự đóng/reopen issue, push PR, merge, deploy hoặc chạy runner cutover trong đợt này.

Tiếp tục bằng `speckit-implement` trên các task được gate hiện hành cho phép, ghép `origin/main` có `6be8371` trước kiểm chứng liên quan (không làm lại PR #302), rồi chạy lại `speckit-converge`. Nghiệm thu cuối phải gồm task hội tụ và người dùng thật, không chỉ 99 checkbox lịch sử.

**Checkpoint Phase 12 (#323 / T102, 2026-11-12; chưa merge):** runner đã được sửa chỉ prepare, không tự công bố `workspace.schema_name`. Đường kích hoạt rỗng khóa workspace và fail-closed khi còn dữ liệu cũ trong bất kỳ bảng tenant-scoped nào; regression native IT chờ CI. Phần sao chép dữ liệu từ public, context service còn dùng dữ liệu hệ thống, fan-out thông báo xuyên hai schema và toàn bộ E2E runtime vẫn **CHƯA XONG**. Hàng F03 ở trên là phát hiện tại thời điểm audit, không phải trạng thái mới của `main` sau PR này.

**Checkpoint T109/F09 trên PR #323 (chưa merge):** §15 `contracts/authorization.md` chốt giới hạn ngoại lệ SQL của role/gói/recovery với bảng route + ACCOUNT eligibility fence + workspace lock, giữ khác biệt never-period/expired/PERSONAL và không nới sang sample action/result/PDP. Chưa có native positive/negative E2E đầy đủ từ mutation thực cho tất cả nhánh; không tick T109 và không thay test bằng kết luận tài liệu.
