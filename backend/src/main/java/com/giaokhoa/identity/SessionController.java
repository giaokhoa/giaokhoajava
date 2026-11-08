package com.giaokhoa.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.identity.IdentityService.BootstrapResult;
import com.giaokhoa.identity.IdentityService.CurrentUser;
import com.giaokhoa.identity.keycloak.IdentityProvisioningClient;
import com.giaokhoa.tenancy.WorkspaceDirectory;
import com.giaokhoa.tenancy.WorkspaceDirectory.ListedWorkspace;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.SystemPracticeService;
import com.giaokhoa.verification.SystemPracticeService.PracticeEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của phiên đăng nhập và của người dùng hiện tại: mô tả phiên kèm CSRF token cho SPA,
 * danh sách workspace, lịch sử practice và yêu cầu xác minh danh tính. Không response nào chứa
 * bearer token hay secret; mọi response mang Cache-Control private, no-store theo hợp đồng.
 */
@RestController
@RequestMapping("/api")
public class SessionController {
    private final IdentityService identities;
    private final WorkspaceDirectory directory;
    private final SystemPracticeService practice;
    private final IdentityProvisioningClient provisioning;
    private final ProtectedActionExecutor executor;
    private final JdbcTemplate jdbc;

    public SessionController(
            IdentityService identities,
            WorkspaceDirectory directory,
            SystemPracticeService practice,
            IdentityProvisioningClient provisioning,
            ProtectedActionExecutor executor,
            JdbcTemplate jdbc) {
        this.identities = identities;
        this.directory = directory;
        this.practice = practice;
        this.provisioning = provisioning;
        this.executor = executor;
        this.jdbc = jdbc;
    }

    public record CsrfView(String headerName, String token) {}

    public record SessionUserView(
            UUID id,
            String username,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String email,
            boolean emailVerified,
            String identityState) {}

    public record SessionView(
            boolean authenticated,
            CsrfView csrf,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable SessionUserView user) {}

    public record SubscriptionView(boolean effective, @Nullable Instant expiresAt) {}

    public record WorkspaceView(
            UUID id,
            String name,
            String kind,
            String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID centerId,
            List<String> roles,
            SubscriptionView subscription,
            String syncState) {
        public WorkspaceView {
            roles = List.copyOf(roles);
        }
    }

    public record OperationReceipt(UUID id, String state, boolean completed) {}

    @GetMapping("/session")
    public ResponseEntity<SessionView> session(
            @Nullable Authentication authentication, HttpServletRequest request) {
        CsrfView csrf = csrfOf(request);
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return protectedOk(new SessionView(false, csrf, null));
        }
        // Đọc phiên là điểm chạm đầu tiên sau đăng nhập: bootstrap tại đây biến lần đăng nhập đầu
        // thành account + personal workspace, và đồng bộ lại email/verified từ profile Keycloak.
        BootstrapResult bootstrapped = identities.bootstrap(authentication.getName());
        CurrentUser current = identities.currentUser(bootstrapped.userId());
        SessionUserView user =
                new SessionUserView(
                        current.userId(),
                        current.username(),
                        current.email(),
                        current.emailVerified(),
                        current.identityState());
        return protectedOk(new SessionView(true, csrf, user));
    }

    @GetMapping("/me/workspaces")
    public ResponseEntity<List<WorkspaceView>> workspaces(
            Authentication authentication, HttpServletRequest request) {
        UUID actor = executor.actorUserId(authentication);
        UUID personal = identities.currentUser(actor).personalWorkspaceId();
        return protectedOk(
                executor.execute(
                        authentication,
                        request,
                        personal,
                        List.of(
                                new ProtectedActionExecutor.Target(
                                        NativePolicyTemplates.profileKey(actor),
                                        List.of(
                                                PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                        .scopeName()))),
                        () ->
                                directory.listWorkspaces(actor).stream()
                                        .map(w -> workspaceView(actor, w))
                                        .toList()));
    }

    @GetMapping("/me/practice-history")
    public ResponseEntity<List<PracticeEntry>> practiceHistory(
            Authentication authentication, HttpServletRequest request) {
        UUID actor = executor.actorUserId(authentication);
        UUID personal = identities.currentUser(actor).personalWorkspaceId();
        return protectedOk(
                executor.execute(
                        authentication,
                        request,
                        personal,
                        List.of(
                                new ProtectedActionExecutor.Target(
                                        NativePolicyTemplates.profileKey(actor),
                                        List.of(
                                                PermissionCatalog.ProfileScope.HISTORY_READ
                                                        .scopeName()))),
                        () -> practice.historyFor(actor)));
    }

    @PostMapping("/identity/verification")
    public ResponseEntity<OperationReceipt> requestVerification(Authentication authentication) {
        UUID actor = executor.actorUserId(authentication);
        List<String> subjects =
                jdbc.queryForList(
                        "SELECT keycloak_sub FROM user_account WHERE id = ?", String.class, actor);
        if (subjects.isEmpty()) {
            throw new IllegalStateException(
                    "Account row without Keycloak subject for actor " + actor);
        }
        provisioning.sendVerifyEmail(subjects.getFirst());
        // Gửi email là tác vụ phụ trợ đồng bộ, không có thực thể operation để poll: biên nhận hoàn
        // tất ngay mang chính định danh của actor theo hình dạng tracked operation của hợp đồng.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new OperationReceipt(actor, "SUCCEEDED", true));
    }

    private WorkspaceView workspaceView(UUID actor, ListedWorkspace workspace) {
        String name =
                workspace.kind() == Workspace.Kind.CENTER
                        ? centerName(workspace.centerId())
                        : identities.currentUser(actor).username();
        List<String> roles =
                directory.rolesIn(actor, workspace.id()).stream()
                        .map(role -> role.name())
                        .sorted()
                        .toList();
        return new WorkspaceView(
                workspace.id(),
                name,
                workspace.kind().name(),
                workspace.state(),
                workspace.centerId(),
                roles,
                subscriptionOf(workspace.id()),
                syncStateOf(workspace.id()));
    }

    private String centerName(@Nullable UUID centerId) {
        if (centerId == null) {
            return "";
        }
        List<String> names =
                jdbc.queryForList("SELECT name FROM center WHERE id = ?", String.class, centerId);
        return names.isEmpty() ? "" : names.getFirst();
    }

    private SubscriptionView subscriptionOf(UUID workspaceId) {
        List<Instant> periods =
                jdbc.query(
                        "SELECT expires_at FROM subscription_period WHERE workspace_id = ?"
                                + " AND valid_from <= now() AND expires_at > now()"
                                + " ORDER BY expires_at DESC LIMIT 1",
                        (rs, _) -> rs.getTimestamp(1).toInstant(),
                        workspaceId);
        return new SubscriptionView(
                !periods.isEmpty(), periods.isEmpty() ? null : periods.getFirst());
    }

    private String syncStateOf(UUID workspaceId) {
        List<String> states =
                jdbc.queryForList(
                        "SELECT state FROM authz_projection WHERE target_key = ?",
                        String.class,
                        "workspace:" + workspaceId);
        return states.isEmpty() ? "UNKNOWN" : states.getFirst();
    }

    private static CsrfView csrfOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute == null) {
            attribute = request.getAttribute("_csrf");
        }
        // Handler mặc định của Spring Security bọc token trong một Supplier hoãn tải kèm che giấu
        // BREACH: đọc qua Supplier mới ép tạo token và lấy đúng giá trị client phải gửi lại.
        CsrfToken token =
                switch (attribute) {
                    case CsrfToken direct -> direct;
                    case Supplier<?> supplier when supplier.get() instanceof CsrfToken deferred ->
                            deferred;
                    case null, default ->
                            throw new IllegalStateException(
                                    "CsrfToken request attribute is not available");
                };
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
