-- Đầu derived thuộc schema hiện tại; đầu source và grant có thể thuộc tenant khác.
-- Giữ ID nguồn để ứng dụng phân giải theo provenance, không ép nguồn có bản ghi tại nơi nhận.
ALTER TABLE resource_dependency DROP CONSTRAINT fk_resource_dependency_source_version;
ALTER TABLE resource_dependency DROP CONSTRAINT resource_dependency_share_grant_id_fkey;
