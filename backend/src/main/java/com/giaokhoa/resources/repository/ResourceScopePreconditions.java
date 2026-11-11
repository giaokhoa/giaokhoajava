package com.giaokhoa.resources.repository;

import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Các đọc tiền đề theo phạm vi mà nghiệp vụ resource cần từ dữ kiện KHÔNG thuộc module resources:
 * kỳ gói của workspace, membership ACTIVE của center và học sinh còn ghi danh của một môn–khóa. Đây
 * là điểm nối liên module duy nhất của module này (cùng mẫu điểm nối của module giảng dạy): các
 * bảng đó thuộc sở hữu của subscriptions, tenancy và teaching nên được đọc qua repository của
 * module sở hữu. Chữ ký và hành vi giữ nguyên như tầng JDBC cũ.
 */
@Component
public class ResourceScopePreconditions {

    private final SubscriptionPeriodRepository periods;
    private final CenterMembershipRepository memberships;
    private final EnrollmentRepository enrollments;
    private final UserAccountRepository accounts;

    public ResourceScopePreconditions(
            SubscriptionPeriodRepository periods,
            CenterMembershipRepository memberships,
            EnrollmentRepository enrollments,
            UserAccountRepository accounts) {
        this.periods = periods;
        this.memberships = memberships;
        this.enrollments = enrollments;
        this.accounts = accounts;
    }

    /** Một kỳ gói của workspace, theo hình projector dùng để dựng gate hiệu lực theo kỳ. */
    public record SubscriptionPeriod(UUID id, Instant validFrom, Instant expiresAt) {}

    /** Các kỳ gói của workspace, sắp theo mốc bắt đầu rồi định danh như đường dựng bundle cũ. */
    public List<SubscriptionPeriod> subscriptionPeriods(UUID workspaceId) {
        return periods.findByWorkspaceIdOrderByValidFromAscIdAsc(workspaceId).stream()
                .map(period ->
                        new SubscriptionPeriod(
                                period.getId(), period.getValidFrom(), period.getExpiresAt()))
                .toList();
    }

    /** Người dùng còn là thành viên ACTIVE của workspace trung tâm này không. */
    public boolean centerMembershipActive(UUID workspaceId, UUID userId) {
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, userId, CenterMembership.State.ACTIVE)
                .isPresent();
    }

    /**
     * Các user trong danh sách còn là thành viên ACTIVE của workspace trung tâm này, tra một lô.
     */
    public Set<UUID> centerMembershipActiveUserIds(UUID workspaceId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return memberships
                .findByWorkspaceIdAndUserIdInAndState(
                        workspaceId, userIds, CenterMembership.State.ACTIVE)
                .stream()
                .map(CenterMembership::getUserId)
                .collect(Collectors.toSet());
    }

    /** Một học sinh còn ghi danh: định danh tài khoản và subject Keycloak của học sinh. */
    public record EnrolledStudent(UUID userId, String keycloakSub) {}

    /** Học sinh còn ghi danh (PENDING/ACTIVE) của một môn–khóa tại workspace cá nhân. */
    public List<EnrolledStudent> enrolledStudents(UUID workspaceId, UUID courseSubjectId) {
        List<UUID> studentIds =
                enrollments.findEnrolledStudentIds(
                        workspaceId, courseSubjectId, List.of("PENDING", "ACTIVE"));
        if (studentIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> subjects = new LinkedHashMap<>();
        accounts.findByIdIn(studentIds)
                .forEach(account -> subjects.put(account.getId(), account.getKeycloakSub()));
        return studentIds.stream()
                .filter(subjects::containsKey)
                .map(id -> new EnrolledStudent(id, Objects.requireNonNull(subjects.get(id))))
                .toList();
    }
}
