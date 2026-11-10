package com.giaokhoa.tenancy;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP quản trị center ở tầng nền tảng: tạo center và liệt kê metadata của mọi center. Quyền
 * operator không suy ra từ role nội bộ nào: mỗi lời gọi đều hỏi Keycloak PDP bằng token của chính
 * người gọi trên resource platform với scope tương ứng, đúng mô hình quyết định của
 * RecoveryController, có ACCOUNT/target fence và đồng bộ eligibility trước mỗi quyết định.
 */
@RestController
@RequestMapping("/api/platform/centers")
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

    public record CenterCreateRequest(
            @NotBlank @Size(max = 255) @Nullable String name,
            @NotNull @Nullable UUID ownerUserId) {}

    @PostMapping
    public ResponseEntity<IdempotencyService.OperationReceipt> createCenter(
            Authentication authentication,
            HttpServletRequest request,
            @RequestBody @Valid @Nullable CenterCreateRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key) {
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
                        throw HttpProblemException.validationFailed(
                                "Thiếu chủ sở hữu của trung tâm.",
                                List.of(
                                        new Violation(
                                                "ownerUserId",
                                                "NotNull",
                                                "Giá trị không hợp lệ.")));
                    }
                    String name = body.name();
                    UUID ownerUserId = body.ownerUserId();
                    if (ownerUserId == null) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu chủ sở hữu của trung tâm.",
                                List.of(
                                        new Violation(
                                                "ownerUserId",
                                                "NotNull",
                                                "Giá trị không hợp lệ.")));
                    }
                    if (name == null || name.isBlank()) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu tên trung tâm.",
                                List.of(
                                        new Violation(
                                                "name", "NotBlank", "Giá trị không hợp lệ.")));
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

    public record CenterPage(
            List<CenterService.CenterView> items,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    @Nullable String nextCursor) {
        public CenterPage {
            items = List.copyOf(items);
        }
    }

    @GetMapping
    public ResponseEntity<CenterPage> listCenters(
            Authentication authentication,
            HttpServletRequest request,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit) {
        UUID actor = executor.actorUserId(authentication);
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.platformKey(),
                                List.of(PermissionCatalog.PlatformScope.CENTER_LIST.scopeName()))),
                () -> {
                    CenterService.CenterPage page =
                            centers.pageCenters(actor, cursor, limit == null ? 20 : limit);
                    return protectedOk(new CenterPage(page.items(), page.nextCursor()));
                });
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
