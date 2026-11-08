package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP quản trị center ở tầng nền tảng: tạo center và liệt kê metadata của mọi center. Quyền
 * operator không suy ra từ role nội bộ nào: mỗi lời gọi đều hỏi Keycloak PDP bằng token của chính
 * người gọi trên resource platform với scope tương ứng, đúng mô hình quyết định của
 * RecoveryController, có ACCOUNT/target fence và đồng bộ eligibility trước mỗi quyết định.
 */
@RestController
public class CenterController {
    private static final JsonMapper JSON = new JsonMapper();
    private final CenterService centers;
    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;

    public CenterController(
            CenterService centers,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency) {
        this.centers = centers;
        this.executor = executor;
        this.idempotency = idempotency;
    }

    public record CenterCreateRequest(@Nullable String name, @Nullable UUID ownerUserId) {}

    @PostMapping("/api/platform/centers")
    public ResponseEntity<IdempotencyService.OperationReceipt> createCenter(
            Authentication authentication,
            HttpServletRequest request,
            @RequestBody @Nullable CenterCreateRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(
                                        PermissionCatalog.PlatformScope.CENTER_CREATE
                                                .scopeName()))),
                () -> {
                    if (body == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu chủ sở hữu của trung tâm.");
                    }
                    String name = body.name();
                    UUID ownerUserId = body.ownerUserId();
                    if (ownerUserId == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu chủ sở hữu của trung tâm.");
                    }
                    if (name == null || name.isBlank()) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu tên trung tâm.");
                    }
                    UUID actor = executor.actorUserId(authentication);
                    AuthorizationOperation operation =
                            Objects.requireNonNull(
                                    centers.createCenter(
                                            actor,
                                            name,
                                            ownerUserId,
                                            key,
                                            idempotency.payloadHash(
                                                    "POST",
                                                    "/api/platform/centers",
                                                    JSON.valueToTree(body))));
                    return idempotency.responseFor(operation);
                });
    }

    @GetMapping("/api/platform/centers")
    public ResponseEntity<List<CenterService.CenterView>> listCenters(
            Authentication authentication, HttpServletRequest request) {
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(PermissionCatalog.PlatformScope.CENTER_LIST.scopeName()))),
                () -> {
                    return protectedOk(centers.listCenters());
                });
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
