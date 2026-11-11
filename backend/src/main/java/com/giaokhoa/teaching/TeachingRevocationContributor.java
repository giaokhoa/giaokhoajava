package com.giaokhoa.teaching;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.teaching.repository.ClassStudentRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Đóng các nguồn giảng dạy phụ thuộc khi nguồn tiền đề của chúng bị gỡ, trong cùng transaction (và
 * do đó cùng revision của operation) với thay đổi đã gỡ tiền đề đó. Đây là phía dữ kiện nguồn của
 * bất biến hợp đồng "gỡ role/class_student thì đóng dependent assignment/enrollment trong cùng
 * revision, không tái sinh ngầm": hàng phụ thuộc chuyển hẳn sang REVOKED kèm revoked_at, giữ nguyên
 * lịch sử. Gán lại sau đó là hàng mới theo ngữ nghĩa hợp tập của các service, không bao giờ phục
 * hồi hàng đã đóng ở đây.
 *
 * <p>Các điểm gỡ tiền đề gọi contributor này:
 *
 * <ul>
 *   <li>Gỡ role TEACHER của một membership (đổi role hoặc thu hồi membership): đóng phân công giảng
 *       dạy và phân công chủ nhiệm của người đó trong center.
 *   <li>Gỡ role STUDENT: đóng ghi danh của người đó trong center. Dòng roster giữ nguyên vì roster
 *       là quan hệ tổ chức lớp (chỉ đòi membership ACTIVE), không phải nguồn theo role.
 *   <li>Thu hồi membership: đóng toàn bộ quan hệ giảng dạy của người đó trong center — roster, ghi
 *       danh, phân công giảng dạy, phân công chủ nhiệm — vì mọi tiền đề membership đều mất.
 *   <li>Thu hồi lớp/khóa/môn–khóa: đóng ghi danh và phân công phụ thuộc của đúng phạm vi đó. Riêng
 *       lớp còn đóng phân công chủ nhiệm của lớp.
 * </ul>
 *
 * <p>Mỗi phép đóng trả lại các target mà bundle của chúng vừa đổi vì phép đóng, để lời gọi nào có
 * ghi operation (đổi role, thu hồi membership) ghi lại đúng các bundle đó trong cùng operation.
 * Đường thu hồi lớp/khóa/môn–khóa ở tầng service chưa ghi operation nên bỏ qua giá trị trả về này:
 * route của chúng ghi bundle khi được cài. Thu hồi một dòng roster đóng ghi danh phụ thuộc của
 * chính dòng đó đã nằm ở {@code ClassService.revokeStudent} qua repository, theo hợp đồng của route
 * roster.revoke, nên không lặp lại ở đây.
 *
 * <p>Các bảng giảng dạy nằm trong schema tenant của workspace, mà một số lời gọi (đổi role, thu hồi
 * membership ở tầng tenancy) không tự đặt context: contributor tự đặt context của đúng workspace
 * qua {@link WorkspaceTransaction} ở đầu mỗi phép đóng, trước truy vấn đầu tiên. Contributor chỉ
 * cập nhật dữ kiện nguồn; việc đối chiếu group/policy ở Keycloak thuộc {@link
 * TeachingPolicyProjector} khi operation của thay đổi tiền đề được reconcile, và các role/nguồn độc
 * lập còn lại của cùng người không bị đụng tới vì mọi phép đóng đều khóa đúng phạm vi tiền đề đã
 * gỡ.
 */
@Component
public class TeachingRevocationContributor {
    private static final List<String> CURRENT_STATES = List.of("PENDING", "ACTIVE");

    private final ClassStudentRepository classStudents;
    private final CourseSubjectRepository courseSubjects;
    private final EnrollmentRepository enrollments;
    private final TeachingAssignmentRepository teachingAssignments;
    private final HomeroomAssignmentRepository homeroomAssignments;
    private final WorkspaceTransaction workspaceTransaction;

    public TeachingRevocationContributor(
            ClassStudentRepository classStudents,
            CourseSubjectRepository courseSubjects,
            EnrollmentRepository enrollments,
            TeachingAssignmentRepository teachingAssignments,
            HomeroomAssignmentRepository homeroomAssignments,
            WorkspaceTransaction workspaceTransaction) {
        this.classStudents = classStudents;
        this.courseSubjects = courseSubjects;
        this.enrollments = enrollments;
        this.teachingAssignments = teachingAssignments;
        this.homeroomAssignments = homeroomAssignments;
        this.workspaceTransaction = workspaceTransaction;
    }

    /** Gỡ role TEACHER: đóng phân công giảng dạy và chủ nhiệm của người này trong center. */
    public List<String> revokeAssignmentsOfTeacher(UUID workspaceId, UUID teacherUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(teacherUserId, "teacherUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<String> targets = new ArrayList<>();
        homeroomAssignments
                .findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                        workspaceId, teacherUserId, CURRENT_STATES)
                .forEach(classId ->
                        targets.add(NativePolicyTemplates.classKey(workspaceId, classId)));
        teachingAssignments
                .findCourseSubjectIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                        workspaceId, teacherUserId, CURRENT_STATES)
                .forEach(courseSubjectId ->
                        targets.add(
                                NativePolicyTemplates.courseSubjectKey(
                                        workspaceId, courseSubjectId)));
        teachingAssignments.revokeCurrentByTeacherUserId(
                workspaceId, teacherUserId, CURRENT_STATES);
        homeroomAssignments.revokeCurrentByTeacherUserId(
                workspaceId, teacherUserId, CURRENT_STATES);
        return List.copyOf(targets);
    }

    /** Gỡ role STUDENT: đóng ghi danh của người này trong center, giữ nguyên dòng roster. */
    public List<String> revokeEnrollmentsOfStudent(UUID workspaceId, UUID studentUserId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(studentUserId, "studentUserId");
        workspaceTransaction.applyContext(workspaceId);
        List<String> targets = new ArrayList<>();
        enrollments
                .findCourseSubjectIdsByStudentUserId(workspaceId, studentUserId, CURRENT_STATES)
                .forEach(courseSubjectId ->
                        targets.add(
                                NativePolicyTemplates.courseSubjectKey(
                                        workspaceId, courseSubjectId)));
        enrollments.revokeCurrentByStudentUserId(workspaceId, studentUserId, CURRENT_STATES);
        return List.copyOf(targets);
    }

    /** Thu hồi membership: đóng mọi quan hệ giảng dạy của người này trong center. */
    public List<String> revokeAllRelationsOfMember(UUID workspaceId, UUID userId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        List<String> targets = new ArrayList<>(revokeEnrollmentsOfStudent(workspaceId, userId));
        targets.addAll(revokeAssignmentsOfTeacher(workspaceId, userId));
        classStudents.revokeCurrentByWorkspaceIdAndStudentUserId(
                workspaceId, userId, CURRENT_STATES);
        return List.copyOf(targets);
    }

    /** Thu hồi lớp: đóng ghi danh, phân công giảng dạy và chủ nhiệm phụ thuộc lớp đó. */
    public List<String> revokeDependentsOfClass(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        List<String> targets = new ArrayList<>();
        targets.add(NativePolicyTemplates.classKey(workspaceId, classId));
        courseSubjects
                .findIdsByWorkspaceIdAndClassId(workspaceId, classId)
                .forEach(courseSubjectId ->
                        targets.add(
                                NativePolicyTemplates.courseSubjectKey(
                                        workspaceId, courseSubjectId)));
        enrollments.revokeCurrentByClassId(workspaceId, classId, CURRENT_STATES);
        teachingAssignments.revokeCurrentByClassId(workspaceId, classId, CURRENT_STATES);
        homeroomAssignments.revokeCurrentByClassId(workspaceId, classId, CURRENT_STATES);
        return List.copyOf(targets);
    }

    /** Thu hồi khóa: đóng ghi danh và phân công giảng dạy phụ thuộc các môn–khóa của khóa đó. */
    public List<String> revokeDependentsOfCourse(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        workspaceTransaction.applyContext(workspaceId);
        List<String> targets = new ArrayList<>();
        courseSubjects
                .findIdsByWorkspaceIdAndCourseId(workspaceId, courseId)
                .forEach(courseSubjectId ->
                        targets.add(
                                NativePolicyTemplates.courseSubjectKey(
                                        workspaceId, courseSubjectId)));
        enrollments.revokeCurrentByCourseId(workspaceId, courseId, CURRENT_STATES);
        teachingAssignments.revokeCurrentByCourseId(workspaceId, courseId, CURRENT_STATES);
        return List.copyOf(targets);
    }

    /** Gỡ môn khỏi khóa: đóng ghi danh và phân công giảng dạy phụ thuộc môn–khóa đó. */
    public List<String> revokeDependentsOfCourseSubject(UUID workspaceId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        workspaceTransaction.applyContext(workspaceId);
        enrollments.revokeCurrentByCourseSubjectId(workspaceId, courseSubjectId, CURRENT_STATES);
        teachingAssignments.revokeCurrentByCourseSubjectId(
                workspaceId, courseSubjectId, CURRENT_STATES);
        return List.of(NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId));
    }
}
