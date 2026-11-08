-- Khóa và môn của khóa: course thuộc đúng một lớp trong cùng workspace; course_subject
-- gắn môn danh mục vào khóa với ID phạm vi riêng, cùng môn ở hai khóa là hai phạm vi
-- quyền khác nhau. Composite FK (workspace_id, class_id, course_id) khóa course thuộc
-- đúng lớp; unique từng phần chỉ cho cặp course/subject hiện hành để thu hồi giữ lịch
-- sử rồi gắn lại được bằng hàng mới; RLS khóa mọi hàng theo context workspace của
-- transaction; các trigger dưới đây là chốt chặn cuối ở tầng DB — service kiểm tra lại
-- trước khi ghi để trả mã lỗi nghiệp vụ (400) thay vì lỗi DB.
CREATE TABLE course (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    class_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL CHECK (btrim(name) <> ''),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_course_school_class
        FOREIGN KEY (workspace_id, class_id) REFERENCES school_class (workspace_id, id),
    CONSTRAINT uq_course_workspace_class_id UNIQUE (workspace_id, class_id, id)
);
CREATE INDEX ix_course_workspace_state ON course (workspace_id, state);

ALTER TABLE course ENABLE ROW LEVEL SECURITY;
CREATE POLICY course_workspace_isolation ON course
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE course_subject (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    class_id UUID NOT NULL,
    course_id UUID NOT NULL,
    subject_id UUID NOT NULL REFERENCES subject(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_course_subject_course
        FOREIGN KEY (workspace_id, class_id, course_id) REFERENCES course (workspace_id, class_id, id),
    CONSTRAINT ck_course_subject_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_course_subject_current_unique
    ON course_subject (workspace_id, class_id, course_id, subject_id) WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_course_subject_workspace_state ON course_subject (workspace_id, state);

ALTER TABLE course_subject ENABLE ROW LEVEL SECURITY;
CREATE POLICY course_subject_workspace_isolation ON course_subject
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Khóa và môn của khóa không bị hard-delete làm mất lịch sử: chỉ state/revoked_at đổi
-- được, các cột định danh và thời điểm tạo bất biến.
CREATE FUNCTION guard_course_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Course history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Course identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked course cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active course cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_course_preserve_history BEFORE UPDATE OR DELETE ON course
    FOR EACH ROW EXECUTE FUNCTION guard_course_history();

CREATE FUNCTION guard_course_subject_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Course subject history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.course_id IS DISTINCT FROM NEW.course_id
        OR OLD.subject_id IS DISTINCT FROM NEW.subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Course subject identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked course subject cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active course subject cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_course_subject_preserve_history BEFORE UPDATE OR DELETE ON course_subject
    FOR EACH ROW EXECUTE FUNCTION guard_course_subject_history();
