-- FR-055: hoàn tất là trạng thái lịch sử riêng, không phải REVOKED. Không sửa baseline.
ALTER TABLE enrollment ADD COLUMN completed_at timestamptz;
ALTER TABLE enrollment DROP CONSTRAINT ck_enrollment_revocation;
ALTER TABLE enrollment ADD CONSTRAINT ck_enrollment_revocation CHECK (
    (state IN ('PENDING','ACTIVE') AND revoked_at IS NULL AND completed_at IS NULL)
    OR (state = 'COMPLETED' AND revoked_at IS NULL AND completed_at IS NOT NULL)
    OR (state = 'REVOKED' AND revoked_at IS NOT NULL)
);
ALTER TABLE enrollment DROP CONSTRAINT enrollment_state_check;
ALTER TABLE enrollment ADD CONSTRAINT enrollment_state_check
    CHECK (state IN ('PENDING','ACTIVE','COMPLETED','REVOKED'));

-- Chặn hồi sinh COMPLETED bằng SQL trực tiếp, kể cả actor bypass service hoặc retry sau revoke.
CREATE FUNCTION guard_enrollment_completion_history() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.completed_at IS NOT NULL THEN
        IF NEW.completed_at IS DISTINCT FROM OLD.completed_at THEN
            RAISE EXCEPTION 'Enrollment completed_at is immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.state NOT IN ('COMPLETED', 'REVOKED') THEN
            RAISE EXCEPTION 'Completed enrollment cannot be reactivated' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_enrollment_completion_history BEFORE UPDATE ON enrollment
    FOR EACH ROW EXECUTE FUNCTION guard_enrollment_completion_history();
