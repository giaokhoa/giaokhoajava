package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.CourseSubject;
import com.giaokhoa.verification.dto.CourseSubjectStateView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc môn–khóa trên entity của module teaching cho các luồng xác thực. */
public interface CourseSubjectRepository extends JpaRepository<CourseSubject, UUID> {

    /** Trạng thái của môn–khóa cùng lớp/khóa chứa nó, nối bằng giá trị id trong cùng mặt phẳng. */
    @Query(
            "select new com.giaokhoa.verification.dto.CourseSubjectStateView("
                    + "cs.subjectId, cs.state, sc.state, co.state)"
                    + " from CourseSubject cs, SchoolClass sc,"
                    + " Course co"
                    + " where sc.id = cs.classId and sc.workspaceId = cs.workspaceId"
                    + " and co.id = cs.courseId and co.workspaceId = cs.workspaceId"
                    + " and cs.workspaceId = :workspaceId and cs.id = :courseSubjectId")
    Optional<CourseSubjectStateView> findStateView(
            @Param("workspaceId") UUID workspaceId, @Param("courseSubjectId") UUID courseSubjectId);
}
