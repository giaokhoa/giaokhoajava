package com.giaokhoa.authorization.decision;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khả năng của actor hiện tại: với mỗi target/scope được yêu cầu thuộc workspace của route, đây là
 * quyết định PDP trực tiếp lấy qua fence của dependency manifest. View này chỉ phục vụ UI; nó không
 * cấp quyền gì và không lộ định danh policy hay group native.
 */
@RestController
public class CapabilitiesController {
    private final ProtectedActionExecutor executor;

    public CapabilitiesController(ProtectedActionExecutor executor) {
        this.executor = executor;
    }

    public record TargetInput(
            @NotBlank String type,
            @Nullable UUID id,
            @Nullable UUID versionId,
            List<String> scopes) {
        public TargetInput {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record CapabilitiesRequest(List<@Valid TargetInput> targets) {
        public CapabilitiesRequest {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    public record ScopeDecision(String scope, boolean allowed, String reasonCode) {}

    public record TargetDecision(
            String type, @Nullable UUID id, @Nullable UUID versionId, List<ScopeDecision> scopes) {
        public TargetDecision {
            scopes = List.copyOf(scopes);
        }
    }

    public record CapabilitiesView(List<TargetDecision> targets) {
        public CapabilitiesView {
            targets = List.copyOf(targets);
        }
    }

    @PostMapping("/api/workspaces/{workspaceId}/capabilities")
    public ResponseEntity<CapabilitiesView> evaluateCapabilities(
            @PathVariable UUID workspaceId,
            @RequestBody @Valid CapabilitiesRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request == null || request.targets() == null || request.targets().isEmpty()) {
            throw invalidTarget();
        }
        UUID actorUserId = executor.actorUserId(authentication);
        List<ProtectedActionExecutor.Target> targets =
                request.targets().stream()
                        .map(input -> toTarget(workspaceId, actorUserId, input))
                        .toList();
        ProtectedActionExecutor.Decisions decisions =
                executor.evaluate(authentication, httpRequest, workspaceId, targets);
        List<TargetDecision> view =
                request.targets().stream()
                        .map(input -> {
                            String key = targetKey(workspaceId, actorUserId, input);
                            List<ScopeDecision> scopes =
                                    input.scopes().stream()
                                            .map(scope -> {
                                                boolean allowed = decisions.allowed(key, scope);
                                                return new ScopeDecision(
                                                        scope,
                                                        allowed,
                                                        allowed ? "ALLOWED" : "DENIED");
                                            })
                                            .toList();
                            return new TargetDecision(
                                    input.type(), input.id(), input.versionId(), scopes);
                        })
                        .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new CapabilitiesView(view));
    }

    private static ProtectedActionExecutor.Target toTarget(
            UUID workspaceId, UUID actorUserId, TargetInput input) {
        if (input == null || input.scopes() == null || input.scopes().isEmpty()) {
            throw invalidTarget();
        }
        try {
            return new ProtectedActionExecutor.Target(
                    targetKey(workspaceId, actorUserId, input), input.scopes());
        } catch (IllegalArgumentException ex) {
            throw invalidTarget(ex);
        }
    }

    private static String targetKey(UUID workspaceId, UUID actorUserId, TargetInput input) {
        NativePolicyTemplates.TargetType type;
        try {
            type = NativePolicyTemplates.TargetType.valueOf(String.valueOf(input.type()));
        } catch (IllegalArgumentException ex) {
            throw invalidTarget(ex);
        }
        return switch (type) {
            case PLATFORM -> NativePolicyTemplates.platformKey();
            case PROFILE -> {
                // Target profile là chính tài khoản của actor; hợp đồng giới hạn capability
                // target trong workspace của route và actor hiện tại.
                if (!actorUserId.equals(requireId(input))) {
                    throw invalidTarget();
                }
                yield NativePolicyTemplates.profileKey(actorUserId);
            }
            case WORKSPACE -> {
                if (!workspaceId.equals(input.id())) {
                    throw invalidTarget();
                }
                yield NativePolicyTemplates.workspaceKey(workspaceId);
            }
            case CLASS -> NativePolicyTemplates.classKey(workspaceId, requireId(input));
            case COURSE -> NativePolicyTemplates.courseKey(workspaceId, requireId(input));
            case COURSE_SUBJECT ->
                    NativePolicyTemplates.courseSubjectKey(workspaceId, requireId(input));
            case RESOURCE -> NativePolicyTemplates.resourceKey(workspaceId, requireId(input));
            case RESOURCE_VERSION -> {
                UUID versionId = input.versionId();
                if (versionId == null) {
                    throw invalidTarget();
                }
                yield NativePolicyTemplates.resourceVersionKey(
                        workspaceId, requireId(input), versionId);
            }
            case ACTIVITY -> NativePolicyTemplates.activityKey(workspaceId, requireId(input));
            case RESULT -> NativePolicyTemplates.resultKey(workspaceId, requireId(input));
            case GRANT -> NativePolicyTemplates.grantKey(requireId(input));
        };
    }

    private static UUID requireId(TargetInput input) {
        UUID id = input.id();
        if (id == null) {
            throw invalidTarget();
        }
        return id;
    }

    private static HttpProblemException invalidTarget() {
        return invalidTarget(null);
    }

    private static HttpProblemException invalidTarget(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.INVALID_TARGET,
                "Đối tượng đích không hợp lệ.",
                cause);
    }
}
