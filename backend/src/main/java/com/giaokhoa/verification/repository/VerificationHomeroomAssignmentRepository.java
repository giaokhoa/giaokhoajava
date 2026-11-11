package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.HomeroomAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc phân công chủ nhiệm trên entity của module teaching cho các luồng xác thực. */
public interface VerificationHomeroomAssignmentRepository
        extends JpaRepository<HomeroomAssignment, UUID> {

    /**
     * Chủ nhiệm ACTIVE của lớp chứa môn–khóa, mỗi giáo viên một định danh: lớp của phân công lấy
     * qua môn–khóa của chính workspace như phép nối của projector giảng dạy.
     */
    @Query(
            "select distinct h.teacherUserId from HomeroomAssignment h, CourseSubject cs"
                    + " where cs.id = :courseSubjectId and cs.workspaceId = h.workspaceId"
                    + " and cs.classId = h.classId and h.workspaceId = :workspaceId"
                    + " and h.state = 'ACTIVE'")
    List<UUID> findTeacherUserIdsByCourseSubject(
            @Param("workspaceId") UUID workspaceId, @Param("courseSubjectId") UUID courseSubjectId);
}
