package com.giaokhoa.identity;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấp danh tính học sinh do center phát hành. Registrar chỉ ghi phía directory: account (origin
 * CENTER_ISSUED do server xác lập) kèm personal workspace và membership STUDENT trong center. Việc
 * tạo user trên Keycloak và bàn giao credential tạm thuộc task workflow khôi phục (caller tạo
 * Keycloak user trước rồi đưa Keycloak sub vào đây); registrar không tạo user Keycloak, không đặt
 * mật khẩu, không trả mật khẩu cho ai. Account đã có không bao giờ bị đổi origin hay ghi đè: mời
 * account có sẵn chỉ thêm membership, không reset danh tính. Rời center chỉ thu hồi membership,
 * không xóa account hay personal workspace — việc thu hồi thuộc luồng riêng, registrar không xóa
 * gì.
 */
@Service
public class IssuedStudentRegistrationService {
    private final JdbcTemplate jdbc;

    private final ResourceMutationCoordinator coordinator;

    public IssuedStudentRegistrationService(
            JdbcTemplate jdbc, ResourceMutationCoordinator coordinator) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
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
        List<Map<String, Object>> centers =
                jdbc.queryForList(
                        "SELECT kind, state FROM workspace WHERE id = ?", centerWorkspaceId);
        if (centers.isEmpty()
                || !"CENTER".equals(centers.getFirst().get("kind"))
                || !"ACTIVE".equals(centers.getFirst().get("state"))) {
            // Không phân biệt thiếu/không phải center/không ACTIVE: không lộ sự tồn tại.
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.WORKSPACE_NOT_FOUND,
                    "Không tìm thấy trung tâm hợp lệ.");
        }
        List<String> operatorRoles =
                jdbc.queryForList(
                        "SELECT r.role FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                + " AND m.state = 'ACTIVE' AND r.role IN ('OWNER', 'MANAGER')",
                        String.class,
                        centerWorkspaceId,
                        operatorUserId);
        if (operatorRoles.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.OPERATOR_NOT_AUTHORIZED,
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được cấp danh tính học sinh.");
        }

        UUID userId;
        UUID personalWorkspaceId;
        boolean accountCreated;
        List<Map<String, Object>> accounts =
                jdbc.queryForList(
                        "SELECT id, username FROM user_account WHERE keycloak_sub = ?",
                        keycloakSub);
        if (!accounts.isEmpty()) {
            Map<String, Object> account = accounts.getFirst();
            if (!username.equals(account.get("username"))) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.USERNAME_MISMATCH,
                        "Tên đăng nhập không khớp danh tính đã có.");
            }
            userId = (UUID) Objects.requireNonNull(account.get("id"), "id");
            personalWorkspaceId = personalWorkspaceOf(userId);
            accountCreated = false;
        } else {
            List<String> taken =
                    jdbc.queryForList(
                            "SELECT keycloak_sub FROM user_account WHERE username = ?",
                            String.class,
                            username);
            if (!taken.isEmpty()) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.USERNAME_TAKEN,
                        "Tên đăng nhập đã thuộc về một danh tính khác.");
            }
            userId = UUID.randomUUID();
            personalWorkspaceId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO user_account"
                            + " (id, keycloak_sub, username, email, email_verified, origin,"
                            + " identity_state) VALUES (?, ?, ?, NULL, FALSE, 'CENTER_ISSUED',"
                            + " 'ACTIVE')",
                    userId,
                    keycloakSub,
                    username);
            jdbc.update(
                    "INSERT INTO workspace (id, kind, owner_user_id, state)"
                            + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                    personalWorkspaceId,
                    userId);
            accountCreated = true;
        }

        UUID membershipId;
        boolean membershipCreated;
        List<UUID> memberships =
                jdbc.queryForList(
                        "SELECT id FROM center_membership WHERE workspace_id = ? AND user_id = ?"
                                + " AND state IN ('PENDING', 'ACTIVE')",
                        UUID.class,
                        centerWorkspaceId,
                        userId);
        if (!memberships.isEmpty()) {
            membershipId = memberships.getFirst();
            membershipCreated = false;
            // Cấp trực tiếp kích hoạt luôn membership đã mời: chỉ đổi state, giữ nguyên
            // joined_at vì trigger guard_directory_history cấm sửa lịch sử tham gia.
            // Điều kiện state trong câu lệnh khiến lượt cập nhật vô hại với membership ACTIVE.
            jdbc.update(
                    "UPDATE center_membership SET state = 'ACTIVE' WHERE id = ?"
                            + " AND state = 'PENDING'",
                    membershipId);
        } else {
            membershipId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO center_membership"
                            + " (id, workspace_id, user_id, state, joined_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now())",
                    membershipId,
                    centerWorkspaceId,
                    userId);
            membershipCreated = true;
        }
        jdbc.update(
                "INSERT INTO membership_role (membership_id, role) VALUES (?, 'STUDENT')"
                        + " ON CONFLICT (membership_id, role) DO NOTHING",
                membershipId);
        return new IssuedStudent(
                userId, personalWorkspaceId, membershipId, accountCreated, membershipCreated);
    }

    private UUID personalWorkspaceOf(UUID userId) {
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        userId);
        if (found.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.IDENTITY_UNAVAILABLE,
                    "Không xác minh được danh tính hiện hành, thử lại sau.");
        }
        return found.getFirst();
    }
}
