package com.giaokhoa.teaching.repository;

import com.giaokhoa.verification.model.SampleActivity;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Điểm đọc của module giảng dạy trên {@code sample_activity} thuộc module verification: guard lịch
 * sử chỉ cần biết một môn–khóa, khóa hoặc lớp đã phát sinh hoạt động được giao hay chưa. Bài nộp và
 * kết quả luôn treo dưới một hoạt động nên tồn tại hoạt động là gốc của mọi lịch sử học tập phát
 * sinh từ cấu trúc giảng dạy đó.
 */
public interface TeachingSampleActivityRepository extends Repository<SampleActivity, UUID> {

    boolean existsByWorkspaceIdAndCourseSubjectId(UUID workspaceId, UUID courseSubjectId);

    @Query(
            "select count(a) > 0 from SampleActivity a where a.workspaceId = :workspaceId"
                    + " and a.courseSubjectId in (select cs.id from CourseSubject cs"
                    + " where cs.workspaceId = :workspaceId and cs.courseId = :courseId)")
    boolean existsByWorkspaceIdAndCourseId(
            @Param("workspaceId") UUID workspaceId, @Param("courseId") UUID courseId);

    @Query(
            "select count(a) > 0 from SampleActivity a where a.workspaceId = :workspaceId"
                    + " and a.courseSubjectId in (select cs.id from CourseSubject cs"
                    + " where cs.workspaceId = :workspaceId and cs.classId = :classId)")
    boolean existsByWorkspaceIdAndClassId(
            @Param("workspaceId") UUID workspaceId, @Param("classId") UUID classId);
}
