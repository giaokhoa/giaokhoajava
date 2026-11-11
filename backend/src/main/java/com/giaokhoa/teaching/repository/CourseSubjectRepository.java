package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.CourseSubject;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate môn–khóa. */
public interface CourseSubjectRepository extends JpaRepository<CourseSubject, UUID> {

    /** Các môn hiện hành của một khóa; thứ tự hiển thị theo mã môn được tầng ứng dụng chốt. */
    List<CourseSubject> findByWorkspaceIdAndCourseIdAndStateIn(
            UUID workspaceId, UUID courseId, Collection<String> states);

    /** Các môn hiện hành của một lô khóa trong cùng workspace, nạp một lần cho cả trang. */
    List<CourseSubject> findByWorkspaceIdAndCourseIdInAndStateIn(
            UUID workspaceId, Collection<UUID> courseIds, Collection<String> states);

    Optional<CourseSubject> findByWorkspaceIdAndCourseIdAndSubjectIdAndStateIn(
            UUID workspaceId, UUID courseId, UUID subjectId, Collection<String> states);

    /** Bản khóa của một môn–khóa cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT cs FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.courseId = :courseId AND cs.id = :id")
    Optional<CourseSubject> lockByWorkspaceIdAndCourseIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseId") UUID courseId,
            @Param("id") UUID id);

    /** Một môn–khóa đầy đủ phạm vi (lớp/khóa cha, trạng thái của chính dòng) theo id. */
    Optional<CourseSubject> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    boolean existsByWorkspaceIdAndId(UUID workspaceId, UUID id);

    @Query(
            "SELECT cs.classId FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.id = :id")
    Optional<UUID> findClassIdByWorkspaceIdAndId(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    /** Mọi môn–khóa thuộc một lớp trong workspace. */
    @Query(
            "SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.classId = :classId")
    List<UUID> findIdsByWorkspaceIdAndClassId(
            @Param("workspaceId") UUID workspaceId, @Param("classId") UUID classId);

    /** Mọi môn–khóa thuộc một khóa trong workspace. */
    @Query(
            "SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.courseId = :courseId")
    List<UUID> findIdsByWorkspaceIdAndCourseId(
            @Param("workspaceId") UUID workspaceId, @Param("courseId") UUID courseId);

    @Query("SELECT cs.id FROM CourseSubject cs WHERE cs.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
