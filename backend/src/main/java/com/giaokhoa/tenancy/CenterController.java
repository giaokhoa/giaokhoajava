package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.decision.KeycloakDecisionClient;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.decision.RequiredPermissions;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * RecoveryController (không qua fence nghiệp vụ của action thường).
 */
@RestController
public class CenterController {
    private static final Logger LOG = LoggerFactory.getLogger(CenterController.class);

    private static final JsonMapper JSON = new JsonMapper();
    private final CenterService centers;
    private final ProtectedActionExecutor executor;
    private final KeycloakDecisionClient decisions;
    private final PolicyGraphWriter policyWriter;
    private final IdempotencyService idempotency;

    public CenterController(
            CenterService centers,
            ProtectedActionExecutor executor,
            KeycloakDecisionClient decisions,
            PolicyGraphWriter policyWriter,
            IdempotencyService idempotency) {
        this.centers = centers;
        this.executor = executor;
        this.decisions = decisions;
        this.policyWriter = policyWriter;
        this.idempotency = idempotency;
    }

    public record CenterCreateRequest(@Nullable String name, @Nullable UUID ownerUserId) {}

    @PostMapping("/api/platform/centers")
    public ResponseEntity<IdempotencyService.OperationReceipt> createCenter(
            Authentication authentication,
            HttpServletRequest request,
            @RequestBody @Nullable CenterCreateRequest body,
            @RequestHeader("Idempotency-Key") String key) {
        requirePlatformScope(
                authentication,
                request,
                PermissionCatalog.PlatformScope.CENTER_CREATE,
                "Tài khoản không có quyền vận hành tạo trung tâm.");
        if (body == null) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thiếu chủ sở hữu của trung tâm.");
        }
        String name = body.name();
        UUID ownerUserId = body.ownerUserId();
        if (ownerUserId == null) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thiếu chủ sở hữu của trung tâm.");
        }
        if (name == null || name.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Thiếu tên trung tâm.");
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
                                        "POST", "/api/platform/centers", JSON.valueToTree(body))));
        return idempotency.responseFor(operation);
    }

    @GetMapping("/api/platform/centers")
    public ResponseEntity<List<CenterService.CenterView>> listCenters(
            Authentication authentication, HttpServletRequest request) {
        requirePlatformScope(
                authentication,
                request,
                PermissionCatalog.PlatformScope.CENTER_LIST,
                "Tài khoản không có quyền vận hành xem danh sách trung tâm.");
        return protectedOk(centers.listCenters());
    }

    private void requirePlatformScope(
            Authentication authentication,
            HttpServletRequest request,
            PermissionCatalog.PlatformScope scope,
            String detail) {
        Optional<String> resourceId;
        try {
            resourceId = policyWriter.findResourceId(NativePolicyTemplates.platformKey());
        } catch (IllegalStateException ex) {
            // Resource server chưa được bootstrap trong realm này thì chưa thể tồn tại grant
            // operator nào: từ chối như trường hợp không có resource, không coi là lỗi hệ thống.
            // Ghi WARN để vận hành phân biệt được realm chưa bootstrap với lời gọi trái phép.
            LOG.warn("Không tra được resource platform khi kiểm tra quyền operator", ex);
            resourceId = Optional.empty();
        }
        boolean allowed =
                resourceId.isPresent()
                        && decisions.isAllowed(
                                authentication,
                                request,
                                RequiredPermissions.of(
                                        RequiredPermissions.require(
                                                resourceId.get(), scope.scopeName())));
        if (!allowed) {
            throw new HttpProblemException(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
        }
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
