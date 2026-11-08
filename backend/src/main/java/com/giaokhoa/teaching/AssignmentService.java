package com.giaokhoa.teaching;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import com.giaokhoa.teaching.data.TeachingRepository.CourseSubjectScopeRow;
import com.giaokhoa.teaching.data.TeachingRepository.HomeroomAssignmentRow;
import com.giaokhoa.teaching.data.TeachingRepository.TeachingAssignmentRow;
import com.giaokhoa.teaching.data.TeachingRepository.WorkspaceInfo;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ phân công ở tầng dữ kiện nguồn và nguồn quyền: phân công/thu hồi giáo viên cho một
 * môn–khóa và phân công/thu hồi chủ nhiệm cho một lớp, chỉ trong CENTER workspace (route phân công
 * không áp dụng cho personal). Phân công là thay đổi nguồn quyền — giáo viên thành nguồn của group
 * giáo viên thuộc môn–khóa hoặc group chủ nhiệm của lớp — nên mỗi mutation ghi một authorization
 * operation qua {@link TeachingPolicyProjector} với bundle hiện hành của target bị ảnh hưởng, và
 * phân công chỉ được coi là đã đồng bộ khi operation SUCCEEDED; service không tự quyết quyền thay
 * Keycloak PDP và không báo hoàn tất khi operation còn PENDING.
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
    private final TeachingRepository repository;
    private final WorkspaceTransaction workspaceTransaction;
    private final IdempotencyService idempotency;
    private final TeachingPolicyProjector projector;
    private final ResourceMutationCoordinator coordinator;

    public AssignmentService(
            TeachingRepository repository,
            WorkspaceTransaction workspaceTransaction,
            IdempotencyService idempotency,
            TeachingPolicyProjector projector,
            ResourceMutationCoordinator coordinator) {
        this.repository = repository;
        this.workspaceTransaction = workspaceTransaction;
        this.idempotency = idempotency;
        this.projector = projector;
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
        CourseSubjectScopeRow subject = requireCurrentCourseSubject(workspaceId, courseSubjectId);
        requireClassAndCourseActive(workspaceId, subject);
        requireSubscriptionCurrent(workspaceId);
        for (UUID teacherUserId : teachers) {
            requireCenterTeacher(workspaceId, teacherUserId);
        }
        for (UUID teacherUserId : teachers) {
            if (repository
                    .findCurrentTeachingAssignment(workspaceId, courseSubjectId, teacherUserId)
                    .isEmpty()) {
                repository.insertTeachingAssignment(
                        UUID.randomUUID(), workspaceId, courseSubjectId, teacherUserId);
            }
        }
        return projector.recordTeachingChange(
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
        TeachingAssignmentRow row =
                repository
                        .lockTeachingAssignment(workspaceId, courseSubjectId, assignmentId)
                        .orElseThrow(() -> assignmentNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_ASSIGNMENT_ALREADY_REVOKED",
                    "Phân công giảng dạy đã bị thu hồi trước đó.");
        }
        repository.revokeTeachingAssignment(assignmentId);
        return projector.recordTeachingChange(
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
        var clazz = repository.lockClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        boolean classRevoked = "REVOKED".equals(clazz.state());
        if (classRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "CLASS_NOT_ACTIVE",
                    "Lớp đã bị thu hồi, không phân công chủ nhiệm được nữa.");
        }
        requireSubscriptionCurrent(workspaceId);
        requireCenterTeacher(workspaceId, teacherUserId);
        if (repository
                .findCurrentHomeroomAssignment(workspaceId, classId, teacherUserId)
                .isEmpty()) {
            repository.insertHomeroomAssignment(
                    UUID.randomUUID(), workspaceId, classId, teacherUserId);
        }
        return projector.recordTeachingChange(
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
        HomeroomAssignmentRow row =
                repository
                        .lockHomeroomAssignment(workspaceId, classId, assignmentId)
                        .orElseThrow(() -> assignmentNotFound());
        boolean alreadyRevoked = "REVOKED".equals(row.state());
        if (alreadyRevoked) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "HOMEROOM_ASSIGNMENT_ALREADY_REVOKED",
                    "Phân công chủ nhiệm đã bị thu hồi trước đó.");
        }
        repository.revokeHomeroomAssignment(assignmentId);
        return projector.recordTeachingChange(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                homeroomAffectedTargets(workspaceId, classId));
    }

    /** Các phân công giảng dạy hiện hành của một môn–khóa. Chỉ đọc dữ kiện nguồn. */
    @Transactional(readOnly = true)
    public List<TeachingAssignmentView> listTeacherAssignments(
            UUID workspaceId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        workspaceTransaction.applyContext(workspaceId);
        repository
                .findCourseSubjectScope(workspaceId, courseSubjectId)
                .orElseThrow(() -> courseSubjectNotFound());
        return repository.listCurrentTeachingAssignments(workspaceId, courseSubjectId).stream()
                .map(row ->
                        new TeachingAssignmentView(
                                row.id(),
                                row.courseSubjectId(),
                                row.teacherUserId(),
                                row.state(),
                                row.version()))
                .toList();
    }

    /** Các phân công chủ nhiệm hiện hành của một lớp. Chỉ đọc dữ kiện nguồn. */
    @Transactional(readOnly = true)
    public List<HomeroomAssignmentView> listHomeroomAssignments(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        workspaceTransaction.applyContext(workspaceId);
        repository.findClass(workspaceId, classId).orElseThrow(() -> classNotFound());
        return repository.listCurrentHomeroomAssignments(workspaceId, classId).stream()
                .map(row ->
                        new HomeroomAssignmentView(
                                row.id(),
                                row.classId(),
                                row.teacherUserId(),
                                row.state(),
                                row.version()))
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
        for (UUID courseId : repository.courseIdsOfClass(workspaceId, classId)) {
            targets.add(NativePolicyTemplates.courseKey(workspaceId, courseId));
        }
        for (UUID courseSubjectId : repository.courseSubjectIdsOfClass(workspaceId, classId)) {
            targets.add(NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId));
        }
        return targets;
    }

    private CourseSubjectScopeRow requireCurrentCourseSubject(
            UUID workspaceId, UUID courseSubjectId) {
        CourseSubjectScopeRow subject =
                repository
                        .findCourseSubjectScope(workspaceId, courseSubjectId)
                        .orElseThrow(() -> courseSubjectNotFound());
        if (!"PENDING".equals(subject.state()) && !"ACTIVE".equals(subject.state())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_SUBJECT_NOT_ACTIVE",
                    "Môn–khóa đã bị gỡ khỏi khóa, không phân công được nữa.");
        }
        return subject;
    }

    private void requireClassAndCourseActive(UUID workspaceId, CourseSubjectScopeRow subject) {
        var clazz =
                repository
                        .findClass(workspaceId, subject.classId())
                        .orElseThrow(() -> classNotFound());
        var course =
                repository
                        .findCourse(workspaceId, subject.courseId())
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "COURSE_NOT_FOUND",
                                        "Không tìm thấy khóa trong workspace này."));
        if ("REVOKED".equals(clazz.state()) || "REVOKED".equals(course.state())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_CONTEXT_NOT_ACTIVE",
                    "Lớp hoặc khóa của môn–khóa đã bị thu hồi, không phân công được nữa.");
        }
    }

    private void requireCenterTeacher(UUID workspaceId, UUID teacherUserId) {
        if (!repository.hasActiveCenterMembershipWithRole(workspaceId, teacherUserId, "TEACHER")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "TEACHER_NOT_CENTER_TEACHER",
                    "Người được phân công phải là giáo viên đang hoạt động của trung tâm này.");
        }
    }

    private WorkspaceInfo requireCenter(UUID workspaceId) {
        WorkspaceInfo workspace =
                repository.findWorkspace(workspaceId).orElseThrow(() -> workspaceNotFound());
        if (!"CENTER".equals(workspace.kind()) || !"ACTIVE".equals(workspace.state())) {
            throw workspaceNotFound();
        }
        return workspace;
    }

    private void requireSubscriptionCurrent(UUID workspaceId) {
        if (!repository.subscriptionCurrent(workspaceId)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng của trung tâm đã hết hiệu lực.");
        }
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
     * service lớp/khóa — để dữ kiện nguồn mà projector chiếu không bao giờ thiếu một mutation đang
     * chạy đồng thời của cùng workspace.
     */
    private void lockWorkspace(UUID workspaceId) {
        coordinator.lockWorkspace(workspaceId);
    }
}
