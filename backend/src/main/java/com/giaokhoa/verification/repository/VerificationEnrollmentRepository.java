package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.Enrollment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc ghi danh trên entity của module teaching cho các luồng xác thực. */
public interface VerificationEnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    String JOIN_ROSTER = " where cs.id = e.classStudentId";
    String WORKSPACE_COURSE_SUBJECT =
            " and e.workspaceId = :workspaceId and e.courseSubjectId = :courseSubjectId";
    String COURSE_SUBJECT_ID = "courseSubjectId";

    /** Số ghi danh hiện hành của actor vào môn–khóa, nối danh sách lớp bằng giá trị id. */
    @Query(
            "select count(e) from Enrollment e, ClassStudent cs"
                    + JOIN_ROSTER
                    + WORKSPACE_COURSE_SUBJECT
                    + " and e.state in ('PENDING', 'ACTIVE')"
                    + " and cs.workspaceId = :workspaceId and cs.studentUserId = :studentUserId")
    long countCurrentEnrollment(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("studentUserId") UUID studentUserId);

    /** Định danh học sinh của các ghi danh hiện hành khớp môn–khóa và chính học sinh đó. */
    @Query(
            "select cs.studentUserId from Enrollment e, ClassStudent cs"
                    + JOIN_ROSTER
                    + WORKSPACE_COURSE_SUBJECT
                    + " and cs.studentUserId = :studentUserId"
                    + " and e.state in ('PENDING', 'ACTIVE')")
    List<UUID> findEnrolledStudentIds(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("studentUserId") UUID studentUserId);

    /** Lịch sử COMPLETED chỉ cấp published result của chính học sinh, không cấp quyền nộp. */
    @Query(
            "select cs.studentUserId from Enrollment e, ClassStudent cs"
                    + JOIN_ROSTER
                    + WORKSPACE_COURSE_SUBJECT
                    + " and cs.studentUserId = :studentUserId and e.state = 'COMPLETED'")
    List<UUID> findCompletedStudentIds(
            @Param("workspaceId") UUID workspaceId,
            @Param(COURSE_SUBJECT_ID) UUID courseSubjectId,
            @Param("studentUserId") UUID studentUserId);

    /** Định danh học sinh của mọi ghi danh hiện hành của môn–khóa. */
    @Query(
            "select distinct cs.studentUserId from Enrollment e, ClassStudent cs"
                    + JOIN_ROSTER
                    + WORKSPACE_COURSE_SUBJECT
                    + " and e.state in ('PENDING', 'ACTIVE')")
    List<UUID> findEnrolledStudentIdsByCourseSubject(
            @Param("workspaceId") UUID workspaceId, @Param(COURSE_SUBJECT_ID) UUID courseSubjectId);
}
