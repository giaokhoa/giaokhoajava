package com.giaokhoa.authorization.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.policy.NativePolicyTemplates.TargetType;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.identity.model.RecoveryMutation;
import com.giaokhoa.identity.repository.RecoveryMutationRepository;
import com.giaokhoa.identity.repository.RecoveryRequestRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final AuthorizationOperationRepository operations;
    private final AuthzPartitionRepository partitions;
    private final RecoveryRequestRepository recoveryRequests;
    private final RecoveryMutationRepository recoveryMutations;

    public OperationController(
            ProtectedActionExecutor executor,
            AuthorizationOperationRepository operations,
            AuthzPartitionRepository partitions,
            RecoveryRequestRepository recoveryRequests,
            RecoveryMutationRepository recoveryMutations) {
        this.executor = executor;
        this.operations = operations;
        this.partitions = partitions;
        this.recoveryRequests = recoveryRequests;
        this.recoveryMutations = recoveryMutations;
    }

    public record OperationView(
            UUID id,
            String state,
            boolean completed,
            String targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID targetId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String errorCode) {}

    @GetMapping("/api/me/operations/{operationId}")
    public ResponseEntity<OperationView> status(
            @PathVariable UUID operationId, Authentication authentication) {
        UUID actorUserId = executor.actorUserId(authentication);
        var found = operations.findByIdAndInitiatorUserId(operationId, actorUserId);
        if (found.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .body(recoveryStatus(operationId, actorUserId));
        }
        AuthorizationOperation operation = found.get();
        AuthorizationPartition primary =
                operation.getAffectedPartitions().isEmpty()
                        ? null
                        : partitions
                                .findById(operation.getAffectedPartitions().getFirst())
                                .orElse(null);
        TargetRef target = targetOf(primary, operation.getWorkspaceId(), actorUserId);
        OperationView view =
                new OperationView(
                        operationId,
                        operation.getState().name(),
                        operation.getState() == AuthorizationOperation.State.SUCCEEDED,
                        target.type().name(),
                        target.id(),
                        operation.getLastErrorCode());
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
        var found =
                recoveryRequests
                        .findById(operationId)
                        .filter(
                                request ->
                                        request.getRequesterUserId().equals(actorUserId)
                                                || recoveryMutations
                                                        .existsByRequestIdAndActorAndKind(
                                                                operationId,
                                                                actorUserId,
                                                                RecoveryMutation.Kind.RESOLVE));
        if (found.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
        }
        var request = found.get();
        String requestState = request.getState().name();
        String state =
                switch (requestState) {
                    case "RESOLVED" -> "SUCCEEDED";
                    case "REJECTED" -> "FAILED";
                    default -> "PENDING";
                };
        UUID targetId = request.getTargetUserId();
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
    private static TargetRef targetOf(
            @Nullable AuthorizationPartition primary,
            @Nullable UUID workspaceId,
            UUID actorUserId) {
        AuthorizationPartition.@Nullable Kind kind = primary == null ? null : primary.getKind();
        UUID ownerId = primary == null ? null : trailingUuid(primary.getOwnerKey());
        if (kind == AuthorizationPartition.Kind.ACCOUNT) {
            return new TargetRef(TargetType.PROFILE, ownerId != null ? ownerId : actorUserId);
        }
        if (kind == AuthorizationPartition.Kind.GRANT && ownerId != null) {
            return new TargetRef(TargetType.GRANT, ownerId);
        }
        if (workspaceId != null) {
            return new TargetRef(TargetType.WORKSPACE, workspaceId);
        }
        if (kind == AuthorizationPartition.Kind.WORKSPACE && ownerId != null) {
            return new TargetRef(TargetType.WORKSPACE, ownerId);
        }
        return new TargetRef(TargetType.PROFILE, actorUserId);
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
