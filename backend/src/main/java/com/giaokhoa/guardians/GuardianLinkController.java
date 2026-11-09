package com.giaokhoa.guardians;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.guardians.GuardianLinkService.GuardianLinkView;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.MembershipRole;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bề mặt HTTP vòng đời guardian link: phụ huynh gửi request, quản lý duyệt, quản lý gỡ liên kết, và
 * danh sách theo quyền. Ghi operation qua service rồi trả receipt 202; quyết quyền route theo dữ
 * kiện nguồn mà CenterPolicyProjector chiếu, vì center seed bằng JDBC không có projection cho
 * workspace target nên hỏi PDP trên workspace target sẽ luôn deny (xem B57).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/guardian-links")
public class GuardianLinkController {
    private static final JsonMapper JSON = new JsonMapper();

    private final GuardianLinkService links;
    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final ResourceMutationCoordinator coordinator;

    public GuardianLinkController(
            GuardianLinkService links,
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            ResourceMutationCoordinator coordinator) {
        this.links = links;
        this.executor = executor;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.coordinator = coordinator;
    }

    /**
     * Body tạo liên kết. Trường lạ (ví dụ parentUserId của người khác) bị bỏ qua: parent luôn là
     * actor hiện hành, không bao giờ tạo link cho phụ huynh khác.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardianRequest(@Nullable UUID studentUserId) {}

    /** Body quyết định liên kết: APPROVE hoặc REJECT kèm row version đã đọc. */
    public record GuardianDecision(@Nullable String decision, @Nullable Long expectedVersion) {}

    /** Trang danh sách liên kết: chỉ items, không totalCount. */
    public record GuardianLinkPage(List<GuardianLinkView> items) {
        public GuardianLinkPage {
            items = List.copyOf(items);
        }
    }

    @PostMapping
    public ResponseEntity<IdempotencyService.OperationReceipt> requestLink(
            @PathVariable UUID workspaceId,
            @RequestBody @Nullable GuardianRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
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
                                "Thiếu studentUserId.");
                    }
                    UUID studentUserId = body.studentUserId();
                    if (studentUserId == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu studentUserId.");
                    }
                    requireParent(actor, workspaceId);
                    AuthorizationOperation operation =
                            links.requestLink(
                                    actor,
                                    workspaceId,
                                    studentUserId,
                                    idempotencyKey,
                                    idempotency.payloadHash(
                                            "POST",
                                            "/api/workspaces/" + workspaceId + "/guardian-links",
                                            JSON.valueToTree(body)));
                    return idempotency.responseFor(operation);
                });
    }

    @GetMapping
    public ResponseEntity<GuardianLinkPage> listLinks(
            @PathVariable UUID workspaceId,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    Set<MembershipRole.Role> roles = actorRoles(actor, workspaceId);
                    List<GuardianLinkView> items;
                    if (roles.contains(MembershipRole.Role.OWNER)
                            || roles.contains(MembershipRole.Role.MANAGER)) {
                        items = links.listLinksForReview(workspaceId);
                    } else if (roles.contains(MembershipRole.Role.PARENT)) {
                        items = links.listLinksOfParent(workspaceId, actor);
                    } else {
                        throw new HttpProblemException(
                                HttpStatus.FORBIDDEN,
                                "PERMISSION_DENIED",
                                "Actor không có quyền xem danh sách liên kết của center này.");
                    }
                    return protectedOk(new GuardianLinkPage(items));
                });
    }

    @PostMapping("/{linkId}/decision")
    public ResponseEntity<IdempotencyService.OperationReceipt> decideLink(
            @PathVariable UUID workspaceId,
            @PathVariable UUID linkId,
            @RequestBody @Nullable GuardianDecision body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
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
                                "Thiếu decision hoặc expectedVersion.");
                    }
                    String decision = body.decision();
                    Long expectedVersion = body.expectedVersion();
                    if (decision == null || expectedVersion == null) {
                        throw new HttpProblemException(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_FAILED",
                                "Thiếu decision hoặc expectedVersion.");
                    }
                    requireAdmin(actor, workspaceId);
                    AuthorizationOperation operation =
                            links.decideLink(
                                    actor,
                                    workspaceId,
                                    linkId,
                                    decision,
                                    expectedVersion,
                                    idempotencyKey,
                                    idempotency.payloadHash(
                                            "POST",
                                            "/api/workspaces/"
                                                    + workspaceId
                                                    + "/guardian-links/"
                                                    + linkId
                                                    + "/decision",
                                            JSON.valueToTree(body)));
                    return idempotency.responseFor(operation);
                });
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeLink(
            @PathVariable UUID workspaceId,
            @PathVariable UUID linkId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return withEligibleActor(
                authentication,
                request,
                workspaceId,
                actor -> {
                    requireAdmin(actor, workspaceId);
                    AuthorizationOperation operation =
                            links.revokeLink(
                                    actor,
                                    workspaceId,
                                    linkId,
                                    idempotencyKey,
                                    idempotency.payloadHash(
                                            "DELETE",
                                            "/api/workspaces/"
                                                    + workspaceId
                                                    + "/guardian-links/"
                                                    + linkId,
                                            JSON.createObjectNode()));
                    return idempotency.responseFor(operation);
                });
    }

    private ResponseEntity<GuardianLinkPage> protectedOk(GuardianLinkPage page) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(page);
    }

    private void requireParent(UUID actor, UUID workspaceId) {
        if (!actorRoles(actor, workspaceId).contains(MembershipRole.Role.PARENT)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "GUARDIAN_PARENT_NOT_CURRENT",
                    "Actor không phải phụ huynh còn hiệu lực của center này.");
        }
    }

    private void requireAdmin(UUID actor, UUID workspaceId) {
        Set<MembershipRole.Role> roles = actorRoles(actor, workspaceId);
        if (!roles.contains(MembershipRole.Role.OWNER)
                && !roles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Actor không phải owner hoặc manager của center này.");
        }
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
}
