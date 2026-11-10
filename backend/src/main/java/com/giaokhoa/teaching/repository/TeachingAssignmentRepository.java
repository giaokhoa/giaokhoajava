package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.TeachingAssignment;
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

/** Repository của aggregate phân công giảng dạy. */
public interface TeachingAssignmentRepository extends JpaRepository<TeachingAssignment, UUID> {

    Optional<TeachingAssignment> findByWorkspaceIdAndCourseSubjectIdAndTeacherUserIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, UUID teacherUserId, Collection<String> states);

    /** Bản khóa của một phân công cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT t FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.id = :id")
    Optional<TeachingAssignment> lockByWorkspaceIdAndCourseSubjectIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseSubjectId") UUID courseSubjectId,
            @Param("id") UUID id);

    /** Các phân công giảng dạy hiện hành của một môn–khóa, theo thứ tự tạo ổn định. */
    List<TeachingAssignment> findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    Window<TeachingAssignmentListItem>
            findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
                    UUID workspaceId,
                    UUID courseSubjectId,
                    Collection<String> states,
                    ScrollPosition position,
                    Limit limit);

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Mốc thời gian của hàng anchor của trang phân công; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT t.createdAt FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseSubjectId") UUID courseSubjectId,
            @Param("id") UUID id);

    /** Các môn–khóa mà một giáo viên đang có phân công giảng dạy hiện hành trong workspace. */
    List<UUID> findCourseSubjectIdByWorkspaceIdAndTeacherUserIdAndStateIn(
            UUID workspaceId, UUID teacherUserId, Collection<String> states);

    /** Mọi giáo viên từng có phân công giảng dạy trong workspace, bất kể trạng thái. */
    List<UUID> findTeacherUserIdByWorkspaceId(UUID workspaceId);

    /** Các cặp (giáo viên, môn–khóa) hiện hành, cho projector dựng group mong muốn. */
    @Query(
            "SELECT t.teacherUserId AS teacherUserId, t.courseSubjectId AS courseSubjectId"
                    + " FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.state IN :states")
    List<TeachingAssignmentPair> findPairsByWorkspaceIdAndStateIn(
            @Param("workspaceId") UUID workspaceId, @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành của một giáo viên trong workspace. */
    @Modifying
    @Query(
            "UPDATE TeachingAssignment t SET t.state = 'REVOKED',"
                    + " t.revokedAt = CURRENT_TIMESTAMP, t.version = t.version + 1,"
                    + " t.updatedAt = CURRENT_TIMESTAMP WHERE t.workspaceId = :workspaceId"
                    + " AND t.teacherUserId = :teacherUserId AND t.state IN :states")
    int revokeCurrentByTeacherUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("teacherUserId") UUID teacherUserId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành thuộc các môn–khóa của một lớp. */
    @Modifying
    @Query(
            "UPDATE TeachingAssignment t SET t.state = 'REVOKED',"
                    + " t.revokedAt = CURRENT_TIMESTAMP, t.version = t.version + 1,"
                    + " t.updatedAt = CURRENT_TIMESTAMP WHERE t.workspaceId = :workspaceId"
                    + " AND t.state IN :states AND t.courseSubjectId IN"
                    + " (SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.classId = :classId)")
    int revokeCurrentByClassId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành thuộc các môn–khóa của một khóa. */
    @Modifying
    @Query(
            "UPDATE TeachingAssignment t SET t.state = 'REVOKED',"
                    + " t.revokedAt = CURRENT_TIMESTAMP, t.version = t.version + 1,"
                    + " t.updatedAt = CURRENT_TIMESTAMP WHERE t.workspaceId = :workspaceId"
                    + " AND t.state IN :states AND t.courseSubjectId IN"
                    + " (SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.courseId = :courseId)")
    int revokeCurrentByCourseId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseId") UUID courseId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành của một môn–khóa. */
    @Modifying
    @Query(
            "UPDATE TeachingAssignment t SET t.state = 'REVOKED',"
                    + " t.revokedAt = CURRENT_TIMESTAMP, t.version = t.version + 1,"
                    + " t.updatedAt = CURRENT_TIMESTAMP WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.state IN :states")
    int revokeCurrentByCourseSubjectId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseSubjectId") UUID courseSubjectId,
            @Param("states") Collection<String> states);
}
