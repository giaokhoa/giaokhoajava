package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.TeachingAssignment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc phân công giảng dạy trên entity của module teaching. */
public interface VerificationTeachingAssignmentRepository
        extends JpaRepository<TeachingAssignment, UUID> {

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);

    /** Giáo viên đang được phân công ACTIVE ở môn–khóa, mỗi giáo viên một định danh. */
    @Query(
            "select distinct t.teacherUserId from TeachingAssignment t"
                    + " where t.workspaceId = :workspaceId"
                    + " and t.courseSubjectId = :courseSubjectId and t.state = 'ACTIVE'")
    List<UUID> findTeacherUserIdsByCourseSubject(
            @Param("workspaceId") UUID workspaceId, @Param("courseSubjectId") UUID courseSubjectId);
}
