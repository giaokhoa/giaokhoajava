package com.giaokhoa.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.identity.keycloak.IdentityProvisioningClient;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.identity.service.IdentityService;
import com.giaokhoa.identity.service.IdentityService.BootstrapResult;
import com.giaokhoa.identity.service.IdentityService.CurrentUser;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.tenancy.WorkspaceDirectoryService;
import com.giaokhoa.tenancy.WorkspaceDirectoryService.ListedWorkspace;
import com.giaokhoa.tenancy.model.Center;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterRepository;
import com.giaokhoa.verification.SystemPracticeService;
import com.giaokhoa.verification.SystemPracticeService.PracticeEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
    private final WorkspaceDirectoryService directory;
    private final SystemPracticeService practice;
    private final IdentityProvisioningClient provisioning;
    private final ProtectedActionExecutor executor;
    private final UserAccountRepository accounts;
    private final CenterRepository centers;
    private final SubscriptionPeriodRepository periods;
    private final AuthzProjectionRepository projections;

    public SessionController(
            IdentityService identities,
            WorkspaceDirectoryService directory,
            SystemPracticeService practice,
            IdentityProvisioningClient provisioning,
            ProtectedActionExecutor executor,
            UserAccountRepository accounts,
            CenterRepository centers,
            SubscriptionPeriodRepository periods,
            AuthzProjectionRepository projections) {
        this.identities = identities;
        this.directory = directory;
        this.practice = practice;
        this.provisioning = provisioning;
        this.executor = executor;
        this.accounts = accounts;
        this.centers = centers;
        this.periods = periods;
        this.projections = projections;
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
    public ResponseEntity<SessionView> currentSession(
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
    public ResponseEntity<List<WorkspaceView>> listWorkspaces(
            Authentication authentication, HttpServletRequest request) {
        UUID actor = executor.actorUserId(authentication);
        CurrentUser current = identities.currentUser(actor);
        return protectedOk(
                executor.execute(
                        authentication,
                        request,
                        current.personalWorkspaceId(),
                        List.of(
                                new ProtectedActionExecutor.Target(
                                        NativePolicyTemplates.profileKey(actor),
                                        List.of(
                                                PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                        .scopeName()))),
                        () ->
                                workspaceViews(
                                        actor,
                                        current.username(),
                                        directory.listWorkspaces(actor))));
    }

    @GetMapping("/me/practice-history")
    public ResponseEntity<List<PracticeEntry>> listPracticeHistory(
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
        UserAccount account =
                accounts.findById(actor)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Account row without Keycloak subject for actor " + actor));
        provisioning.sendVerifyEmail(account.getKeycloakSub());
        // Gửi email là tác vụ phụ trợ đồng bộ, không có thực thể operation để poll: biên nhận hoàn
        // tất ngay mang chính định danh của actor theo hình dạng tracked operation của hợp đồng.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new OperationReceipt(actor, "SUCCEEDED", true));
    }

    // Mọi dữ kiện phụ (tên center, role, kỳ gói, trạng thái đồng bộ) nạp hàng loạt theo tập
    // workspace id bằng một truy vấn cho mỗi loại, thay vì lặp truy vấn theo từng workspace.
    private List<WorkspaceView> workspaceViews(
            UUID actor, String username, List<ListedWorkspace> workspaces) {
        if (workspaces.isEmpty()) {
            return List.of();
        }
        List<UUID> workspaceIds = workspaces.stream().map(ListedWorkspace::id).toList();
        Map<UUID, Set<MembershipRole.Role>> rolesByWorkspace =
                directory.rolesIn(actor, workspaceIds);
        Map<UUID, String> centerNames = centerNamesOf(workspaces);
        Map<UUID, Instant> subscriptionExpiries = subscriptionExpiriesOf(workspaceIds);
        Map<String, String> syncStates = syncStatesOf(workspaceIds);
        return workspaces.stream()
                .map(workspace -> {
                    String name =
                            workspace.kind() == Workspace.Kind.CENTER
                                    ? centerNames.getOrDefault(workspace.centerId(), "")
                                    : username;
                    List<String> roles =
                            rolesByWorkspace.getOrDefault(workspace.id(), Set.of()).stream()
                                    .map(role -> role.name())
                                    .sorted()
                                    .toList();
                    Instant expiry = subscriptionExpiries.get(workspace.id());
                    return new WorkspaceView(
                            workspace.id(),
                            name,
                            workspace.kind().name(),
                            workspace.state(),
                            workspace.centerId(),
                            roles,
                            new SubscriptionView(expiry != null, expiry),
                            syncStates.getOrDefault("workspace:" + workspace.id(), "UNKNOWN"));
                })
                .toList();
    }

    private Map<UUID, String> centerNamesOf(List<ListedWorkspace> workspaces) {
        List<UUID> centerIds =
                workspaces.stream()
                        .map(ListedWorkspace::centerId)
                        .filter(id -> id != null)
                        .distinct()
                        .toList();
        Map<UUID, String> names = new HashMap<>();
        for (Center center : centers.findAllById(centerIds)) {
            names.put(center.getId(), center.getName());
        }
        return names;
    }

    private Map<UUID, Instant> subscriptionExpiriesOf(List<UUID> workspaceIds) {
        Map<UUID, Instant> expiries = new HashMap<>();
        for (SubscriptionPeriodRepository.WorkspaceExpiry expiry :
                periods.findCurrentExpiries(workspaceIds, Instant.now())) {
            expiries.put(expiry.getWorkspaceId(), expiry.getExpiresAt());
        }
        return expiries;
    }

    private Map<String, String> syncStatesOf(List<UUID> workspaceIds) {
        List<String> targetKeys = workspaceIds.stream().map(id -> "workspace:" + id).toList();
        if (targetKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> states = new HashMap<>();
        for (AuthorizationProjection projection : projections.findByTargetKeyIn(targetKeys)) {
            states.put(projection.getTargetKey(), projection.getState().name());
        }
        return states;
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
