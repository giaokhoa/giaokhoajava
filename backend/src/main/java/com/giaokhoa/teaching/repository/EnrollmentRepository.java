package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.Enrollment;
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

/**
 * Repository của aggregate ghi danh. Các phép nối với {@code ClassStudent} đều nằm trong cùng mặt
 * phẳng tenant; học sinh được phân giải tiếp ở tầng ứng dụng, không nối sang mặt phẳng hệ thống.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    /** Mệnh đề SET chung của mọi phép thu hồi hiện hành: đóng trạng thái, đóng dấu thời gian. */
    String REVOKE_SET_CLAUSE =
            "UPDATE Enrollment e SET e.state = 'REVOKED', e.revokedAt = CURRENT_TIMESTAMP,"
                    + " e.version = e.version + 1, e.updatedAt = CURRENT_TIMESTAMP";

    /** Mảnh WHERE lọc theo workspace và tập trạng thái hiện hành, dùng chung cho đọc và thu hồi. */
    String BY_WORKSPACE_AND_STATES = " WHERE e.workspaceId = :workspaceId AND e.state IN :states";

    /**
     * Mảnh điều kiện theo môn–khóa và tập trạng thái hiện hành, dùng chung cho các truy vấn trang.
     */
    String BY_COURSE_SUBJECT_AND_STATES =
            " AND e.courseSubjectId = :courseSubjectId AND e.state IN :states";

    /** Tên tham số môn–khóa dùng chung cho mọi @Param của repository này. */
    String COURSE_SUBJECT_ID = "courseSubjectId";

    Optional<Enrollment> findByWorkspaceIdAndClassStudentIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID classStudentId, UUID courseSubjectId, Collection<String> states);

    /** Bản khóa của một ghi danh cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Enrollment e WHERE e.workspaceId = :workspaceId AND e.id = :id")
    Optional<Enrollment> lockByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    /** Các ghi danh hiện hành của một môn–khóa kèm học sinh của dòng roster, thứ tự tạo ổn định. */
    List<EnrollmentListItem> findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Trang ghi danh đầu tiên theo keyset (created_at, id); người gọi lấy thừa một hàng. */
    @Query(
            "SELECT e FROM Enrollment e WHERE e.workspaceId = :workspaceId"
                    + BY_COURSE_SUBJECT_AND_STATES
                    + " ORDER BY e.createdAt ASC, e.id ASC")
    List<EnrollmentListItem> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states,
            Pageable pageable);

    /**
     * Trang ghi danh sau mốc keyset (created_at, id); mốc được tra riêng kể cả khi hàng đã thu hồi.
     */
    @Query(
            "SELECT e FROM Enrollment e WHERE e.workspaceId = :workspaceId"
                    + BY_COURSE_SUBJECT_AND_STATES
                    + " AND (e.createdAt > :anchorCreatedAt"
                    + " OR (e.createdAt = :anchorCreatedAt AND e.id > :anchorId))"
                    + " ORDER BY e.createdAt ASC, e.id ASC")
    List<EnrollmentListItem> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states,
            @Param("anchorCreatedAt") Instant anchorCreatedAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Mốc thời gian của hàng anchor của trang ghi danh; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT e.createdAt FROM Enrollment e WHERE e.workspaceId = :workspaceId"
                    + " AND e.courseSubjectId = :courseSubjectId AND e.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndCourseSubjectIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("id") UUID id);

    /** Các môn–khóa mà một dòng roster đang có ghi danh hiện hành. */
    @Query(
            "SELECT e.courseSubjectId FROM Enrollment e WHERE e.workspaceId = :workspaceId"
                    + " AND e.classStudentId = :classStudentId AND e.state IN :states")
    List<UUID> findCourseSubjectIdsByClassStudentId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classStudentId") UUID classStudentId,
            @Param("states") Collection<String> states);

    /** Các môn–khóa mà một học sinh đang có ghi danh hiện hành, qua các dòng roster của họ. */
    @Query(
            "SELECT e.courseSubjectId FROM Enrollment e JOIN ClassStudent cs"
                    + " ON cs.id = e.classStudentId WHERE e.workspaceId = :workspaceId"
                    + " AND cs.studentUserId = :studentUserId AND e.state IN :states")
    List<UUID> findCourseSubjectIdsByStudentUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            @Param("states") Collection<String> states);

    /** Mọi học sinh từng có ghi danh trong workspace, bất kể trạng thái (đồng bộ group). */
    @Query(
            "SELECT DISTINCT cs.studentUserId FROM Enrollment e JOIN ClassStudent cs"
                    + " ON cs.id = e.classStudentId WHERE e.workspaceId = :workspaceId")
    List<UUID> findDistinctStudentUserIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Học sinh đang ghi danh hiện hành vào một môn–khóa, theo định danh tài khoản. */
    @Query(
            "SELECT DISTINCT cs.studentUserId FROM Enrollment e JOIN ClassStudent cs"
                    + " ON cs.id = e.classStudentId WHERE e.workspaceId = :workspaceId"
                    + BY_COURSE_SUBJECT_AND_STATES
                    + " ORDER BY cs.studentUserId")
    List<UUID> findEnrolledStudentIds(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states);

    /** Các cặp (học sinh, môn–khóa) của ghi danh hiện hành, cho projector dựng group mong muốn. */
    @Query(
            "SELECT cs.studentUserId AS studentUserId, e.courseSubjectId AS courseSubjectId"
                    + " FROM Enrollment e JOIN ClassStudent cs ON cs.id = e.classStudentId"
                    + BY_WORKSPACE_AND_STATES)
    List<EnrolledStudentPair> findEnrolledPairsByWorkspaceIdAndStateIn(
            @Param("workspaceId") UUID workspaceId, @Param("states") Collection<String> states);

    /** Thu hồi mọi ghi danh hiện hành phụ thuộc một dòng roster, giữ nguyên hàng lịch sử. */
    @Modifying
    @Query(
            REVOKE_SET_CLAUSE
                    + " WHERE e.workspaceId = :workspaceId"
                    + " AND e.classStudentId = :classStudentId AND e.state IN :states")
    int revokeCurrentByClassStudentId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classStudentId") UUID classStudentId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi ghi danh hiện hành của một học sinh, qua các dòng roster của họ. */
    @Modifying
    @Query(
            REVOKE_SET_CLAUSE
                    + BY_WORKSPACE_AND_STATES
                    + " AND e.classStudentId IN (SELECT cs.id FROM ClassStudent cs"
                    + " WHERE cs.workspaceId = :workspaceId AND cs.studentUserId = :studentUserId)")
    int revokeCurrentByStudentUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi ghi danh hiện hành thuộc các môn–khóa của một lớp. */
    @Modifying
    @Query(
            REVOKE_SET_CLAUSE
                    + BY_WORKSPACE_AND_STATES
                    + " AND e.courseSubjectId IN (SELECT cs.id FROM CourseSubject cs"
                    + " WHERE cs.workspaceId = :workspaceId AND cs.classId = :classId)")
    int revokeCurrentByClassId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi ghi danh hiện hành thuộc các môn–khóa của một khóa. */
    @Modifying
    @Query(
            REVOKE_SET_CLAUSE
                    + BY_WORKSPACE_AND_STATES
                    + " AND e.courseSubjectId IN (SELECT cs.id FROM CourseSubject cs"
                    + " WHERE cs.workspaceId = :workspaceId AND cs.courseId = :courseId)")
    int revokeCurrentByCourseId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseId") UUID courseId,
            @Param("states") Collection<String> states);

    /** Thu hồi mọi ghi danh hiện hành của một môn–khóa. */
    @Modifying
    @Query(REVOKE_SET_CLAUSE + " WHERE e.workspaceId = :workspaceId" + BY_COURSE_SUBJECT_AND_STATES)
    int revokeCurrentByCourseSubjectId(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("states") Collection<String> states);
}
