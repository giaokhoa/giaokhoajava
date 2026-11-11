package com.giaokhoa.identity.service;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.infrastructure.tenancy.TenantSchemaProvisioner;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.MembershipIndexSynchronizer;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấp danh tính học sinh do center phát hành. Registrar chỉ ghi phía directory: account (origin
 * CENTER_ISSUED do server xác lập) kèm personal workspace và membership STUDENT trong center. Việc
 * tạo user trên Keycloak và bàn giao credential tạm thuộc về caller (caller tạo Keycloak user trước
 * rồi đưa Keycloak sub vào đây). Registrar không tạo user Keycloak, không đặt mật khẩu, không trả
 * mật khẩu cho ai. Account đã có không bao giờ bị đổi origin hay ghi đè: mời account có sẵn chỉ
 * thêm membership, không reset danh tính. Rời center chỉ thu hồi membership, không xóa account hay
 * personal workspace — việc thu hồi thuộc luồng riêng, registrar không xóa gì.
 */
@Service
public class IssuedStudentRegistrationService {
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CenterMembershipRepository memberships;
    private final MembershipIndexSynchronizer membershipIndex;

    private final ResourceMutationCoordinator coordinator;
    private final TenantSchemaProvisioner tenantProvisioner;

    public IssuedStudentRegistrationService(
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CenterMembershipRepository memberships,
            MembershipIndexSynchronizer membershipIndex,
            ResourceMutationCoordinator coordinator,
            TenantSchemaProvisioner tenantProvisioner) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.membershipIndex = membershipIndex;
        this.coordinator = coordinator;
        this.tenantProvisioner = tenantProvisioner;
    }

    /** Kết quả cấp: user, personal workspace và membership STUDENT, kèm cờ tạo mới. */
    public record IssuedStudent(
            UUID userId,
            UUID personalWorkspaceId,
            UUID membershipId,
            boolean accountCreated,
            boolean membershipCreated) {}

    /**
     * Cấp danh tính học sinh trong một center. Operator phải đang là OWNER hoặc MANAGER ACTIVE của
     * center đó — đây là tiền điều kiện dữ kiện của thao tác ghi, không phải quyết định PDP thay
     * cho endpoint (endpoint vẫn phải qua PDP). Membership do center trực tiếp phát hành nên ACTIVE
     * ngay: đường mời/chấp nhận PENDING thuộc luồng invite riêng. Gọi lại cùng student là
     * idempotent: trả đúng membership đã có, không tạo membership trùng.
     */
    @Transactional
    public IssuedStudent issueStudent(
            UUID operatorUserId, UUID centerWorkspaceId, String keycloakSub, String username) {
        Objects.requireNonNull(operatorUserId, "operatorUserId");
        Objects.requireNonNull(centerWorkspaceId, "centerWorkspaceId");
        Objects.requireNonNull(keycloakSub, "keycloakSub");
        Objects.requireNonNull(username, "username");
        if (keycloakSub.isBlank() || username.isBlank()) {
            throw new IllegalArgumentException("Keycloak subject and username are required");
        }

        coordinator.lockWorkspace(centerWorkspaceId);
        boolean centerActive =
                workspaces
                        .findById(centerWorkspaceId)
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
        Set<MembershipRole.Role> operatorRoles =
                memberships
                        .findByWorkspaceIdAndUserIdAndState(
                                centerWorkspaceId, operatorUserId, CenterMembership.State.ACTIVE)
                        .map(membership -> membership.getRoles())
                        .orElse(Set.of());
        if (!operatorRoles.contains(MembershipRole.Role.OWNER)
                && !operatorRoles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.OPERATOR_NOT_AUTHORIZED,
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được cấp danh tính học sinh.");
        }

        UUID userId;
        UUID personalWorkspaceId;
        boolean accountCreated;
        var existing = accounts.findByKeycloakSub(keycloakSub);
        if (existing.isPresent()) {
            UserAccount account = existing.get();
            if (!username.equals(account.getUsername())) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.USERNAME_MISMATCH,
                        "Tên đăng nhập không khớp danh tính đã có.");
            }
            userId = account.getId();
            personalWorkspaceId = personalWorkspaceOf(userId);
            accountCreated = false;
        } else {
            if (accounts.existsByUsername(username)) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.USERNAME_TAKEN,
                        "Tên đăng nhập đã thuộc về một danh tính khác.");
            }
            userId = UUID.randomUUID();
            personalWorkspaceId = UUID.randomUUID();
            accounts.save(
                    new UserAccount(
                            userId,
                            keycloakSub,
                            username,
                            null,
                            false,
                            UserAccount.Origin.CENTER_ISSUED,
                            "ACTIVE"));
            workspaces.save(
                    new Workspace(
                            personalWorkspaceId, Workspace.Kind.PERSONAL, userId, null, "ACTIVE"));
            tenantProvisioner.provisionAfterCommit(personalWorkspaceId);
            accountCreated = true;
        }

        UUID membershipId;
        boolean membershipCreated;
        var current =
                memberships.findByWorkspaceIdAndUserIdAndStateIn(
                        centerWorkspaceId,
                        userId,
                        List.of(CenterMembership.State.PENDING, CenterMembership.State.ACTIVE));
        if (current.isPresent()) {
            CenterMembership membership = current.get();
            membershipId = membership.getId();
            membershipCreated = false;
            // Cấp trực tiếp kích hoạt luôn membership đã mời bằng cập nhật có điều kiện không
            // tăng version và giữ nguyên joined_at, vì trigger guard_directory_history cấm sửa
            // lịch sử tham gia; membership đã ACTIVE thì cập nhật không chạm hàng nào.
            memberships.activateIfPending(
                    membershipId, CenterMembership.State.ACTIVE, CenterMembership.State.PENDING);
            membership.getRoles().add(MembershipRole.Role.STUDENT);
            membershipIndex.sync(userId, centerWorkspaceId, CenterMembership.State.ACTIVE);
        } else {
            membershipId = UUID.randomUUID();
            CenterMembership membership =
                    new CenterMembership(
                            membershipId,
                            centerWorkspaceId,
                            userId,
                            CenterMembership.State.ACTIVE,
                            Instant.now());
            membership.getRoles().add(MembershipRole.Role.STUDENT);
            memberships.save(membership);
            membershipIndex.sync(userId, centerWorkspaceId, CenterMembership.State.ACTIVE);
            membershipCreated = true;
        }
        return new IssuedStudent(
                userId, personalWorkspaceId, membershipId, accountCreated, membershipCreated);
    }

    private UUID personalWorkspaceOf(UUID userId) {
        List<Workspace> found =
                workspaces.findByKindAndOwnerUserId(Workspace.Kind.PERSONAL, userId);
        if (found.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.IDENTITY_UNAVAILABLE,
                    "Không xác minh được danh tính hiện hành, thử lại sau.");
        }
        return found.getFirst().getId();
    }
}
