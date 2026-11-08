package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingRevocationContributor;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
 */
@Service
public class CenterService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuthorizationOperationService operations;
    private final CenterPolicyProjector projector;
    private final KeycloakProvisioningClient directory;
    private final TeachingRevocationContributor teachingRevocations;
    private final TeachingPolicyProjector teachingProjector;

    public CenterService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuthorizationOperationService operations,
            CenterPolicyProjector projector,
            KeycloakProvisioningClient directory,
            TeachingRevocationContributor teachingRevocations,
            TeachingPolicyProjector teachingProjector) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.operations = operations;
        this.projector = projector;
        this.directory = directory;
        this.teachingRevocations = teachingRevocations;
        this.teachingProjector = teachingProjector;
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
        String organizationId = directory.ensureOrganization(workspaceId, trimmed);
        jdbc.update(
                "INSERT INTO workspace (id, kind, owner_user_id, center_id, state)"
                        + " VALUES (?, 'CENTER', NULL, ?, 'ACTIVE')",
                workspaceId,
                centerId);
        jdbc.update(
                "INSERT INTO center (id, workspace_id, keycloak_organization_id, name,"
                        + " owner_user_id, state) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                centerId,
                workspaceId,
                organizationId,
                trimmed,
                ownerUserId);
        UUID membershipId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO center_membership (id, workspace_id, user_id, state, joined_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', now())",
                membershipId,
                workspaceId,
                ownerUserId);
        jdbc.update(
                "INSERT INTO membership_role (membership_id, role) VALUES (?, 'OWNER')",
                membershipId);
        return recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
    }

    /** Mọi center đã tạo, cũ nhất trước; chỉ metadata, không kèm dữ liệu học thuật. */
    @Transactional(readOnly = true)
    public List<CenterView> listCenters() {
        return jdbc.query(
                "SELECT id, workspace_id, name, owner_user_id, state FROM center"
                        + " ORDER BY created_at ASC, id ASC",
                (rs, _) ->
                        new CenterView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("workspace_id", UUID.class),
                                rs.getString("name"),
                                rs.getObject("owner_user_id", UUID.class),
                                rs.getString("state")));
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
        lockOn("center-membership:" + membershipId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        MembershipRow row = loadMembershipForUpdate(membershipId, workspaceId);
        if (!"ACTIVE".equals(row.state()) && !"PENDING".equals(row.state())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "MEMBERSHIP_NOT_ACTIVE",
                    "Membership đã bị thu hồi, không đổi role được nữa.");
        }
        idempotency.requireIfMatch(ifMatch, row.version());
        Set<MembershipRole.Role> current = rolesOf(membershipId);
        boolean hasOwner = current.contains(MembershipRole.Role.OWNER);
        if (desiredRoles.contains(MembershipRole.Role.OWNER) && !hasOwner) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "OWNER_ROLE_NOT_ASSIGNABLE",
                    "Role OWNER chỉ đến từ lúc tạo center, không gán qua đổi role.");
        }
        if (hasOwner && !desiredRoles.contains(MembershipRole.Role.OWNER)) {
            throw lastOwner();
        }
        jdbc.update("DELETE FROM membership_role WHERE membership_id = ?", membershipId);
        for (MembershipRole.Role role : desiredRoles) {
            jdbc.update(
                    "INSERT INTO membership_role (membership_id, role) VALUES (?, ?)",
                    membershipId,
                    role.name());
        }
        jdbc.update(
                "UPDATE center_membership SET version = version + 1, updated_at = now()"
                        + " WHERE id = ?",
                membershipId);
        // Gỡ role tiền đề thì đóng nguồn giảng dạy phụ thuộc trong cùng transaction/revision:
        // gỡ TEACHER đóng phân công của người đó, gỡ STUDENT đóng ghi danh của người đó. Các
        // role còn lại của cùng người giữ nguồn độc lập của chúng.
        Set<MembershipRole.Role> removed = EnumSet.noneOf(MembershipRole.Role.class);
        removed.addAll(current);
        removed.removeAll(desiredRoles);
        if (removed.contains(MembershipRole.Role.TEACHER)) {
            teachingRevocations.revokeAssignmentsOfTeacher(workspaceId, row.userId());
        }
        if (removed.contains(MembershipRole.Role.STUDENT)) {
            teachingRevocations.revokeEnrollmentsOfStudent(workspaceId, row.userId());
        }
        return recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
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
        lockOn("center-membership:" + membershipId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        MembershipRow row = loadMembershipForUpdate(membershipId, workspaceId);
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "MEMBERSHIP_ALREADY_REVOKED",
                    "Membership đã bị thu hồi trước đó.");
        }
        if (rolesOf(membershipId).contains(MembershipRole.Role.OWNER)) {
            throw lastOwner();
        }
        jdbc.update(
                "UPDATE center_membership SET state = 'REVOKED', revoked_at = now(),"
                        + " version = version + 1, updated_at = now() WHERE id = ?",
                membershipId);
        // Mất membership là mất mọi tiền đề: đóng toàn bộ quan hệ giảng dạy của người này
        // trong center ở cùng transaction/revision, để phân công/ghi danh không sống lại ngầm
        // nếu người đó tham gia lại sau này.
        teachingRevocations.revokeAllRelationsOfMember(workspaceId, row.userId());
        return recordWorkspaceOperation(initiatorUserId, workspaceId, idempotencyKey, payloadHash);
    }

    private record MembershipRow(UUID workspaceId, UUID userId, String state, long version) {}

    private MembershipRow loadMembershipForUpdate(UUID membershipId, UUID workspaceId) {
        List<MembershipRow> rows =
                jdbc.query(
                        "SELECT workspace_id, user_id, state, version FROM center_membership"
                                + " WHERE id = ? FOR UPDATE",
                        (rs, _) ->
                                new MembershipRow(
                                        rs.getObject("workspace_id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("state"),
                                        rs.getLong("version")),
                        membershipId);
        // Sai workspace và không tồn tại trả cùng một 404: membership của center khác không
        // được lộ sự tồn tại qua đường quản trị của center này.
        if (rows.isEmpty() || !workspaceId.equals(rows.getFirst().workspaceId())) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "MEMBERSHIP_NOT_FOUND",
                    "Không tìm thấy membership trong trung tâm này.");
        }
        return rows.getFirst();
    }

    private Set<MembershipRole.Role> rolesOf(UUID membershipId) {
        return Set.copyOf(
                jdbc
                        .queryForList(
                                "SELECT role FROM membership_role WHERE membership_id = ?",
                                String.class,
                                membershipId)
                        .stream()
                        .map(MembershipRole.Role::valueOf)
                        .toList());
    }

    /**
     * Ghi operation cho một thay đổi trên workspace: toàn bộ bundle desired của projector tại dữ
     * kiện nguồn hiện hành, trên WORKSPACE partition của chính workspace đó. Gắn kèm bundle của mọi
     * target giảng dạy của workspace, vì thay đổi membership ở đây có thể đổi nguồn quản trị
     * (owner/manager) mà mọi bundle giảng dạy đều tham chiếu, và phép đóng nguồn phụ thuộc của
     * {@link TeachingRevocationContributor} vừa đổi dữ kiện nguồn của các bundle đó trong cùng
     * transaction.
     */
    private AuthorizationOperation recordWorkspaceOperation(
            UUID initiatorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace authorization partition is missing: " + workspaceId);
        }
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        projections.add(
                new AuthorizationOperationService.DesiredProjection(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                        projector.bundleDescriptors(workspaceId),
                        List.of()));
        projections.addAll(teachingProjector.allBundleProjections(workspaceId));
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partitions.getFirst(), List.copyOf(projections))));
    }

    private void requireAccount(UUID userId) {
        List<UUID> accounts =
                jdbc.queryForList("SELECT id FROM user_account WHERE id = ?", UUID.class, userId);
        if (accounts.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private static String normalizeName(@Nullable String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CENTER_NAME",
                    "Tên trung tâm phải có từ 1 đến 255 ký tự.");
        }
        return name.trim();
    }

    private static HttpProblemException lastOwner() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "CANNOT_REMOVE_LAST_OWNER",
                "Không được gỡ owner cuối cùng của trung tâm.");
    }

    /**
     * Tuần tự hoá trong phạm vi transaction hiện hành bằng advisory lock của PostgreSQL, cùng cách
     * ResourceMutationCoordinator khoá: lock tự nhả khi transaction kết thúc nên không cần đường mở
     * khoá riêng, và hai instance của ứng dụng vẫn tôn trọng nhau.
     */
    private void lockOn(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", _ -> {}, lockId(key));
    }

    private static long lockId(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("giaokhoa:center-service:" + key)
                                            .getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
