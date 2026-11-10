-- Đính chính nguồn của US7: correction_notice là thông báo một version của một
-- resource bị lỗi và version thay thế sửa nó; notification_receipt là xác nhận
-- theo từng workspace đã nhận version bị lỗi. FK composite
-- (resource_id, version_id) -> resource_version (resource_id, id) cho cả hai
-- phía theo đúng mẫu V019/V023/V024: version bị ảnh hưởng và version thay thế
-- đều phải thuộc đúng resource của notice, nên replacement không thể trỏ sang
-- resource khác. Unique (resource_id, id) của resource_version đã có từ V019.
--
-- Hai bảng mới là bản ghi canonical như resource/resource_version/
-- resource_dependency (V008/V024, không RLS): data-model.md chỉ liệt kê RLS
-- cho các bảng tenant mang workspace_id của chính hàng và xếp nhóm canonical
-- resource/version/dependency dùng repository riêng; correction_notice không
-- có cột workspace nào. notification_receipt có recipient_workspace_id nhưng
-- nó là nơi nhận của một notice canonical xuyên workspace, không phải
-- workspace sở hữu của hàng: người đọc hợp lệ là cả phía phát hành notice (ở
-- workspace của resource nguồn) lẫn phía nhận, nên policy một context kiểu
-- V023 sẽ chặn chính thao tác ghi của phía không khớp context. Quyền xem một
-- version vẫn qua PDP/repository riêng; receipt không tự cấp quyền gì (FR-034).
-- Căn cứ đầy đủ ghi ở Sổ B81.
CREATE TABLE correction_notice (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL,
    affected_version_id UUID NOT NULL,
    replacement_version_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_correction_notice_affected_version
        FOREIGN KEY (resource_id, affected_version_id)
        REFERENCES resource_version (resource_id, id),
    CONSTRAINT fk_correction_notice_replacement_version
        FOREIGN KEY (resource_id, replacement_version_id)
        REFERENCES resource_version (resource_id, id),
    CONSTRAINT ck_correction_notice_replacement_differs CHECK (
        replacement_version_id <> affected_version_id)
);
CREATE INDEX ix_correction_notice_resource ON correction_notice (resource_id);

-- Notice là lịch sử đính chính bất biến: mọi trường đã ghi là căn cứ truy vết
-- (version nào lỗi, thay bằng version nào, vì sao) nên không sửa và không
-- hard-delete; đính chính sai thì tạo notice mới, không viết lại lịch sử.
CREATE FUNCTION guard_correction_notice_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Correction notice history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Correction notice cannot be modified' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_correction_notice_preserve_history BEFORE UPDATE OR DELETE
    ON correction_notice
    FOR EACH ROW EXECUTE FUNCTION guard_correction_notice_history();

-- Receipt theo từng notice và workspace nhận: mỗi nơi nhận version bị lỗi chỉ
-- có một receipt cho một notice (PRIMARY KEY cặp), ghi mốc xác nhận khi nơi
-- nhận đã biết đính chính. Receipt chỉ là dữ kiện thông báo, không tham chiếu
-- tới nguồn quyền nào và không đổi được quyền xem version (FR-034).
CREATE TABLE notification_receipt (
    notice_id UUID NOT NULL REFERENCES correction_notice(id),
    recipient_workspace_id UUID NOT NULL REFERENCES workspace(id),
    acknowledged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notification_receipt PRIMARY KEY (notice_id, recipient_workspace_id)
);
CREATE INDEX ix_notification_receipt_recipient ON notification_receipt (recipient_workspace_id);

-- Cặp nhận diện của receipt bất biến và receipt không hard-delete (mất receipt
-- là mất bằng chứng nơi nhận đã được thông báo); chỉ acknowledged_at được ghi
-- một lần khi nơi nhận xác nhận, không xóa mốc đã xác nhận.
CREATE FUNCTION guard_notification_receipt_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Notification receipt history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.notice_id IS DISTINCT FROM NEW.notice_id
        OR OLD.recipient_workspace_id IS DISTINCT FROM NEW.recipient_workspace_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at
        OR (OLD.acknowledged_at IS NOT NULL
            AND OLD.acknowledged_at IS DISTINCT FROM NEW.acknowledged_at) THEN
        RAISE EXCEPTION 'Notification receipt identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ck_notification_receipt_preserve_history BEFORE UPDATE OR DELETE
    ON notification_receipt
    FOR EACH ROW EXECUTE FUNCTION guard_notification_receipt_history();
