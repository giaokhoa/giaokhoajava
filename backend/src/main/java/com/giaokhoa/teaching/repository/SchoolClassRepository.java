package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.SchoolClass;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate lớp; mọi truy vấn đều khóa theo workspace như tầng JDBC cũ. */
public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

    Optional<SchoolClass> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** Bản khóa của findByWorkspaceIdAndId cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SchoolClass c WHERE c.workspaceId = :workspaceId AND c.id = :id")
    Optional<SchoolClass> lockByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    /** Các lớp hiện hành của workspace, cũ nhất trước; lớp đã thu hồi là lịch sử, không liệt kê. */
    List<SchoolClass> findByWorkspaceIdAndStateOrderByCreatedAtAscIdAsc(
            UUID workspaceId, String state);

    boolean existsByWorkspaceIdAndId(UUID workspaceId, UUID id);

    boolean existsByWorkspaceIdAndIdInAndState(
            UUID workspaceId, Collection<UUID> ids, String state);

    @Query("SELECT c.id FROM SchoolClass c WHERE c.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Mốc thời gian của hàng anchor của trang lớp; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT c.createdAt FROM SchoolClass c"
                    + " WHERE c.workspaceId = :workspaceId AND c.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    Window<ClassListItem> findByWorkspaceIdAndStateOrderByCreatedAtAscIdAsc(
            UUID workspaceId, String state, ScrollPosition position, Limit limit);

    Window<ClassListItem> findByWorkspaceIdAndStateAndIdInOrderByCreatedAtAscIdAsc(
            UUID workspaceId,
            String state,
            Collection<UUID> ids,
            ScrollPosition position,
            Limit limit);
}
