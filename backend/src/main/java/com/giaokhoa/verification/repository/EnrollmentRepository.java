package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.Enrollment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc ghi danh qua entity chuyển tiếp; repository đầy đủ thuộc module teaching. */
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    /** Số ghi danh hiện hành của actor vào môn–khóa, nối danh sách lớp bằng giá trị id. */
    @Query(
            "select count(e) from VerificationEnrollment e, VerificationClassStudent cs"
                    + " where cs.id = e.classStudentId"
                    + " and e.workspaceId = :workspaceId and e.courseSubjectId = :courseSubjectId"
                    + " and e.state in ('PENDING', 'ACTIVE')"
                    + " and cs.workspaceId = :workspaceId and cs.studentUserId = :studentUserId")
    long countCurrentEnrollment(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseSubjectId") UUID courseSubjectId,
            @Param("studentUserId") UUID studentUserId);

    /** Định danh học sinh của các ghi danh hiện hành khớp môn–khóa và chính học sinh đó. */
    @Query(
            "select cs.studentUserId from VerificationEnrollment e, VerificationClassStudent cs"
                    + " where cs.id = e.classStudentId"
                    + " and e.workspaceId = :workspaceId and e.courseSubjectId = :courseSubjectId"
                    + " and cs.studentUserId = :studentUserId"
                    + " and e.state in ('PENDING', 'ACTIVE')")
    List<UUID> findEnrolledStudentIds(
            @Param("workspaceId") UUID workspaceId,
            @Param("courseSubjectId") UUID courseSubjectId,
            @Param("studentUserId") UUID studentUserId);
}
