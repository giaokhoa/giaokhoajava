-- Hai catalog cố định cần nới cho luồng chia sẻ tài nguyên hoạt động ở mức
-- version, theo đúng điểm dự liệu đã ghi trong Sổ ở B79(ii) và mẫu V028 (nới
-- catalog của resource_assignment bằng migration mới, không sửa V023/V024):
--
-- 1. actions của share_grant: V024 khóa năm token của danh mục phân công
-- (draft.edit, review, use, answer.read, share), nhưng payload chia sẻ và
-- projector của resource version làm việc bằng token mức version. Catalog mới
-- là đúng tập token mà phép map action -> scope của projector hiểu được:
-- năm token cũ giữ nguyên, thêm content.read, fork và library.save.
--
-- 2. target_type của sample_action_mutation: V022 khóa bốn loại đích của các
-- sample action đầu tiên, nhưng service thư viện đã ghi biên nhận với đích
-- LIBRARY_ITEM và luồng chia sẻ ghi đích SHARE_GRANT; thiếu hai giá trị này
-- thì chính thao tác lưu/chia sẻ bị CHECK chặn trên cơ sở dữ liệu thật.
ALTER TABLE share_grant
    DROP CONSTRAINT share_grant_actions_check;
ALTER TABLE share_grant
    ADD CONSTRAINT share_grant_actions_check CHECK (
        actions <> '{}'
        AND actions <@ ARRAY[
            'draft.edit', 'review', 'use', 'answer.read', 'share',
            'content.read', 'fork', 'library.save'
        ]::text[]);

ALTER TABLE sample_action_mutation
    DROP CONSTRAINT sample_action_mutation_target_type_check;
ALTER TABLE sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_target_type_check CHECK (
        target_type IN (
            'RESOURCE', 'ACTIVITY', 'SUBMISSION', 'RESULT',
            'LIBRARY_ITEM', 'SHARE_GRANT'));
