package com.giaokhoa.tenancy.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpConstants;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.shared.http.Violation;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.service.CenterService;
import com.giaokhoa.tenancy.service.InvitationService;
import com.giaokhoa.tenancy.service.MembershipService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP quản trị thành viên của một center: danh sách thành viên, lời mời, chấp nhận lời mời,
 * cấp tài khoản học sinh, đổi role và thu hồi membership.
 *
 * <p>Eligibility của actor luôn qua PDP profile và ACCOUNT fence. Quyết định role ở tầng này tính
 * từ đúng dữ kiện nguồn mà CenterPolicyProjector chiếu lên native graph: role của membership ACTIVE
 * của chính actor trong workspace và cửa sổ hiệu lực của các kỳ gói đã xác nhận. Bảng nguồn áp dụng
 * theo projector: xem danh sách chỉ cần là thành viên hiện hành; mời, đổi role (phần thêm) và cấp
 * học sinh cần owner/manager cộng gói chưa hết hiệu lực; bổ nhiệm hoặc gỡ manager chỉ thuộc owner;
 * thu hồi không bị gate bởi gói. Route tạo lớp đã chuyển sang controller giảng dạy với cùng biên
 * quyết quyền bằng dữ kiện nguồn này. Gói chỉ chặn khi center đã từng có kỳ gói mà không kỳ nào còn
 * hiệu lực — center chưa từng được xác nhận gói vẫn do owner/manager quản trị, đúng hành vi hợp
 * đồng US2 yêu cầu. Các service giữ tiền điều kiện dữ kiện riêng của thao tác ghi (đúng một đích
 * mời, OWNER không mời được, học sinh trùng username) và trả lỗi nghiệp vụ của chúng.
 */
@RestController
@RequestMapping("/api")
public class MembershipController {
    private static final JsonMapper JSON = new JsonMapper();

    /** Tên trường danh sách role trong body yêu cầu mời/đổi role. */
    private static final String ROLES_FIELD = "roles";

    private final MembershipService memberships;
    private final InvitationService invitations;
    private final CenterService centers;
    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final ResourceMutationCoordinator coordinator;

    public MembershipController(
            MembershipService memberships,
            InvitationService invitations,
            CenterService centers,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            ResourceMutationCoordinator coordinator) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.centers = centers;
        this.executor = executor;
        this.idempotency = idempotency;
        this.coordinator = coordinator;
    }

    public record MembershipUserView(UUID id, String username, String displayName) {}

    public record MembershipItem(
            UUID id, MembershipUserView user, List<String> roles, String state, long version) {
        public MembershipItem {
            roles = List.copyOf(roles);
        }
    }

    public record MembershipPage(
            List<MembershipItem> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor) {
        public MembershipPage {
            items = List.copyOf(items);
        }
    }

    public record InvitationCreateRequest(
            @Nullable String email, @Nullable UUID userId, List<String> roles) {
        public InvitationCreateRequest {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    public record InvitationCreatedView(
            UUID operationId,
            String state,
            boolean completed,
            String statusUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID invitationId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String invitationToken) {}

    public record AcceptRequest() {}

    public record StudentIssueRequest(
            @NotBlank @Nullable String username,
            @NotBlank @Nullable String displayName,
            @Nullable String email) {}

    public record OperationPart(UUID id, String state, boolean completed) {}

    public record HandoffView(String username, String temporaryPassword) {}

    public record StudentIssueView(
            OperationPart operation,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable HandoffView handoff) {}

    public record RoleChangeRequest(List<String> roles) {
        public RoleChangeRequest {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    @GetMapping("/workspaces/{workspaceId}/memberships")
    public ResponseEntity<MembershipPage> listMemberships(
            @PathVariable UUID workspaceId,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (memberships.activeRoles(actor, workspaceId).isEmpty()) {
                        throw denied(
                                "Chỉ thành viên hiện hành của trung tâm mới xem được danh sách này.");
                    }
                    int pageSize = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
                    MembershipService.MembershipPage page =
                            memberships.pageMemberships(workspaceId, cursor, pageSize);
                    return protectedOk(
                            new MembershipPage(
                                    page.items().stream().map(MembershipController::item).toList(),
                                    page.nextCursor()));
                });
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationCreatedView> invite(
            @PathVariable UUID workspaceId,
            @RequestBody @Valid @Nullable InvitationCreateRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu danh sách role của lời mời.",
                                List.of(Violation.notNull(ROLES_FIELD)));
                    }
                    Set<MembershipRole.Role> roles = parseRoles(body.roles());
                    memberships.requireAdmin(actor, workspaceId);
                    memberships.requireSubscriptionCurrent(workspaceId);
                    InvitationService.InvitationIssued issued =
                            invitations.invite(
                                    actor,
                                    workspaceId,
                                    body.email(),
                                    body.userId(),
                                    roles,
                                    key,
                                    idempotency.payloadHash(
                                            "POST",
                                            HttpConstants.WORKSPACE_API_PREFIX
                                                    + workspaceId
                                                    + "/invitations",
                                            JSON.valueToTree(body)));
                    AuthorizationOperation operation = issued.operation();
                    boolean completed = completedOrThrow(operation);
                    InvitationCreatedView view =
                            new InvitationCreatedView(
                                    operation.getId(),
                                    operation.getState().name(),
                                    completed,
                                    "/api/me/operations/" + operation.getId(),
                                    issued.invitationId(),
                                    issued.token());
                    return ResponseEntity.status(completed ? HttpStatus.OK : HttpStatus.ACCEPTED)
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                            .body(view);
                });
    }

    @PostMapping("/invitations/{token}/accept")
    public ResponseEntity<IdempotencyService.OperationReceipt> acceptInvitation(
            @PathVariable String token,
            @RequestBody @Valid @Nullable AcceptRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                null,
                actor -> {
                    AuthorizationOperation operation =
                            invitations.accept(
                                    actor,
                                    token,
                                    key,
                                    idempotency.payloadHash(
                                            "POST",
                                            "/api/invitations/" + token + "/accept",
                                            JSON.valueToTree(
                                                    body == null ? new AcceptRequest() : body)));
                    return idempotency.responseFor(operation);
                });
    }

    @PostMapping("/workspaces/{workspaceId}/students")
    public ResponseEntity<StudentIssueView> issueStudent(
            @PathVariable UUID workspaceId,
            @RequestBody @Valid @Nullable StudentIssueRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu thông tin học sinh cần cấp.",
                                List.of(Violation.notNull("username")));
                    }
                    String username = body.username();
                    String displayName = body.displayName();
                    String email = body.email();
                    if (username == null
                            || username.isBlank()
                            || displayName == null
                            || displayName.isBlank()
                            || (email != null && email.isBlank())) {
                        List<Violation> violations = new ArrayList<>();
                        if (username == null || username.isBlank()) {
                            violations.add(Violation.notBlank("username"));
                        }
                        if (displayName == null || displayName.isBlank()) {
                            violations.add(Violation.notBlank("displayName"));
                        }
                        if (email != null && email.isBlank()) {
                            violations.add(Violation.notBlank("email"));
                        }
                        throw HttpProblemException.validationFailed(
                                "Thiếu tên đăng nhập hoặc tên hiển thị của học sinh.", violations);
                    }
                    memberships.requireAdmin(actor, workspaceId);
                    memberships.requireSubscriptionCurrent(workspaceId);
                    MembershipService.StudentIssueResult result =
                            memberships.issueStudent(
                                    actor,
                                    workspaceId,
                                    username,
                                    displayName,
                                    email,
                                    key,
                                    idempotency.payloadHash(
                                            "POST",
                                            HttpConstants.WORKSPACE_API_PREFIX
                                                    + workspaceId
                                                    + "/students",
                                            JSON.valueToTree(body)));
                    AuthorizationOperation operation = result.operation();
                    boolean completed = completedOrThrow(operation);
                    // Giữ handoff trong biến cục bộ: gọi result.handoff() nhiều lần khiến phân tích
                    // null
                    // không nối được lần kiểm tra với lần dùng.
                    var issued = result.handoff();
                    HandoffView handoff =
                            issued == null
                                    ? null
                                    : new HandoffView(
                                            issued.username(), issued.temporaryPassword());
                    StudentIssueView view =
                            new StudentIssueView(
                                    new OperationPart(
                                            operation.getId(),
                                            operation.getState().name(),
                                            completed),
                                    handoff);
                    return ResponseEntity.status(completed ? HttpStatus.OK : HttpStatus.ACCEPTED)
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                            .body(view);
                });
    }

    @PutMapping("/workspaces/{workspaceId}/memberships/{membershipId}/roles")
    public ResponseEntity<IdempotencyService.OperationReceipt> replaceRoles(
            @PathVariable UUID workspaceId,
            @PathVariable UUID membershipId,
            @RequestBody @Valid @Nullable RoleChangeRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw HttpProblemException.validationFailed(
                                "Thiếu danh sách role đích.",
                                List.of(Violation.notNull(ROLES_FIELD)));
                    }
                    Set<MembershipRole.Role> desired = parseRoles(body.roles());
                    Set<MembershipRole.Role> actorRoles =
                            memberships.requireAdmin(actor, workspaceId);
                    Set<MembershipRole.Role> current =
                            memberships.currentRoles(workspaceId, membershipId);
                    Set<MembershipRole.Role> added = EnumSet.noneOf(MembershipRole.Role.class);
                    added.addAll(desired);
                    added.removeAll(current);
                    Set<MembershipRole.Role> removed = EnumSet.noneOf(MembershipRole.Role.class);
                    removed.addAll(current);
                    removed.removeAll(desired);
                    // Bổ nhiệm và gỡ manager chỉ thuộc owner, khớp bảng nguồn của projector:
                    // manager không
                    // bao giờ là nguồn của manager.assign/manager.revoke.
                    if ((added.contains(MembershipRole.Role.MANAGER)
                                    || added.contains(MembershipRole.Role.OWNER)
                                    || removed.contains(MembershipRole.Role.MANAGER))
                            && !actorRoles.contains(MembershipRole.Role.OWNER)) {
                        throw denied("Chỉ chủ sở hữu mới được bổ nhiệm hoặc gỡ quản lý.");
                    }
                    if (!added.isEmpty()) {
                        memberships.requireSubscriptionCurrent(workspaceId);
                    }
                    AuthorizationOperation operation =
                            centers.replaceRoles(
                                    actor,
                                    workspaceId,
                                    membershipId,
                                    desired,
                                    ifMatch,
                                    key,
                                    idempotency.payloadHash(
                                            "PUT",
                                            HttpConstants.WORKSPACE_API_PREFIX
                                                    + workspaceId
                                                    + "/memberships/"
                                                    + membershipId
                                                    + "/roles",
                                            JSON.valueToTree(body)));
                    return idempotency.responseFor(operation);
                });
    }

    @DeleteMapping("/workspaces/{workspaceId}/memberships/{membershipId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeMembership(
            @PathVariable UUID workspaceId,
            @PathVariable UUID membershipId,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    Set<MembershipRole.Role> actorRoles =
                            memberships.requireAdmin(actor, workspaceId);
                    Set<MembershipRole.Role> current =
                            memberships.currentRoles(workspaceId, membershipId);
                    if ((current.contains(MembershipRole.Role.MANAGER)
                                    || current.contains(MembershipRole.Role.OWNER))
                            && !actorRoles.contains(MembershipRole.Role.OWNER)) {
                        throw denied("Chỉ chủ sở hữu mới được thu hồi quản lý hoặc chủ sở hữu.");
                    }
                    AuthorizationOperation operation =
                            centers.revokeMembership(
                                    actor,
                                    workspaceId,
                                    membershipId,
                                    key,
                                    idempotency.payloadHash(
                                            "DELETE",
                                            HttpConstants.WORKSPACE_API_PREFIX
                                                    + workspaceId
                                                    + "/memberships/"
                                                    + membershipId,
                                            JSON.createObjectNode()));
                    return idempotency.responseFor(operation);
                });
    }

    private <T> T withEligibleActor(
            Authentication authentication,
            HttpServletRequest request,
            @Nullable UUID workspaceId,
            Function<UUID, T> action) {
        UUID actor = executor.actorUserId(authentication);
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.profileKey(actor),
                                List.of(
                                        PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                .scopeName()))),
                () -> {
                    if (workspaceId != null) {
                        coordinator.lockWorkspace(workspaceId);
                    }
                    return action.apply(actor);
                });
    }

    private static MembershipItem item(MembershipService.MembershipView view) {
        // Directory chỉ giữ username làm tên định danh; displayName của view lấy cùng giá trị đó
        // vì user_account không có cột tên hiển thị riêng.
        return new MembershipItem(
                view.id(),
                new MembershipUserView(view.userId(), view.username(), view.username()),
                view.roles().stream().map(role -> role.name()).toList(),
                view.state(),
                view.version());
    }

    private static Set<MembershipRole.Role> parseRoles(List<String> roles) {
        Set<MembershipRole.Role> parsed = EnumSet.noneOf(MembershipRole.Role.class);
        for (String role : roles) {
            if (role == null) {
                throw HttpProblemException.validationFailed(
                        "Role không hợp lệ.", List.of(Violation.invalid(ROLES_FIELD)));
            }
            try {
                parsed.add(MembershipRole.Role.valueOf(role));
            } catch (IllegalArgumentException ex) {
                throw HttpProblemException.validationFailed(
                        "Role không hợp lệ.", List.of(Violation.invalid(ROLES_FIELD)), ex);
            }
        }
        return Set.copyOf(parsed);
    }

    private static boolean completedOrThrow(AuthorizationOperation operation) {
        if (operation.getState() == AuthorizationOperation.State.FAILED) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.OPERATION_FAILED,
                    "Đồng bộ quyền không thành công.");
        }
        return operation.getState() == AuthorizationOperation.State.SUCCEEDED;
    }

    private static HttpProblemException denied(String detail) {
        return new HttpProblemException(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, detail);
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
