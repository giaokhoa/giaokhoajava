-- Danh mục môn tham chiếu tối thiểu: mã duy nhất, không cây chương trình hay taxonomy.
-- Không có workspace_id: danh mục dùng chung toàn hệ thống, course_subject giữ FK
-- danh mục nhưng có ID phạm vi riêng (data-model.md). Không RLS vì không có cột
-- workspace. Seed đúng ba môn M1/M2/M3 theo quickstart (K ba môn); test tích hợp
-- giảng dạy dùng đúng 3 môn qua GET /api/subjects, seed thừa sẽ làm test đỏ sai lý do.
CREATE TABLE subject (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE CHECK (btrim(code) <> ''),
    name VARCHAR(255) NOT NULL CHECK (btrim(name) <> ''),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO subject (id, code, name, state) VALUES
    (gen_random_uuid(), 'M1', 'Môn 1', 'ACTIVE'),
    (gen_random_uuid(), 'M2', 'Môn 2', 'ACTIVE'),
    (gen_random_uuid(), 'M3', 'Môn 3', 'ACTIVE');

-- Danh mục không mở DELETE vật lý làm mất lịch sử (http-api.md); mã là định danh
-- ổn định nên không đổi, chỉ đổi được tên và trạng thái.
CREATE FUNCTION guard_subject_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Subject cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.code IS DISTINCT FROM NEW.code THEN
        RAISE EXCEPTION 'Subject identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_subject_preserve_history BEFORE UPDATE OR DELETE ON subject
    FOR EACH ROW EXECUTE FUNCTION guard_subject_history();
