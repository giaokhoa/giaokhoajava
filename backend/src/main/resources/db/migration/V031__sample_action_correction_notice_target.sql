-- Biên nhận của action CORRECT_SOURCE (T082) trỏ tới correction_notice vừa tạo,
-- theo đúng mẫu LIBRARY_ITEM của T080 và SHARE_GRANT của T081 (V029): catalog
-- target_type của sample_action_mutation thêm CORRECTION_NOTICE bằng migration
-- mới, không sửa V022/V029. Giá trị mới dài 17 ký tự nên cột target_type cũng
-- phải nới từ VARCHAR(16) lên VARCHAR(32) trong cùng migration; nới độ dài cột
-- không đổi giá trị nào đã lưu.
ALTER TABLE sample_action_mutation
    ALTER COLUMN target_type TYPE VARCHAR(32);

ALTER TABLE sample_action_mutation
    DROP CONSTRAINT sample_action_mutation_target_type_check;
ALTER TABLE sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_target_type_check CHECK (
        target_type IN (
            'RESOURCE', 'ACTIVITY', 'SUBMISSION', 'RESULT',
            'LIBRARY_ITEM', 'SHARE_GRANT', 'CORRECTION_NOTICE'));
