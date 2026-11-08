-- Lớp và roster: school_class thuộc một workspace; class_student gắn học sinh vào lớp
-- trong cùng workspace. Composite FK (workspace_id, class_id) khóa lớp đúng workspace;
-- unique từng phần chỉ cho roster hiện hành để thu hồi giữ lịch sử rồi ghi danh lại được
-- bằng hàng mới; RLS khóa mọi hàng theo context workspace của transaction; các trigger
-- dưới đây là chốt chặn cuối ở tầng DB — service kiểm tra lại trước khi ghi để trả mã
-- lỗi nghiệp vụ (400) thay vì lỗi DB.
CREATE TABLE school_class (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(255) NOT NULL CHECK (btrim(name) <> ''),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_school_class_workspace_id UNIQUE (workspace_id, id)
);
CREATE INDEX ix_school_class_workspace_state ON school_class (workspace_id, state);

ALTER TABLE school_class ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_class_workspace_isolation ON school_class
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE class_student (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    class_id UUID NOT NULL,
    student_user_id UUID NOT NULL REFERENCES user_account(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_class_student_school_class
        FOREIGN KEY (workspace_id, class_id) REFERENCES school_class (workspace_id, id),
    CONSTRAINT ck_class_student_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_class_student_active_unique
    ON class_student (workspace_id, class_id, student_user_id) WHERE state = 'ACTIVE';
CREATE INDEX ix_class_student_workspace_state ON class_student (workspace_id, state);

ALTER TABLE class_student ENABLE ROW LEVEL SECURITY;
CREATE POLICY class_student_workspace_isolation ON class_student
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Học sinh center phải có membership ACTIVE của center đó mới được vào roster; học
-- sinh personal không cần membership. Chỉ kiểm tra lúc INSERT vì class_id và
-- student_user_id bất biến sau khi tạo (trigger giữ lịch sử dưới đây khóa đổi).
CREATE FUNCTION validate_class_student_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind = 'CENTER' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.student_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Center roster requires an active center membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_class_student_membership BEFORE INSERT ON class_student
    FOR EACH ROW EXECUTE FUNCTION validate_class_student_membership();

-- Lớp và roster không bị hard-delete làm mất lịch sử: chỉ state/revoked_at đổi được,
-- các cột định danh và thời điểm tạo bất biến.
CREATE FUNCTION guard_school_class_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Class history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Class identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked class cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active class cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_school_class_preserve_history BEFORE UPDATE OR DELETE ON school_class
    FOR EACH ROW EXECUTE FUNCTION guard_school_class_history();

CREATE FUNCTION guard_class_student_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Roster history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Roster identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked roster entry cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active roster entry cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_class_student_preserve_history BEFORE UPDATE OR DELETE ON class_student
    FOR EACH ROW EXECUTE FUNCTION guard_class_student_history();
