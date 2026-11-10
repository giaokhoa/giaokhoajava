-- Cùng một thay thế như migration hệ thống V034 cho bản sao của
-- enforce_directory_immutable_links trong schema tenant: chuyển ba nhánh kiểm tra bất biến
-- liên kết thư mục sang IF/ELSIF lồng nhau để field riêng của mỗi bảng chỉ được chạm sau
-- khi TG_TABLE_NAME đã khớp, tránh lỗi "record old has no field kind" khi trigger bắn trên
-- center hoặc center_membership. Ngữ nghĩa và thông điệp lỗi giữ nguyên.
CREATE OR REPLACE FUNCTION enforce_directory_immutable_links() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_TABLE_NAME = 'workspace' THEN
        IF OLD.kind IS DISTINCT FROM NEW.kind
            OR OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id THEN
            RAISE EXCEPTION 'Workspace kind and personal owner cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'center' THEN
        IF OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
            OR OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id THEN
            RAISE EXCEPTION 'Center workspace and owner cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'center_membership' THEN
        IF OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
            OR OLD.user_id IS DISTINCT FROM NEW.user_id THEN
            RAISE EXCEPTION 'Membership workspace and user cannot change' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
