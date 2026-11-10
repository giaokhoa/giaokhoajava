package com.giaokhoa.authorization.decision;

import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.fence.DependencyManifestResolver;
import com.giaokhoa.authorization.fence.DependencyManifestResolver.Manifest;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Pipeline của protected action theo hợp đồng authorization: resolve actor từ phiên, resolve
 * dependency manifest, giữ transaction fence, hỏi Keycloak PDP về mọi cặp resource/scope bắt buộc
 * bằng token của chính user — ngoài mọi transaction cơ sở dữ liệu — rồi mới chạy thao tác đọc hoặc
 * mutation của caller bên trong một fence transaction thứ hai chứng minh các revision đã ghi không
 * đổi. Closure hoặc revision thay đổi sẽ khởi động lại pipeline với manifest mới; partition đang bị
 * fence hoặc quyết định không xác minh được sẽ fail closed với 503, native deny với 403 cho action.
 */
@Component
public class ProtectedActionExecutor {
    private static final int MAX_ATTEMPTS = 3;

    private final DependencyManifestResolver resolver;
    private final AuthorizationFence fence;
    private final KeycloakDecisionClient decisions;
    private final PolicyGraphWriter writer;
    private final JdbcTemplate jdbc;
    private final IdentityEligibilityService eligibility;

    public ProtectedActionExecutor(
            DependencyManifestResolver resolver,
            AuthorizationFence fence,
            KeycloakDecisionClient decisions,
            PolicyGraphWriter writer,
            JdbcTemplate jdbc,
            IdentityEligibilityService eligibility) {
        this.resolver = resolver;
        this.fence = fence;
        this.decisions = decisions;
        this.writer = writer;
        this.jdbc = jdbc;
        this.eligibility = eligibility;
    }

    /** Một target của protected request cùng các scope theo template cố định mà nó cần. */
    public record Target(String targetKey, List<String> scopes) {
        public Target {
            NativePolicyTemplates.TargetType type =
                    NativePolicyTemplates.targetTypeOf(targetKey)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Target key matches no fixed template: " + targetKey));
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            if (scopes.isEmpty() || !type.scopes().containsAll(scopes)) {
                throw new IllegalArgumentException(
                        "Scopes are not fixed for target " + targetKey + ": " + scopes);
            }
        }
    }

    /** Đáp án PDP của mọi cặp được yêu cầu; một cặp chỉ được phép khi có native allow. */
    public record Decisions(Map<String, Map<String, Boolean>> byTarget) {
        public Decisions {
            byTarget = Map.copyOf(Objects.requireNonNull(byTarget, "byTarget"));
        }

        public boolean allowed(String targetKey, String scope) {
            return byTarget.getOrDefault(targetKey, Map.of()).getOrDefault(scope, false);
        }

        public boolean allAllowed() {
            return byTarget.values().stream()
                    .flatMap(perScope -> perScope.values().stream())
                    .allMatch(allowed -> allowed.booleanValue());
        }
    }

    /** Đánh giá mọi cặp và trả về các quyết định để hiển thị; không bao giờ chạy action. */
    public Decisions evaluate(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            List<Target> targets) {
        return run(authentication, request, workspaceId, targets, (_, result) -> result);
    }

    /**
     * Đọc dữ liệu có lọc theo từng quyết định PDP ngay trong fence transaction chốt. Caller phải
     * chỉ trả các hàng được cho phép; không đọc dữ liệu nhạy cảm sau khi transaction kết thúc.
     */
    public <T> T evaluateAndRead(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            List<Target> targets,
            Function<Decisions, T> read) {
        return run(
                authentication, request, workspaceId, targets, (_, result) -> read.apply(result));
    }

    /**
     * Đánh giá mọi cặp, rồi chạy {@code action} bên trong fence transaction chốt. Mọi native deny
     * đều hủy với 403 trước khi action chạy.
     */
    public <T> T execute(
            Authentication authentication,
            HttpServletRequest request,
            @Nullable UUID workspaceId,
            List<Target> targets,
            Supplier<T> action) {
        return run(
                authentication,
                request,
                workspaceId,
                targets,
                (_, result) -> {
                    if (!result.allAllowed()) {
                        throw new HttpProblemException(
                                HttpStatus.FORBIDDEN,
                                ErrorCodes.PERMISSION_DENIED,
                                "Không có quyền thực hiện thao tác.");
                    }
                    return action.get();
                });
    }

    /** Action theo actor/target, không kéo một workspace nghiệp vụ không liên quan vào fence. */
    public <T> T executeForActor(
            Authentication authentication,
            HttpServletRequest request,
            List<Target> targets,
            Supplier<T> action) {
        return execute(authentication, request, null, targets, action);
    }

    private <T> T run(
            Authentication authentication,
            HttpServletRequest request,
            @Nullable UUID workspaceId,
            List<Target> targets,
            java.util.function.BiFunction<AuthorizationFence.Snapshot, Decisions, T> finisher) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("At least one target is required");
        }
        UUID actorUserId = actorUserId(authentication);
        List<String> targetKeys = targets.stream().map(target -> target.targetKey()).toList();
        for (int attempt = 1; ; attempt++) {
            eligibility.synchronizeEligibility(actorUserId);
            Manifest manifest = resolver.resolve(actorUserId, workspaceId, targetKeys);
            AuthorizationFence.Snapshot snapshot = fence.acquire(manifest);
            Decisions result = decide(authentication, request, snapshot.manifest(), targets);
            try {
                return fence.atSnapshot(snapshot, () -> finisher.apply(snapshot, result));
            } catch (AuthorizationFence.ClosureChangedException changed) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw AuthorizationFence.notReady(changed);
                }
            }
        }
    }

    private Decisions decide(
            Authentication authentication,
            HttpServletRequest request,
            Manifest manifest,
            List<Target> targets) {
        Map<String, Map<String, Boolean>> byTarget = new LinkedHashMap<>();
        for (Target target : targets) {
            String resourceId = manifest.recordedResourceIds().get(target.targetKey());
            if (resourceId == null) {
                // Resource native được đặt tên theo target key; ID của projection đã ghi thắng
                // mỗi khi tồn tại, tra cứu này chỉ áp cho target chưa có projection.
                resourceId = writer.findResourceId(target.targetKey()).orElse(null);
            }
            Map<String, Boolean> perScope =
                    byTarget.computeIfAbsent(target.targetKey(), _ -> new LinkedHashMap<>());
            for (String scope : target.scopes()) {
                if (perScope.containsKey(scope)) {
                    continue;
                }
                perScope.put(
                        scope,
                        resourceId != null
                                && decisions.isAllowed(
                                        authentication,
                                        request,
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(resourceId, scope))));
            }
        }
        return new Decisions(byTarget);
    }

    /**
     * Điểm resolve actor của phiên duy nhất: tài khoản directory của subject đã xác thực.
     * Controller nào phải gắn dữ liệu request với actor trước khi chạy pipeline thì dùng điểm này
     * thay vì tự resolve tài khoản.
     */
    public UUID actorUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new HttpProblemException(
                    HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "Cần đăng nhập.");
        }
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM user_account WHERE keycloak_sub = ?",
                        UUID.class,
                        authentication.getName());
        if (found.isEmpty()) {
            // Phiên đã xác thực nhưng không có tài khoản directory thì không có ACCOUNT partition
            // để fence, nên không quyết định protected nào được đưa ra cho nó.
            throw AuthorizationFence.notReady();
        }
        return found.getFirst();
    }
}
