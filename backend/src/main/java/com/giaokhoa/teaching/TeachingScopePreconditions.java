package com.giaokhoa.teaching;

import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction.WorkspaceInfo;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Các đọc tiền đề theo phạm vi mà nghiệp vụ giảng dạy cần từ dữ kiện KHÔNG thuộc module giảng dạy:
 * thông tin workspace, tài khoản, membership của center kèm role, và kỳ gói. Đây là điểm nối liên
 * module duy nhất của đợt chuyển JPA của module này: các bảng đó thuộc sở hữu của tenancy, identity
 * và subscriptions — dữ kiện giảng dạy thuần đã chuyển hẳn sang các repository JPA trong {@code
 * teaching.repository}. Khi các module sở hữu cung cấp repository của chúng, thân các phương thức ở
 * đây được thay bằng lời gọi repository tương ứng và JdbcTemplate rời khỏi module; chữ ký và hành
 * vi (mã lỗi, thứ tự kiểm tra) giữ nguyên như tầng JDBC cũ.
 */
@Component
public class TeachingScopePreconditions {
    private final JdbcTemplate jdbc;

    public TeachingScopePreconditions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chiếu tối thiểu của một tài khoản: định danh và subject Keycloak của nó. */
    public record UserAccountRef(UUID id, String keycloakSub) {}

    /** Một kỳ gói đã xác nhận của workspace, theo hình projector dùng để dựng gate hiệu lực. */
    public record SubscriptionPeriod(UUID id, Instant validFrom, Instant expiresAt) {}

    public Optional<WorkspaceInfo> workspaceInfo(UUID workspaceId) {
        return jdbc
                .query(
                        "SELECT id, kind, state, owner_user_id FROM workspace WHERE id = ?",
                        (rs, _) ->
                                new WorkspaceInfo(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("kind"),
                                        rs.getString("state"),
                                        rs.getObject("owner_user_id", UUID.class)),
                        workspaceId)
                .stream()
                .findFirst();
    }

    /** Workspace giảng dạy hợp lệ: đang ACTIVE và là center hoặc personal. */
    public WorkspaceInfo requireTeachingWorkspace(UUID workspaceId) {
        WorkspaceInfo workspace = workspaceInfo(workspaceId).orElseThrow(() -> workspaceNotFound());
        if (!"ACTIVE".equals(workspace.state())
                || (!"CENTER".equals(workspace.kind()) && !"PERSONAL".equals(workspace.kind()))) {
            throw workspaceNotFound();
        }
        return workspace;
    }

    public boolean subscriptionCurrent(UUID workspaceId) {
        // Personal lấy gói làm entitlement bắt buộc: phải có kỳ đang hiệu lực mới mở rộng
        // được. Center giữ quy tắc của SubscriptionPolicy: chưa từng xác nhận gói thì chưa
        // bị khóa, đã có gói thì phải còn hiệu lực.
        boolean personal =
                !jdbc.queryForList(
                                "SELECT id FROM workspace WHERE id = ? AND kind = 'PERSONAL'",
                                UUID.class,
                                workspaceId)
                        .isEmpty();
        return personal
                ? SubscriptionPolicy.current(jdbc, workspaceId)
                : SubscriptionPolicy.currentOrUnconfirmed(jdbc, workspaceId);
    }

    /** Cổng gói dùng chung của các service giảng dạy: kỳ gói phải còn hiệu lực hiện hành. */
    public void requireSubscriptionCurrent(UUID workspaceId) {
        if (!subscriptionCurrent(workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
    }

    public boolean accountExists(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM user_account WHERE id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    /** Membership ACTIVE của center kèm đúng một role tiền đề — tiền đề của nguồn phân công. */
    public boolean hasActiveCenterMembershipWithRole(UUID workspaceId, UUID userId, String role) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                + " AND m.state = 'ACTIVE' AND r.role = ?",
                        Integer.class,
                        workspaceId,
                        userId,
                        role);
        return count != null && count > 0;
    }

    /**
     * Còn ít nhất một membership ACTIVE của workspace mang role này (nguồn quản trị của center).
     */
    public boolean existsActiveMemberWithRole(UUID workspaceId, String role) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.state = 'ACTIVE'"
                                + " AND r.role = ?)",
                        Boolean.class,
                        workspaceId,
                        role));
    }

    /** Mọi user đang là thành viên ACTIVE của workspace với role này. */
    public Set<UUID> activeMemberIdsWithRole(UUID workspaceId, String role) {
        return new LinkedHashSet<>(
                jdbc.queryForList(
                        "SELECT DISTINCT m.user_id FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.state = 'ACTIVE'"
                                + " AND r.role = ?",
                        UUID.class,
                        workspaceId,
                        role));
    }

    /** Tên hiển thị của một lô tài khoản, phân giải một lần cho cả lô thay cho join cũ. */
    public Map<UUID, String> usernamesByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> usernames = new LinkedHashMap<>();
        new NamedParameterJdbcTemplate(jdbc)
                .query(
                        "SELECT id, username FROM user_account WHERE id IN (:ids)",
                        new MapSqlParameterSource().addValue("ids", userIds),
                        (rs, _) ->
                                Map.entry(rs.getObject("id", UUID.class), rs.getString("username")))
                .forEach(entry -> usernames.put(entry.getKey(), entry.getValue()));
        return usernames;
    }

    /** Chiếu (id, keycloak_sub) của một lô tài khoản cho các đường đồng bộ group của projector. */
    public List<UserAccountRef> userAccountsByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return new NamedParameterJdbcTemplate(jdbc)
                .query(
                        "SELECT id, keycloak_sub FROM user_account WHERE id IN (:ids)",
                        new MapSqlParameterSource().addValue("ids", userIds),
                        (rs, _) ->
                                new UserAccountRef(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("keycloak_sub")));
    }

    /** Chủ sở hữu của một PERSONAL workspace kèm subject Keycloak; rỗng nếu không phải personal. */
    public Optional<UserAccountRef> personalOwnerAccount(UUID workspaceId) {
        return jdbc
                .query(
                        "SELECT u.id, u.keycloak_sub FROM workspace w"
                                + " JOIN user_account u ON u.id = w.owner_user_id"
                                + " WHERE w.id = ? AND w.kind = 'PERSONAL'",
                        (rs, _) ->
                                new UserAccountRef(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("keycloak_sub")),
                        workspaceId)
                .stream()
                .findFirst();
    }

    /** Mọi kỳ gói đã xác nhận của workspace, theo thứ tự hiệu lực — nguồn gate của projector. */
    public List<SubscriptionPeriod> subscriptionPeriods(UUID workspaceId) {
        return jdbc.query(
                "SELECT id, valid_from, expires_at FROM subscription_period"
                        + " WHERE workspace_id = ? ORDER BY valid_from, id",
                (rs, _) ->
                        new SubscriptionPeriod(
                                rs.getObject("id", UUID.class),
                                Objects.requireNonNull(
                                                rs.getObject("valid_from", OffsetDateTime.class))
                                        .toInstant(),
                                Objects.requireNonNull(
                                                rs.getObject("expires_at", OffsetDateTime.class))
                                        .toInstant()),
                workspaceId);
    }

    private static HttpProblemException workspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy workspace giảng dạy hợp lệ.");
    }
}
