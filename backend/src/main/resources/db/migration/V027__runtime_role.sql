-- Runtime role cho datasource của ứng dụng, theo data-model.md (mục "Cách ly,
-- khóa và chỉ mục", điểm 1 và 6): RLS chỉ có hiệu lực khi kết nối không phải
-- owner, không superuser và không có BYPASSRLS (PostgreSQL 18, §5.9 Row
-- Security Policies: superuser/BYPASSRLS luôn bypass, owner bypass trừ khi
-- FORCE ROW LEVEL SECURITY). Trước migration này repo chỉ mô tả role
-- "giaokhoa_runtime" ở .env.example mà không artifact nào tạo nó, nên mọi
-- môi trường repo định nghĩa đều chạy datasource bằng role owner/bootstrap
-- và RLS bị bypass toàn bộ.
--
-- Role cố ý NOLOGIN và không đặt mật khẩu: migration không giữ credential;
-- vận hành cấp LOGIN và mật khẩu riêng cho role khi triển khai (xem
-- backend/README.md). Role là đối tượng toàn cluster nên tạo có điều kiện
-- tồn tại, để migration an toàn khi nhiều database/schema trên cùng cluster
-- cùng chạy Flyway. Toàn bộ việc cấp quyền nằm trong một DO block vì GRANT
-- ON SCHEMA và ALTER DEFAULT PRIVILEGES chỉ nhận tên schema tường minh,
-- không nhận biểu thức current_schema().
DO $$
DECLARE
    target_schema TEXT := current_schema();
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'giaokhoa_runtime') THEN
        CREATE ROLE giaokhoa_runtime NOSUPERUSER NOBYPASSRLS NOLOGIN;
    END IF;

    -- Chỉ cấp DML trên các bảng của schema migration hiện hành, không cấp
    -- DDL hay quyền owner: runtime role chỉ đọc/ghi dữ kiện ứng dụng.
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO giaokhoa_runtime', target_schema);
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO giaokhoa_runtime',
        target_schema);
    EXECUTE format(
        'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO giaokhoa_runtime',
        target_schema);

    -- Bảng do migration sau tạo trong cùng schema tự có sẵn quyền cho
    -- runtime role. Default privileges gắn với chính role đang chạy
    -- migration nên không phụ thuộc tên migration role của từng môi trường.
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        || 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO giaokhoa_runtime',
        target_schema);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        || 'GRANT USAGE, SELECT ON SEQUENCES TO giaokhoa_runtime',
        target_schema);
END
$$;
