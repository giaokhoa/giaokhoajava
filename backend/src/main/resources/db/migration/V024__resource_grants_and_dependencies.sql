-- Chia sẻ tài nguyên và truy vết nguồn của US7: share_grant là lượt cấp một
-- version cụ thể cho một workspace nhận theo một mode, còn resource_dependency
-- là cạnh truy vết "bản phái sinh dùng nguồn nào". FK composite
-- (resource_id, version_id) -> resource_version (resource_id, id) theo đúng mẫu
-- V019/V023: version được cấp và hai đầu của cạnh phải thuộc đúng resource của
-- chúng, không thể trỏ lệch cặp.
--
-- Migration này cũng trả nợ FK đã ghi ở Sổ B78: library_item.share_grant_id nay
-- trỏ về share_grant(id); resource_dependency.share_grant_id khai FK ngay
-- trong bảng của nó.
--
-- Hai bảng mới là bản ghi canonical xuyên workspace như resource/resource_version
-- (V008, không RLS): data-model.md chỉ liệt kê RLS cho các bảng tenant mang
-- workspace_id của chính hàng, còn dependency được xếp vào nhóm canonical dùng
-- repository riêng; share_grant cũng không có một workspace_id duy nhất (phía
-- cấp và phía nhận là hai workspace khác nhau) nên policy một context kiểu V023
-- sẽ chặn chính thao tác tạo grant từ phía cấp. Căn cứ đầy đủ ghi ở Sổ B79.
CREATE TABLE share_grant (
    id UUID PRIMARY KEY,
    source_resource_id UUID NOT NULL,
    source_version_id UUID NOT NULL,
    grantor_user_id UUID NOT NULL REFERENCES user_account(id),
    recipient_workspace_id UUID NOT NULL REFERENCES workspace(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('REVOCABLE', 'LONG_TERM')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('OFFERED', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    actions TEXT[] NOT NULL CHECK (
        actions <> '{}'
        AND actions <@ ARRAY['draft.edit', 'review', 'use', 'answer.read', 'share']::text[]),
    allowed_workspace_ids UUID[] NOT NULL DEFAULT '{}',
    valid_from TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_share_grant_source_version
        FOREIGN KEY (source_resource_id, source_version_id)
        REFERENCES resource_version (resource_id, id),
    CONSTRAINT ck_share_grant_valid_window CHECK (
        expires_at IS NULL OR expires_at > valid_from),
    CONSTRAINT ck_share_grant_state_timestamps CHECK (
        (state = 'OFFERED' AND accepted_at IS NULL AND revoked_at IS NULL)
        OR (state = 'ACCEPTED' AND accepted_at IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND mode = 'REVOCABLE'
            AND accepted_at IS NOT NULL AND revoked_at IS NOT NULL)
        OR (state = 'EXPIRED' AND revoked_at IS NULL))
);
CREATE INDEX ix_share_grant_recipient_state ON share_grant (recipient_workspace_id, state);
CREATE INDEX ix_share_grant_source_version ON share_grant (source_resource_id, source_version_id);

-- Lượt cấp giữ lịch sử như các nguồn quyền khác: không hard-delete, định danh
-- của lượt cấp (nguồn, người cấp, nơi nhận, mode, actions, phạm vi, cửa sổ hiệu
-- lực) bất biến, trạng thái chỉ tiến theo máy trạng thái của data-model.md và
-- REVOKED/EXPIRED là trạng thái cuối. CHECK ở trên đã khóa REVOKED chỉ dành cho
-- mode REVOCABLE, nên LONG_TERM không có đường thu hồi đơn phương ngay ở tầng
-- dữ liệu (FR-037).
CREATE FUNCTION guard_share_grant_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Share grant history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.source_resource_id IS DISTINCT FROM NEW.source_resource_id
        OR OLD.source_version_id IS DISTINCT FROM NEW.source_version_id
        OR OLD.grantor_user_id IS DISTINCT FROM NEW.grantor_user_id
        OR OLD.recipient_workspace_id IS DISTINCT FROM NEW.recipient_workspace_id
        OR OLD.mode IS DISTINCT FROM NEW.mode
        OR OLD.actions IS DISTINCT FROM NEW.actions
        OR OLD.allowed_workspace_ids IS DISTINCT FROM NEW.allowed_workspace_ids
        OR OLD.valid_from IS DISTINCT FROM NEW.valid_from
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Share grant identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('REVOKED', 'EXPIRED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Closed share grant cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF NEW.state IS DISTINCT FROM OLD.state
        AND NOT ((OLD.state = 'OFFERED' AND NEW.state IN ('ACCEPTED', 'EXPIRED'))
            OR (OLD.state = 'ACCEPTED' AND NEW.state IN ('REVOKED', 'EXPIRED'))) THEN
        RAISE EXCEPTION 'Share grant state transition is not allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_share_grant_preserve_history BEFORE UPDATE OR DELETE ON share_grant
    FOR EACH ROW EXECUTE FUNCTION guard_share_grant_history();

ALTER TABLE library_item
    ADD CONSTRAINT fk_library_item_share_grant
    FOREIGN KEY (share_grant_id) REFERENCES share_grant(id);

-- Cạnh truy vết nguồn: bản phái sinh (fork hoặc bản chỉnh sửa) trỏ về đúng cặp
-- resource/version nguồn, kèm grant làm căn cứ nếu có và giới hạn dùng kế thừa
-- từ nguồn. usage_constraints giữ dạng văn bản mờ ở tầng schema vì data-model.md
-- không định nghĩa cấu trúc cho nó; diễn giải cấu trúc thuộc tầng service (Sổ B79).
CREATE TABLE resource_dependency (
    id UUID PRIMARY KEY,
    derived_resource_id UUID NOT NULL,
    derived_version_id UUID NOT NULL,
    source_resource_id UUID NOT NULL,
    source_version_id UUID NOT NULL,
    share_grant_id UUID REFERENCES share_grant(id),
    usage_constraints TEXT NOT NULL CHECK (btrim(usage_constraints) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_resource_dependency_derived_version
        FOREIGN KEY (derived_resource_id, derived_version_id)
        REFERENCES resource_version (resource_id, id),
    CONSTRAINT fk_resource_dependency_source_version
        FOREIGN KEY (source_resource_id, source_version_id)
        REFERENCES resource_version (resource_id, id),
    CONSTRAINT ck_resource_dependency_not_self CHECK (
        derived_resource_id <> source_resource_id OR derived_version_id <> source_version_id)
);
CREATE INDEX ix_resource_dependency_source_grant
    ON resource_dependency (source_version_id, share_grant_id);
CREATE INDEX ix_resource_dependency_derived
    ON resource_dependency (derived_resource_id, derived_version_id);

-- Provenance bất biến: mọi trường nghiệp vụ của cạnh đã ghi thì không sửa và
-- không hard-delete; muốn đổi nguồn phải tạo cạnh mới, không viết lại lịch sử.
CREATE FUNCTION guard_resource_dependency_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Resource dependency history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.derived_resource_id IS DISTINCT FROM NEW.derived_resource_id
        OR OLD.derived_version_id IS DISTINCT FROM NEW.derived_version_id
        OR OLD.source_resource_id IS DISTINCT FROM NEW.source_resource_id
        OR OLD.source_version_id IS DISTINCT FROM NEW.source_version_id
        OR OLD.share_grant_id IS DISTINCT FROM NEW.share_grant_id
        OR OLD.usage_constraints IS DISTINCT FROM NEW.usage_constraints
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Resource dependency provenance cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_resource_dependency_preserve_history BEFORE UPDATE OR DELETE
    ON resource_dependency
    FOR EACH ROW EXECUTE FUNCTION guard_resource_dependency_history();

-- Graph phụ thuộc không được có vòng: CHECK ở trên chặn cạnh tự trỏ đúng cặp
-- version của chính nó; vòng dài hơn chặn bằng trigger duyệt đệ quy từ đầu
-- nguồn của cạnh mới — đi theo các cạnh "phụ thuộc vào" từ nguồn mà quay lại
-- được chính bản phái sinh thì cạnh mới tạo vòng. PostgreSQL docs (mục
-- Constraints) chốt CHECK không tham chiếu được hàng khác nên kiểm tra xuyên
-- hàng tại thời điểm insert phải dùng trigger; duyệt đệ quy theo mẫu WITH
-- RECURSIVE của cùng docs. Giới hạn đồng thời của cơ chế này ghi ở Sổ B79.
CREATE FUNCTION guard_resource_dependency_acyclic() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        WITH RECURSIVE reach (resource_id, version_id) AS (
            SELECT NEW.source_resource_id, NEW.source_version_id
            UNION
            SELECT d.source_resource_id, d.source_version_id
            FROM resource_dependency d
            JOIN reach r ON d.derived_resource_id = r.resource_id
                AND d.derived_version_id = r.version_id
        )
        SELECT 1 FROM reach
        WHERE resource_id = NEW.derived_resource_id AND version_id = NEW.derived_version_id
    ) THEN
        RAISE EXCEPTION 'Resource dependency graph cannot contain a cycle'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_resource_dependency_acyclic BEFORE INSERT ON resource_dependency
    FOR EACH ROW EXECUTE FUNCTION guard_resource_dependency_acyclic();
