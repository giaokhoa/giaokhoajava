# Triển khai production: Dokploy + Cloudflare Tunnel

Stack production nằm ở `infra/compose.prod.yaml` (service `app-db`,
`keycloak-db`, `keycloak`, `backend`, `frontend`, `gateway`, `mailpit`),
đóng gói bằng `backend/Dockerfile` và `frontend/Dockerfile`, gateway theo
`infra/gateway/Caddyfile.prod`. Luồng request:

Cloudflare Tunnel → `127.0.0.1:3000` (gateway, cổng host duy nhất của stack)
→ `giaokhoa.fuckr.net` và hai tenant test `trung-tam-1.fuckr.net`,
`trung-tam-2.fuckr.net` vào backend (đường `/api`, `/oauth2`, `/login`,
`/logout`, `/actuator`) và frontend; `auth.fuckr.net` vào Keycloak.
TLS kết thúc ở Cloudflare; trong stack chỉ có HTTP nội bộ.

## Tạo service trên Dokploy

1. Tạo Compose service mới, trỏ vào repo này, nhánh `main`, đường dẫn
   compose: `infra/compose.prod.yaml`. Dokploy build `backend` và
   `frontend` từ hai Dockerfile trong repo; các service còn lại dùng image
   đã pin phiên bản.
2. Đặt biến môi trường theo `.env.prod.example` trong phần Environment của
   service. Mọi giá trị `replace-with-...` phải được thay bằng secret thật
   của môi trường; compose từ chối khởi động nếu thiếu secret bắt buộc.
3. Cloudflare Tunnel: ingress của cả bốn hostname trỏ vào
   `http://localhost:3000`. Hostname `dokploy.fuckr.net` giữ nguyên trỏ vào
   panel của Dokploy, không đi qua stack này.

## Dựng lần đầu

Deploy toàn stack lần đầu: `backend` có thể chưa lên vì hai bước một lần
dưới đây chưa làm — đó là trạng thái bình thường, làm xong thì restart
riêng service `backend` trong Dokploy.

1. **Tạo LOGIN cho role runtime.** Baseline migration chỉ tạo
   `giaokhoa_runtime` ở dạng `NOLOGIN` khi role chưa tồn tại (xem
   `backend/README.md`, mục "Vai trò database"). Chạy một lần bằng psql vào
   `app-db` (qua SSH của server hoặc chức năng terminal của Dokploy), mật
   khẩu lấy đúng giá trị `SPRING_DATASOURCE_PASSWORD` đã đặt:

   ```sh
   # Nếu chạy trước lần migrate đầu tiên (role chưa hề tồn tại):
   psql -U giaokhoa -d giaokhoa -c \
     "CREATE ROLE giaokhoa_runtime LOGIN PASSWORD '<SPRING_DATASOURCE_PASSWORD>' NOSUPERUSER NOBYPASSRLS"
   # Nếu backend đã migrate rồi (role đã tồn tại ở dạng NOLOGIN):
   psql -U giaokhoa -d giaokhoa -c \
     "ALTER ROLE giaokhoa_runtime LOGIN PASSWORD '<SPRING_DATASOURCE_PASSWORD>'"
   ```

2. **Dựng realm Keycloak.** Từ một máy có `curl` và `jq` cùng bản sao của
   repo, chạy script bootstrap của repo với base URL công khai (TLS qua
   tunnel và gateway):

   ```sh
   KEYCLOAK_ADMIN_BASE_URL=https://auth.fuckr.net \
   KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin \
   KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD='<KC_BOOTSTRAP_ADMIN_PASSWORD>' \
   OIDC_CLIENT_SECRET='<OIDC_CLIENT_SECRET>' \
   KEYCLOAK_PROVISIONER_CLIENT_SECRET='<KEYCLOAK_PROVISIONER_CLIENT_SECRET>' \
   APP_ORIGIN=https://giaokhoa.fuckr.net \
   bash infra/keycloak/bootstrap.sh
   ```

   Script tạo realm `giaokhoa` ở trạng thái tắt, gắn secret và redirect
   theo `APP_ORIGIN`, gỡ quyền mặc định của Keycloak rồi mới bật realm;
   chạy lại an toàn (reconcile, giữ nguyên dữ liệu directory và policy đã có).

3. **Restart `backend`** trong Dokploy, mở `https://giaokhoa.fuckr.net` và
   đăng nhập thử: trình duyệt phải chuyển qua `https://auth.fuckr.net` rồi
   quay lại được.

4. **Cấu hình SMTP cho Keycloak.** Baseline để trống `smtpServer`: thư
   verify email và đặt lại mật khẩu do Keycloak gửi. Trong Admin console
   (`https://auth.fuckr.net`), Realm settings → Email: host `mailpit`,
   port `1025`, tắt TLS và authentication (Mailpit nội bộ hứng toàn bộ thư
   ở môi trường này; muốn gửi thư thật ra ngoài thì thay bằng SMTP ngoài và
   bỏ service `mailpit`).

## Cập nhật và lưu ý

- Cập nhật: merge vào `main` rồi Redeploy service trong Dokploy; image
  `backend`/`frontend` được build lại từ Dockerfile trên `main`.
- Backend nạp OIDC discovery qua issuer công khai
  `https://auth.fuckr.net/realms/giaokhoa` (đi vòng qua tunnel), nên tunnel
  phải thông khi backend khởi động; các lời gọi Admin/Protection API đi
  thẳng `http://keycloak:8080` trong mạng nội bộ.
- Admin console của Keycloak lộ tại `https://auth.fuckr.net` theo thiết kế
  hostname này: giữ `KC_BOOTSTRAP_ADMIN_PASSWORD` mạnh và chỉ dùng tài
  khoản bootstrap cho việc quản trị.
- Dữ liệu nằm ở hai volume `giaokhoa-prod_prod_app_data` và
  `giaokhoa-prod_prod_keycloak_data`; không chạy `down -v` trên stack này.
