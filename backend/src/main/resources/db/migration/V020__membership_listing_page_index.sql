-- Keyset pagination for membership listing in stable (joined_at, id) order.
-- Revoked rows remain accessible by the primary key as cursor anchors.
CREATE INDEX ix_center_membership_listing_page
    ON center_membership (workspace_id, joined_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
