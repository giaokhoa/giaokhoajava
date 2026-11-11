-- Chỉ mục membership tối thiểu ở schema hệ thống cho đợt tách schema theo tenant:
-- các đường đọc xuyên workspace (đăng nhập, phân giải thư mục) tra trạng thái membership
-- hiện hành của một user mà không quét mọi schema tenant. Nguồn sự thật vẫn là
-- center_membership; chỉ mục được service ghi membership đồng bộ trong cùng transaction.
CREATE TABLE membership_index (
    user_id UUID NOT NULL REFERENCES user_account(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, workspace_id)
);

-- Backfill theo membership gần nhất của từng (user, workspace): index từng workspace chỉ
-- giữ một hàng hiện hành cho mỗi user, và hàng membership tạo sau nhất là hàng hiện hành
-- khi còn PENDING/ACTIVE (index từng phần uq_membership_current bảo đảm tính duy nhất đó).
-- Khi mốc tạo trùng nhau (thu hồi rồi tạo lại trong cùng một transaction), hàng hiện hành
-- PENDING/ACTIVE phải thắng hàng REVOKED nên trạng thái được ưu tiên trước mốc tạo.
INSERT INTO membership_index (user_id, workspace_id, state, created_at, updated_at)
SELECT DISTINCT ON (m.user_id, m.workspace_id)
    m.user_id, m.workspace_id, m.state, m.created_at, m.updated_at
FROM center_membership m
ORDER BY m.user_id, m.workspace_id, (m.state = 'REVOKED') ASC, m.created_at DESC, m.id DESC;
