package com.giaokhoa.authorization.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.policy.NativePolicyTemplates.TargetType;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trạng thái operation cho chính initiator. Đây là đường đọc control-plane theo phiên: nó cố ý
 * không đi qua fence/PDP nghiệp vụ, vì mục đích của nó là cho initiator theo dõi lúc projection còn
 * PENDING — đúng trạng thái mà fence sẽ chặn. Chỉ metadata điều khiển được trả: không payload
 * nghiệp vụ, không idempotency key, không payload hash; operation của người khác được giấu thành
 * 404 để không lộ sự tồn tại.
 */
@RestController
public class OperationController {
    private final ProtectedActionExecutor executor;
    private final JdbcTemplate jdbc;

    public OperationController(ProtectedActionExecutor executor, JdbcTemplate jdbc) {
        this.executor = executor;
        this.jdbc = jdbc;
    }

    public record OperationView(
            UUID id,
            String state,
            boolean completed,
            String targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID targetId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String errorCode) {}

    @GetMapping("/api/me/operations/{operationId}")
    public ResponseEntity<OperationView> getStatus(
            @PathVariable UUID operationId, Authentication authentication) {
        UUID actorUserId = executor.actorUserId(authentication);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT o.state AS state, o.last_error_code AS last_error_code,"
                                + " o.workspace_id AS workspace_id, p.kind AS primary_kind,"
                                + " p.owner_key AS primary_owner_key"
                                + " FROM authorization_operation o"
                                + " LEFT JOIN authz_partition p"
                                + " ON p.id = o.affected_partitions[1]"
                                + " WHERE o.id = ? AND o.initiator_user_id = ?",
                        operationId,
                        actorUserId);
        if (rows.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .body(recoveryStatus(operationId, actorUserId));
        }
        Map<String, Object> row = rows.getFirst();
        String state = String.valueOf(row.get("state"));
        TargetRef target = targetOf(row, actorUserId);
        OperationView view =
                new OperationView(
                        operationId,
                        state,
                        AuthorizationOperation.State.SUCCEEDED.name().equals(state),
                        target.type().name(),
                        target.id(),
                        row.get("last_error_code") instanceof String code ? code : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(view);
    }

    /**
     * Yêu cầu khôi phục danh tính không phải authorization operation nhưng biên nhận gửi yêu cầu
     * mang chính ID của nó làm operationId, nên đường poll này tra tiếp bảng yêu cầu khi không thấy
     * operation: người gửi hoặc operator đã gửi mutation xử lý được theo dõi biên nhận của mình.
     * Trạng thái yêu cầu được ánh xạ sang vòng đời operation và đích là profile của tài khoản được
     * hỗ trợ.
     */
    private OperationView recoveryStatus(UUID operationId, UUID actorUserId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT state, target_user_id FROM recovery_request"
                                + " WHERE id = ? AND (requester_user_id = ? OR EXISTS"
                                + " (SELECT 1 FROM recovery_mutation m WHERE m.request_id = recovery_request.id"
                                + " AND m.actor_user_id = ? AND m.kind = 'RESOLVE'))",
                        operationId,
                        actorUserId,
                        actorUserId);
        if (rows.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Không tìm thấy tài nguyên.");
        }
        Map<String, Object> row = rows.getFirst();
        String requestState = String.valueOf(row.get("state"));
        String state =
                switch (requestState) {
                    case "RESOLVED" -> "SUCCEEDED";
                    case "REJECTED" -> "FAILED";
                    default -> "PENDING";
                };
        UUID targetId = row.get("target_user_id") instanceof UUID id ? id : actorUserId;
        return new OperationView(
                operationId,
                state,
                "SUCCEEDED".equals(state) || "FAILED".equals(state),
                TargetType.PROFILE.name(),
                targetId,
                // Yêu cầu bị từ chối phải mang mã lỗi ổn định để client phân biệt được với lỗi
                // hệ thống khi poll: ghi chú từ chối của operator chỉ operator thấy ở danh sách
                // platform, không trả qua poll của người gửi.
                "REJECTED".equals(requestState) ? "RECOVERY_REJECTED" : null);
    }

    private record TargetRef(TargetType type, @Nullable UUID id) {}

    /**
     * Đích chính của operation là partition đầu tiên bị ảnh hưởng — partition giữ desired_revision
     * của operation — và owner key của nó chính là target key gốc: account của initiator, workspace
     * đích hoặc grant đích.
     */
    private static TargetRef targetOf(Map<String, Object> row, UUID actorUserId) {
        AuthorizationPartition.Kind kind = partitionKind(row.get("primary_kind"));
        UUID ownerId =
                row.get("primary_owner_key") instanceof String key ? trailingUuid(key) : null;
        if (kind == AuthorizationPartition.Kind.ACCOUNT) {
            return new TargetRef(TargetType.PROFILE, ownerId != null ? ownerId : actorUserId);
        }
        if (kind == AuthorizationPartition.Kind.GRANT && ownerId != null) {
            return new TargetRef(TargetType.GRANT, ownerId);
        }
        if (row.get("workspace_id") instanceof UUID workspaceId) {
            return new TargetRef(TargetType.WORKSPACE, workspaceId);
        }
        if (kind == AuthorizationPartition.Kind.WORKSPACE && ownerId != null) {
            return new TargetRef(TargetType.WORKSPACE, ownerId);
        }
        return new TargetRef(TargetType.PROFILE, actorUserId);
    }

    private static AuthorizationPartition.@Nullable Kind partitionKind(@Nullable Object value) {
        if (value instanceof String name) {
            try {
                return AuthorizationPartition.Kind.valueOf(name);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable UUID trailingUuid(String ownerKey) {
        int split = ownerKey.lastIndexOf(':');
        if (split < 0 || split == ownerKey.length() - 1) {
            return null;
        }
        try {
            return UUID.fromString(ownerKey.substring(split + 1));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
