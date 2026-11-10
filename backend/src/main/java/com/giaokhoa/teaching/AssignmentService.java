package com.giaokhoa.teaching;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingScopePreconditions.WorkspaceInfo;
import com.giaokhoa.teaching.model.CourseSubject;
import com.giaokhoa.teaching.model.HomeroomAssignment;
import com.giaokhoa.teaching.model.TeachingAssignment;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository.Page;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ phân công ở tầng dữ kiện nguồn và nguồn quyền: phân công/thu hồi giáo viên cho một
 * môn–khóa và phân công/thu hồi chủ nhiệm cho một lớp, chỉ trong CENTER workspace (route phân công
 * không áp dụng cho personal). Phân công là thay đổi nguồn quyền — giáo viên thành nguồn của group
 * giáo viên thuộc môn–khóa hoặc group chủ nhiệm của lớp — nên mỗi mutation ghi một authorization
 * operation qua {@link SourceRevocationCoordinator} với bundle hiện hành của target bị ảnh hưởng
 * kèm toàn bộ bundle kết quả của workspace (phân công giảng dạy là nguồn của {@code read}/{@code
 * grade}/{@code publish}/{@code read.published} trên kết quả), và phân công chỉ được coi là đã đồng
 * bộ khi operation SUCCEEDED; service không tự quyết quyền thay Keycloak PDP và không báo hoàn tất
 * khi operation còn PENDING.
 *
 * <p>Tiền đề role đúng center được kiểm tra ở đây trước khi ghi để trả 400 nghiệp vụ (trigger ở DB
 * chỉ kiểm tra membership ACTIVE cho chủ nhiệm, không kiểm tra role): người được phân công phải có
 * membership ACTIVE của chính center này kèm role TEACHER — giáo viên của center khác, thành viên
 * không có role TEACHER và người ngoài center đều bị từ chối cho cả lô trước khi ghi bất cứ dòng
 * nào. Phân công theo ngữ nghĩa hợp tập như roster/ghi danh: cặp đã có dòng hiện hành thì giữ
 * nguyên, chưa có thì tạo dòng ACTIVE mới, không phục hồi dòng lịch sử. Phân công là thao tác mở
 * rộng nên bị chặn khi gói đã hết hiệu lực; thu hồi thì không bị chặn bởi gói. Môn– khóa/lớp đích
 * phải đang hoạt động tại thời điểm phân công: môn–khóa của khóa hay lớp đã thu hồi không nhận thêm
 * phân công.
 *
 * <p>Như các service giảng dạy khác, mỗi phương thức đặt context workspace cho transaction qua
 * {@link WorkspaceTransaction} ngay khi bắt đầu vì các bảng phân công nằm dưới RLS theo đúng
 * context đó, và các mutation trong cùng một workspace được tuần tự hoá bằng khóa workspace thống
 * nhất giữ tới commit như ở {@code EnrollmentService}.
 */
@Service
public class AssignmentService {
    private static final List<String> CURRENT_STATES = List.of("PENDING", "ACTIVE");

    private final SchoolClassRepository schoolClasses;
    private final CourseRepository courses;
    private final CourseSubjectRepository courseSubjects;
    private final TeachingAssignmentRepository teachingAssignments;
    private final HomeroomAssignmentRepository homeroomAssignments;
    private final TeachingPageRepository pages;
    private final TeachingScopePreconditions preconditions;
    private final WorkspaceTransaction workspaceTransaction;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator revocations;
    private final ResourceMutationCoordinator coordinator;

    public AssignmentService(
            SchoolClassRepository schoolClasses,
            CourseRepository courses,
            CourseSubjectRepository courseSubjects,
            TeachingAssignmentRepository teachingAssignments,
            HomeroomAssignmentRepository homeroomAssignments,
            TeachingPageRepository pages,
            TeachingScopePreconditions preconditions,
            WorkspaceTransaction workspaceTransaction,
            IdempotencyService idempotency,
            SourceRevocationCoordinator revocations,
            ResourceMutationCoordinator coordinator) {
        this.schoolClasses = schoolClasses;
        this.courses = courses;
        this.courseSubjects = courseSubjects;
        this.teachingAssignments = teachingAssignments;
        this.homeroomAssignments = homeroomAssignments;
        this.pages = pages;
        this.preconditions = preconditions;
        this.workspaceTransaction = workspaceTransaction;
        this.idempotency = idempotency;
        this.revocations = revocations;
        this.coordinator = coordinator;
    }

    /** Một phân công giảng dạy: định danh dòng, giáo viên và trạng thái hiện hành tối thiểu. */
    public record TeachingAssignmentView(
            UUID id, UUID courseSubjectId, UUID teacherUserId, String state, long version) {}

    /** Một phân công chủ nhiệm: định danh dòng, giáo viên và trạng thái hiện hành tối thiểu. */
    public record HomeroomAssignmentView(
            UUID id, UUID classId, UUID teacherUserId, String state, long version) {}

    /**
     * Phân công một lô giáo viên cho một môn–khóa theo ngữ nghĩa hợp tập. Toàn bộ lô được kiểm tra
     * tiền đề TEACHER của center trước khi ghi bất cứ dòng nào. Trả operation chưa hoàn tất.
     */
    @Transactional
    public AuthorizationOperation assignTeachers(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID courseSubjectId,
            List<UUID> teacherUserIds,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        Objects.requireNonNull(teacherUserIds, "teacherUserIds");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<UUID> teachers = List.copyOf(new LinkedHashSet<>(teacherUserIds));
        if (teachers.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "TEACHERS_REQUIRED",
                    "Danh sách giáo viên phân công không được rỗng.");
        }
        requireCenter(workspaceId);
        CourseSubject subject = requireCurrentCourseSubject(workspaceId, courseSubjectId);
        requireClassAndCourseActive(workspaceId, subject);
        preconditions.requireSubscriptionCurrent(workspaceId);
        for (UUID teacherUserId : teachers) {
            requireCenterTeacher(workspaceId, teacherUserId);
        }
        for (UUID teacherUserId : teachers) {
            if (teachingAssignments
                    .findByWorkspaceIdAndCourseSubjectIdAndTeacherUserIdAndStateIn(
                            workspaceId, courseSubjectId, teacherUserId, CURRENT_STATES)
                    .isEmpty()) {
                teachingAssignments.save(
                        new TeachingAssignment(
                                UUID.randomUUID(), workspaceId, courseSubjectId, teacherUserId));
            }
        }
        return revocations.recordTeachingRevocation(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId)));
    }

    /** Thu hồi một phân công giảng dạy: đổi state/revoked_at, giữ lịch sử; không qua cổng gói. */
    @Transactional
    public AuthorizationOperation revokeTeacherAssignment(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID courseSubjectId,
            UUID assignmentId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        TeachingAssignment row =
                teachingAssignments
                        .lockByWorkspaceIdAndCourseSubjectIdAndId(
                                workspaceId, courseSubjectId, assignmentId)
                        .orElseThrow(() -> assignmentNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_ASSIGNMENT_ALREADY_REVOKED",
                    "Phân công giảng dạy đã bị thu hồi trước đó.");
        }
        row.revoke();
        return revocations.recordTeachingRevocation(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId)));
    }

    /**
     * Phân công một giáo viên làm chủ nhiệm một lớp theo ngữ nghĩa hợp tập; tiền đề TEACHER của
     * center kiểm tra trước khi ghi. Trả operation chưa hoàn tất.
     */
    @Transactional
    public AuthorizationOperation assignHomeroom(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID classId,
            UUID teacherUserId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(teacherUserId, "teacherUserId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireCenter(workspaceId);
        var clazz =
                schoolClasses
                        .lockByWorkspaceIdAndId(workspaceId, classId)
                        .orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.getState());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "CLASS_NOT_ACTIVE",
                    "Lớp đã bị thu hồi, không phân công chủ nhiệm được nữa.");
        }
        preconditions.requireSubscriptionCurrent(workspaceId);
        requireCenterTeacher(workspaceId, teacherUserId);
        if (homeroomAssignments
                .findByWorkspaceIdAndClassIdAndTeacherUserIdAndStateIn(
                        workspaceId, classId, teacherUserId, CURRENT_STATES)
                .isEmpty()) {
            homeroomAssignments.save(
                    new HomeroomAssignment(UUID.randomUUID(), workspaceId, classId, teacherUserId));
        }
        return revocations.recordTeachingRevocation(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                homeroomAffectedTargets(workspaceId, classId));
    }

    /** Thu hồi một phân công chủ nhiệm: đổi state/revoked_at, giữ lịch sử; không qua cổng gói. */
    @Transactional
    public AuthorizationOperation revokeHomeroomAssignment(
            UUID initiatorUserId,
            UUID workspaceId,
            UUID classId,
            UUID assignmentId,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        idempotency.requireKey(idempotencyKey);
        workspaceTransaction.applyContext(workspaceId);
        lockWorkspace(workspaceId);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        HomeroomAssignment row =
                homeroomAssignments
                        .lockByWorkspaceIdAndClassIdAndId(workspaceId, classId, assignmentId)
                        .orElseThrow(() -> assignmentNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.getState());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "HOMEROOM_ASSIGNMENT_ALREADY_REVOKED",
                    "Phân công chủ nhiệm đã bị thu hồi trước đó.");
        }
        row.revoke();
        return revocations.recordTeachingRevocation(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                homeroomAffectedTargets(workspaceId, classId));
    }

    @Transactional(readOnly = true)
    public Page<TeachingAssignmentView> pageTeachingAssignments(
            UUID workspaceId,
            UUID courseSubjectId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        courseSubjects
                .findByWorkspaceIdAndId(workspaceId, courseSubjectId)
                .orElseThrow(() -> courseSubjectNotFound());
        return pages.pageTeachingAssignments(workspaceId, courseSubjectId, cursor, limit)
                .map(item ->
                        new TeachingAssignmentView(
                                item.getId(),
                                item.getCourseSubjectId(),
                                item.getTeacherUserId(),
                                item.getState(),
                                item.getVersion()));
    }

    /** Các phân công giảng dạy hiện hành của một môn–khóa. Chỉ đọc dữ kiện nguồn. */
    @Transactional(readOnly = true)
    public List<TeachingAssignmentView> listTeacherAssignments(
            UUID workspaceId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        workspaceTransaction.applyContext(workspaceId);
        courseSubjects
                .findByWorkspaceIdAndId(workspaceId, courseSubjectId)
                .orElseThrow(() -> courseSubjectNotFound());
        return teachingAssignments
                .findByWorkspaceIdAndCourseSubjectIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, courseSubjectId, CURRENT_STATES)
                .stream()
                .map(row ->
                        new TeachingAssignmentView(
                                row.getId(),
                                row.getCourseSubjectId(),
                                row.getTeacherUserId(),
                                row.getState(),
                                row.getVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<HomeroomAssignmentView> pageHomeroomAssignments(
            UUID workspaceId, UUID classId, @Nullable String cursor, @Nullable Integer limit) {
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        return pages.pageHomeroomAssignments(workspaceId, classId, cursor, limit)
                .map(item ->
                        new HomeroomAssignmentView(
                                item.getId(),
                                item.getClassId(),
                                item.getTeacherUserId(),
                                item.getState(),
                                item.getVersion()));
    }

    /** Các phân công chủ nhiệm hiện hành của một lớp. Chỉ đọc dữ kiện nguồn. */
    @Transactional(readOnly = true)
    public List<HomeroomAssignmentView> listHomeroomAssignments(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        schoolClasses
                .findByWorkspaceIdAndId(workspaceId, classId)
                .orElseThrow(() -> classNotFound());
        return homeroomAssignments
                .findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
                        workspaceId, classId, CURRENT_STATES)
                .stream()
                .map(row ->
                        new HomeroomAssignmentView(
                                row.getId(),
                                row.getClassId(),
                                row.getTeacherUserId(),
                                row.getState(),
                                row.getVersion()))
                .toList();
    }

    /**
     * Mọi target có bundle tham chiếu group chủ nhiệm của lớp này: chính lớp, các khóa của lớp và
     * các môn–khóa của lớp. Đổi chủ nhiệm phải ghi lại cả ba loại trong cùng operation, nếu không
     * projection của khóa/môn–khóa sẽ lệch desired hash đã ghi khi worker đối chiếu.
     */
    private List<String> homeroomAffectedTargets(UUID workspaceId, UUID classId) {
        List<String> targets = new ArrayList<>();
        targets.add(NativePolicyTemplates.classKey(workspaceId, classId));
        for (UUID courseId : courses.findIdsByWorkspaceIdAndClassId(workspaceId, classId)) {
            targets.add(NativePolicyTemplates.courseKey(workspaceId, courseId));
        }
        for (UUID courseSubjectId :
                courseSubjects.findIdsByWorkspaceIdAndClassId(workspaceId, classId)) {
            targets.add(NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId));
        }
        return targets;
    }

    private CourseSubject requireCurrentCourseSubject(UUID workspaceId, UUID courseSubjectId) {
        CourseSubject subject =
                courseSubjects
                        .findByWorkspaceIdAndId(workspaceId, courseSubjectId)
                        .orElseThrow(() -> courseSubjectNotFound());
        if (!"PENDING".equals(subject.getState()) && !"ACTIVE".equals(subject.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_SUBJECT_NOT_ACTIVE",
                    "Môn–khóa đã bị gỡ khỏi khóa, không phân công được nữa.");
        }
        return subject;
    }

    private void requireClassAndCourseActive(UUID workspaceId, CourseSubject subject) {
        var clazz =
                schoolClasses
                        .findByWorkspaceIdAndId(workspaceId, subject.getClassId())
                        .orElseThrow(() -> classNotFound());
        var course =
                courses.findByWorkspaceIdAndId(workspaceId, subject.getCourseId())
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "COURSE_NOT_FOUND",
                                        "Không tìm thấy khóa trong workspace này."));
        if ("REVOKED".equals(clazz.getState()) || "REVOKED".equals(course.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_CONTEXT_NOT_ACTIVE",
                    "Lớp hoặc khóa của môn–khóa đã bị thu hồi, không phân công được nữa.");
        }
    }

    private void requireCenterTeacher(UUID workspaceId, UUID teacherUserId) {
        if (!preconditions.hasActiveCenterMembershipWithRole(
                workspaceId, teacherUserId, "TEACHER")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "TEACHER_NOT_CENTER_TEACHER",
                    "Người được phân công phải là giáo viên đang hoạt động của trung tâm này.");
        }
    }

    private WorkspaceInfo requireCenter(UUID workspaceId) {
        WorkspaceInfo workspace =
                preconditions.workspaceInfo(workspaceId).orElseThrow(() -> workspaceNotFound());
        if (!"CENTER".equals(workspace.kind()) || !"ACTIVE".equals(workspace.state())) {
            throw workspaceNotFound();
        }
        return workspace;
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND", "Không tìm thấy lớp trong workspace này.");
    }

    private static HttpProblemException courseSubjectNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "COURSE_SUBJECT_NOT_FOUND",
                "Không tìm thấy môn–khóa trong workspace này.");
    }

    private static HttpProblemException assignmentNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "ASSIGNMENT_NOT_FOUND",
                "Không tìm thấy phân công trong phạm vi này.");
    }

    private static HttpProblemException workspaceNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_NOT_FOUND",
                "Không tìm thấy trung tâm hợp lệ cho phân công.");
    }

    /**
     * Tuần tự hoá mọi mutation của một workspace trên khóa workspace thống nhất của {@link
     * ResourceMutationCoordinator} — cùng một khóa với CenterService, SubscriptionService và các
     * service lớp/khóa — để dữ kiện nguồn mà projector tính từ dữ kiện nguồn không bao giờ thiếu
     * một mutation đang chạy đồng thời trong cùng workspace.
     */
    private void lockWorkspace(UUID workspaceId) {
        coordinator.lockWorkspace(workspaceId);
    }
}
