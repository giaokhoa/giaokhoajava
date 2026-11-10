-- enforce_directory_immutable_links dùng chung cho ba trigger trên workspace, center và
-- center_membership. Bản gốc gộp tên bảng và field riêng của bảng đó vào cùng một biểu thức
-- IF ... AND (OLD.<field> ...): khi trigger bắn trên center hoặc center_membership (hai bảng
-- không có cột kind), PostgreSQL vẫn phải phân giải OLD.kind khi lập kế hoạch biểu thức và
-- ném "record old has no field kind". Đường JDBC cũ chỉ SET đúng cột đã đổi nên trigger
-- UPDATE OF workspace_id, user_id trên center_membership gần như không bắn; đường JPA flush
-- toàn bộ cột của entity nên trigger bắn và lỗi lộ ra. Viết lại bằng IF/ELSIF lồng nhau để
-- field riêng của mỗi bảng chỉ được chạm sau khi TG_TABLE_NAME đã khớp bảng đó, giữ nguyên
-- ngữ nghĩa và thông điệp lỗi.
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
