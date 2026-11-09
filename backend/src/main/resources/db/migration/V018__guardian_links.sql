-- Liên kết phụ huynh: guardian_link nối một parent với một học sinh trong đúng một
-- center. Request tự thân không mở quyền — liên kết chỉ thành nguồn parent khi được
-- owner/manager duyệt (APPROVED); từ chối (REJECTED) là kết thúc và muốn liên kết lại
-- phải tạo request mới; gỡ (REVOKED) chỉ từ APPROVED và giữ nguyên lịch sử.
--
-- CHECK khóa hình dạng trường theo từng trạng thái; unique từng phần chỉ cho cặp
-- hiện hành (REQUESTED/APPROVED) để sau REJECTED/REVOKED tạo lại được bằng hàng mới;
-- RLS khóa mọi hàng theo context workspace của transaction; các trigger dưới đây là
-- chốt chặn cuối ở tầng DB — service kiểm tra lại trước khi ghi để trả mã lỗi nghiệp
-- vụ thay vì lỗi DB. Tiền đề role (PARENT cho parent, STUDENT cho học sinh,
-- OWNER/MANAGER cho người duyệt) do service kiểm tra trước khi ghi, theo đúng mẫu
-- trigger membership ở V014/V017 (tầng DB chỉ kiểm tra membership ACTIVE).
CREATE TABLE guardian_link (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    parent_user_id UUID NOT NULL REFERENCES user_account(id),
    student_user_id UUID NOT NULL REFERENCES user_account(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('REQUESTED', 'APPROVED', 'REJECTED', 'REVOKED')),
    requested_by UUID NOT NULL REFERENCES user_account(id),
    approved_by UUID REFERENCES user_account(id),
    approved_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_guardian_link_state_fields CHECK (
        (state IN ('REQUESTED', 'REJECTED')
            AND approved_by IS NULL AND approved_at IS NULL AND revoked_at IS NULL)
        OR (state = 'APPROVED'
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED'
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_guardian_link_current_unique
    ON guardian_link (workspace_id, parent_user_id, student_user_id)
    WHERE state IN ('REQUESTED', 'APPROVED');
CREATE INDEX ix_guardian_link_workspace_state ON guardian_link (workspace_id, state);

ALTER TABLE guardian_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY guardian_link_workspace_isolation ON guardian_link
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Liên kết chỉ tồn tại trong center và cả parent lẫn học sinh phải là thành viên
-- ACTIVE của center đó ngay lúc tạo: child ngoài phạm vi center không tạo được
-- liên kết, và đây cũng là chốt chặn để guardian_link không bị đồng nhất với một
-- quan hệ gia đình toàn nền tảng không gắn workspace.
CREATE FUNCTION validate_guardian_link_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind IS DISTINCT FROM 'CENTER' THEN
        RAISE EXCEPTION 'Guardian link requires a CENTER workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.parent_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Guardian link requires the parent to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.student_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Guardian link requires the student to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_guardian_link_membership BEFORE INSERT ON guardian_link
    FOR EACH ROW EXECUTE FUNCTION validate_guardian_link_membership();

-- Điều kiện "parent hiện hành" được xét tại thời điểm duyệt, không phải thời điểm
-- gửi request: membership bị thu hồi giữa chừng thì duyệt phải bị chặn ở tầng DB.
CREATE FUNCTION validate_guardian_link_approval() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'REQUESTED' AND NEW.state = 'APPROVED' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.parent_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'Guardian link approval requires the parent to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_guardian_link_approval BEFORE UPDATE ON guardian_link
    FOR EACH ROW EXECUTE FUNCTION validate_guardian_link_approval();

-- Lịch sử liên kết không bị hard-delete và không sửa ngược: các cột định danh bất
-- biến, trạng thái kết thúc (REJECTED/REVOKED) đóng băng toàn hàng, và chỉ hai
-- chuyển trạng thái của vòng đời được phép (REQUESTED sang APPROVED/REJECTED,
-- APPROVED sang REVOKED) — chặn cả đường REQUESTED sang REVOKED lách qua duyệt.
CREATE FUNCTION guard_guardian_link_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Guardian link history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.parent_user_id IS DISTINCT FROM NEW.parent_user_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.requested_by IS DISTINCT FROM NEW.requested_by
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Guardian link identity cannot change'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('REJECTED', 'REVOKED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Closed guardian link cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF (OLD.state = 'REQUESTED' AND NEW.state = 'REVOKED')
        OR (OLD.state = 'APPROVED' AND NEW.state IN ('REQUESTED', 'REJECTED')) THEN
        RAISE EXCEPTION 'Guardian link state transition is not allowed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_guardian_link_preserve_history BEFORE UPDATE OR DELETE ON guardian_link
    FOR EACH ROW EXECUTE FUNCTION guard_guardian_link_history();
