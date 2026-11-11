package com.giaokhoa.teaching.repository;

import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.SubscriptionPolicy;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các đọc tiền đề theo phạm vi mà nghiệp vụ giảng dạy cần từ dữ kiện KHÔNG thuộc module giảng dạy:
 * thông tin workspace, tài khoản, membership của center kèm role, và kỳ gói. Đây là điểm nối liên
 * module duy nhất của module này: các bảng đó thuộc sở hữu của tenancy, identity và subscriptions
 * nên được đọc qua repository của module sở hữu; dữ kiện giảng dạy thuần nằm ở các repository JPA
 * trong {@code teaching.repository}. Chữ ký và hành vi (mã lỗi, thứ tự kiểm tra) giữ nguyên như
 * tầng JDBC cũ.
 */
@Component
@Transactional(readOnly = true)
public class TeachingScopePreconditions {
    private final WorkspaceRepository workspaces;
    private final CenterMembershipRepository memberships;
    private final UserAccountRepository accounts;
    private final SubscriptionPeriodRepository periods;
    private final SubscriptionPolicy subscriptionPolicy;

    public TeachingScopePreconditions(
            WorkspaceRepository workspaces,
            CenterMembershipRepository memberships,
            UserAccountRepository accounts,
            SubscriptionPeriodRepository periods,
            SubscriptionPolicy subscriptionPolicy) {
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.accounts = accounts;
        this.periods = periods;
        this.subscriptionPolicy = subscriptionPolicy;
    }

    /** Chiếu tối thiểu của một tài khoản: định danh và subject Keycloak của nó. */
    public record UserAccountRef(UUID id, String keycloakSub) {}

    /** Thông tin tối thiểu của workspace để service kiểm tra tiền điều kiện dữ kiện. */
    public record WorkspaceInfo(UUID id, String kind, String state, @Nullable UUID ownerUserId) {}

    /** Một kỳ gói đã xác nhận của workspace, theo hình projector dùng để dựng gate hiệu lực. */
    public record SubscriptionPeriod(UUID id, Instant validFrom, Instant expiresAt) {}

    public Optional<WorkspaceInfo> workspaceInfo(UUID workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace ->
                        new WorkspaceInfo(
                                workspace.getId(),
                                workspace.getKind().name(),
                                workspace.getState(),
                                workspace.getOwnerUserId()));
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
                workspaces
                        .findById(workspaceId)
                        .map(workspace -> workspace.getKind() == Workspace.Kind.PERSONAL)
                        .orElse(false);
        return personal
                ? subscriptionPolicy.current(workspaceId)
                : subscriptionPolicy.currentOrUnconfirmed(workspaceId);
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
        return accounts.existsById(userId);
    }

    /** Membership ACTIVE của center kèm đúng một role tiền đề — tiền đề của nguồn phân công. */
    public boolean hasActiveCenterMembershipWithRole(UUID workspaceId, UUID userId, String role) {
        MembershipRole.Role parsed = parseRole(role);
        if (parsed == null) {
            return false;
        }
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, userId, CenterMembership.State.ACTIVE)
                .map(membership -> membership.getRoles().contains(parsed))
                .orElse(false);
    }

    /**
     * Còn ít nhất một membership ACTIVE của workspace mang role này (nguồn quản trị của center).
     */
    public boolean existsActiveMemberWithRole(UUID workspaceId, String role) {
        MembershipRole.Role parsed = parseRole(role);
        if (parsed == null) {
            return false;
        }
        return !memberships
                .findIdsByWorkspaceIdAndStateAndRole(
                        workspaceId, CenterMembership.State.ACTIVE, parsed, PageRequest.of(0, 1))
                .isEmpty();
    }

    /** Mọi user đang là thành viên ACTIVE của workspace với role này. */
    public Set<UUID> activeMemberIdsWithRole(UUID workspaceId, String role) {
        MembershipRole.Role parsed = parseRole(role);
        if (parsed == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(
                memberships.findUserIdsByWorkspaceIdAndStateAndRole(
                        workspaceId, CenterMembership.State.ACTIVE, parsed));
    }

    /** Tên hiển thị của một lô tài khoản, phân giải một lần cho cả lô thay cho join cũ. */
    public Map<UUID, String> usernamesByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> usernames = new LinkedHashMap<>();
        for (UserAccount account : accounts.findByIdIn(userIds)) {
            usernames.put(account.getId(), account.getUsername());
        }
        return usernames;
    }

    /** Chiếu (id, keycloak_sub) của một lô tài khoản cho các đường đồng bộ group của projector. */
    public List<UserAccountRef> userAccountsByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return accounts.findByIdIn(userIds).stream()
                .map(account -> new UserAccountRef(account.getId(), account.getKeycloakSub()))
                .toList();
    }

    /** Chủ sở hữu của một PERSONAL workspace kèm subject Keycloak; rỗng nếu không phải personal. */
    public Optional<UserAccountRef> personalOwnerAccount(UUID workspaceId) {
        Optional<Workspace> workspace = workspaces.findById(workspaceId);
        if (workspace.isEmpty()
                || workspace.get().getKind() != Workspace.Kind.PERSONAL
                || workspace.get().getOwnerUserId() == null) {
            return Optional.empty();
        }
        return accounts.findById(workspace.get().getOwnerUserId())
                .map(account -> new UserAccountRef(account.getId(), account.getKeycloakSub()));
    }

    /** Mọi kỳ gói đã xác nhận của workspace, theo thứ tự hiệu lực — nguồn gate của projector. */
    public List<SubscriptionPeriod> subscriptionPeriods(UUID workspaceId) {
        return periods.findByWorkspaceIdOrderByValidFromAscIdAsc(workspaceId).stream()
                .map(period ->
                        new SubscriptionPeriod(
                                period.getId(), period.getValidFrom(), period.getExpiresAt()))
                .toList();
    }

    private static MembershipRole.@Nullable Role parseRole(String role) {
        try {
            return MembershipRole.Role.valueOf(role);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static HttpProblemException workspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy workspace giảng dạy hợp lệ.");
    }
}
