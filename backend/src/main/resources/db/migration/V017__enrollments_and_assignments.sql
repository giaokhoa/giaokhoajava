-- Ghi danh và phân công: enrollment gắn học sinh trong lớp vào môn–khóa trong cùng lớp
-- và workspace; teaching_assignment gắn giáo viên vào môn–khóa; homeroom_assignment
-- gắn giáo viên chủ nhiệm vào lớp. Composite FK khóa các phía đúng workspace; unique
-- từng phần chỉ cho cặp hiện hành để thu hồi giữ lịch sử rồi gắn lại được bằng hàng mới;
-- RLS khóa mọi hàng theo context workspace của transaction; các trigger dưới đây là chốt
-- chặn cuối ở tầng DB — service kiểm tra lại trước khi ghi để trả mã lỗi nghiệp vụ (400)
-- thay vì lỗi DB.
--
-- class_student và course_subject chưa có unique trên (workspace_id, id) nên FK composite
-- của enrollment/teaching_assignment cần bổ sung ở đây (migration mới, không sửa V014/V016):
-- id vốn là khóa chính nên cặp (workspace_id, id) không thể trùng, thêm ràng buộc an toàn.
ALTER TABLE class_student
    ADD CONSTRAINT uq_class_student_workspace_id UNIQUE (workspace_id, id);
ALTER TABLE course_subject
    ADD CONSTRAINT uq_course_subject_workspace_id UNIQUE (workspace_id, id);

CREATE TABLE enrollment (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    class_student_id UUID NOT NULL,
    course_subject_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_enrollment_class_student
        FOREIGN KEY (workspace_id, class_student_id) REFERENCES class_student (workspace_id, id),
    CONSTRAINT fk_enrollment_course_subject
        FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject (workspace_id, id),
    CONSTRAINT ck_enrollment_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_enrollment_current_unique
    ON enrollment (workspace_id, class_student_id, course_subject_id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_enrollment_workspace_state ON enrollment (workspace_id, state);

ALTER TABLE enrollment ENABLE ROW LEVEL SECURITY;
CREATE POLICY enrollment_workspace_isolation ON enrollment
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE teaching_assignment (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    course_subject_id UUID NOT NULL,
    teacher_user_id UUID NOT NULL REFERENCES user_account(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_teaching_assignment_course_subject
        FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject (workspace_id, id),
    CONSTRAINT ck_teaching_assignment_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_teaching_assignment_current_unique
    ON teaching_assignment (workspace_id, course_subject_id, teacher_user_id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_teaching_assignment_workspace_state ON teaching_assignment (workspace_id, state);

ALTER TABLE teaching_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY teaching_assignment_workspace_isolation ON teaching_assignment
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

CREATE TABLE homeroom_assignment (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    class_id UUID NOT NULL,
    teacher_user_id UUID NOT NULL REFERENCES user_account(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_homeroom_assignment_school_class
        FOREIGN KEY (workspace_id, class_id) REFERENCES school_class (workspace_id, id),
    CONSTRAINT ck_homeroom_assignment_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_homeroom_assignment_current_unique
    ON homeroom_assignment (workspace_id, class_id, teacher_user_id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_homeroom_assignment_workspace_state ON homeroom_assignment (workspace_id, state);

ALTER TABLE homeroom_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY homeroom_assignment_workspace_isolation ON homeroom_assignment
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Hai phía của ghi danh phải cùng lớp: enrollment không mang cột class_id nên cùng-lớp
-- không cài được bằng composite FK thuần; trigger này là chốt chặn cuối ở tầng DB.
-- Chỉ kiểm tra lúc INSERT vì class_student_id và course_subject_id bất biến sau khi
-- tạo (trigger giữ lịch sử dưới đây khóa đổi). Cùng workspace đã khóa bởi hai FK
-- composite ở trên.
CREATE FUNCTION validate_enrollment_same_class() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM class_student cs, course_subject css
        WHERE cs.id = NEW.class_student_id
          AND css.id = NEW.course_subject_id
          AND cs.class_id = css.class_id
    ) THEN
        RAISE EXCEPTION 'Enrollment requires both sides in the same class'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_enrollment_same_class BEFORE INSERT ON enrollment
    FOR EACH ROW EXECUTE FUNCTION validate_enrollment_same_class();

-- Chủ nhiệm phải là thành viên ACTIVE của center đó; personal không cần membership.
-- Chỉ kiểm tra lúc INSERT vì class_id và teacher_user_id bất biến sau khi tạo
-- (trigger giữ lịch sử dưới đây khóa đổi). Tiền đề role TEACHER do service kiểm tra
-- trước khi ghi để trả mã lỗi nghiệp vụ, theo đúng mẫu trigger roster ở V014 (chỉ
-- kiểm tra membership, không kiểm tra role).
CREATE FUNCTION validate_homeroom_assignment_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind = 'CENTER' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.teacher_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Homeroom assignment requires an active center membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_homeroom_assignment_membership BEFORE INSERT ON homeroom_assignment
    FOR EACH ROW EXECUTE FUNCTION validate_homeroom_assignment_membership();

-- Ghi danh và phân công không bị hard-delete làm mất lịch sử: chỉ state/revoked_at đổi
-- được, các cột định danh và thời điểm tạo bất biến.
CREATE FUNCTION guard_enrollment_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Enrollment history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_student_id IS DISTINCT FROM NEW.class_student_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Enrollment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked enrollment cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active enrollment cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_enrollment_preserve_history BEFORE UPDATE OR DELETE ON enrollment
    FOR EACH ROW EXECUTE FUNCTION guard_enrollment_history();

CREATE FUNCTION guard_teaching_assignment_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Teaching assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.teacher_user_id IS DISTINCT FROM NEW.teacher_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Teaching assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked teaching assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active teaching assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_teaching_assignment_preserve_history BEFORE UPDATE OR DELETE
    ON teaching_assignment
    FOR EACH ROW EXECUTE FUNCTION guard_teaching_assignment_history();

CREATE FUNCTION guard_homeroom_assignment_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Homeroom assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.teacher_user_id IS DISTINCT FROM NEW.teacher_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Homeroom assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked homeroom assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active homeroom assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_homeroom_assignment_preserve_history BEFORE UPDATE OR DELETE
    ON homeroom_assignment
    FOR EACH ROW EXECUTE FUNCTION guard_homeroom_assignment_history();
