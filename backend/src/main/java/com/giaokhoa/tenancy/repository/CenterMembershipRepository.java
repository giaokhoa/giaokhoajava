package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CenterMembershipRepository extends JpaRepository<CenterMembership, UUID> {
    /**
     * Roles được nạp cùng membership: mọi điểm đọc qua truy vấn này (projector, kiểm tra scope) đều
     * dùng tới roles và nhiều điểm nằm ngoài transaction của repository, nên để LAZY sẽ lỗi khi
     * collection bị chạm sau khi session đã đóng.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<CenterMembership> findByWorkspaceIdAndUserIdAndState(
            UUID workspaceId, UUID userId, CenterMembership.State state);

    List<CenterMembership> findByWorkspaceIdAndUserIdInAndState(
            UUID workspaceId, Collection<UUID> userIds, CenterMembership.State state);

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

    /** Trang roster đầu tiên theo keyset (joined_at, id), chưa có mốc cursor. */
    @Query(
            "SELECT m FROM CenterMembership m"
                    + " WHERE m.workspaceId = :workspaceId AND m.state IN :states"
                    + " ORDER BY m.joinedAt ASC, m.id ASC")
    List<CenterMembership> findFirstPage(
            UUID workspaceId, Collection<CenterMembership.State> states, Pageable pageable);

    /**
     * Một trang roster theo keyset (joined_at, id) sau mốc cursor. Hàng đã thu hồi vẫn được tính ở
     * bước tra mốc nên bộ lọc trạng thái không áp ở truy vấn này. Trang đầu dùng {@link
     * #findFirstPage}: một tham số mốc null đứng một mình trong vế IS NULL không có ngữ cảnh kiểu
     * để PostgreSQL suy ra kiểu tham số.
     */
    @Query(
            "SELECT m FROM CenterMembership m"
                    + " WHERE m.workspaceId = :workspaceId AND m.state IN :states"
                    + " AND (m.joinedAt > :joinedAt"
                    + " OR (m.joinedAt = :joinedAt AND m.id > :cursorId))"
                    + " ORDER BY m.joinedAt ASC, m.id ASC")
    List<CenterMembership> findPageAfter(
            UUID workspaceId,
            Collection<CenterMembership.State> states,
            Instant joinedAt,
            UUID cursorId,
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
