package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.SchoolClass;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate lớp; mọi truy vấn đều khóa theo workspace như tầng JDBC cũ. */
public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

    /** Mảnh SELECT lớp theo workspace và một trạng thái, dùng chung cho các truy vấn trang. */
    String BY_WORKSPACE_AND_STATE =
            "SELECT c FROM SchoolClass c WHERE c.workspaceId = :workspaceId AND c.state = :state";

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

    /**
     * Trang lớp đầu tiên theo keyset (created_at, id); người gọi lấy thừa một hàng để biết còn
     * trang sau.
     */
    @Query(BY_WORKSPACE_AND_STATE + " ORDER BY c.createdAt ASC, c.id ASC")
    List<ClassListItem> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param("state") String state,
            Pageable pageable);

    /** Trang lớp sau mốc keyset (created_at, id); mốc được tra riêng kể cả khi hàng đã thu hồi. */
    @Query(
            BY_WORKSPACE_AND_STATE
                    + " AND (c.createdAt > :anchorCreatedAt"
                    + " OR (c.createdAt = :anchorCreatedAt AND c.id > :anchorId))"
                    + " ORDER BY c.createdAt ASC, c.id ASC")
    List<ClassListItem> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("state") String state,
            @Param("anchorCreatedAt") Instant anchorCreatedAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);

    /** Như {@link #findFirstPage} nhưng chỉ trong tập lớp đã phân giải theo chủ nhiệm. */
    @Query(BY_WORKSPACE_AND_STATE + " AND c.id IN :ids ORDER BY c.createdAt ASC, c.id ASC")
    List<ClassListItem> findFirstPageByIds(
            @Param("workspaceId") UUID workspaceId,
            @Param("state") String state,
            @Param("ids") Collection<UUID> ids,
            Pageable pageable);

    /** Như {@link #findPageAfter} nhưng chỉ trong tập lớp đã phân giải theo chủ nhiệm. */
    @Query(
            BY_WORKSPACE_AND_STATE
                    + " AND c.id IN :ids"
                    + " AND (c.createdAt > :anchorCreatedAt"
                    + " OR (c.createdAt = :anchorCreatedAt AND c.id > :anchorId))"
                    + " ORDER BY c.createdAt ASC, c.id ASC")
    List<ClassListItem> findPageAfterByIds(
            @Param("workspaceId") UUID workspaceId,
            @Param("state") String state,
            @Param("ids") Collection<UUID> ids,
            @Param("anchorCreatedAt") Instant anchorCreatedAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);
}
