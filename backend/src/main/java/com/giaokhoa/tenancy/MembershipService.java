package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.InitialCredentialHandoff;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Nghiệp vụ membership của center ở tầng service: cấp học sinh mới và đọc danh sách thành viên. Đổi
 * role và thu hồi membership nằm ở CenterService (cùng ghi bundle của workspace), còn service này
 * sở hữu đường cấp học sinh vì nó là workflow qua Keycloak chứ không chỉ là thay đổi dữ kiện nguồn
 * trong app-db.
 *
 * <p>Cấp học sinh là CREATE mới qua {@link InitialCredentialHandoff}: tạo user Keycloak kèm
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
 * contracts/authorization.md, controller quyết bằng dữ kiện SQL — {@link #requireAdmin} ép
 * OWNER/MANAGER ACTIVE và {@link #requireSubscriptionCurrent} ép gói còn hiệu lực bằng chính sách
 * gói trên dữ kiện kỳ gói — còn pipeline profile của executor chỉ đồng bộ eligibility hiện hành và
 * giữ ACCOUNT fence trước khi route chạy. Operation ghi cuối luồng qua {@link
 * MembershipPolicyProjector} mới là mốc hoàn tất bất đồng bộ: membership chỉ được coi là đã đồng bộ
 * khi operation SUCCEEDED.
 */
@Service
public class MembershipService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final InitialCredentialHandoff handoff;
    private final MembershipPolicyProjector projector;
    private final ResourceMutationCoordinator coordinator;
    private final AuditRecorder audit;

    public MembershipService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            InitialCredentialHandoff handoff,
            MembershipPolicyProjector projector,
            ResourceMutationCoordinator coordinator,
            AuditRecorder audit) {
        this.jdbc = jdbc;
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
            AuthorizationOperation operation, InitialCredentialHandoff.@Nullable Handoff handoff) {}

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
        InitialCredentialHandoff.Handoff issued =
                handoff.issueNewStudent(initiatorUserId, workspaceId, username, displayName, email);
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
        OffsetDateTime joinedAt = null;
        if (cursor != null) {
            try {
                cursorId = UUID.fromString(cursor);
                if (!cursorId.toString().equals(cursor)) {
                    return new MembershipPage(List.of(), null);
                }
            } catch (IllegalArgumentException ex) {
                return new MembershipPage(List.of(), null);
            }
            List<OffsetDateTime> anchors =
                    jdbc.queryForList(
                            "SELECT joined_at FROM center_membership WHERE workspace_id = ? AND id = ?",
                            OffsetDateTime.class,
                            workspaceId,
                            cursorId);
            if (anchors.isEmpty()) {
                return new MembershipPage(List.of(), null);
            }
            joinedAt = anchors.getFirst();
        }
        String sql =
                "WITH page AS (SELECT id, user_id, state, version, joined_at"
                        + " FROM center_membership WHERE workspace_id = ?"
                        + " AND state IN ('PENDING', 'ACTIVE')"
                        + (cursorId == null ? "" : " AND (joined_at, id) > (?, ?)")
                        + " ORDER BY joined_at, id LIMIT ?)"
                        + " SELECT m.id, m.user_id, u.username, m.state, m.version, r.role"
                        + " FROM page m JOIN user_account u ON u.id = m.user_id"
                        + " LEFT JOIN membership_role r ON r.membership_id = m.id"
                        + " ORDER BY m.joined_at, m.id, r.role";
        Object[] params =
                cursorId == null
                        ? new Object[] {workspaceId, pageSize + 1}
                        : new Object[] {workspaceId, joinedAt, cursorId, pageSize + 1};
        List<MembershipRow> rows =
                jdbc.query(
                        sql,
                        (rs, _) ->
                                new MembershipRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        rs.getString("state"),
                                        rs.getLong("version"),
                                        rs.getString("role") == null
                                                ? null
                                                : MembershipRole.Role.valueOf(
                                                        rs.getString("role"))),
                        params);
        Map<UUID, MembershipView> byId = new LinkedHashMap<>();
        Map<UUID, List<MembershipRole.Role>> rolesById = new LinkedHashMap<>();
        for (MembershipRow row : rows) {
            byId.computeIfAbsent(
                    row.id(),
                    _ ->
                            new MembershipView(
                                    row.id(),
                                    row.userId(),
                                    row.username(),
                                    List.of(),
                                    row.state(),
                                    row.version()));
            if (row.role() != null) {
                rolesById.computeIfAbsent(row.id(), _ -> new ArrayList<>()).add(row.role());
            }
        }
        List<MembershipView> views = new ArrayList<>();
        byId.forEach((membershipId, view) ->
                views.add(
                        new MembershipView(
                                view.id(),
                                view.userId(),
                                view.username(),
                                List.copyOf(rolesById.getOrDefault(membershipId, List.of())),
                                view.state(),
                                view.version())));
        boolean hasMore = views.size() > pageSize;
        List<MembershipView> items =
                List.copyOf(views.subList(0, Math.min(pageSize, views.size())));
        return new MembershipPage(
                items, hasMore && !items.isEmpty() ? items.getLast().id().toString() : null);
    }

    /** Quyền nguồn của actor trong center; dữ kiện được đọc dưới lock/fence của route. */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> activeRoles(UUID actorUserId, UUID workspaceId) {
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

    public Set<MembershipRole.Role> requireAdmin(UUID actorUserId, UUID workspaceId) {
        Set<MembershipRole.Role> roles = activeRoles(actorUserId, workspaceId);
        if (!roles.contains(MembershipRole.Role.OWNER)
                && !roles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Không có quyền quản trị thành viên của trung tâm này.");
        }
        return roles;
    }

    public void requireSubscriptionCurrent(UUID workspaceId) {
        if (!SubscriptionPolicy.currentOrUnconfirmed(jdbc, workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    /** Chỉ đọc role của membership được sửa, không tải toàn bộ thành viên của center. */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> currentRoles(UUID workspaceId, UUID membershipId) {
        return Set.copyOf(
                jdbc
                        .queryForList(
                                "SELECT r.role FROM center_membership m"
                                        + " JOIN membership_role r ON r.membership_id = m.id"
                                        + " WHERE m.workspace_id = ? AND m.id = ?"
                                        + " AND m.state IN ('PENDING', 'ACTIVE') ORDER BY r.role",
                                String.class,
                                workspaceId,
                                membershipId)
                        .stream()
                        .map(MembershipRole.Role::valueOf)
                        .toList());
    }

    private record MembershipRow(
            UUID id,
            UUID userId,
            String username,
            String state,
            long version,
            MembershipRole.@Nullable Role role) {}

    private void requireCenterOperator(UUID initiatorUserId, UUID workspaceId) {
        List<Map<String, Object>> centers =
                jdbc.queryForList("SELECT kind, state FROM workspace WHERE id = ?", workspaceId);
        if (centers.isEmpty()
                || !"CENTER".equals(centers.getFirst().get("kind"))
                || !"ACTIVE".equals(centers.getFirst().get("state"))) {
            // Không phân biệt thiếu/không phải center/không ACTIVE: không lộ sự tồn tại.
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND",
                    "Không tìm thấy trung tâm hợp lệ.");
        }
        List<String> operatorRoles =
                jdbc.queryForList(
                        "SELECT r.role FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                + " AND m.state = 'ACTIVE' AND r.role IN ('OWNER', 'MANAGER')",
                        String.class,
                        workspaceId,
                        initiatorUserId);
        if (operatorRoles.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "OPERATOR_NOT_AUTHORIZED",
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được cấp danh tính học sinh.");
        }
    }
}
