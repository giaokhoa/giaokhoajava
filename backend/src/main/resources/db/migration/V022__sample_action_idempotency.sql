-- Chỉ lưu biên nhận mutation của sample action; đây không phải work queue. Sample action
-- trả kết quả đồng bộ 201 nên không có authorization_operation để replay như các mutation
-- bất đồng bộ; biên nhận giữ đúng kết quả đầu tiên theo tiền lệ recovery_mutation (V011):
-- cùng key và cùng payload thì trả lại target cũ, khác payload thì 409.
CREATE TABLE sample_action_mutation (
    actor_user_id UUID NOT NULL REFERENCES user_account(id),
    idempotency_key VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    target_type VARCHAR(16) NOT NULL
        CHECK (target_type IN ('RESOURCE', 'ACTIVITY', 'SUBMISSION', 'RESULT')),
    target_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_user_id, idempotency_key)
);
