-- Kiểm chứng sau mỗi lần migrate: role runtime (giaokhoa_runtime) phải có đủ quyền
-- SELECT/INSERT/UPDATE/DELETE trên mọi bảng của schema vừa migrate, trừ
-- flyway_schema_history chỉ được đọc. Thiếu grants là lỗi hạ tầng phải lộ ngay tại
-- migrate thay vì thành lỗi quyền khi ứng dụng chạy thật. Schema đích lấy từ
-- current_schema() (schema mà Flyway đang migrate) nên cùng một bản kiểm chứng áp cho
-- cả dòng hệ thống (public) và dòng tenant (schema của workspace). Bỏ qua khi role
-- chưa tồn tại (môi trường chỉ chạy migration thuần tuý).
DO $$
DECLARE
    target_schema text := current_schema();
    missing text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'giaokhoa_runtime') THEN
        SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
          INTO missing
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = target_schema
           AND c.relkind = 'r'
           AND c.relname <> 'flyway_schema_history'
           AND NOT (
                has_table_privilege('giaokhoa_runtime', c.oid, 'SELECT')
            AND has_table_privilege('giaokhoa_runtime', c.oid, 'INSERT')
            AND has_table_privilege('giaokhoa_runtime', c.oid, 'UPDATE')
            AND has_table_privilege('giaokhoa_runtime', c.oid, 'DELETE'));
        IF missing IS NOT NULL THEN
            RAISE EXCEPTION 'Runtime role is missing DML grants on tables: %', missing;
        END IF;
        IF EXISTS (
                SELECT 1
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = target_schema
                   AND c.relname = 'flyway_schema_history'
                   AND (has_table_privilege('giaokhoa_runtime', c.oid, 'INSERT')
                     OR has_table_privilege('giaokhoa_runtime', c.oid, 'UPDATE')
                     OR has_table_privilege('giaokhoa_runtime', c.oid, 'DELETE'))) THEN
            RAISE EXCEPTION 'Runtime role must not write to flyway_schema_history';
        END IF;
    END IF;
END
$$;
