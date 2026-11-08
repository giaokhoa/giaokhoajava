package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.InitialCredentialHandoff;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * định cho phép ở endpoint vẫn do Keycloak PDP xét qua scope student.issue — gói hết hạn làm PDP từ
 * chối qua time policy của bundle, service không tự tính lại hiệu lực gói. Operation ghi cuối luồng
 * qua {@link MembershipPolicyProjector} mới là mốc hoàn tất bất đồng bộ: membership chỉ được coi là
 * đã đồng bộ khi operation SUCCEEDED.
 */
@Service
public class MembershipService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final InitialCredentialHandoff handoff;
    private final MembershipPolicyProjector projector;
    private final ResourceMutationCoordinator coordinator;

    public MembershipService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            InitialCredentialHandoff handoff,
            MembershipPolicyProjector projector,
            ResourceMutationCoordinator coordinator) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.handoff = handoff;
        this.projector = projector;
        this.coordinator = coordinator;
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
        return new StudentIssueResult(operation, issued);
    }

    /**
     * Mọi membership hiện hành (PENDING và ACTIVE) của một center, theo thứ tự tham gia ổn định,
     * kèm role và row version hiện tại để client gửi If-Match khi đổi role. Membership đã thu hồi
     * là lịch sử, không nằm trong danh sách hiện hành.
     */
    @Transactional(readOnly = true)
    public List<MembershipView> listMemberships(UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        List<MembershipRow> rows =
                jdbc.query(
                        "SELECT m.id, m.user_id, u.username, m.state, m.version, r.role"
                                + " FROM center_membership m"
                                + " JOIN user_account u ON u.id = m.user_id"
                                + " LEFT JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.state IN ('PENDING', 'ACTIVE')"
                                + " ORDER BY m.joined_at ASC, m.id ASC, r.role ASC",
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
                        workspaceId);
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
        return List.copyOf(views);
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
