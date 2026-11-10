-- Chỉ mục cho các đường truy vấn nguồn tài nguyên và hàng đợi authorization:
-- mục lưu tra theo đúng cặp resource/version nguồn và theo grant đã cấp; cạnh
-- dependency tra theo grant. Hai chỉ mục theo share_grant_id là partial vì chỉ
-- các dòng có grant mới được tra theo cột này. Index hàng đợi theo recorded_seq
-- chỉ gồm các state còn sống để lượt claim của worker quét đúng cửa sổ đã sắp
-- thứ tự; ix_authz_operation_queue ở V002 khóa theo (next_attempt_at,
-- created_at) cho lịch retry nên không thay thế được thứ tự recorded_seq này.
CREATE INDEX ix_library_item_resource_version ON library_item (resource_id, version_id);
CREATE INDEX ix_library_item_share_grant ON library_item (share_grant_id)
    WHERE share_grant_id IS NOT NULL;
CREATE INDEX ix_resource_dependency_share_grant ON resource_dependency (share_grant_id)
    WHERE share_grant_id IS NOT NULL;
CREATE INDEX ix_authz_operation_live_recorded_seq ON authorization_operation (recorded_seq)
    WHERE state IN ('PENDING', 'APPLYING', 'RETRYABLE_FAILURE');
