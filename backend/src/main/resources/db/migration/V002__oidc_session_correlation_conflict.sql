-- Đổi id phiên sau đăng nhập vỡ khóa chính của oidc_session_correlation.
-- Spring Security đổi id phiên trong bộ nhớ trước (ChangeSessionIdAuthenticationStrategy),
-- registry OIDC ghi tương quan theo id mới ngay trong request, rồi Spring Session mới
-- flush UPDATE spring_session đổi id cũ sang mới ở cuối request; trigger đổi tương quan
-- cũ sang mới lúc đó đụng dòng đích đã tồn tại. Dòng cũ lúc đó đã lỗi thời (dòng đích
-- mang dữ kiện đăng nhập mới nhất) nên bỏ dòng cũ, giữ dòng đích.
CREATE OR REPLACE FUNCTION sync_oidc_correlation_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM oidc_session_correlation WHERE session_id = OLD.session_id;
    ELSIF OLD.session_id IS DISTINCT FROM NEW.session_id THEN
        DELETE FROM oidc_session_correlation WHERE session_id = OLD.session_id
            AND EXISTS (SELECT 1 FROM oidc_session_correlation WHERE session_id = NEW.session_id);
        UPDATE oidc_session_correlation SET session_id = NEW.session_id
        WHERE session_id = OLD.session_id;
    END IF;
    RETURN NULL;
END;
$$;
