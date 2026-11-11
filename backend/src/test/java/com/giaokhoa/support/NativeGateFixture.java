package com.giaokhoa.support;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixture dữ kiện giả đã biết cho gate native, chỉ tồn tại trong mã test/review. Nó seed thư mục
 * (user_account + personal workspace, partition do trigger của DB tạo) và operation bền qua SQL
 * trên stack TEST, để phép thử gate lái các endpoint production bằng phiên thật của chính actor.
 * Fixture không chứa quyết định quyền nào: không nhận người dùng thay thế, không nhận tham số kỳ
 * vọng cho phép/từ chối; mọi quyết định phải đến từ PEP/PAP production. Tóm tắt chẩn đoán chỉ gồm
 * metadata trạng thái, tuyệt đối không chứa token, mật khẩu, idempotency key hay payload hash.
 */
public final class NativeGateFixture {
    private final JdbcTemplate jdbc;

    public NativeGateFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Actor giả đã biết cùng workspace cá nhân và hai partition gốc của nó. */
    public record Actor(
            UUID userId, UUID workspaceId, UUID accountPartition, UUID workspacePartition) {}

    public Actor seedActor(String keycloakSub) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                actor,
                keycloakSub,
                "native-gate-actor-" + actor,
                "native-gate-" + actor + "@example.invalid");
        jdbc.update(
                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?,'PERSONAL',?,'ACTIVE')",
                workspace,
                actor);
        UUID account =
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                        UUID.class,
                        "account:" + actor);
        UUID workspacePartition =
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                        UUID.class,
                        "workspace:" + workspace);
        return new Actor(
                actor,
                workspace,
                Objects.requireNonNull(account, "account partition"),
                Objects.requireNonNull(workspacePartition, "workspace partition"));
    }

    /**
     * Ghi một operation bền cho actor ở trạng thái yêu cầu. Operation SUCCEEDED chỉ được ghi sau
     * khi mọi partition bị ảnh hưởng đã READY đúng revision, khớp ràng buộc deferred của DB; các
     * trạng thái chưa hoàn tất giữ nguyên fence.
     */
    public UUID seedOperation(
            Actor actor, AuthorizationOperation.State state, @Nullable String errorCode) {
        UUID operation = UUID.randomUUID();
        // Hash payload tổng hợp suy từ chính ID operation: đủ định dạng 64 hex, không phải secret.
        String payloadHash = operation.toString().replace("-", "").repeat(2);
        jdbc.update(
                "INSERT INTO authorization_operation(id,initiator_user_id,workspace_id,"
                        + "idempotency_key,payload_hash,affected_partitions,affected_revisions,desired_revision)"
                        + " VALUES (?,?,?,?,?,ARRAY[?::uuid,?::uuid],"
                        + " ARRAY[0::bigint,0::bigint],0)",
                operation,
                actor.userId(),
                actor.workspaceId(),
                "native-gate-fixture-" + operation,
                payloadHash,
                actor.accountPartition(),
                actor.workspacePartition());
        switch (state) {
            // Các trạng thái chưa hoàn tất được ghim next_attempt_at ở tương lai: worker nền của
            // ứng dụng claim mọi operation tới hạn mỗi giây, nên một trạng thái seed chỉ ổn định
            // để gate đối chiếu khi lần thử kế tiếp chưa tới hạn.
            case PENDING, APPLYING ->
                    jdbc.update(
                            "UPDATE authorization_operation SET state = ?,"
                                    + " next_attempt_at = now() + interval '1 day' WHERE id = ?",
                            state.name(),
                            operation);
            case SUCCEEDED -> {
                jdbc.update(
                        "UPDATE authz_partition SET state = 'READY' WHERE id IN (?, ?)",
                        actor.accountPartition(),
                        actor.workspacePartition());
                jdbc.update(
                        "UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?",
                        operation);
            }
            case RETRYABLE_FAILURE ->
                    jdbc.update(
                            "UPDATE authorization_operation SET state = 'RETRYABLE_FAILURE',"
                                    + " last_error_code = ?,"
                                    + " next_attempt_at = now() + interval '1 day' WHERE id = ?",
                            errorCode,
                            operation);
            case FAILED ->
                    jdbc.update(
                            "UPDATE authorization_operation SET state = 'FAILED', last_error_code = ?"
                                    + " WHERE id = ?",
                            errorCode,
                            operation);
        }
        return operation;
    }

    /**
     * Tóm tắt chẩn đoán cho một operation. Chỉ chọn các cột trạng thái: idempotency key và payload
     * hash không bao giờ được truy vấn vào đây, nên chuỗi kết quả không thể chứa chúng.
     */
    public String diagnostics(UUID operationId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT o.state AS state, o.last_error_code AS error_code,"
                                + " o.workspace_id AS workspace_id,"
                                + " (SELECT string_agg(p.kind || ':' || p.state || ':'"
                                + " || p.applied_revision || '/' || p.desired_revision, ','"
                                + " ORDER BY t.idx) FROM unnest(o.affected_partitions)"
                                + " WITH ORDINALITY AS t(partition_id, idx)"
                                + " JOIN authz_partition p ON p.id = t.partition_id)"
                                + " AS partitions"
                                + " FROM authorization_operation o WHERE o.id = ?",
                        operationId);
        if (rows.isEmpty()) {
            return "operation=" + operationId + " state=MISSING";
        }
        Map<String, Object> row = rows.getFirst();
        String state = String.valueOf(row.get("state"));
        boolean completed = AuthorizationOperation.State.SUCCEEDED.name().equals(state);
        return "operation="
                + operationId
                + " state="
                + state
                + " completed="
                + completed
                + " workspace="
                + row.get("workspace_id")
                + " partitions=["
                + row.get("partitions")
                + "]"
                + " errorCode="
                + (row.get("error_code") == null ? "-" : row.get("error_code"));
    }
}
