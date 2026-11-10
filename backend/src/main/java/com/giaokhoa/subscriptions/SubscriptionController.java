package com.giaokhoa.subscriptions;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpConstants;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.shared.http.Violation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP xác nhận gói sử dụng ở tầng nền tảng. Quyền operator được quyết bằng Keycloak PDP
 * trên resource platform với scope subscription.confirm, đúng mô hình của RecoveryController; tính
 * hợp lệ của chính kỳ gói (mốc thời gian, confirmationRef) do SubscriptionService kiểm tra.
 */
@RestController
public class SubscriptionController {
    private static final JsonMapper JSON = new JsonMapper();
    private final SubscriptionService subscriptions;
    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;

    public SubscriptionController(
            SubscriptionService subscriptions,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency) {
        this.subscriptions = subscriptions;
        this.executor = executor;
        this.idempotency = idempotency;
    }

    public record SubscriptionConfirmRequest(
            @NotNull @Nullable UUID workspaceId,
            @NotNull @Nullable OffsetDateTime validFrom,
            @NotNull @Nullable OffsetDateTime expiresAt,
            @Nullable String confirmationRef) {}

    @PostMapping("/api/platform/subscriptions")
    public ResponseEntity<IdempotencyService.OperationReceipt> confirmSubscription(
            Authentication authentication,
            HttpServletRequest request,
            @RequestBody @Valid @Nullable SubscriptionConfirmRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key) {
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(
                                        PermissionCatalog.PlatformScope.SUBSCRIPTION_CONFIRM
                                                .scopeName()))),
                () -> {
                    if (body == null) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu trường bắt buộc của kỳ gói.",
                                List.of(
                                        Violation.notNull("workspaceId"),
                                        Violation.notNull("validFrom"),
                                        Violation.notNull("expiresAt")));
                    }
                    UUID workspaceId = body.workspaceId();
                    OffsetDateTime validFrom = body.validFrom();
                    OffsetDateTime expiresAt = body.expiresAt();
                    if (workspaceId == null || validFrom == null || expiresAt == null) {
                        List<Violation> violations = new ArrayList<>();
                        if (workspaceId == null) {
                            violations.add(Violation.notNull("workspaceId"));
                        }
                        if (validFrom == null) {
                            violations.add(Violation.notNull("validFrom"));
                        }
                        if (expiresAt == null) {
                            violations.add(Violation.notNull("expiresAt"));
                        }
                        throw HttpProblemException.validationFailed(
                                "Thiếu trường bắt buộc của kỳ gói.", violations);
                    }
                    UUID actor = executor.actorUserId(authentication);
                    AuthorizationOperation operation =
                            Objects.requireNonNull(
                                    subscriptions.confirm(
                                            actor,
                                            workspaceId,
                                            validFrom.toInstant(),
                                            expiresAt.toInstant(),
                                            body.confirmationRef(),
                                            key,
                                            idempotency.payloadHash(
                                                    "POST",
                                                    "/api/platform/subscriptions",
                                                    JSON.valueToTree(body))));
                    return idempotency.responseFor(operation);
                });
    }
}
