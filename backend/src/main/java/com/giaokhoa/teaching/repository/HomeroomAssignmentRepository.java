package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.HomeroomAssignment;
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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate phân công chủ nhiệm. */
public interface HomeroomAssignmentRepository extends JpaRepository<HomeroomAssignment, UUID> {

    Optional<HomeroomAssignment> findByWorkspaceIdAndClassIdAndTeacherUserIdAndStateIn(
            UUID workspaceId, UUID classId, UUID teacherUserId, Collection<String> states);

    /** Bản khóa của một phân công chủ nhiệm cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT h FROM HomeroomAssignment h WHERE h.workspaceId = :workspaceId"
                    + " AND h.classId = :classId AND h.id = :id")
    Optional<HomeroomAssignment> lockByWorkspaceIdAndClassIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("id") UUID id);

    /** Các phân công chủ nhiệm hiện hành của một lớp, theo thứ tự tạo ổn định. */
    List<HomeroomAssignment> findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID classId, Collection<String> states);

    Window<HomeroomAssignmentListItem>
            findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
                    UUID workspaceId,
                    UUID classId,
                    Collection<String> states,
                    ScrollPosition position,
                    Limit limit);

    boolean existsByWorkspaceIdAndClassIdAndStateIn(
            UUID workspaceId, UUID classId, Collection<String> states);

    /** Mốc thời gian của hàng anchor của trang chủ nhiệm; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT h.createdAt FROM HomeroomAssignment h WHERE h.workspaceId = :workspaceId"
                    + " AND h.classId = :classId AND h.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndClassIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("id") UUID id);

    /** Các lớp mà một giáo viên đang có phân công chủ nhiệm hiện hành trong workspace. */
    @Query(
            "SELECT h.classId FROM HomeroomAssignment h WHERE h.workspaceId = :workspaceId"
                    + " AND h.teacherUserId = :teacherUserId AND h.state IN :states")
    List<UUID> findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
            @Param("workspaceId") UUID workspaceId,
            @Param("teacherUserId") UUID teacherUserId,
            @Param("states") Collection<String> states);

    /** Mọi giáo viên từng có phân công chủ nhiệm trong workspace, bất kể trạng thái. */
    @Query(
            "SELECT h.teacherUserId FROM HomeroomAssignment h"
                    + " WHERE h.workspaceId = :workspaceId")
    List<UUID> findTeacherUserIdByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Các cặp (giáo viên, lớp) hiện hành, cho projector dựng group mong muốn. */
    @Query(
            "SELECT h.teacherUserId AS teacherUserId, h.classId AS classId"
                    + " FROM HomeroomAssignment h WHERE h.workspaceId = :workspaceId"
                    + " AND h.state IN :states")
    List<HomeroomAssignmentPair> findPairsByWorkspaceIdAndStateIn(
            @Param("workspaceId") UUID workspaceId, @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công chủ nhiệm hiện hành của một giáo viên trong workspace. */
    @Modifying
    @Query(
            "UPDATE HomeroomAssignment h SET h.state = 'REVOKED',"
                    + " h.revokedAt = CURRENT_TIMESTAMP, h.version = h.version + 1,"
                    + " h.updatedAt = CURRENT_TIMESTAMP WHERE h.workspaceId = :workspaceId"
                    + " AND h.teacherUserId = :teacherUserId AND h.state IN :states")
    int revokeCurrentByTeacherUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("teacherUserId") UUID teacherUserId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công chủ nhiệm hiện hành của một lớp. */
    @Modifying
    @Query(
            "UPDATE HomeroomAssignment h SET h.state = 'REVOKED',"
                    + " h.revokedAt = CURRENT_TIMESTAMP, h.version = h.version + 1,"
                    + " h.updatedAt = CURRENT_TIMESTAMP WHERE h.workspaceId = :workspaceId"
                    + " AND h.classId = :classId AND h.state IN :states")
    int revokeCurrentByClassId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("states") Collection<String> states);
}
