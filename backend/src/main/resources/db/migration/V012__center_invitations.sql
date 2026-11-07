-- Lời mời vào center: token một lần, chỉ lưu hash SHA-256 hex của token thô; đúng
-- một đích (email hoặc user đã có); role đích không chứa OWNER (OWNER chỉ có qua
-- tạo center; MANAGER chỉ owner cấp — hai quy tắc này service cài endpoint kiểm tra
-- lại trước khi ghi, CHECK dưới đây là chốt chặn cuối ở tầng DB).
CREATE TABLE invitation (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    target_email VARCHAR(320) CHECK (target_email IS NULL OR btrim(target_email) <> ''),
    target_user_id UUID REFERENCES user_account(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    intended_roles TEXT[] NOT NULL CHECK (
        intended_roles <> '{}'
        AND intended_roles <@ ARRAY['MANAGER', 'TEACHER', 'STUDENT', 'PARENT']::text[]),
    expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_invitation_exactly_one_target CHECK (
        (target_email IS NULL) <> (target_user_id IS NULL))
);
CREATE INDEX ix_invitation_workspace_state ON invitation (workspace_id, state);
CREATE INDEX ix_invitation_target_state ON invitation (target_user_id, state);

CREATE FUNCTION guard_invitation_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Invitation history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.target_email IS DISTINCT FROM NEW.target_email
        OR OLD.target_user_id IS DISTINCT FROM NEW.target_user_id
        OR OLD.token_hash IS DISTINCT FROM NEW.token_hash
        OR OLD.intended_roles IS DISTINCT FROM NEW.intended_roles
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Invitation identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_invitation_preserve_history BEFORE UPDATE OR DELETE ON invitation
    FOR EACH ROW EXECUTE FUNCTION guard_invitation_history();
