package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.TeachingAssignment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate phân công giảng dạy. */
public interface TeachingAssignmentRepository extends JpaRepository<TeachingAssignment, UUID> {

    /** Tên tham số môn–khóa dùng chung cho mọi @Param của repository này. */
    String COURSE_SUBJECT_ID = "courseSubjectId";

    /** Mệnh đề SET chung của mọi phép thu hồi hiện hành: đóng trạng thái, đóng dấu thời gian. */
    String REVOKE_SET_CLAUSE =
            "UPDATE TeachingAssignment t SET t.state = 'REVOKED',"
                    + " t.revokedAt = CURRENT_TIMESTAMP, t.version = t.version + 1,"
                    + " t.updatedAt = CURRENT_TIMESTAMP WHERE t.workspaceId = :workspaceId";

    Optional<TeachingAssignment> findByWorkspaceIdAndCourseSubjectIdAndTeacherUserIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, UUID teacherUserId, Collection<String> states);

    /** Bản khóa của một phân công cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT t FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.id = :id")
    Optional<TeachingAssignment> lockByWorkspaceIdAndCourseSubjectIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("id") UUID id);

    /** Các phân công giảng dạy hiện hành của một môn–khóa, theo thứ tự tạo ổn định. */
    List<TeachingAssignment> findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Trang phân công giảng dạy đầu tiên theo keyset (created_at, id); lấy thừa một hàng. */
    @Query(
            "SELECT t FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.state IN :states"
                    + " ORDER BY t.createdAt ASC, t.id ASC")
    List<TeachingAssignmentListItem> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states,
            Pageable pageable);

    /**
     * Trang phân công giảng dạy sau mốc keyset (created_at, id); mốc tra riêng kể cả khi đã thu
     * hồi.
     */
    @Query(
            "SELECT t FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.state IN :states"
                    + " AND (t.createdAt > :anchorCreatedAt"
                    + " OR (t.createdAt = :anchorCreatedAt AND t.id > :anchorId))"
                    + " ORDER BY t.createdAt ASC, t.id ASC")
    List<TeachingAssignmentListItem> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states,
            @Param("anchorCreatedAt") Instant anchorCreatedAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Mốc thời gian của hàng anchor của trang phân công; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT t.createdAt FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.courseSubjectId = :courseSubjectId AND t.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("id") UUID id);

    /** Các môn–khóa mà một giáo viên đang có phân công giảng dạy hiện hành trong workspace. */
    @Query(
            "SELECT t.courseSubjectId FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.teacherUserId = :teacherUserId AND t.state IN :states")
    List<UUID> findCourseSubjectIdByWorkspaceIdAndTeacherUserIdAndStateIn(
            @Param("workspaceId") UUID workspaceId,
            @Param("teacherUserId") UUID teacherUserId,
            @Param("states") Collection<String> states);

    /** Mọi giáo viên từng có phân công giảng dạy trong workspace, bất kể trạng thái. */
    @Query(
            "SELECT t.teacherUserId FROM TeachingAssignment t"
                    + " WHERE t.workspaceId = :workspaceId")
    List<UUID> findTeacherUserIdByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Các cặp (giáo viên, môn–khóa) hiện hành, cho projector dựng group mong muốn. */
    @Query(
            "SELECT t.teacherUserId AS teacherUserId, t.courseSubjectId AS courseSubjectId"
                    + " FROM TeachingAssignment t WHERE t.workspaceId = :workspaceId"
                    + " AND t.state IN :states")
    List<TeachingAssignmentPair> findPairsByWorkspaceIdAndStateIn(
            @Param("workspaceId") UUID workspaceId, @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành của một giáo viên trong workspace. */
    @Modifying
    @Query(REVOKE_SET_CLAUSE + " AND t.teacherUserId = :teacherUserId AND t.state IN :states")
    int revokeCurrentByTeacherUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("teacherUserId") UUID teacherUserId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành thuộc các môn–khóa của một lớp. */
    @Modifying
    @Query(
            REVOKE_SET_CLAUSE
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
            REVOKE_SET_CLAUSE
                    + " AND t.state IN :states AND t.courseSubjectId IN"
                    + " (SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.courseId = :courseId)")
    int revokeCurrentByCourseId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseId") UUID courseId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi phân công giảng dạy hiện hành của một môn–khóa. */
    @Modifying
    @Query(REVOKE_SET_CLAUSE + " AND t.courseSubjectId = :courseSubjectId AND t.state IN :states")
    int revokeCurrentByCourseSubjectId(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states);
}
