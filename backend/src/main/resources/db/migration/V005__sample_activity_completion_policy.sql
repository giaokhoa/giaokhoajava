-- FR-057: dữ kiện bài giao rõ chính sách gián đoạn; dòng lịch sử mặc định INTERRUPTIBLE.
-- Bổ sung migration mới, không sửa baseline đã được áp dụng.
ALTER TABLE sample_activity
    ADD COLUMN completion_policy varchar(16) NOT NULL DEFAULT 'INTERRUPTIBLE';
ALTER TABLE sample_activity
    ADD CONSTRAINT ck_sample_activity_completion_policy
    CHECK (completion_policy IN ('GUARANTEED', 'INTERRUPTIBLE'));
