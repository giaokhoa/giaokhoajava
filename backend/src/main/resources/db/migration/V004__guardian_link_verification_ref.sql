-- T106 / FR-056: tham chiếu tới việc xác minh người đại diện nằm ngoài hệ thống.
-- Migrations mới, không sửa baseline đã áp dụng; dữ kiện APPROVED lịch sử giữ nguyên.
ALTER TABLE guardian_link ADD COLUMN verification_ref varchar(160);
