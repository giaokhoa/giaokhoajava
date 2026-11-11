-- Cùng một thay đổi như migration hệ thống V035 cho các bản sao trong schema tenant: bảng
-- lượt mở thêm sample_attempt_opening, trigger validate_sample_submission tính cả lượt đã
-- mở, và catalog target_type của biên nhận thêm ATTEMPT_OPENING/PRACTICE_ATTEMPT. Danh sách
-- target_type ở dòng tenant đang có sáu giá trị (chưa có CORRECTION_NOTICE như dòng hệ thống)
-- nên bản này chỉ cộng hai giá trị mới vào đúng danh sách hiện hành của tenant. Các FK xuyên
-- mặt phẳng (workspace, user_account) bỏ qua như quy ước của baseline tenant V001 — chỉ giữ
-- FK nội bộ tới sample_activity trên cặp (workspace_id, activity_id).
CREATE TABLE sample_attempt_opening (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    activity_id UUID NOT NULL,
    student_user_id UUID NOT NULL,
    opened_by UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sample_attempt_opening_activity
        FOREIGN KEY (workspace_id, activity_id) REFERENCES sample_activity (workspace_id, id)
);
CREATE INDEX ix_sample_attempt_opening_activity_student
    ON sample_attempt_opening (workspace_id, activity_id, student_user_id);

ALTER TABLE sample_attempt_opening ENABLE ROW LEVEL SECURITY;
CREATE POLICY sample_attempt_opening_workspace_isolation ON sample_attempt_opening
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE OR REPLACE FUNCTION validate_sample_submission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    activity_attempt_limit INTEGER;
    activity_course_subject_id UUID;
    opened_attempts INTEGER;
BEGIN
    SELECT attempt_limit, course_subject_id
        INTO activity_attempt_limit, activity_course_subject_id
        FROM sample_activity
        WHERE id = NEW.activity_id AND workspace_id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sample submission requires an activity in the same workspace'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO opened_attempts
        FROM sample_attempt_opening
        WHERE activity_id = NEW.activity_id AND student_user_id = NEW.student_user_id;
    IF NEW.attempt_no > activity_attempt_limit + opened_attempts THEN
        RAISE EXCEPTION 'Sample submission attempt exceeds the activity attempt limit'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
            FROM enrollment e
            JOIN class_student cs ON cs.id = e.class_student_id
        WHERE e.workspace_id = NEW.workspace_id
          AND e.course_subject_id = activity_course_subject_id
          AND e.state IN ('PENDING', 'ACTIVE')
          AND cs.workspace_id = NEW.workspace_id
          AND cs.student_user_id = NEW.student_user_id
    ) THEN
        RAISE EXCEPTION 'Sample submission requires a current enrollment of the student'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Lượt mở là lịch sử đã mở: không hard-delete, không sửa định danh/học sinh/lượt.
CREATE FUNCTION guard_sample_attempt_opening_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample attempt opening history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.opened_by IS DISTINCT FROM NEW.opened_by
        OR OLD.opened_at IS DISTINCT FROM NEW.opened_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample attempt opening identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_sample_attempt_opening_preserve_history
    BEFORE UPDATE OR DELETE ON sample_attempt_opening
    FOR EACH ROW EXECUTE FUNCTION guard_sample_attempt_opening_history();

ALTER TABLE sample_action_mutation
    DROP CONSTRAINT sample_action_mutation_target_type_check;
ALTER TABLE sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_target_type_check CHECK (
        target_type IN (
            'RESOURCE', 'ACTIVITY', 'SUBMISSION', 'RESULT',
            'LIBRARY_ITEM', 'SHARE_GRANT',
            'ATTEMPT_OPENING', 'PRACTICE_ATTEMPT'));
