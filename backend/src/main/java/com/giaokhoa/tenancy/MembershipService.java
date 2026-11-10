package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.InitialCredentialService;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ membership của center ở tầng service: cấp học sinh mới và đọc danh sách thành viên. Đổi
 * role và thu hồi membership nằm ở CenterService (cùng ghi bundle của workspace), còn service này
 * sở hữu đường cấp học sinh vì nó là workflow qua Keycloak chứ không chỉ là thay đổi dữ kiện nguồn
 * trong app-db.
 *
 * <p>Cấp học sinh là CREATE mới qua {@link InitialCredentialService}: tạo user Keycloak kèm
 * credential tạm, rồi registrar ghi account CENTER_ISSUED, personal workspace và membership STUDENT
 * ACTIVE. Username trùng không bao giờ chiếm hay reset danh tính đã có — Keycloak trả 409 cho
 * username đã tồn tại và registrar từ chối subject/username không khớp, cả hai đều thành 409.
 * Handoff chỉ tồn tại trong kết quả của lần tạo đầu tiên: replay cùng idempotency key và payload
 * trả lại đúng operation đã ghi và không mang handoff, nên credential không xuất hiện ở bất kỳ lần
 * đọc lại nào. Vì identity được tạo trước khi operation được ghi, toàn bộ luồng được tuần tự hoá
 * trên (actor, idempotency key) bằng khoá resource dùng chung để hai lời gọi đồng thời cùng key
 * không thể cùng đi qua điểm kiểm tra replay rồi cùng tạo identity.
 *
 * <p>Service kiểm tra trước cùng các tiền điều kiện dữ kiện mà registrar sẽ ép khi ghi (workspace
 * là CENTER đang ACTIVE; người cấp là OWNER/MANAGER ACTIVE) để lời gọi chắc chắn bị từ chối không
 * tạo rồi xoá một user Keycloak vô ích; registrar vẫn là chốt chặn cuối khi ghi directory. Quyết
 * định cho phép ở các route thành viên không qua PDP: theo ngoại lệ mục 10 của
 * contracts/authorization.md, controller quyết bằng dữ kiện thư mục — {@link #requireAdmin} ép
 * OWNER/MANAGER ACTIVE và {@link #requireSubscriptionCurrent} ép gói còn hiệu lực bằng chính sách
 * gói trên dữ kiện kỳ gói — còn pipeline profile của executor chỉ đồng bộ eligibility hiện hành và
 * giữ ACCOUNT fence trước khi route chạy. Operation ghi cuối luồng qua {@link
 * MembershipPolicyProjector} mới là mốc hoàn tất bất đồng bộ: membership chỉ được coi là đã đồng bộ
 * khi operation SUCCEEDED.
 *
 * <p>Danh sách thành viên đọc hai bước theo ID: trang membership thuộc schema tenant trước, tên
 * đăng nhập của account thuộc schema hệ thống tra theo tập ID của trang sau — không join xuyên hai
 * mặt phẳng.
 */
@Service
public class MembershipService {
    private final CenterMembershipRepository memberships;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final SubscriptionPolicy subscriptionPolicy;
    private final IdempotencyService idempotency;
    private final InitialCredentialService handoff;
    private final MembershipPolicyProjector projector;
    private final ResourceMutationCoordinator coordinator;
    private final AuditRecorder audit;

    public MembershipService(
            CenterMembershipRepository memberships,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            SubscriptionPolicy subscriptionPolicy,
            IdempotencyService idempotency,
            InitialCredentialService handoff,
            MembershipPolicyProjector projector,
            ResourceMutationCoordinator coordinator,
            AuditRecorder audit) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.subscriptionPolicy = subscriptionPolicy;
        this.idempotency = idempotency;
        this.handoff = handoff;
        this.projector = projector;
        this.coordinator = coordinator;
        this.audit = audit;
    }

    /**
     * Kết quả cấp học sinh: operation của thay đổi membership, kèm handoff credential chỉ khi lần
     * gọi này thật sự tạo danh tính mới.
     */
    public record StudentIssueResult(
            AuthorizationOperation operation, InitialCredentialService.@Nullable Handoff handoff) {}

    /** Một dòng của danh sách thành viên: dữ kiện thư mục tối thiểu đúng một center. */
    public record MembershipView(
            UUID id,
            UUID userId,
            String username,
            List<MembershipRole.Role> roles,
            String state,
            long version) {
        public MembershipView {
            roles = List.copyOf(roles);
        }
    }

    /**
     * Cấp danh tính học sinh mới trong một center. Người cấp phải là OWNER hoặc MANAGER ACTIVE của
     * center đó. Trả operation chưa hoàn tất; handoff đi kèm chỉ ở lần tạo này.
     */
    @Transactional
    public StudentIssueResult issueStudent(
            UUID initiatorUserId,
            UUID workspaceId,
            String username,
            String displayName,
            @Nullable String email,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        idempotency.requireKey(idempotencyKey);
        coordinator.lockWorkspace(workspaceId);
        coordinator.lockForTransaction("student-issue:" + initiatorUserId + ":" + idempotencyKey);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return new StudentIssueResult(existing.get(), null);
        }
        requireCenterOperator(initiatorUserId, workspaceId);
        InitialCredentialService.Handoff issued =
                handoff.issueNewStudent(initiatorUserId, workspaceId, username, displayName, email);
        // Registrar vừa ghi membership qua JPA trong cùng transaction: flush để projector của
        // operation đọc được dữ kiện nguồn mới nhất bằng đường đọc riêng của nó.
        memberships.flush();
        AuthorizationOperation operation =
                projector.recordMembershipChange(
                        initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        audit.record(
                initiatorUserId,
                workspaceId,
                AuditEvent.Action.PRIVILEGE_CHANGE,
                AuditEvent.TargetType.MEMBERSHIP,
                issued.issued().membershipId(),
                operation.getId(),
                AuditEvent.Outcome.PENDING);
        return new StudentIssueResult(operation, issued);
    }

    /** Một trang membership từ DB; cursor vẫn là UUID, kể cả khi hàng mốc đã REVOKED. */
    public record MembershipPage(List<MembershipView> items, @Nullable String nextCursor) {
        public MembershipPage {
            items = List.copyOf(items);
        }
    }

    /**
     * Keyset pagination theo (joined_at, id). Hàng đã thu hồi vẫn là mốc cursor để không mất trang
     * còn lại khi membership cuối trang trước vừa bị thu hồi.
     */
    @Transactional(readOnly = true)
    public MembershipPage pageMemberships(UUID workspaceId, @Nullable String cursor, int pageSize) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        UUID cursorId = null;
        Instant joinedAt = null;
        if (cursor != null) {
            try {
                cursorId = UUID.fromString(cursor);
                if (!cursorId.toString().equals(cursor)) {
                    return new MembershipPage(List.of(), null);
                }
            } catch (IllegalArgumentException ex) {
                return new MembershipPage(List.of(), null);
            }
            Optional<CenterMembership> anchor = memberships.findById(cursorId);
            if (anchor.isEmpty() || !workspaceId.equals(anchor.get().getWorkspaceId())) {
                return new MembershipPage(List.of(), null);
            }
            joinedAt = anchor.get().getJoinedAt();
        }
        List<CenterMembership> page =
                memberships.findPageAfter(
                        workspaceId,
                        List.of(CenterMembership.State.PENDING, CenterMembership.State.ACTIVE),
                        joinedAt,
                        cursorId,
                        PageRequest.of(0, pageSize + 1));
        boolean hasMore = page.size() > pageSize;
        List<CenterMembership> rows = page.subList(0, Math.min(pageSize, page.size()));
        Map<UUID, String> usernames =
                accounts
                        .findByIdIn(rows.stream().map(CenterMembership::getUserId).toList())
                        .stream()
                        .collect(Collectors.toMap(UserAccount::getId, UserAccount::getUsername));
        List<MembershipView> items =
                rows.stream()
                        .map(membership ->
                                new MembershipView(
                                        membership.getId(),
                                        membership.getUserId(),
                                        Objects.requireNonNull(
                                                usernames.get(membership.getUserId()), "username"),
                                        membership.getRoles().stream()
                                                .sorted(
                                                        Comparator.comparing(
                                                                MembershipRole.Role::name))
                                                .toList(),
                                        membership.getState().name(),
                                        membership.getVersion()))
                        .toList();
        return new MembershipPage(
                items, hasMore && !items.isEmpty() ? items.getLast().id().toString() : null);
    }

    /** Quyền nguồn của actor trong center; dữ kiện được đọc dưới lock/fence của route. */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> activeRoles(UUID actorUserId, UUID workspaceId) {
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, actorUserId, CenterMembership.State.ACTIVE)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    public Set<MembershipRole.Role> requireAdmin(UUID actorUserId, UUID workspaceId) {
        Set<MembershipRole.Role> roles = activeRoles(actorUserId, workspaceId);
        if (!roles.contains(MembershipRole.Role.OWNER)
                && !roles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.PERMISSION_DENIED,
                    "Không có quyền quản trị thành viên của trung tâm này.");
        }
        return roles;
    }

    public void requireSubscriptionCurrent(UUID workspaceId) {
        if (!subscriptionPolicy.currentOrUnconfirmed(workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.SUBSCRIPTION_INACTIVE,
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    /** Chỉ đọc role của membership được sửa, không tải toàn bộ thành viên của center. */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> currentRoles(UUID workspaceId, UUID membershipId) {
        return memberships
                .findById(membershipId)
                .filter(membership ->
                        workspaceId.equals(membership.getWorkspaceId())
                                && membership.getState() != CenterMembership.State.REVOKED)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    private void requireCenterOperator(UUID initiatorUserId, UUID workspaceId) {
        boolean centerActive =
                workspaces
                        .findById(workspaceId)
                        .filter(workspace ->
                                workspace.getKind() == Workspace.Kind.CENTER
                                        && "ACTIVE".equals(workspace.getState()))
                        .isPresent();
        if (!centerActive) {
            // Không phân biệt thiếu/không phải center/không ACTIVE: không lộ sự tồn tại.
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.WORKSPACE_NOT_FOUND,
                    "Không tìm thấy trung tâm hợp lệ.");
        }
        Set<MembershipRole.Role> operatorRoles = activeRoles(initiatorUserId, workspaceId);
        if (!operatorRoles.contains(MembershipRole.Role.OWNER)
                && !operatorRoles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.OPERATOR_NOT_AUTHORIZED,
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được cấp danh tính học sinh.");
        }
    }
}
