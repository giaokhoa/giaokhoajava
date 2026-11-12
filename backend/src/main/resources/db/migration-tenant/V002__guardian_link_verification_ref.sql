-- T106 / FR-056: tenant schema đã được kích hoạt cũng lưu căn cứ xác minh tối thiểu.
ALTER TABLE guardian_link ADD COLUMN verification_ref varchar(160);
