package com.giaokhoa.tenancy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.MembershipRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * theo projector: xem danh sách chỉ cần là thành viên hiện hành; mời, đổi role (phần thêm), cấp học
 * sinh và tạo lớp cần owner/manager cộng gói chưa hết hiệu lực; bổ nhiệm hoặc gỡ manager chỉ thuộc
 * owner; thu hồi không bị gate bởi gói. Gói chỉ chặn khi center đã từng có kỳ gói mà không kỳ nào
 * còn hiệu lực — center chưa từng được xác nhận gói vẫn do owner/manager quản trị, đúng hành vi hợp
 * đồng US2 yêu cầu. Các service giữ tiền điều kiện dữ kiện riêng của thao tác ghi (đúng một đích
 * mời, OWNER không mời được, học sinh trùng username) và trả lỗi nghiệp vụ của chúng.
 */
@RestController
public class MembershipController {
    private static final JsonMapper JSON = new JsonMapper();

    private final MembershipService memberships;
    private final InvitationService invitations;
    private final CenterService centers;
    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final ResourceMutationCoordinator coordinator;

    public MembershipController(
            MembershipService memberships,
            InvitationService invitations,
            CenterService centers,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            ResourceMutationCoordinator coordinator) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.centers = centers;
        this.executor = executor;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
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
            @Nullable String username, @Nullable String displayName, @Nullable String email) {}

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

    public record ClassCreateRequest(@Nullable String name) {}

    @GetMapping("/api/workspaces/{workspaceId}/memberships")
    public ResponseEntity<MembershipPage> listMemberships(
            @PathVariable UUID workspaceId,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                null,
                actor -> {
                    if (actorRoles(actor, workspaceId).isEmpty()) {
                        throw denied(
                                "Chỉ thành viên hiện hành của trung tâm mới xem được danh sách này.");
                    }
                    int pageSize = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
                    List<MembershipService.MembershipView> all =
                            memberships.listMemberships(workspaceId);
                    int start = 0;
                    if (cursor != null) {
                        // Cursor là id của phần tử cuối trang trước; không còn khớp nghĩa là trang
                        // đã cạn.
                        start = all.size();
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i).id().toString().equals(cursor)) {
                                start = i + 1;
                                break;
                            }
                        }
                    }
                    List<MembershipItem> items =
                            all.subList(start, Math.min(start + pageSize, all.size())).stream()
                                    .map(MembershipController::item)
                                    .toList();
                    String nextCursor =
                            !items.isEmpty() && start + items.size() < all.size()
                                    ? items.getLast().id().toString()
                                    : null;
                    return protectedOk(new MembershipPage(items, nextCursor));
                });
    }

    @PostMapping("/api/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationCreatedView> invite(
            @PathVariable UUID workspaceId,
            @RequestBody @Nullable InvitationCreateRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu danh sách role của lời mời.");
                    }
                    Set<MembershipRole.Role> roles = parseRoles(body.roles());
                    requireAdmin(actor, workspaceId);
                    requireSubscriptionCurrent(workspaceId);
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
                                            "/api/workspaces/" + workspaceId + "/invitations",
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

    @PostMapping("/api/invitations/{token}/accept")
    public ResponseEntity<IdempotencyService.OperationReceipt> acceptInvitation(
            @PathVariable String token,
            @RequestBody @Nullable AcceptRequest body,
            @RequestHeader("Idempotency-Key") String key,
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

    @PostMapping("/api/workspaces/{workspaceId}/students")
    public ResponseEntity<StudentIssueView> issueStudent(
            @PathVariable UUID workspaceId,
            @RequestBody @Nullable StudentIssueRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu thông tin học sinh cần cấp.");
                    }
                    String username = body.username();
                    String displayName = body.displayName();
                    String email = body.email();
                    if (username == null
                            || username.isBlank()
                            || displayName == null
                            || displayName.isBlank()
                            || (email != null && email.isBlank())) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu tên đăng nhập hoặc tên hiển thị của học sinh.");
                    }
                    requireAdmin(actor, workspaceId);
                    requireSubscriptionCurrent(workspaceId);
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
                                            "/api/workspaces/" + workspaceId + "/students",
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

    @PutMapping("/api/workspaces/{workspaceId}/memberships/{membershipId}/roles")
    public ResponseEntity<IdempotencyService.OperationReceipt> replaceRoles(
            @PathVariable UUID workspaceId,
            @PathVariable UUID membershipId,
            @RequestBody @Nullable RoleChangeRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    if (body == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu danh sách role đích.");
                    }
                    Set<MembershipRole.Role> desired = parseRoles(body.roles());
                    Set<MembershipRole.Role> actorRoles = requireAdmin(actor, workspaceId);
                    Set<MembershipRole.Role> current = currentRoles(workspaceId, membershipId);
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
                        requireSubscriptionCurrent(workspaceId);
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
                                            "/api/workspaces/"
                                                    + workspaceId
                                                    + "/memberships/"
                                                    + membershipId
                                                    + "/roles",
                                            JSON.valueToTree(body)));
                    return idempotency.responseFor(operation);
                });
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/memberships/{membershipId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeMembership(
            @PathVariable UUID workspaceId,
            @PathVariable UUID membershipId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    Set<MembershipRole.Role> actorRoles = requireAdmin(actor, workspaceId);
                    Set<MembershipRole.Role> current = currentRoles(workspaceId, membershipId);
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
                                            "/api/workspaces/"
                                                    + workspaceId
                                                    + "/memberships/"
                                                    + membershipId,
                                            JSON.createObjectNode()));
                    return idempotency.responseFor(operation);
                });
    }

    @PostMapping("/api/workspaces/{workspaceId}/classes")
    public ResponseEntity<IdempotencyService.OperationReceipt> createClass(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    idempotency.requireKey(key);
                    requireAdmin(actor, workspaceId);
                    requireSubscriptionCurrent(workspaceId);
                    // Aggregate lớp thuộc lát cắt giảng dạy sau; route này hiện chỉ thực thi biên
                    // quyền của
                    // hợp đồng (owner/manager của center và gói chưa hết hiệu lực) và chưa có lớp
                    // để tạo.
                    throw new HttpProblemException(
                            HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
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

    private Set<MembershipRole.Role> actorRoles(UUID actorUserId, UUID workspaceId) {
        return Set.copyOf(
                jdbc
                        .queryForList(
                                "SELECT r.role FROM center_membership m"
                                        + " JOIN membership_role r ON r.membership_id = m.id"
                                        + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                        + " AND m.state = 'ACTIVE'",
                                String.class,
                                workspaceId,
                                actorUserId)
                        .stream()
                        .map(MembershipRole.Role::valueOf)
                        .toList());
    }

    private Set<MembershipRole.Role> requireAdmin(UUID actorUserId, UUID workspaceId) {
        Set<MembershipRole.Role> roles = actorRoles(actorUserId, workspaceId);
        if (!roles.contains(MembershipRole.Role.OWNER)
                && !roles.contains(MembershipRole.Role.MANAGER)) {
            throw denied("Không có quyền quản trị thành viên của trung tâm này.");
        }
        return roles;
    }

    private Set<MembershipRole.Role> currentRoles(UUID workspaceId, UUID membershipId) {
        return memberships.listMemberships(workspaceId).stream()
                .filter(view -> view.id().equals(membershipId))
                .findFirst()
                .map(view -> Set.copyOf(view.roles()))
                .orElse(Set.of());
    }

    private void requireSubscriptionCurrent(UUID workspaceId) {
        Integer total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                        Integer.class,
                        workspaceId);
        if (total == null || total == 0) {
            return;
        }
        Integer active =
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period WHERE workspace_id = ?"
                                + " AND valid_from <= now() AND expires_at > now()",
                        Integer.class,
                        workspaceId);
        if (active == null || active == 0) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    private static Set<MembershipRole.Role> parseRoles(List<String> roles) {
        Set<MembershipRole.Role> parsed = EnumSet.noneOf(MembershipRole.Role.class);
        for (String role : roles) {
            if (role == null) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Role không hợp lệ.");
            }
            try {
                parsed.add(MembershipRole.Role.valueOf(role));
            } catch (IllegalArgumentException ex) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Role không hợp lệ.", ex);
            }
        }
        return Set.copyOf(parsed);
    }

    private static boolean completedOrThrow(AuthorizationOperation operation) {
        if (operation.getState() == AuthorizationOperation.State.FAILED) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OPERATION_FAILED",
                    "Đồng bộ quyền không thành công.");
        }
        return operation.getState() == AuthorizationOperation.State.SUCCEEDED;
    }

    private static HttpProblemException denied(String detail) {
        return new HttpProblemException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", detail);
    }

    private static <T> ResponseEntity<T> protectedOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
