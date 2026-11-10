-- Nới catalog target_type của sample_action_mutation thêm 'LIBRARY_ITEM': đường lưu
-- version về kho (SAVE_VERSION/RECEIVE_UPDATE) ghi biên nhận idempotency với target_type
-- này, nhưng CHECK của V022 chỉ có bốn token cũ nên lần lưu đầu tiên sẽ văng lỗi CHECK
-- trên DB thật; không test nào trong branch chạy đường chèn đó nên chưa lộ. Chỉ nới thêm
-- đúng token này — token cho đường share grant thuộc về #83, không gộp vào đây.
ALTER TABLE sample_action_mutation
    DROP CONSTRAINT sample_action_mutation_target_type_check;
ALTER TABLE sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_target_type_check CHECK (
        target_type IN ('RESOURCE', 'ACTIVITY', 'SUBMISSION', 'RESULT', 'LIBRARY_ITEM'));
