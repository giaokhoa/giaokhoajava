package com.giaokhoa.guardians.service;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.guardians.GuardianPolicyProjector;
import com.giaokhoa.guardians.model.GuardianLink;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.guardians.repository.WorkspaceRepository;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.MembershipService;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ vòng đời guardian link ở tầng dữ kiện nguồn và nguồn quyền: parent tự gửi request cho
 * chính mình, owner/manager quyết định duyệt hoặc từ chối, và gỡ link đã duyệt. Parent của link
 * luôn là actor của lời gọi — service không nhận parent từ bất kỳ tham số nào khác, nên không đường
 * nào tạo được link cho phụ huynh khác. Mỗi mutation ghi một authorization operation qua {@link
 * GuardianPolicyProjector} trong cùng transaction, và link chỉ được coi là đã đồng bộ nguồn khi
 * operation SUCCEEDED; service không tự quyết quyền thay Keycloak PDP (quyết định cho phép ở route)
 * và không báo hoàn tất khi operation còn PENDING.
 *
 * <p>Tiền đề role được kiểm tra trên dữ kiện nguồn trước khi ghi, theo đúng chỗ trigger DB không
 * kiểm: lúc gửi request, actor phải có membership ACTIVE kèm role PARENT trong đúng center và học
 * sinh phải có membership ACTIVE kèm role STUDENT trong đúng center (học sinh ngoài phạm vi trả 404
 * để không lộ sự tồn tại); lúc duyệt, cả hai tiền đề được xét lại tại thời điểm quyết định.
 * REJECTED là kết thúc — muốn liên kết lại phải gửi request mới tạo hàng mới; thu hồi chỉ đi từ
 * APPROVED và không bị chặn bởi gói hết hạn, còn duyệt là thao tác mở quyền nên bị chặn khi gói đã
 * hết hiệu lực. Mọi mutation đều ghi một dòng {@code audit_event} RELATIONSHIP_CHANGE gắn operation
 * của nó, để quyết định từ chối — vốn không ghi người quyết định lên hàng link theo hình dạng trạng
 * thái của schema — vẫn truy được actor đã quyết.
 *
 * <p>Như các service nguồn quyền khác, mỗi phương thức đặt context workspace cho transaction qua
 * {@link WorkspaceTransaction} ngay khi bắt đầu vì bảng guardian_link nằm dưới RLS theo đúng
 * context đó, và giữ khóa workspace thống nhất qua {@link ResourceMutationCoordinator} để dữ kiện
 * nguồn mà projector chiếu không bao giờ thiếu một mutation đang chạy đồng thời.
 */
@Service
public class GuardianLinkService {
    private final GuardianLinkRepository links;
    private final WorkspaceRepository workspaces;
    private final MembershipService memberships;
    private final WorkspaceTransaction workspaceTransaction;
    private final IdempotencyService idempotency;
    private final GuardianPolicyProjector projector;
    private final ResourceMutationCoordinator coordinator;
    private final AuditRecorder audit;

    public GuardianLinkService(
            GuardianLinkRepository links,
            WorkspaceRepository workspaces,
            MembershipService memberships,
            WorkspaceTransaction workspaceTransaction,
            IdempotencyService idempotency,
            GuardianPolicyProjector projector,
            ResourceMutationCoordinator coordinator,
            AuditRecorder audit) {
        this.links = links;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.workspaceTransaction = workspaceTransaction;
        this.idempotency = idempotency;
        this.projector = projector;
        this.coordinator = coordinator;
        this.audit = audit;
    }

    /** Một liên kết phụ huynh: hai phía, trạng thái vòng đời và row version cho decision. */
    public record GuardianLinkView(
            UUID id,
            UUID workspaceId,
            UUID parentUserId,
            UUID studentUserId,
            String state,
            long version) {}

    /**
     * Parent gửi request liên kết tới một học sinh của center cho chính mình. Trả operation chưa
     * hoàn tất; replay cùng idempotency key và payload trả lại đúng operation đã ghi.
     */
    @Transactional
    public AuthorizationOperation requestLink(
            UUID actorUserId,
            UUID workspaceId,
            UUID studentUserId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(studentUserId, "studentUserId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(actorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireCenterWorkspace(workspaceId);
        if (!hasCurrentRole(workspaceId, actorUserId, MembershipRole.Role.PARENT)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "GUARDIAN_PARENT_NOT_CURRENT",
                    "Chỉ phụ huynh hiện hành của trung tâm mới gửi được yêu cầu liên kết.");
        }
        if (!hasCurrentRole(workspaceId, studentUserId, MembershipRole.Role.STUDENT)) {
            throw linkTargetNotFound();
        }
        boolean alreadyCurrent =
                links.existsByWorkspaceIdAndParentUserIdAndStudentUserIdAndStateIn(
                        workspaceId,
                        actorUserId,
                        studentUserId,
                        List.of(GuardianLink.REQUESTED, GuardianLink.APPROVED));
        if (alreadyCurrent) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "GUARDIAN_LINK_ALREADY_CURRENT",
                    "Đã có liên kết hiện hành cho cặp phụ huynh và học sinh này.");
        }
        UUID linkId = UUID.randomUUID();
        links.save(new GuardianLink(linkId, workspaceId, actorUserId, studentUserId, actorUserId));
        AuthorizationOperation operation =
                projector.recordGuardianChange(
                        actorUserId, workspaceId, idempotencyKey, payloadHash);
        recordAudit(actorUserId, workspaceId, linkId, operation);
        return operation;
    }

    /**
     * Quyết định một request đang chờ: APPROVE mở nguồn guardian group của học sinh cho parent,
     * REJECT đóng request vĩnh viễn. {@code expectedVersion} phải khớp row version hiện tại — lệch
     * trả 409 như mọi precondition version của repo. Chỉ owner/manager hiện hành của center mới
     * quyết định được; service kiểm tra lại role này trước khi ghi, không chỉ dựa vào controller.
     */
    @Transactional
    public AuthorizationOperation decideLink(
            UUID actorUserId,
            UUID workspaceId,
            UUID linkId,
            String decision,
            long expectedVersion,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(linkId, "linkId");
        idempotency.requireKey(idempotencyKey);
        boolean approve = "APPROVE".equals(decision);
        if (!approve && !"REJECT".equals(decision)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "GUARDIAN_DECISION_INVALID",
                    "Quyết định liên kết chỉ là APPROVE hoặc REJECT.");
        }
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(actorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireCenterWorkspace(workspaceId);
        GuardianLink link = lockLink(workspaceId, linkId);
        requireAdmin(workspaceId, actorUserId);
        if (!GuardianLink.REQUESTED.equals(link.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "GUARDIAN_LINK_ALREADY_DECIDED",
                    "Liên kết đã có quyết định hoặc đã đóng, không quyết định lại được.");
        }
        if (expectedVersion != link.getVersion()) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "VERSION_CONFLICT", "Phiên bản dữ liệu đã thay đổi.");
        }
        if (approve) {
            requirePrerequisitesCurrent(workspaceId, link);
            requireSubscriptionCurrent(workspaceId);
            link.approve(actorUserId, Instant.now());
        } else {
            link.reject();
        }
        links.flush();
        AuthorizationOperation operation =
                projector.recordGuardianChange(
                        actorUserId, workspaceId, idempotencyKey, payloadHash);
        recordAudit(actorUserId, workspaceId, linkId, operation);
        return operation;
    }

    /**
     * Gỡ một link đã duyệt: chuyển REVOKED kèm revoked_at, giữ lịch sử, và đóng nguồn guardian
     * group trong cùng revision qua operation. Thu hồi không đòi version và không bị chặn bởi gói
     * hết hạn. Chỉ owner/manager hiện hành của center mới gỡ được; service kiểm tra lại role này
     * trước khi ghi, không chỉ dựa vào controller.
     */
    @Transactional
    public AuthorizationOperation revokeLink(
            UUID actorUserId,
            UUID workspaceId,
            UUID linkId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(linkId, "linkId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        coordinator.lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(actorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireCenterWorkspace(workspaceId);
        GuardianLink link = lockLink(workspaceId, linkId);
        requireAdmin(workspaceId, actorUserId);
        switch (link.getState()) {
            case GuardianLink.APPROVED -> {
                // Trạng thái duy nhất thu hồi được; xử lý ở dưới sau switch.
            }
            case GuardianLink.REQUESTED ->
                    throw new HttpProblemException(
                            HttpStatus.CONFLICT,
                            "GUARDIAN_LINK_NOT_APPROVED",
                            "Liên kết chưa được duyệt thì không có nguồn nào để gỡ.");
            case GuardianLink.REJECTED ->
                    throw new HttpProblemException(
                            HttpStatus.CONFLICT,
                            "GUARDIAN_LINK_ALREADY_CLOSED",
                            "Liên kết đã bị từ chối và đã đóng.");
            case GuardianLink.REVOKED ->
                    throw new HttpProblemException(
                            HttpStatus.CONFLICT,
                            "GUARDIAN_LINK_ALREADY_REVOKED",
                            "Liên kết đã bị gỡ trước đó.");
            default ->
                    throw new IllegalStateException(
                            "Unknown guardian link state: " + link.getState());
        }
        link.revoke(Instant.now());
        links.flush();
        AuthorizationOperation operation =
                projector.recordGuardianChange(
                        actorUserId, workspaceId, idempotencyKey, payloadHash);
        recordAudit(actorUserId, workspaceId, linkId, operation);
        return operation;
    }

    /** Mọi link của chính parent trong center, gồm cả lịch sử đã đóng, cũ nhất trước. */
    @Transactional(readOnly = true)
    public List<GuardianLinkView> listLinksOfParent(UUID workspaceId, UUID parentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(parentUserId, "parentUserId");
        workspaceTransaction.applyContext(workspaceId);
        return links
                .findByWorkspaceIdAndParentUserIdOrderByCreatedAtAscIdAsc(workspaceId, parentUserId)
                .stream()
                .map(link -> view(workspaceId, link))
                .toList();
    }

    /**
     * Các link quản lý còn tác động được trong center — đang chờ duyệt hoặc đã duyệt — cũ nhất
     * trước. Lịch sử đã đóng (REJECTED/REVOKED) không nằm trong danh sách cần xử lý này.
     */
    @Transactional(readOnly = true)
    public List<GuardianLinkView> listLinksForReview(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        workspaceTransaction.applyContext(workspaceId);
        return links
                .findByWorkspaceIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, List.of(GuardianLink.REQUESTED, GuardianLink.APPROVED))
                .stream()
                .map(link -> view(workspaceId, link))
                .toList();
    }

    private GuardianLink lockLink(UUID workspaceId, UUID linkId) {
        return links.findByWorkspaceIdAndIdForUpdate(workspaceId, linkId)
                .orElseThrow(() ->
                        new HttpProblemException(
                                HttpStatus.NOT_FOUND,
                                "GUARDIAN_LINK_NOT_FOUND",
                                "Không tìm thấy liên kết phụ huynh trong trung tâm này."));
    }

    /** Tiền đề của cả hai phía phải còn hiện hành tại thời điểm duyệt, không chỉ lúc gửi. */
    private void requirePrerequisitesCurrent(UUID workspaceId, GuardianLink link) {
        if (!hasCurrentRole(workspaceId, link.getParentUserId(), MembershipRole.Role.PARENT)
                || !hasCurrentRole(
                        workspaceId, link.getStudentUserId(), MembershipRole.Role.STUDENT)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "GUARDIAN_PREREQUISITE_NOT_CURRENT",
                    "Phụ huynh hoặc học sinh không còn là thành viên hiện hành đúng vai trò"
                            + " của trung tâm.");
        }
    }

    /**
     * Chỉ owner/manager hiện hành của center mới quyết định hoặc gỡ link — kiểm tra lại ở tầng
     * service, theo đúng tiền lệ MembershipService, để lời gọi thẳng service cũng bị chặn.
     */
    private void requireAdmin(UUID workspaceId, UUID actorUserId) {
        if (!hasCurrentRole(workspaceId, actorUserId, MembershipRole.Role.OWNER)
                && !hasCurrentRole(workspaceId, actorUserId, MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Actor không phải owner hoặc manager của center này.");
        }
    }

    private boolean hasCurrentRole(UUID workspaceId, UUID userId, MembershipRole.Role role) {
        return memberships.activeRoles(userId, workspaceId).contains(role);
    }

    private void requireCenterWorkspace(UUID workspaceId) {
        Workspace workspace = workspaces.findById(workspaceId).orElse(null);
        if (workspace == null
                || !"ACTIVE".equals(workspace.getState())
                || workspace.getKind() != Workspace.Kind.CENTER) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND",
                    "Không tìm thấy trung tâm hợp lệ cho liên kết phụ huynh.");
        }
    }

    /**
     * Gói còn hiệu lực theo đúng mẫu của các service nguồn quyền khác: workspace chưa từng có kỳ
     * gói nào thì không bị chặn; đã có kỳ thì phải có ít nhất một kỳ đang trong hiệu lực — qua
     * MembershipService, nơi giữ cùng chính sách và cùng lỗi 403 SUBSCRIPTION_INACTIVE.
     */
    private void requireSubscriptionCurrent(UUID workspaceId) {
        memberships.requireSubscriptionCurrent(workspaceId);
    }

    private void recordAudit(
            UUID actorUserId, UUID workspaceId, UUID linkId, AuthorizationOperation operation) {
        audit.record(
                actorUserId,
                workspaceId,
                AuditEvent.Action.RELATIONSHIP_CHANGE,
                AuditEvent.TargetType.RELATIONSHIP,
                linkId,
                operation.getId(),
                AuditEvent.Outcome.PENDING);
    }

    private static GuardianLinkView view(UUID workspaceId, GuardianLink link) {
        return new GuardianLinkView(
                link.getId(),
                workspaceId,
                link.getParentUserId(),
                link.getStudentUserId(),
                link.getState(),
                link.getVersion());
    }

    private static HttpProblemException linkTargetNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "GUARDIAN_STUDENT_NOT_FOUND",
                "Không tìm thấy học sinh hiện hành trong trung tâm này.");
    }
}
