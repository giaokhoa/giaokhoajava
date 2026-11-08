package com.giaokhoa.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.identity.RecoveryService.Decision;
import com.giaokhoa.identity.RecoveryService.RequestView;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP của luồng hỗ trợ khôi phục danh tính: người dùng gửi yêu cầu, platform operator xem
 * hàng đợi và quyết định. Quyền operator không suy ra từ role nội bộ nào: mỗi lời gọi đều hỏi
 * Keycloak PDP bằng token của chính người gọi trên resource platform với scope account.recover, qua
 * ACCOUNT/target fence và đồng bộ eligibility trước mỗi quyết định.
 */
@RestController
public class RecoveryController {
    private static final JsonMapper JSON = new JsonMapper();
    private final IdempotencyService idempotency;
    private final RecoveryService recovery;
    private final ProtectedActionExecutor executor;

    public RecoveryController(
            RecoveryService recovery,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency) {
        this.recovery = recovery;
        this.executor = executor;
        this.idempotency = idempotency;
    }

    public record SubmitRequest(
            @Nullable UUID targetUserId, @Nullable UUID workspaceId, @Nullable String reason) {}

    public record SubmitReceipt(
            UUID operationId, String state, boolean completed, String statusUrl) {}

    public record ResolveRequest(@Nullable String decision, @Nullable String verificationNote) {}

    public record ResolveReceipt(
            UUID id, String state, boolean completed, String targetType, UUID targetId) {}

    public record RequestItem(
            UUID id,
            UUID requesterUserId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID workspaceId,
            UUID targetUserId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String reason,
            String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID verifiedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Instant resolvedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String verificationNote,
            Instant createdAt,
            long version) {}

    @PostMapping("/api/me/recovery-requests")
    public ResponseEntity<SubmitReceipt> submit(
            Authentication authentication,
            @RequestBody @Nullable SubmitRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        if (body == null || body.targetUserId() == null) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Thiếu tài khoản đích của yêu cầu.");
        }
        // Giữ lý do trong biến cục bộ: gọi body.reason() hai lần khiến phân tích null
        // không nối được lần kiểm tra với lần dùng.
        String reason = body.reason();
        if (reason == null || reason.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thiếu lý do cần hỗ trợ.");
        }
        UUID actor = executor.actorUserId(authentication);
        RequestView created =
                Objects.requireNonNull(
                        recovery.submitOnce(
                                actor,
                                body.targetUserId(),
                                body.workspaceId(),
                                reason,
                                key,
                                idempotency.payloadHash(
                                        "POST",
                                        "/api/me/recovery-requests",
                                        JSON.valueToTree(body))));
        // Yêu cầu được ghi đồng bộ ngay trong lời gọi này nhưng vòng đời của nó chưa kết thúc:
        // biên nhận 202 mang chính ID của yêu cầu làm operationId để client poll qua endpoint
        // operations chung, nơi trạng thái yêu cầu được ánh xạ sang vòng đời operation.
        SubmitReceipt receipt =
                new SubmitReceipt(
                        created.id(), "PENDING", false, "/api/me/operations/" + created.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(receipt);
    }

    @GetMapping("/api/platform/recovery-requests")
    public ResponseEntity<List<RequestItem>> list(
            Authentication authentication, HttpServletRequest request) {
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(
                                        PermissionCatalog.PlatformScope.ACCOUNT_RECOVER
                                                .scopeName()))),
                () -> {
                    List<RequestItem> items =
                            recovery.listRequests().stream().map(RecoveryController::item).toList();
                    return protectedOk(items);
                });
    }

    @PostMapping("/api/platform/recovery-requests/{requestId}/resolve")
    public ResponseEntity<?> resolve(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable UUID requestId,
            @RequestBody @Nullable ResolveRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch) {
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(
                                        PermissionCatalog.PlatformScope.ACCOUNT_RECOVER
                                                .scopeName()))),
                () -> {
                    if (body == null || body.decision() == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu quyết định xử lý yêu cầu.");
                    }
                    Decision decision;
                    try {
                        decision = Decision.valueOf(body.decision());
                    } catch (IllegalArgumentException ex) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Quyết định xử lý không hợp lệ.",
                                ex);
                    }
                    UUID operator = executor.actorUserId(authentication);
                    RequestView resolved =
                            Objects.requireNonNull(
                                    recovery.resolveOnce(
                                            operator,
                                            requestId,
                                            decision,
                                            body.verificationNote(),
                                            key,
                                            idempotency.payloadHash(
                                                    "POST",
                                                    "/api/platform/recovery-requests/"
                                                            + requestId
                                                            + "/resolve",
                                                    JSON.valueToTree(List.of(body, ifMatch))),
                                            ifMatch));
                    if (resolved.state()
                            == com.giaokhoa.identity.model.RecoveryRequest.State.VERIFIED) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED)
                                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                .body(
                                        new SubmitReceipt(
                                                resolved.id(),
                                                "PENDING",
                                                false,
                                                "/api/me/operations/" + resolved.id()));
                    }
                    ResolveReceipt receipt =
                            new ResolveReceipt(
                                    resolved.id(),
                                    "SUCCEEDED",
                                    true,
                                    "PROFILE",
                                    resolved.targetUserId());
                    return protectedOk(receipt);
                });
    }

    private static RequestItem item(RequestView view) {
        return new RequestItem(
                view.id(),
                view.requesterUserId(),
                view.workspaceId(),
                view.targetUserId(),
                view.reason(),
                view.state().name(),
                view.verifiedBy(),
                view.resolvedAt(),
                view.verificationNote(),
                view.createdAt(),
                view.version());
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
