package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.shared.AdvisoryLocks;
import com.giaokhoa.shared.Sha256;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.Invitation;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.InvitationRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vòng đời lời mời vào center: tạo lời mời một đích kèm token một lần, và chấp nhận lời mời để tạo
 * hoặc bổ sung membership. Chỉ hash SHA-256 hex của token được lưu (theo thiết kế của migration
 * V012); token thô sinh bằng SecureRandom 32 byte, chỉ tồn tại trong kết quả của lần tạo đầu tiên
 * để tầng endpoint chuyển tới người được mời, không bao giờ được lưu hay ghi log ở đây. Chấp nhận
 * tra lời mời theo hash của token trình ra: không thấy là 404 không lộ tồn tại, thấy thì người chấp
 * nhận phải đúng đích đã mời — đúng user khi mời theo userId, hoặc account có email đã xác minh
 * trùng email đích khi mời theo email; tên trùng không bao giờ đủ để ghép tài khoản. Chấp nhận
 * không đụng vào user_account nên origin của danh tính được mời không đổi.
 *
 * <p>Role đích của lời mời không chứa OWNER (DB cũng chặn ở CHECK) và MANAGER chỉ do owner của
 * center mời: đó là quy tắc nghiệp vụ của service vì CHECK tĩnh không khóa được người mời, còn
 * quyết định cho phép ở endpoint vẫn do Keycloak PDP xét qua scope membership.invite/manager.assign
 * trước khi gọi vào đây. Tạo lời mời chưa tạo membership; membership chỉ xuất hiện khi chấp nhận.
 * Cả tạo và chấp nhận đều kết thúc bằng operation ghi lại bundle của workspace qua {@link
 * MembershipPolicyProjector} — chấp nhận đổi dữ kiện membership, còn tạo lời mời tuy chưa đổi nguồn
 * quyền nhưng hợp đồng mutation vẫn đòi một operation hoàn tất được, và operation đó đồng thời bảo
 * đảm partition của workspace được reconcile về trạng thái nguồn hiện hành.
 *
 * <p>Idempotency theo đúng mẫu operation dùng chung: mỗi mutation tuần tự hoá trên (actor,
 * idempotency key) hoặc trên token bằng advisory lock trong transaction hiện hành rồi mới kiểm tra
 * replay, nên replay cùng key và payload trả lại đúng operation đã ghi — token thô không tái tạo
 * được ở lần replay, kết quả replay vì vậy không mang token. {@link JdbcOperations} chỉ còn phục vụ
 * advisory lock (hạ tầng khóa), dữ kiện đọc/ghi qua repository JPA.
 */
@Service
public class InvitationService {
    /** Thời hạn của một lời mời; hết hạn được ép tại lúc chấp nhận theo mốc này. */
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcOperations locks;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CenterMembershipRepository memberships;
    private final InvitationRepository invitations;
    private final MembershipIndexSynchronizer membershipIndex;
    private final IdempotencyService idempotency;
    private final MembershipPolicyProjector projector;
    private final AuditRecorder audit;

    private final ResourceMutationCoordinator coordinator;

    public InvitationService(
            JdbcOperations locks,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CenterMembershipRepository memberships,
            InvitationRepository invitations,
            MembershipIndexSynchronizer membershipIndex,
            IdempotencyService idempotency,
            MembershipPolicyProjector projector,
            ResourceMutationCoordinator coordinator,
            AuditRecorder audit) {
        this.locks = locks;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.invitations = invitations;
        this.membershipIndex = membershipIndex;
        this.idempotency = idempotency;
        this.projector = projector;
        this.coordinator = coordinator;
        this.audit = audit;
    }

    /**
     * Kết quả tạo lời mời. Token thô chỉ có ở lần tạo đầu tiên; replay theo idempotency key trả
     * operation đã ghi với cả invitationId và token đều vắng.
     */
    public record InvitationIssued(
            AuthorizationOperation operation,
            @Nullable UUID invitationId,
            @Nullable String token) {}

    /**
     * Tạo lời mời vào một center cho đúng một đích: {@code targetUserId} của account đã có, hoặc
     * {@code targetEmail} sẽ được chứng minh khi chấp nhận. Người mời phải là OWNER hoặc MANAGER
     * ACTIVE của center — tiền điều kiện dữ kiện của thao tác ghi, không thay quyết định PDP của
     * endpoint.
     */
    @Transactional
    public InvitationIssued invite(
            UUID initiatorUserId,
            UUID workspaceId,
            @Nullable String targetEmail,
            @Nullable UUID targetUserId,
            Set<MembershipRole.Role> roles,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(roles, "roles");
        idempotency.requireKey(idempotencyKey);
        coordinator.lockWorkspace(workspaceId);
        lockOn("invitation-create:" + initiatorUserId + ":" + idempotencyKey);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return new InvitationIssued(existing.get(), null, null);
        }
        if ((targetEmail == null) == (targetUserId == null)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_INVITATION_TARGET,
                    "Lời mời phải có đúng một đích: email hoặc người dùng đã có.");
        }
        if (roles.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_INVITATION_ROLES,
                    "Lời mời phải có ít nhất một role đích.");
        }
        if (roles.contains(MembershipRole.Role.OWNER)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.OWNER_NOT_INVITABLE,
                    "Lời mời không được cấp role OWNER.");
        }
        requireCenter(workspaceId);
        Set<MembershipRole.Role> initiatorRoles = activeRolesOf(initiatorUserId, workspaceId);
        if (!initiatorRoles.contains(MembershipRole.Role.OWNER)
                && !initiatorRoles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.INVITER_NOT_AUTHORIZED,
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được mời thành viên.");
        }
        if (roles.contains(MembershipRole.Role.MANAGER)
                && !initiatorRoles.contains(MembershipRole.Role.OWNER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.MANAGER_REQUIRES_OWNER,
                    "Chỉ chủ sở hữu trung tâm mới được mời quản lý.");
        }
        String email;
        if (targetUserId != null) {
            requireAccount(targetUserId);
            email = null;
        } else {
            email = normalizeEmail(Objects.requireNonNull(targetEmail, "targetEmail"));
        }

        String token = generateToken();
        UUID invitationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(INVITATION_TTL);
        List<String> roleNames = roles.stream().map(role -> role.name()).sorted().toList();
        invitations.save(
                new Invitation(
                        invitationId,
                        workspaceId,
                        email,
                        targetUserId,
                        tokenHash(token),
                        roleNames,
                        expiresAt));
        AuthorizationOperation operation =
                projector.recordMembershipChange(
                        initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        return new InvitationIssued(operation, invitationId, token);
    }

    /**
     * Chấp nhận lời mời bằng token thô được trình ra. Token chỉ dùng được một lần: chấp nhận xong
     * trạng thái là ACCEPTED và mọi lần dùng lại đều bị từ chối. Membership được tạo ACTIVE ngay
     * với các role đích, hoặc bổ sung role vào membership hiện hành nếu người được mời đã là thành
     * viên — không tạo membership trùng; membership đã thu hồi không bị phục hồi ngầm, lần chấp
     * nhận tạo một quan hệ hiện hành mới theo đúng bất biến một quan hệ hiện hành mỗi
     * user/workspace.
     */
    @Transactional
    public AuthorizationOperation accept(
            UUID acceptorUserId, String rawToken, String idempotencyKey, String payloadHash) {
        Objects.requireNonNull(acceptorUserId, "acceptorUserId");
        Objects.requireNonNull(rawToken, "rawToken");
        idempotency.requireKey(idempotencyKey);
        String hash = tokenHash(rawToken);
        lockOn("invitation-accept:" + hash);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(acceptorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        Invitation invitation =
                invitations
                        .findForUpdateByTokenHash(hash)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        ErrorCodes.INVITATION_NOT_FOUND,
                                        "Không tìm thấy lời mời."));
        // Token lạ và token không tồn tại trả cùng một 404: không lộ lời mời nào.
        coordinator.lockWorkspace(invitation.getWorkspaceId());
        if (invitation.getState() != Invitation.State.PENDING) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.INVITATION_NOT_PENDING,
                    "Lời mời không còn chờ chấp nhận.");
        }
        if (!invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, ErrorCodes.INVITATION_EXPIRED, "Lời mời đã hết hạn.");
        }
        requireTargetMatch(invitation, acceptorUserId);

        MembershipActivation activation =
                activateMembership(invitation.getWorkspaceId(), acceptorUserId);
        CenterMembership membership = activation.membership();
        UUID membershipId = membership.getId();
        boolean addedNewRole = false;
        for (String role : invitation.getIntendedRoles()) {
            addedNewRole |= membership.getRoles().add(MembershipRole.Role.valueOf(role));
        }
        if (activation.preExisting() && addedNewRole) {
            // Role nằm ở bảng riêng nên phải chạm row membership thì version mới tăng: nếu
            // không, một writer khác giữ If-Match cũ vẫn ghi đè được và làm mất role vừa cộng.
            memberships.incrementVersionById(membershipId);
        }
        invitation.accept();
        memberships.flush();
        AuthorizationOperation operation =
                projector.recordMembershipChange(
                        acceptorUserId, invitation.getWorkspaceId(), idempotencyKey, payloadHash);
        // Chỉ chấp nhận mới đổi đặc quyền (tạo membership hoặc cộng role); tạo lời mời chưa
        // tạo membership nào nên không ghi audit ở đường invite.
        audit.record(
                acceptorUserId,
                invitation.getWorkspaceId(),
                AuditEvent.Action.PRIVILEGE_CHANGE,
                AuditEvent.TargetType.MEMBERSHIP,
                membershipId,
                operation.getId(),
                AuditEvent.Outcome.PENDING);
        return operation;
    }

    private void requireTargetMatch(Invitation invitation, UUID acceptorUserId) {
        UUID targetUserId = invitation.getTargetUserId();
        if (targetUserId != null) {
            if (!targetUserId.equals(acceptorUserId)) {
                throw targetMismatch();
            }
            return;
        }
        String targetEmail = Objects.requireNonNull(invitation.getTargetEmail(), "target_email");
        UserAccount account =
                accounts.findById(acceptorUserId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "ACCOUNT_NOT_FOUND",
                                        "Chưa có tài khoản cho danh tính này."));
        // Email đích chỉ ghép được khi account hiện hành mang đúng email đó và email đã xác
        // minh: khớp tên hay email chưa xác minh đều không chứng minh được quyền sở hữu đích.
        String email = account.getEmail();
        if (!account.isEmailVerified() || email == null || !email.equalsIgnoreCase(targetEmail)) {
            throw targetMismatch();
        }
    }

    /** Membership sau bước kích hoạt, kèm cờ cho biết membership đã tồn tại từ trước. */
    private record MembershipActivation(CenterMembership membership, boolean preExisting) {}

    private MembershipActivation activateMembership(UUID workspaceId, UUID userId) {
        Optional<CenterMembership> current =
                memberships.findByWorkspaceIdAndUserIdAndStateIn(
                        workspaceId,
                        userId,
                        List.of(CenterMembership.State.PENDING, CenterMembership.State.ACTIVE));
        if (current.isPresent()) {
            CenterMembership membership = current.get();
            if (membership.getState() == CenterMembership.State.PENDING) {
                // Chỉ đổi state, giữ nguyên joined_at vì trigger guard_directory_history
                // cấm sửa lịch sử tham gia.
                membership.activate();
                membershipIndex.sync(userId, workspaceId, CenterMembership.State.ACTIVE);
            }
            return new MembershipActivation(membership, true);
        }
        CenterMembership membership =
                new CenterMembership(
                        UUID.randomUUID(),
                        workspaceId,
                        userId,
                        CenterMembership.State.ACTIVE,
                        Instant.now());
        memberships.save(membership);
        membershipIndex.sync(userId, workspaceId, CenterMembership.State.ACTIVE);
        return new MembershipActivation(membership, false);
    }

    private void requireCenter(UUID workspaceId) {
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
    }

    private void requireAccount(UUID userId) {
        if (!accounts.existsById(userId)) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.ACCOUNT_NOT_FOUND,
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private Set<MembershipRole.Role> activeRolesOf(UUID userId, UUID workspaceId) {
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, userId, CenterMembership.State.ACTIVE)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    private static HttpProblemException targetMismatch() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN,
                ErrorCodes.INVITATION_TARGET_MISMATCH,
                "Lời mời này không dành cho tài khoản hiện hành.");
    }

    private static String normalizeEmail(String email) {
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > 320 || !trimmed.contains("@")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_TARGET_EMAIL,
                    "Email đích của lời mời không hợp lệ.");
        }
        return trimmed;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hash tra cứu của token: SHA-256 hex, đúng định dạng cột token_hash của V012. */
    private static String tokenHash(String rawToken) {
        return Sha256.hex(rawToken);
    }

    /**
     * Tuần tự hoá trong phạm vi transaction hiện hành bằng advisory lock của PostgreSQL, cùng cách
     * CenterService khoá: lock tự nhả khi transaction kết thúc nên không cần đường mở khoá riêng,
     * và hai instance của ứng dụng vẫn tôn trọng nhau.
     */
    private void lockOn(String key) {
        AdvisoryLocks.lock(locks, "giaokhoa:invitation-service:", key);
    }
}
