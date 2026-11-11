# T099 — Tổng kết chạy quickstart thật trên môi trường test sạch

## Phạm vi và môi trường chạy

- Việc: chạy toàn bộ lệnh trong `specs/001-multitenant-access/quickstart.md` trên một
  môi trường test sạch, ghi từng command và exit status nguyên văn, không suy diễn kết
  quả từ sự hiện diện của test hay từ lượt chạy của môi trường khác.
- Mã đã kiểm chứng: nhánh `docs/101-quickstart-verification` tại commit nền
  `da8ba0719fa99b8f878c5a440c1a8845c8fe7a83` (main tại thời điểm bắt đầu việc; main đã
  tiến tiếp trong lúc chạy — kết quả dưới đây chỉ khẳng định cho đúng commit này).
- Ngày chạy: 2026-11-11 (giờ UTC trong từng log).
- Môi trường: máy sandbox **không chạy được Docker** — binary Docker có mặt nhưng tạo
  container luôn gãy ở tầng syscall (`setns` trả `EPERM`; bằng chứng nguyên văn ở CMD1
  và kết luận đã ghi trong `~/workspace/tools/test-stack/RUNBOOK.md`). Vì vậy stack TEST
  được dựng **native** đúng tương đương `infra/compose.test.yaml` + bước "Start isolated
  TEST services" của `.github/workflows/ci.yml`, trên **làn 3**: PostgreSQL 18.6 cổng
  15434, Keycloak 26.8.1 cổng 18083, realm `giaokhoa-it` import từ
  `infra/keycloak/test-realm.json`. Các điểm lệch native so với compose (một server PG
  gộp hai database, Keycloak chạy tarball, Mailpit không chạy native) đã được khai
  trong RUNBOOK mục 6 và được nhắc lại ở mục "Giới hạn còn lại" dưới đây.
- Log nguyên văn từng lệnh: thư mục `~/workspace/logs-polish-101/` trên máy chạy
  (cmd01…cmd26). Bảng dưới đây trích đúng exit status đã ghi trong các log đó.
- Trong phiên chạy, máy bị thay mới hai lần (stack ở `/var/tmp` mất, dựng lại ở CMD10–
  CMD13) và bốn đội agent liên tiếp bị sự cố hạ tầng nền tảng (pool-timeout, daemon
  restart); các lệnh bị ngắt giữa chừng được ghi rõ là bị ngắt, không ghi nhận exit
  status thay cho chúng.

## Kết quả theo lệnh của quickstart

### Mục 2 — Cấu hình: cấp LOGIN cho role runtime

| Lệnh (nguyên văn ý) | Kết quả | Bằng chứng |
| --- | --- | --- |
| `CREATE ROLE giaokhoa_runtime LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS` + `SELECT 1` bằng chính role đó (bản native của bước `docker compose exec psql` ở stack TEST, mục 4) | Lần đầu gãy vì `psql` thiếu `LD_LIBRARY_PATH` (exit 127, lỗi `libpq.so.5` nguyên văn trong log); chạy lại với `LD_LIBRARY_PATH` đúng: **exit 0 cả ba vế** | CMD3 (127) → CMD3b (0); dựng lại sau thay máy: CMD11 (0) |

### Mục 3 — Khởi động DEV

| Lệnh | Kết quả | Bằng chứng |
| --- | --- | --- |
| `docker compose -f .devcontainer/compose.yml -f infra/compose.dev.yaml up -d …` | **Không chạy được trong môi trường này**: `docker: unknown command: docker compose` / `unknown shorthand flag: 'p'`, exit 125 và 1 | CMD1 |
| `bash infra/keycloak/bootstrap.sh`, `./backend/mvnw … spring-boot:run`, `npm --prefix frontend run dev` | **Không chạy** — phụ thuộc stack DEV bằng Docker (không tồn tại ở đây). Không ghi nhận kết quả thay thế từ stack TEST | — |

### Mục 4 — Stack TEST

| Lệnh | Kết quả | Bằng chứng |
| --- | --- | --- |
| `docker compose -p giaokhoa-test -f infra/compose.test.yaml up -d` | **Không chạy được** (Docker, như trên) → thay bằng dựng native làn 3: initdb + start PostgreSQL **exit 0**; tạo role/database **exit 0**; copy + start Keycloak **exit 0** (lần start đầu KC lên chậm, kiểm tra HTTP tại chỗ trả 000, kcadm đăng nhập thành công ở bước sau xác nhận KC đã lên) | CMD1; CMD2, CMD3/CMD3b, CMD4, CMD5; sau thay máy: CMD11, CMD12 |
| Cấp role cho service account `giaokhoa-it-fixtures` (đúng bước CI) | kcadm add-roles từng role **exit 0**; kiểm chứng qua Admin REST: đủ **11 role** trên client `realm-management` (`manage-authorization, manage-organizations, manage-users, query-clients, query-groups, query-organizations, query-users, view-authorization, view-clients, view-organizations, view-users`) — gồm 8 role CI cấp ở `ci.yml:112-119` cộng `view-users`/`query-users` theo finding của gate US8 | CMD6, CMD7 (5 role ở lượt đầu), CMD13 (7 role sau dựng lại), CMD19 (bổ sung, mọi add exit 0), CMD19b (REST: 11 role) |
| `./backend/mvnw -B -ntp -f backend/pom.xml -Pintegration verify` | **exit 1 — BUILD FAILURE, nhưng không phải lỗi sản phẩm**: Tests run: 325, Errors: 317, Skipped: 8; mọi test lỗi ngay ở bước chuẩn bị với cùng một nguyên nhân `IllegalArgumentException: Integration tests require the dedicated local TEST database and issuer` tại `IntegrationEnvironment.validateTargets` (`IntegrationEnvironment.java:324`). Hàm này chỉ cho phép đúng hai cặp đích TEST đã khai cứng — làn 1 (DB cổng 15432, issuer cổng 18081) và làn 2 (15433/18082); làn 3 (15434/18083) bị từ chối theo thiết kế của harness. Theo luật làn, đội này không chạy chéo sang làn 1/2 (đội gate đang giữ) → cổng integration **không lấy được bằng chứng trên làn 3**; bằng chứng integration tại commit này thuộc CI và các làn đã khai | CMD22 |

### Mục 4 — Cổng chất lượng đầy đủ (đúng danh sách cuối mục 4)

| Lệnh | Kết quả | Bằng chứng |
| --- | --- | --- |
| `./backend/mvnw -B -ntp -f backend/pom.xml spotless:apply` | **exit 0 — BUILD SUCCESS** (1:27) | CMD14 |
| `./backend/mvnw -B -ntp -f backend/pom.xml clean verify` | Lượt 1: **bị OOM-kill, exit 137**, tại pha PMD — unit test đã qua 252/252 trước đó. Lượt 2 (giới hạn heap Maven 1024m): **bị OOM-kill, exit 137**, tại pha SpotBugs — unit test 252/252. Lượt 3: bị ngắt bởi sự cố hạ tầng (đội chạy chết), không có exit status. Lượt 4 (chờ cửa sổ máy yên, heap cap 1024m): **exit 0 — BUILD SUCCESS**, unit test 252/252, PMD và SpotBugs qua hết | CMD15, CMD16, CMD17, CMD21 |
| `npm --prefix frontend ci` | Lượt 1 (CMD18): bị ngắt bởi sự cố hạ tầng, không ghi được exit status (log chỉ còn cảnh báo deprecated của npm). Lượt 2 (CMD20): **bị OOM-kill, exit 137**. Lượt 3 (chờ cửa sổ máy yên 1020 giây, `NODE_OPTIONS` cap 1024m): **exit 0** | CMD18, CMD20, CMD20r |
| `npm --prefix frontend run lint` | **exit 0** — eslint chạy sạch, không lỗi | CMD23 |
| `npm --prefix frontend run test` | **exit 0** — 14 test files, **117 tests, tất cả pass** | CMD24 |
| `npm --prefix frontend run build` | **exit 0** — Next build thành công (`Compiled successfully`, 10/10 static pages) | CMD25 |
| `npm --prefix frontend run test:e2e` | **exit 1** — 56 test: **5 passed, 34 skipped, 17 failed**. Cả 17 ca failed đều gãy ở tiền đề môi trường, không ca nào fail vì khẳng định hành vi sản phẩm: 15 ca cần biến `NATIVE_GATE_APP_ORIGIN` (môi trường native gate, không thuộc lượt này → chờ gate); 2 ca bị guard `TestEnvironment` chặn vì `TEST_LANES` trong `frontend/tests/e2e/support/test-environment.ts` cũng chỉ khai làn 1 và làn 2 — làn 3 không được phép theo thiết kế. 34 ca skipped do thiếu tiền đề tương ứng | CMD26 |
| `bash infra/keycloak/verify-native.sh` | **exit 1** — script cần Docker API (`failed to connect to the docker API at unix:///var/run/docker.sock`), không tồn tại trong môi trường này. Bằng chứng native gate tương đương thuộc gate #88 → **chờ gate** (xem mục dưới) | CMD8 |
| `node infra/keycloak/verify-oidc-logout.mjs` | **exit 1 khi thử với env làn 3** — và đây là hành vi đúng thiết kế: file tự khai "làn 1 duy nhất" ở comment đầu và ghim cứng cặp cổng làn 1 (`assert.equal` ở dòng 15–17: `TEST_KEYCLOAK_BASE_URL` phải là `http://127.0.0.1:18081`, DB phải là cổng 15432). Đội này ở làn 3, theo luật làn không chạy chéo sang làn 1 → ghi nhận là **không chạy**, không phải defect | CMD9 + đọc trực tiếp file |

Ghi chú về dạng lệnh đã chạy: trên máy này, mọi lệnh `mvnw` bắt buộc kèm
`-s <maven-settings.xml của làn>` (Maven phải đi qua egress proxy — RUNBOOK mục 2) và
biến `JAVA_TOOL_OPTIONS`/`MAVEN_OPTS` chứa `-Djava.net.preferIPv4Stack=true` (bẫy mạng
của sandbox — RUNBOOK mục 2). Thân lệnh giữ nguyên văn quickstart; hai bổ sung trên là
cấu hình môi trường đã được các đội trước ghi ngay trong tiêu đề log.

## Mục gate

- **Gate #88 (US7, native gate và nghiệm thu US7.1–US7.9)**: vòng 3 đã kết luận
  **ĐẠT** trên main `59137da4` (kiểm chứng ngày 2026-11-11): US7.1–US7.9 đạt đủ —
  `ResourceAccessIT` 5/5, `ResourceSharingIT` 6/6, `RevocationMatrixIT` 6/6 ở lượt
  chạy lại đơn độc (lượt gộp có ca timeout ở bước dựng dữ kiện do máy dùng chung
  quá tải), unit 252/252. Issue #88 đã đóng kèm comment bằng chứng id `6796686919`.
  Bằng chứng native gate tương đương mà CMD8 chờ thuộc kết luận này. 15 ca e2e cần
  `NATIVE_GATE_APP_ORIGIN` ở CMD26 thuộc môi trường native gate của gate này; kết
  luận gate ở trên là bằng chứng nghiệm thu US7 tương ứng, không phải kết quả chạy
  lại các ca e2e đó trong lượt làn 3 này. Sau kết luận gate, main đã tiến tới
  `89fbef1a` qua PR #287 và PR #291 (CI của cả hai đều 8/8).
- **Gate #95 (US8)**: đội gate đã kết luận ĐẠT có điều kiện (7/7 scenario) trên main
  `da8ba071`; việc đóng issue còn chờ land chuỗi sửa gia hạn gói và PR test US8
  liên quan — tính đến ngày kiểm chứng 2026-11-11 vẫn **chờ gate #95 hoàn tất
  đóng**.

## Giới hạn còn lại

1. **Docker không tồn tại trong sandbox** (daemon không tạo được container vì
   `setns` bị chặn): mọi lệnh `docker compose` nguyên văn của quickstart (mục 3 DEV và
   bước dựng stack ở mục 4) đều không thực thi được tại đây; stack TEST được thay
   bằng bản native tương đương theo RUNBOOK. Trên CI GitHub và máy dev thường, các
   lệnh này chạy bằng Docker thật — giới hạn này là của môi trường kiểm chứng này,
   không phải của repository.
2. **Bộ nhớ máy dùng chung nhỏ** (khoảng 8 GB cho nhiều đội + 3 làn stack): `clean
   verify` bị OOM-kill hai lượt ở pha PMD/SpotBugs dù unit test đã xanh 252/252, và
   `npm ci` bị OOM-kill một lượt. Khi chờ được cửa sổ máy yên (không đội nào chạy
   lượt nặng), cả hai lệnh đều chạy lại đạt exit 0 — các lần OOM-kill là giới hạn của
   môi trường dùng chung, không phải lỗi code hay lỗi test, và kết quả từng lượt đã
   ghi riêng ở bảng trên thay vì chỉ ghi kết quả cuối.
3. **Harness test chỉ khai hai làn TEST**: cả `IntegrationEnvironment` (Java) lẫn
   `TEST_LANES` (frontend) đều chỉ cho phép làn 1 và làn 2. Làn 3 do đội này dựng
   thêm để tránh tranh làn với các đội gate không nằm trong allowlist, nên hai cổng
   `-Pintegration verify` và phần lớn `test:e2e` không lấy được bằng chứng trọn vẹn
   trên làn 3 (chi tiết ở bảng). Muốn kiểm chứng hai cổng này tại đây phải dùng làn
   1 hoặc làn 2 khi các đội gate trả làn, hoặc bổ sung làn vào allowlist bằng một
   thay đổi test riêng — nằm ngoài phạm vi việc này.
4. **Mailpit không chạy native** (RUNBOOK mục 6 lệch 2): realm TEST vẫn trỏ SMTP tới
   host `test-mailpit` không phân giải được ở bản native; luồng gửi mail thật không
   thuộc các lệnh đã chạy ở đây.
5. **`verify-oidc-logout.mjs` chỉ chạy trên làn 1 theo thiết kế** — muốn có bằng
   chứng của script này phải chạy trên làn 1 (đội khác đang giữ), không thuộc lượt
   kiểm chứng làn 3 này.
6. Kết quả chỉ khẳng định cho commit nền `da8ba071` và cấu hình native tương đương;
   không khẳng định cho main hiện tại tại thời điểm đọc summary.

## Cập nhật `tasks.md`

Cập nhật ngày 2026-11-11, sau khi gate #88 vòng 3 có kết luận — vẫn không tick
checkbox nào:

- **T099 vẫn không tick**: một trong hai điều kiện thiếu trước đây đã được bổ
  sung — bằng chứng native gate nay có kết luận gate #88 vòng 3 ĐẠT (mục "Mục
  gate" ở trên). Điều kiện còn lại vẫn chưa đủ: một phần lệnh quickstart (Docker)
  không thực thi được trong môi trường kiểm chứng này (CMD1 và các lệnh
  `docker compose` ở mục 3, mục 4), mới chỉ có bản native tương đương. Tick khi
  phần lệnh nguyên văn chưa từng chạy sẽ biến giới hạn môi trường thành trạng
  thái đạt giả. Điều kiện còn thiếu để tick: chạy các lệnh Docker nguyên văn
  trên máy có Docker, hoặc đối chiếu run `verification.yml` trên GitHub — do
  điều phối quyết.
- Các task khác (T094–T098) không thuộc bằng chứng của đội này nên không đụng vào,
  kể cả khi file đích của chúng đã có mặt trong repository.

## Bàn giao

- Không publish, không deploy, không merge: PR của đợt này ở dạng draft, chỉ chứa
  file summary này.
- Bước tiếp theo cho điều phối: bằng chứng native gate của gate #88 đã bổ sung ở
  cập nhật ngày 2026-11-11; T099 chỉ còn chờ điều kiện lệnh Docker nguyên văn —
  chạy lại trên máy có Docker hoặc đối chiếu run CI `verification.yml` trên
  GitHub, đạt thì tick T099.
