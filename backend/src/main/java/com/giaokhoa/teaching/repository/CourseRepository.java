package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.Course;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate khóa. */
public interface CourseRepository extends JpaRepository<Course, UUID> {

    Optional<Course> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** Bản khóa của findByWorkspaceIdAndId cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.workspaceId = :workspaceId AND c.id = :id")
    Optional<Course> lockByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    /** Các khóa hiện hành của một lớp, cũ nhất trước. */
    List<Course> findByWorkspaceIdAndClassIdAndStateOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID classId, String state);

    boolean existsByWorkspaceIdAndId(UUID workspaceId, UUID id);

    @Query("SELECT c.classId FROM Course c WHERE c.workspaceId = :workspaceId AND c.id = :id")
    Optional<UUID> findClassIdByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    /** Mọi khóa thuộc một lớp trong workspace, theo id. */
    @Query(
            "SELECT c.id FROM Course c WHERE c.workspaceId = :workspaceId"
                    + " AND c.classId = :classId ORDER BY c.id")
    List<UUID> findIdsByWorkspaceIdAndClassId(
            @Param("workspaceId") UUID workspaceId, @Param("classId") UUID classId);

    @Query("SELECT c.id FROM Course c WHERE c.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Mốc thời gian của hàng anchor của trang khóa; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT c.createdAt FROM Course c WHERE c.workspaceId = :workspaceId"
                    + " AND c.classId = :classId AND c.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndClassIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("id") UUID id);

    /** Trang khóa đầu tiên theo keyset (created_at, id); người gọi lấy thừa một hàng. */
    @Query(
            "SELECT c FROM Course c WHERE c.workspaceId = :workspaceId"
                    + " AND c.classId = :classId AND c.state = :state"
                    + " ORDER BY c.createdAt ASC, c.id ASC")
    List<CourseListItem> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("state") String state,
            Pageable pageable);

    /** Trang khóa sau mốc keyset (created_at, id); mốc được tra riêng kể cả khi hàng đã thu hồi. */
    @Query(
            "SELECT c FROM Course c WHERE c.workspaceId = :workspaceId"
                    + " AND c.classId = :classId AND c.state = :state"
                    + " AND (c.createdAt > :anchorCreatedAt"
                    + " OR (c.createdAt = :anchorCreatedAt AND c.id > :anchorId))"
                    + " ORDER BY c.createdAt ASC, c.id ASC")
    List<CourseListItem> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("state") String state,
            @Param("anchorCreatedAt") Instant anchorCreatedAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);
}
