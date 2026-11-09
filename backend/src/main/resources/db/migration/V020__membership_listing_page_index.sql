-- Phân trang membership theo thứ tự ổn định (joined_at, id).
-- Hàng đã thu hồi vẫn tra được qua khóa chính để làm mốc cursor.
CREATE INDEX ix_center_membership_listing_page
    ON center_membership (workspace_id, joined_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
