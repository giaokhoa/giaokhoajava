package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.guardians.GuardianRevocationContributor;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.infrastructure.tenancy.TenantSchemaProvisioner;
import com.giaokhoa.shared.AdvisoryLocks;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingRevocationContributor;
import com.giaokhoa.tenancy.model.Center;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.CenterRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị center ở tầng dữ kiện nguồn: tạo tenant (workspace CENTER + center + Organization +
 * membership OWNER) và quản lý role/thu hồi membership trong đúng một workspace. Mọi thay đổi quyền
 * đều đi qua authorization operation và desired projection của {@link CenterPolicyProjector}:
 * service chỉ ghi dữ kiện nguồn và operation trong transaction của app-db, không tự quyết quyền
 * thay Keycloak PDP và không báo đồng bộ xong khi operation còn PENDING. Quyết định cho phép ở
 * endpoint vẫn do PDP xét trước khi gọi vào đây; các kiểm tra trong service này là bất biến dữ kiện
 * (đúng workspace, owner đích đã có danh tính, không gỡ owner cuối cùng) chứ không phải một engine
 * quyền thứ hai.
 *
 * <p>Idempotency theo đúng hợp đồng operation: replay cùng key và cùng payload trả lại đúng
 * operation đã ghi — không tạo tenant thứ hai hay owner thứ hai — còn cùng key khác payload là 409.
 * Vì dữ kiện nguồn nằm ngoài callback của operation, mỗi mutation tự tuần tự hoá trên (actor,
 * idempotency key) hoặc trên membership bằng advisory lock trong chính transaction hiện hành, rồi
 * mới kiểm tra replay: hai lời gọi đồng thời cùng key không thể cùng đi qua điểm kiểm tra. OWNER
 * chỉ đến từ lúc tạo center; đường đổi role không cấp mới OWNER và không gỡ OWNER khỏi membership
 * của chủ sở hữu, vì center phải luôn có đúng một owner.
 *
 * <p>Dữ kiện directory đọc/ghi qua repository JPA; {@link JdbcOperations} chỉ còn phục vụ advisory
 * lock trong transaction (hạ tầng khóa của PostgreSQL, không phải đường truy cập dữ kiện). Trước
 * khi gọi các cộng tác viên của module khác đọc dữ kiện nguồn bằng đường riêng của chúng, service
 * flush tường minh để thay đổi JPA đã ghi luôn nhìn thấy được trong cùng transaction.
 */
@Service
public class CenterService {
    private static final int CURSOR_PART_COUNT = 3;
    private final JdbcOperations locks;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CenterRepository centers;
    private final CenterMembershipRepository memberships;
    private final MembershipIndexSynchronizer membershipIndex;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator revocations;
    private final AuthorizationProvisioningClient directory;
    private final TeachingRevocationContributor teachingRevocations;
    private final GuardianRevocationContributor guardianRevocations;
    private final AuditRecorder audit;
    private final TenantSchemaProvisioner tenantProvisioner;

    private final ResourceMutationCoordinator coordinator;

    public CenterService(
            JdbcOperations locks,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CenterRepository centers,
            CenterMembershipRepository memberships,
            MembershipIndexSynchronizer membershipIndex,
            IdempotencyService idempotency,
            SourceRevocationCoordinator revocations,
            AuthorizationProvisioningClient directory,
            ResourceMutationCoordinator coordinator,
            TeachingRevocationContributor teachingRevocations,
            GuardianRevocationContributor guardianRevocations,
            AuditRecorder audit,
            TenantSchemaProvisioner tenantProvisioner) {
        this.locks = locks;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.centers = centers;
        this.memberships = memberships;
        this.membershipIndex = membershipIndex;
        this.idempotency = idempotency;
        this.revocations = revocations;
        this.directory = directory;
        this.coordinator = coordinator;
        this.teachingRevocations = teachingRevocations;
        this.guardianRevocations = guardianRevocations;
        this.audit = audit;
        this.tenantProvisioner = tenantProvisioner;
    }

    /** Metadata tối thiểu của một center cho danh sách của platform operator. */
    public record CenterView(
            UUID id, UUID workspaceId, String name, UUID ownerUserId, String state) {}

    /**
     * Tạo center cho một owner đã có danh tính. Organization được provision đồng bộ trước khi ghi
     * dữ kiện nguồn; workspaceId sinh ngẫu nhiên ở mỗi lần gọi nên nếu transaction rollback sau
     * bước provision, lần retry tạo Organization khác và Organization cũ thành mồ côi vô hại trên
     * Keycloak. Graph quyền đi qua operation bất đồng bộ như mọi mutation authorization khác.
     */
    @Transactional
    public AuthorizationOperation createCenter(
            UUID initiatorUserId,
            String name,
            UUID ownerUserId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        idempotency.requireKey(idempotencyKey);
        lockOn("center-create:" + initiatorUserId + ":" + idempotencyKey);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        String trimmed = normalizeName(name);
        requireAccount(ownerUserId);

        UUID workspaceId = UUID.randomUUID();
        UUID centerId = UUID.randomUUID();
        coordinator.lockWorkspace(workspaceId);
        String organizationId = directory.ensureOrganization(workspaceId, trimmed);
        workspaces.save(
                new Workspace(workspaceId, Workspace.Kind.CENTER, null, centerId, "ACTIVE"));
        tenantProvisioner.provisionAfterCommit(workspaceId);
        centers.save(
                new Center(centerId, workspaceId, organizationId, trimmed, ownerUserId, "ACTIVE"));
        UUID membershipId = UUID.randomUUID();
        CenterMembership membership =
                new CenterMembership(
                        membershipId,
                        workspaceId,
                        ownerUserId,
                        CenterMembership.State.ACTIVE,
                        Instant.now());
        membership.getRoles().add(MembershipRole.Role.OWNER);
        memberships.save(membership);
        membershipIndex.sync(ownerUserId, workspaceId, CenterMembership.State.ACTIVE);
        memberships.flush();
        AuthorizationOperation operation =
                recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        audit.record(
                initiatorUserId,
                workspaceId,
                AuditEvent.Action.PRIVILEGE_CHANGE,
                AuditEvent.TargetType.MEMBERSHIP,
                membershipId,
                operation.getId(),
                AuditEvent.Outcome.PENDING);
        return operation;
    }

    /** Danh sách metadata dành cho operator; keyset theo (created_at, id). */
    public record CenterPage(List<CenterView> items, @Nullable String nextCursor) {
        public CenterPage {
            items = List.copyOf(items);
        }
    }

    @Transactional(readOnly = true)
    public CenterPage pageCenters(UUID actor, @Nullable String cursor, int requestedLimit) {
        Objects.requireNonNull(actor, "actor");
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        UUID anchorId = null;
        Instant createdAt = null;
        if (cursor != null) {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != CURSOR_PART_COUNT
                    || !actor.toString().equals(parts[0])
                    || !Integer.toString(limit).equals(parts[1])) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.INVALID_CURSOR,
                        "Cursor danh sách không hợp lệ.");
            }
            try {
                anchorId = UUID.fromString(parts[2]);
                if (!anchorId.toString().equals(parts[2])) {
                    throw new IllegalArgumentException("Non-canonical UUID");
                }
            } catch (IllegalArgumentException ex) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.INVALID_CURSOR,
                        "Cursor danh sách không hợp lệ.",
                        ex);
            }
            Optional<Center> anchor = centers.findById(anchorId);
            if (anchor.isEmpty()) {
                throw new HttpProblemException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.INVALID_CURSOR,
                        "Cursor danh sách không hợp lệ.");
            }
            createdAt = anchor.get().getCreatedAt();
        }
        List<Center> rows =
                anchorId == null
                        ? centers.findAllByOrderByCreatedAtAscIdAsc(PageRequest.of(0, limit + 1))
                        : centers.findAfter(
                                Objects.requireNonNull(createdAt, "createdAt"),
                                anchorId,
                                PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<CenterView> items =
                rows.stream()
                        .limit(limit)
                        .map(center ->
                                new CenterView(
                                        center.getId(),
                                        center.getWorkspaceId(),
                                        center.getName(),
                                        center.getOwnerUserId(),
                                        center.getState()))
                        .toList();
        return new CenterPage(
                items,
                hasMore && !items.isEmpty()
                        ? actor + "." + limit + "." + items.getLast().id()
                        : null);
    }

    /**
     * Thay toàn bộ tập role của một membership trong đúng workspace của nó. Bắt buộc If-Match khớp
     * row version hiện tại; replay cùng key được kiểm tra trước version để retry của mutation đã
     * commit trả lại biên nhận thay vì 409. Đổi role chạm vào chính row membership nên version
     * tăng, vì role nằm ở bảng riêng còn version mà client giữ là của membership.
     */
    @Transactional
    public AuthorizationOperation replaceRoles(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID membershipId,
            Set<MembershipRole.Role> desiredRoles,
            @Nullable String ifMatch,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(desiredRoles, "desiredRoles");
        idempotency.requireKey(idempotencyKey);
        coordinator.lockWorkspace(workspaceId);
        lockOn("center-membership:" + membershipId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        CenterMembership membership = loadMembershipForUpdate(membershipId, workspaceId);
        if (membership.getState() != CenterMembership.State.ACTIVE
                && membership.getState() != CenterMembership.State.PENDING) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.MEMBERSHIP_NOT_ACTIVE,
                    "Membership đã bị thu hồi, không đổi role được nữa.");
        }
        IdempotencyService.requireIfMatch(ifMatch, membership.getVersion());
        Set<MembershipRole.Role> current = Set.copyOf(membership.getRoles());
        boolean hasOwner = current.contains(MembershipRole.Role.OWNER);
        if (desiredRoles.contains(MembershipRole.Role.OWNER) && !hasOwner) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.OWNER_ROLE_NOT_ASSIGNABLE,
                    "Role OWNER chỉ đến từ lúc tạo center, không gán qua đổi role.");
        }
        if (hasOwner && !desiredRoles.contains(MembershipRole.Role.OWNER)) {
            throw lastOwner();
        }
        membership.replaceRoles(desiredRoles);
        // Role nằm ở bảng riêng nên phải chạm row membership thì version mới tăng: tăng tường
        // minh bằng cập nhật hàng loạt sau khi thay role đã flush.
        memberships.incrementVersionById(membershipId);
        memberships.flush();
        // Gỡ role tiền đề thì đóng nguồn giảng dạy phụ thuộc trong cùng transaction/revision:
        // gỡ TEACHER đóng phân công của người đó, gỡ STUDENT đóng ghi danh của người đó. Các
        // role còn lại của cùng người vẫn giữ nguồn độc lập của chúng.
        Set<MembershipRole.Role> removed = EnumSet.noneOf(MembershipRole.Role.class);
        removed.addAll(current);
        removed.removeAll(desiredRoles);
        if (removed.contains(MembershipRole.Role.TEACHER)) {
            teachingRevocations.revokeAssignmentsOfTeacher(workspaceId, membership.getUserId());
        }
        if (removed.contains(MembershipRole.Role.STUDENT)) {
            teachingRevocations.revokeEnrollmentsOfStudent(workspaceId, membership.getUserId());
        }
        // Cùng bất biến cho liên kết phụ huynh: gỡ PARENT đóng link đã duyệt của parent đó,
        // gỡ STUDENT đóng link đã duyệt của học sinh đó, trong đúng revision này.
        if (removed.contains(MembershipRole.Role.PARENT)) {
            guardianRevocations.revokeLinksOfParent(workspaceId, membership.getUserId());
        }
        if (removed.contains(MembershipRole.Role.STUDENT)) {
            guardianRevocations.revokeLinksOfStudent(workspaceId, membership.getUserId());
        }
        AuthorizationOperation operation =
                recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        audit.record(
                initiatorUserId,
                workspaceId,
                AuditEvent.Action.PRIVILEGE_CHANGE,
                AuditEvent.TargetType.MEMBERSHIP,
                membershipId,
                operation.getId(),
                AuditEvent.Outcome.PENDING);
        return operation;
    }

    /**
     * Thu hồi một membership: chuyển REVOKED và ghi revoked_at, giữ nguyên joined_at và toàn bộ
     * lịch sử. Thu hồi không đòi If-Match (theo mẫu thu hồi hiện có) và không bị gate bởi gói; chỉ
     * bị chặn khi membership mang role OWNER, vì gỡ owner là mất chủ sở hữu duy nhất của center.
     */
    @Transactional
    public AuthorizationOperation revokeMembership(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID membershipId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(membershipId, "membershipId");
        idempotency.requireKey(idempotencyKey);
        coordinator.lockWorkspace(workspaceId);
        lockOn("center-membership:" + membershipId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        CenterMembership membership = loadMembershipForUpdate(membershipId, workspaceId);
        if (membership.getState() == CenterMembership.State.REVOKED) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.MEMBERSHIP_ALREADY_REVOKED,
                    "Membership đã bị thu hồi trước đó.");
        }
        if (membership.getRoles().contains(MembershipRole.Role.OWNER)) {
            throw lastOwner();
        }
        membership.revoke(Instant.now());
        membershipIndex.sync(membership.getUserId(), workspaceId, CenterMembership.State.REVOKED);
        memberships.flush();
        // Mất membership là mất mọi tiền đề: đóng toàn bộ quan hệ giảng dạy của người này
        // trong center ở cùng transaction/revision, để phân công/ghi danh không sống lại ngầm
        // nếu người đó tham gia lại sau này.
        teachingRevocations.revokeAllRelationsOfMember(workspaceId, membership.getUserId());
        // Liên kết phụ huynh cũng là nguồn theo membership: đóng link đã duyệt ở cả hai phía.
        guardianRevocations.revokeAllLinksOfMember(workspaceId, membership.getUserId());
        AuthorizationOperation operation =
                recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        audit.record(
                initiatorUserId,
                workspaceId,
                AuditEvent.Action.PRIVILEGE_CHANGE,
                AuditEvent.TargetType.MEMBERSHIP,
                membershipId,
                operation.getId(),
                AuditEvent.Outcome.PENDING);
        return operation;
    }

    private CenterMembership loadMembershipForUpdate(UUID membershipId, UUID workspaceId) {
        Optional<CenterMembership> found = memberships.findForUpdateById(membershipId);
        // Sai workspace và không tồn tại trả cùng một 404: membership của center khác không
        // được lộ sự tồn tại qua đường quản trị của center này.
        if (found.isEmpty() || !workspaceId.equals(found.get().getWorkspaceId())) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.MEMBERSHIP_NOT_FOUND,
                    "Không tìm thấy membership trong trung tâm này.");
        }
        return found.get();
    }

    /**
     * Ghi operation cho một thay đổi trên workspace qua {@link SourceRevocationCoordinator}: toàn
     * bộ bundle desired của workspace tại dữ kiện nguồn hiện hành — bundle workspace, mọi bundle
     * giảng dạy và mọi bundle kết quả — trên WORKSPACE partition của chính workspace đó. Ghi kèm
     * bundle kết quả vì thay đổi membership ở đây có thể đổi nguồn quản trị (owner/manager) mà mọi
     * bundle giảng dạy đều tham chiếu, và phép đóng nguồn phụ thuộc của {@link
     * TeachingRevocationContributor} vừa đổi dữ kiện nguồn của các bundle đó trong cùng
     * transaction; membership/role cũng là tiền đề của nguồn học sinh và nguồn phụ huynh trong
     * bundle kết quả.
     */
    private AuthorizationOperation recordWorkspaceOperation(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        return revocations.recordMembershipRevocation(
                initiatorUserId, workspaceId, idempotencyKey, payloadHash);
    }

    private void requireAccount(UUID userId) {
        if (!accounts.existsById(userId)) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.ACCOUNT_NOT_FOUND,
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private static String normalizeName(@Nullable String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_CENTER_NAME,
                    "Tên trung tâm phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException lastOwner() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.CANNOT_REMOVE_LAST_OWNER,
                "Không được gỡ owner cuối cùng của trung tâm.");
    }

    /**
     * Tuần tự hoá trong phạm vi transaction hiện hành bằng advisory lock của PostgreSQL, cùng cách
     * ResourceMutationCoordinator khoá: lock tự nhả khi transaction kết thúc nên không cần đường mở
     * khoá riêng, và hai instance của ứng dụng vẫn tôn trọng nhau.
     */
    private void lockOn(String key) {
        AdvisoryLocks.lock(locks, "giaokhoa:center-service:", key);
    }
}
