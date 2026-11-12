# Production readiness — findings audit Spring Boot / Next.js / Keycloak

- **Ngày kiểm kê:** 2026-11-12
- **Nguồn audit:** `origin/main` tại `6be8371b2f56d44ebe280f4b6df2f8d40a325e64`, mã nguồn + tài liệu + pipeline GitHub Actions
- **Issue theo dõi:** [#304 — Production readiness backlog](https://github.com/giaokhoa/giaokhoa/issues/304)
- **Trạng thái:** BACKLOG — chưa triển khai findings, chưa phải gate blocking của [feature #2](https://github.com/giaokhoa/giaokhoa/issues/2)

## Phạm vi, giới hạn và cách đọc

Kiểm kê so với một số dự án OSS cùng/gần stack: [YAS](https://github.com/nashtech-garage/yas) (Spring Boot + Next.js + Keycloak), [JHipster](https://github.com/jhipster/generator-jhipster) (Spring Boot + Keycloak; không mặc định Next.js) và [spring-microservices-otel-k8s](https://github.com/ricsanfre/spring-microservices-otel-k8s) (tham khảo observability). **Không lấy lượng sao GitHub, số service hay số công nghệ làm thước đo sẵn sàng production.** Không có đề xuất thêm Kafka, Redis, Kubernetes hay tách microservice lúc này.

Đây là **audit tĩnh** theo nội dung được commit, **không phải pentest, kiểm tra máy production, load test hoặc xác nhận cấu hình tại Cloudflare/Dokploy**. Nhãn **“không thấy trong repo” không chứng minh tính năng không tồn tại ngoài repo**: khi xử lý phải xác minh dashboard/cloud, công cụ backup và môi trường thực trước. Ngày/commit ở trên là snapshot, không đại diện tự động cho mọi lần deploy sau này.

## Điểm mạnh đã triển khai — không mở issue trùng

- Spring Boot 4.1.2 (Java 25), Next.js 16.4.5, Keycloak 26.8.1, PostgreSQL 18.x và Docker multi-stage.
- OIDC Authorization Code, Spring Session JDBC, cookie HttpOnly/SameSite và CSRF; frontend không phải tự giữ bearer token.
- Keycloak native authorization/PDP, policy nguồn, revision fence và cơ chế fail-closed; tách tenant bằng schema và role runtime bị giới hạn, tách role migration Flyway.
- Test unit, integration PostgreSQL/Keycloak thật, Playwright browser real login/old cookie, native Keycloak bootstrap và OIDC back-channel logout; CI và Verification có quality gates.
- Có Docker Compose production, Caddy reverse proxy, Cloudflare Tunnel/Dokploy, `/actuator/health`, MDC `traceId`, tài liệu chuẩn bị deploy và recovery account.

Các ưu điểm này **không thay thế** yêu cầu về backup, giám sát và khôi phục sự cố.

## Danh sách finding và mức ưu tiên

| ID | Mức | Finding (trạng thái tại snapshot) | Bằng chứng chính | Xác minh và tiêu chí đóng |
| --- | --- | --- | --- | --- |
| **F01** | **P0** | Chưa có backup/restore **production** được tài liệu hóa, tự động và thử phục hồi. Volume Docker không phải backup. | `specs/001-multitenant-access/quickstart.md` §6 **“Backup — chỉ DEV/TEST”** ghi rõ không có công cụ backup tự động/quy trình production; `infra/compose.prod.yaml` khai báo `prod_app_data`, `prod_keycloak_data` | Chốt RPO/RTO; backup **cả app DB và Keycloak DB**, mã hóa, off-site/off-host, retention, giám sát backup; **thử restore cô lập** với xác nhận login/tenancy/Flyway. Xác minh trước xem hạ tầng bên ngoài đã backup hay chưa. |
| **F02** | **P0** | Token lời mời nằm trong URL của route chấp nhận, nguy cơ access log. **Finding đã tồn tại**, không tạo bug trùng. | `specs/001-multitenant-access/validation/security.md` §6 (6/7 đạt, 1 phần); `backend/src/main/java/com/giaokhoa/tenancy/web/MembershipController.java` route `/api/invitations/{token}/accept` | Theo dõi cùng [#97](https://github.com/giaokhoa/giaokhoa/issues/97): chuyển token khỏi URL theo hợp đồng, sửa frontend/idempotency/tests, xác minh TTL/single-use, logs/redaction và compatibility. Không đóng #97 chỉ nhờ issue #304. |
| **F03** | **P1** | Observability/alerting chưa thành một hệ vận hành rõ ràng. | `backend/src/main/resources/application.yml` hiện chỉ expose `/actuator/health` + log MDC `traceId`; không thấy config Prometheus/OTel/Grafana/alerting trong repo | Định nghĩa SLI/SLO cho login, API/PDP, projection sync; instrument latency p95/p99, tỷ lệ lỗi, DB pool, queue depth, tuổi operation `PENDING/FAILED`, Keycloak unavailable; dashboards + alert thresholds + runbook + thử alert. Chú ý scrub PII/token. |
| **F04** | **P1** | Hardening/rollback production chưa đủ bằng chứng. | `infra/compose.prod.yaml` healthcheck cho app-db/keycloak-db/Keycloak, chưa có riêng backend/frontend; không thấy `restart`, `deploy.resources`/resource limits; `infra/DEPLOY.md` chỉ nêu redeploy | Xác minh runtime Dokploy trước. Bổ sung startup/readiness/liveness cho app, restart/recovery policy, memory/CPU guard theo máy thực, smoke-test sau deploy, rollback + migration compatibility, runbook khi auth/tunnel/db lỗi. Không vội yêu cầu HA đa node khi chưa có SLO. |
| **F05** | **P1** | Lớp bảo vệ edge và endpoint nhạy cảm cần rà. | `infra/gateway/Caddyfile.prod` không có rate limit; `infra/DEPLOY.md` ghi Keycloak Admin console public; CSP Next đang có `script-src 'unsafe-inline'` (được giải thích bởi RSC) | Xác minh **Cloudflare WAF/rate limiting có cấu hình ngoài repo hay chưa**. Thiết kế giới hạn login, invite, recovery, verify, capability (theo actor/IP); đo 429/false positive; giảm exposed admin khi phù hợp; cân nhắc CSP nonce/report-only, không phá OIDC/logout/back-channel. |
| **F06** | **P1** | Thiếu bằng chứng kiểm thử tải và năng lực hệ thống. | Không có k6/Gatling/JMeter/Locust hoặc báo cáo load/soak trong tracked repo/CI; CI chủ yếu khẳng định correctness | Định nghĩa workload theo số học sinh/giáo viên/center, 10/100/500 concurrent sessions theo capacity, rate PDP, queue lag, DB contention, policy freshness. Thêm load + soak reproducible và báo cáo bottleneck; chỉ chốt SLO sau khi đo. |
| **F07** | **P2** | Supply-chain checks chưa đủ: dependency/container/secret scanning và SBOM. | `.github/workflows/ci.yml`, `verification.yml`: Maven quality gates, lint/test/E2E; không thấy CodeQL/OSV/Trivy/gitleaks/SBOM CI. Gh đã cảnh báo 1 Dependabot moderate (cần kiểm tra lại khi xử lý). | Chọn SAST/SCA, image scan, secret scan, phát hành SBOM, exception có thời hạn, triage CVE có owner; policy không leak secrets/false positives. Xác nhận tính năng GH Security/Dependabot đang bật bên ngoài workflow. |
| **F08** | **P2** | HTTP contract rất chi tiết nhưng chưa có OpenAPI kiểm tra drift tự động. | `specs/001-multitenant-access/contracts/http-api.md` tự ghi là hợp đồng thiết kế; chưa thấy springdoc/OpenAPI generator | Sinh OpenAPI từ endpoint thực hoặc schema contract đã duyệt; kiểm tra diff breaking changes qua CI, DTO/status/Idempotency/CSRF; không public policy/token/internal DTO. |
| **F09** | **P2** | Vệ sinh Compose và tài liệu runtime chưa đồng bộ đầy đủ. | `infra/compose.prod.yaml` vẫn có service `mailpit`; `infra/DEPLOY.md` nói production gửi email qua SMTP của Resend, Mailpit dành DEV/local | Xác minh Mailpit thực tế có còn dùng; bỏ khỏi production nếu thừa, rà retention/logs/Docker digest pinning, đảm bảo không đổi luồng gửi thư hoặc recovery. |

### Thứ tự xử lý đề xuất

1. **Trước khi giữ dữ liệu học sinh/người dùng thật quan trọng:** F01 và F02 (F02 có sẵn việc theo dõi trong #97).
2. **Trước khi tăng số trung tâm/người dùng:** F03, F04, F05, F06. Quan sát và khôi phục trước khi tối ưu/tách service.
3. **Nâng chất lượng chu kỳ release:** F07, F08, F09. Có thể làm song song với các mục vận hành nếu không ảnh hưởng nghiệm thu hiện tại.

### Cách tách việc sau này

- Một **finding = một issue/PR con** khi bắt đầu làm; link ngược #304, ghi owner, quyết định thiết kế và rollback nếu cần. Finding F02 liên kết #97, không tạo implementation trùng.
- Mọi vấn đề nêu từ *thiếu cấu hình trong repo* phải có bước kiểm chứng tình trạng thực tế ngoài repo trước khi đổi. Khi thực hiện F01 phải có **restore test pass**; khi thực hiện F03/F04 phải có cảnh báo và chạy thử giả lập; khi thực hiện F06 phải có số liệu và quy mô máy rõ ràng.
- Tài liệu này là **backlog độc lập**, không tick completion của `tasks.md` hay đóng tự động feature #2/#95–#101. Chỉ update các finding khi có PR và bằng chứng thực tế.
