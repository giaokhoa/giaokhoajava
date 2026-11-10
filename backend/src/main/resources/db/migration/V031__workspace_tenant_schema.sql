-- Registry schema tenant của workspace (đợt di trú schema-per-tenant, Wave 0):
-- workspace đã được cấp schema riêng thì cột này giữ đúng tên schema đó
-- (t_ + UUID bỏ dấu gạch, suy ra tất định từ id của workspace); NULL nghĩa là
-- workspace chưa có schema riêng và được phân giải về schema hệ thống `public`
-- như trước đợt di trú. Cột chỉ do TenantSchemaProvisioner ghi sau khi dòng
-- migration tenant đã chạy xong trong schema đó.
ALTER TABLE workspace ADD COLUMN schema_name VARCHAR(63);
