# Giáo khoa

Nền tảng quản lý học tập đa tenant: backend Spring Boot và frontend Next.js, dùng PostgreSQL và Keycloak.

## Điều kiện

- JDK 25
- Node 24 (ghim trong `.nvmrc` ở gốc repo và `engines` của `frontend/package.json`)

## Bắt đầu

Cách khuyến nghị là dùng devcontainer đã cấu hình sẵn (JDK 25, Node 24): mở repo trong VS Code và chọn "Reopen in Container". Cấu hình nằm ở `.devcontainer/devcontainer.json`.

Tạo file môi trường local từ mẫu:

```sh
cp .env.example .env
```

## Kiểm chứng

Backend:

```sh
./backend/mvnw -B -ntp -f backend/pom.xml clean verify
```

Frontend:

```sh
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build
```

## Tài liệu

- [Hướng dẫn chạy và nghiệm thu bộ khung](specs/001-multitenant-access/quickstart.md)
- [Kiểm tra chất lượng backend](backend/README.md)
