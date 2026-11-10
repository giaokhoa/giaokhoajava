-- Kho tài nguyên và nguồn phân công tài nguyên của US7: library_item là mục lưu
-- trỏ thẳng tới đúng cặp resource/version nguồn (không nhân bản resource), còn
-- resource_assignment là nguồn quyền tường minh theo người trong một workspace.
-- FK composite (resource_id, version_id) -> resource_version (resource_id, id)
-- theo đúng mẫu sample_activity ở V019: cặp version phải thuộc đúng resource,
-- nên mục lưu không thể trỏ lệch nguồn. Unique (resource_id, id) của
-- resource_version đã được bổ sung ở V019 nên không cần ALTER ở đây.
--
-- share_grant_id chưa khai FK: bảng share_grant thuộc migration của task share
-- grant ngay sau task này, migration đó phải bổ sung FK về share_grant (ghi ở
-- Sổ B78). Cột giữ UUID thuần để mục lưu từ grant có chỗ ghi nguồn cấp.
--
-- custody OWNED nghĩa là workspace nhận sở hữu canonical resource đó;
-- LICENSED nghĩa là quyền giữ đến từ nguồn cấp (hệ thống hoặc share grant) và
-- không bao giờ đổi owner canonical của resource (owner đã bất biến bởi
-- trigger ở V008).
CREATE TABLE library_item (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    resource_id UUID NOT NULL,
    version_id UUID NOT NULL,
    share_grant_id UUID,
    custody VARCHAR(16) NOT NULL CHECK (custody IN ('OWNED', 'LICENSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_library_item_workspace_id UNIQUE (workspace_id, id),
    CONSTRAINT fk_library_item_resource_version
        FOREIGN KEY (resource_id, version_id) REFERENCES resource_version (resource_id, id)
);
CREATE INDEX ix_library_item_workspace_resource ON library_item (workspace_id, resource_id);

ALTER TABLE library_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY library_item_workspace_isolation ON library_item
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Mục lưu là con trỏ nguồn bất biến: đổi đích lưu là xóa mềm không có trong
-- model, muốn lưu bản khác thì tạo mục mới; không hard-delete mất truy vết.
CREATE FUNCTION guard_library_item_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Library item history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.version_id IS DISTINCT FROM NEW.version_id
        OR OLD.share_grant_id IS DISTINCT FROM NEW.share_grant_id
        OR OLD.custody IS DISTINCT FROM NEW.custody
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Library item source reference cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_library_item_preserve_history BEFORE UPDATE OR DELETE ON library_item
    FOR EACH ROW EXECUTE FUNCTION guard_library_item_history();

-- Phân công tài nguyên tường minh cho một người trong workspace: đích là một
-- resource cụ thể, một môn trong danh mục, hoặc resource trong phạm vi môn;
-- hàng không có đích nào thì không cấp được gì nên CHECK chặn ngay. Danh mục
-- actions là tên scope cố định của PermissionCatalog cho năm hành động mẫu có
-- nguồn quyền tường minh (biên soạn/duyệt/dùng/xem đáp án/chia sẻ theo AS-005
-- và data-model.md; spec không liệt kê token nên quyết định này ghi ở Sổ B78).
-- Trạng thái và revoked_at theo đúng mẫu phân công giảng dạy ở V017.
CREATE TABLE resource_assignment (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    user_id UUID NOT NULL REFERENCES user_account(id),
    resource_id UUID REFERENCES resource(id),
    subject_id UUID REFERENCES subject(id),
    actions TEXT[] NOT NULL CHECK (
        actions <> '{}'
        AND actions <@ ARRAY['draft.edit', 'review', 'use', 'answer.read', 'share']::text[]),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_resource_assignment_has_target CHECK (
        resource_id IS NOT NULL OR subject_id IS NOT NULL),
    CONSTRAINT ck_resource_assignment_revocation CHECK (
        (state IN ('PENDING', 'ACTIVE') AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_resource_assignment_current_resource
    ON resource_assignment (workspace_id, user_id, resource_id)
    WHERE state IN ('PENDING', 'ACTIVE') AND resource_id IS NOT NULL;
CREATE UNIQUE INDEX ix_resource_assignment_current_subject
    ON resource_assignment (workspace_id, user_id, subject_id)
    WHERE state IN ('PENDING', 'ACTIVE') AND subject_id IS NOT NULL;
CREATE INDEX ix_resource_assignment_workspace_state
    ON resource_assignment (workspace_id, state);

ALTER TABLE resource_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY resource_assignment_workspace_isolation ON resource_assignment
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (
        workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Phân công giữ lịch sử như mọi phân công khác: không hard-delete, định danh
-- và tập actions bất biến (nới/thu hành động là thu hồi rồi cấp hàng mới),
-- REVOKED là trạng thái cuối và ACTIVE không lùi về PENDING.
CREATE FUNCTION guard_resource_assignment_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Resource assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.user_id IS DISTINCT FROM NEW.user_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.subject_id IS DISTINCT FROM NEW.subject_id
        OR OLD.actions IS DISTINCT FROM NEW.actions
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Resource assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked resource assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active resource assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_resource_assignment_preserve_history BEFORE UPDATE OR DELETE
    ON resource_assignment
    FOR EACH ROW EXECUTE FUNCTION guard_resource_assignment_history();
