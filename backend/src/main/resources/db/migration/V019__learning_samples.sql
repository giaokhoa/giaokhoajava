-- Hoạt động, bài nộp và kết quả mẫu phục vụ read side kết quả của phụ huynh: ba bảng
-- primitive đi qua cùng RLS/FK/snapshot như dữ liệu thật. sample_activity chốt snapshot
-- lịch (assigned_at/due_at), kỳ gói và số lượt tại thời điểm giao; sample_submission là
-- bài nộp bất biến theo lượt; sample_result chỉ cho phụ huynh thấy khi PUBLISHED. Các
-- trigger dưới đây là chốt chặn cuối ở tầng DB — service kiểm tra lại trước khi ghi để
-- trả mã lỗi nghiệp vụ thay vì lỗi DB.
--
-- resource_version và subscription_period chưa có unique trên cặp khóa cần cho FK
-- composite nên bổ sung ở đây (migration mới, không sửa V001/V008): id vốn là khóa
-- chính nên các cặp này không thể trùng, thêm ràng buộc an toàn.
ALTER TABLE resource_version
    ADD CONSTRAINT uq_resource_version_resource_id UNIQUE (resource_id, id);
ALTER TABLE subscription_period
    ADD CONSTRAINT uq_subscription_period_workspace_id UNIQUE (workspace_id, id);

CREATE TABLE sample_activity (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    course_subject_id UUID NOT NULL,
    resource_id UUID NOT NULL REFERENCES resource(id),
    version_id UUID NOT NULL,
    assigned_by UUID NOT NULL REFERENCES user_account(id),
    assigned_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    attempt_limit INTEGER NOT NULL CHECK (attempt_limit >= 1),
    subscription_period_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ASSIGNED', 'CLOSED', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sample_activity_workspace_id UNIQUE (workspace_id, id),
    CONSTRAINT fk_sample_activity_course_subject
        FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject (workspace_id, id),
    CONSTRAINT fk_sample_activity_resource_version
        FOREIGN KEY (resource_id, version_id) REFERENCES resource_version (resource_id, id),
    CONSTRAINT fk_sample_activity_subscription_period
        FOREIGN KEY (workspace_id, subscription_period_id)
        REFERENCES subscription_period (workspace_id, id)
);
CREATE INDEX ix_sample_activity_workspace_state ON sample_activity (workspace_id, state);
CREATE INDEX ix_sample_activity_course_subject ON sample_activity (workspace_id, course_subject_id);

ALTER TABLE sample_activity ENABLE ROW LEVEL SECURITY;
CREATE POLICY sample_activity_workspace_isolation ON sample_activity
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE sample_submission (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    activity_id UUID NOT NULL,
    student_user_id UUID NOT NULL REFERENCES user_account(id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    submitted_at TIMESTAMPTZ NOT NULL,
    content_stub TEXT NOT NULL,
    -- Spec chưa chốt danh mục trạng thái của bài nộp nên chỉ khóa không rỗng, theo đúng
    -- tiền lệ cột state của resource ở V008; nới/thu danh mục bằng migration sau nếu cần.
    state VARCHAR(32) NOT NULL CHECK (btrim(state) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sample_submission_workspace_id UNIQUE (workspace_id, id),
    CONSTRAINT fk_sample_submission_activity
        FOREIGN KEY (workspace_id, activity_id) REFERENCES sample_activity (workspace_id, id),
    CONSTRAINT uq_sample_submission_attempt UNIQUE (activity_id, student_user_id, attempt_no)
);
CREATE INDEX ix_sample_submission_workspace_state ON sample_submission (workspace_id, state);

ALTER TABLE sample_submission ENABLE ROW LEVEL SECURITY;
CREATE POLICY sample_submission_workspace_isolation ON sample_submission
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE sample_result (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    submission_id UUID NOT NULL,
    student_user_id UUID NOT NULL REFERENCES user_account(id),
    course_subject_id UUID NOT NULL,
    score_stub TEXT,
    comment_stub TEXT,
    state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED')),
    published_at TIMESTAMPTZ,
    graded_by UUID REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sample_result_submission
        FOREIGN KEY (workspace_id, submission_id) REFERENCES sample_submission (workspace_id, id),
    CONSTRAINT fk_sample_result_course_subject
        FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject (workspace_id, id),
    CONSTRAINT ck_sample_result_publication CHECK (
        (state = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX ix_sample_result_workspace_state ON sample_result (workspace_id, state);
CREATE INDEX ix_sample_result_course_subject ON sample_result (workspace_id, course_subject_id);

ALTER TABLE sample_result ENABLE ROW LEVEL SECURITY;
CREATE POLICY sample_result_workspace_isolation ON sample_result
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Bài nộp chỉ hợp lệ khi học sinh còn ghi danh hiện hành vào đúng môn–khóa của hoạt
-- động và số lượt không vượt snapshot attempt_limit của hoạt động. Không cài được bằng
-- FK thuần nên trigger là chốt chặn cuối; chỉ kiểm tra lúc INSERT vì các cột liên quan
-- của bài nộp bất biến sau khi tạo (trigger giữ lịch sử dưới đây khóa đổi).
CREATE FUNCTION validate_sample_submission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    activity_attempt_limit INTEGER;
    activity_course_subject_id UUID;
BEGIN
    SELECT attempt_limit, course_subject_id
        INTO activity_attempt_limit, activity_course_subject_id
        FROM sample_activity
        WHERE id = NEW.activity_id AND workspace_id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sample submission requires an activity in the same workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.attempt_no > activity_attempt_limit THEN
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
CREATE TRIGGER ck_sample_submission_valid BEFORE INSERT ON sample_submission
    FOR EACH ROW EXECUTE FUNCTION validate_sample_submission();

-- Học sinh và môn–khóa của kết quả suy ra từ bài nộp và hoạt động, không tin giá trị
-- tự khai: lệch với nguồn thì chặn ngay ở tầng DB. Chỉ kiểm tra lúc INSERT vì các cột
-- này bất biến sau khi tạo (trigger giữ lịch sử dưới đây khóa đổi).
CREATE FUNCTION validate_sample_result_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source_student_user_id UUID;
    source_course_subject_id UUID;
BEGIN
    SELECT s.student_user_id, a.course_subject_id
        INTO source_student_user_id, source_course_subject_id
        FROM sample_submission s
        JOIN sample_activity a ON a.id = s.activity_id
    WHERE s.id = NEW.submission_id AND s.workspace_id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sample result requires a submission in the same workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.student_user_id IS DISTINCT FROM source_student_user_id
        OR NEW.course_subject_id IS DISTINCT FROM source_course_subject_id THEN
        RAISE EXCEPTION 'Sample result student and subject must derive from the submission'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_sample_result_source BEFORE INSERT ON sample_result
    FOR EACH ROW EXECUTE FUNCTION validate_sample_result_source();

-- Hoạt động giữ snapshot lịch/kỳ/lượt bất biến: chỉ state và due_at (gia hạn lịch là
-- thao tác có chủ đích) đổi được; ARCHIVED là trạng thái cuối, đóng băng toàn bộ hàng.
CREATE FUNCTION guard_sample_activity_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample activity history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ARCHIVED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Archived sample activity cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.version_id IS DISTINCT FROM NEW.version_id
        OR OLD.assigned_by IS DISTINCT FROM NEW.assigned_by
        OR OLD.assigned_at IS DISTINCT FROM NEW.assigned_at
        OR OLD.attempt_limit IS DISTINCT FROM NEW.attempt_limit
        OR OLD.subscription_period_id IS DISTINCT FROM NEW.subscription_period_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample activity snapshot cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'CLOSED' AND NEW.state = 'ASSIGNED' THEN
        RAISE EXCEPTION 'Closed sample activity cannot return to ASSIGNED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_sample_activity_preserve_history BEFORE UPDATE OR DELETE ON sample_activity
    FOR EACH ROW EXECUTE FUNCTION guard_sample_activity_history();

-- Bài nộp là lịch sử đã nộp: không hard-delete, không sửa định danh/lượt/nội dung.
CREATE FUNCTION guard_sample_submission_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample submission history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.attempt_no IS DISTINCT FROM NEW.attempt_no
        OR OLD.submitted_at IS DISTINCT FROM NEW.submitted_at
        OR OLD.content_stub IS DISTINCT FROM NEW.content_stub
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample submission content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_sample_submission_preserve_history BEFORE UPDATE OR DELETE
    ON sample_submission
    FOR EACH ROW EXECUTE FUNCTION guard_sample_submission_history();

-- Kết quả đã công bố là snapshot phụ huynh nhìn thấy: đóng băng toàn bộ hàng khi
-- PUBLISHED; bản nháp chỉ khóa định danh và nguồn suy ra, nội dung chấm sửa tự do.
CREATE FUNCTION guard_sample_result_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample result history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Published sample result cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.submission_id IS DISTINCT FROM NEW.submission_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample result identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_sample_result_preserve_history BEFORE UPDATE OR DELETE ON sample_result
    FOR EACH ROW EXECUTE FUNCTION guard_sample_result_history();
