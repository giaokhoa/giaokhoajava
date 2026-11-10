package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CenterMembershipRepository extends JpaRepository<CenterMembership, UUID> {
    Optional<CenterMembership> findByWorkspaceIdAndUserIdAndState(
            UUID workspaceId, UUID userId, CenterMembership.State state);

    Optional<CenterMembership> findByWorkspaceIdAndUserIdAndStateIn(
            UUID workspaceId, UUID userId, Collection<CenterMembership.State> states);

    List<CenterMembership> findByUserIdAndState(UUID userId, CenterMembership.State state);

    List<CenterMembership> findByUserIdAndStateAndWorkspaceIdIn(
            UUID userId, CenterMembership.State state, Collection<UUID> workspaceIds);

    boolean existsByWorkspaceIdAndState(UUID workspaceId, CenterMembership.State state);

    boolean existsByWorkspaceIdAndUserIdAndStateIn(
            UUID workspaceId, UUID userId, Collection<CenterMembership.State> states);

    /** Mọi user từng có membership ở workspace, phục vụ đồng bộ realm group của projector. */
    @Query("SELECT DISTINCT m.userId FROM CenterMembership m WHERE m.workspaceId = :workspaceId")
    List<UUID> findDistinctUserIdsByWorkspaceId(UUID workspaceId);

    /** Có ít nhất một membership ACTIVE mang role này trong workspace hay không. */
    @Query(
            "SELECT m.id FROM CenterMembership m JOIN m.roles r"
                    + " WHERE m.workspaceId = :workspaceId AND m.state = :state AND r = :role")
    List<UUID> findIdsByWorkspaceIdAndStateAndRole(
            UUID workspaceId,
            CenterMembership.State state,
            MembershipRole.Role role,
            Pageable pageable);

    /** Mọi user có membership ở trạng thái này mang role này trong workspace. */
    @Query(
            "SELECT DISTINCT m.userId FROM CenterMembership m JOIN m.roles r"
                    + " WHERE m.workspaceId = :workspaceId AND m.state = :state AND r = :role")
    List<UUID> findUserIdsByWorkspaceIdAndStateAndRole(
            UUID workspaceId, CenterMembership.State state, MembershipRole.Role role);

    /**
     * Một trang roster theo keyset (joined_at, id); mốc null là trang đầu. Hàng đã thu hồi vẫn được
     * tính ở bước tra mốc nên bộ lọc trạng thái không áp ở truy vấn này.
     */
    @Query(
            "SELECT m FROM CenterMembership m"
                    + " WHERE m.workspaceId = :workspaceId AND m.state IN :states"
                    + " AND (:joinedAt IS NULL OR m.joinedAt > :joinedAt"
                    + " OR (m.joinedAt = :joinedAt AND m.id > :cursorId))"
                    + " ORDER BY m.joinedAt ASC, m.id ASC")
    List<CenterMembership> findPageAfter(
            UUID workspaceId,
            Collection<CenterMembership.State> states,
            @Nullable Instant joinedAt,
            @Nullable UUID cursorId,
            Pageable pageable);

    /**
     * Đọc khóa ghi cho các đường đổi role/thu hồi: version được kiểm If-Match trên hàng đã khóa.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM CenterMembership m WHERE m.id = :id")
    Optional<CenterMembership> findForUpdateById(UUID id);

    /**
     * Tăng version của membership mà không đổi dữ kiện nào khác: đổi role chỉ chạm bảng role nên
     * phải tăng tường minh, vì version client giữ là của membership. Trả số hàng đã tăng.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE CenterMembership m SET m.version = m.version + 1,"
                    + " m.updatedAt = CURRENT_TIMESTAMP WHERE m.id = :id")
    int incrementVersionById(UUID id);

    /**
     * Kích hoạt membership PENDING mà không tăng version và không đổi updated_at, giữ đúng ngữ
     * nghĩa của đường cấp trực tiếp. Trả số hàng đã kích hoạt.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE CenterMembership m SET m.state = :active"
                    + " WHERE m.id = :id AND m.state = :pending")
    int activateIfPending(UUID id, CenterMembership.State active, CenterMembership.State pending);
}
