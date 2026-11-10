package com.giaokhoa.resources.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Các đọc tiền đề theo phạm vi mà nghiệp vụ resource cần từ dữ kiện KHÔNG thuộc module resources:
 * kỳ gói của workspace, membership ACTIVE của center và học sinh còn ghi danh của một môn–khóa. Đây
 * là điểm nối liên module duy nhất của đợt chuyển JPA của module này (cùng mẫu điểm nối của module
 * giảng dạy): các bảng {@code subscription_period}, {@code center_membership}, {@code
 * enrollment}/{@code class_student} chưa có entity trên base này — chúng thuộc sở hữu của
 * subscriptions, tenancy và teaching. Mọi dữ kiện thuộc resources và các entity đã có của module
 * khác đã chuyển hẳn sang các repository JPA trong package này. Khi các module sở hữu land
 * repository của chúng, thân các phương thức ở đây được thay bằng lời gọi repository tương ứng và
 * JdbcTemplate rời khỏi module; chữ ký và hành vi giữ nguyên.
 */
@Component
public class ResourceScopePreconditions {

    private final JdbcTemplate jdbc;

    public ResourceScopePreconditions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một kỳ gói của workspace, theo hình projector dùng để dựng gate hiệu lực theo kỳ. */
    public record SubscriptionPeriod(UUID id, Instant validFrom, Instant expiresAt) {}

    /** Các kỳ gói của workspace, sắp theo mốc bắt đầu rồi định danh như đường dựng bundle cũ. */
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

    /** Người dùng còn là thành viên ACTIVE của workspace trung tâm này không. */
    public boolean centerMembershipActive(UUID workspaceId, UUID userId) {
        return !jdbc.queryForList(
                        "SELECT id FROM center_membership WHERE workspace_id = ?"
                                + " AND user_id = ? AND state = 'ACTIVE'",
                        UUID.class,
                        workspaceId,
                        userId)
                .isEmpty();
    }

    /** Một học sinh còn ghi danh: định danh tài khoản và subject Keycloak của học sinh. */
    public record EnrolledStudent(UUID userId, String keycloakSub) {}

    /** Học sinh còn ghi danh (PENDING/ACTIVE) của một môn–khóa tại workspace cá nhân. */
    public List<EnrolledStudent> enrolledStudents(UUID workspaceId, UUID courseSubjectId) {
        return jdbc.query(
                "SELECT DISTINCT u.id AS user_id, u.keycloak_sub FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " JOIN user_account u ON u.id = cs.student_user_id"
                        + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                        + " AND e.state IN ('PENDING', 'ACTIVE')",
                (rs, _) ->
                        new EnrolledStudent(
                                rs.getObject("user_id", UUID.class), rs.getString("keycloak_sub")),
                workspaceId,
                courseSubjectId);
    }
}
