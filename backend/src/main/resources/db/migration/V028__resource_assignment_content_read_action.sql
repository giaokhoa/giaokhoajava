-- Nới catalog action của resource_assignment thêm 'content.read': phân công chuyên môn
-- cần trao quyền đọc nội dung ở mức version (scope content.read của hợp đồng) mà catalog
-- 5 token của V023 không biểu diễn được; điểm nới này đã được dự liệu trong Sổ ở mục B78(i)
-- — issue cài projector của resource_assignment tự nới bằng migration mới, không sửa V023.
ALTER TABLE resource_assignment
    DROP CONSTRAINT resource_assignment_actions_check;
ALTER TABLE resource_assignment
    ADD CONSTRAINT resource_assignment_actions_check CHECK (
        actions <> '{}'
        AND actions <@ ARRAY[
            'draft.edit', 'review', 'use', 'answer.read', 'share', 'content.read'
        ]::text[]);
