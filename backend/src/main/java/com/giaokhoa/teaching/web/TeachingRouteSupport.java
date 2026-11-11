package com.giaokhoa.teaching.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.teaching.repository.ClassStudentRepository;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.WorkspaceInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing dùng chung của các controller giảng dạy: resolve actor từ phiên, quyết quyền ở tầng
 * route và ghi operation cho các mutation mà service dữ kiện nguồn không tự ghi operation.
 *
 * <p>Quyết quyền ở tầng route tính từ đúng dữ kiện nguồn mà {@link TeachingPolicyProjector} chiếu
 * lên native graph — role của membership ACTIVE, phân công chủ nhiệm/giảng dạy hiện hành, ghi danh
 * hiện hành và chủ sở hữu của personal workspace. Đây là ngoại lệ SQL mà hợp đồng authorization mở
 * rộng cho route giảng dạy: quyết định có hiệu lực ngay từ dữ kiện nguồn, không chờ projection áp
 * xong. Thu hồi phân công hay membership vì vậy chặn lời gọi tiếp theo trong cùng phiên.
 *
 * <p>Mỗi request đồng bộ eligibility hiện hành qua ACCOUNT/target fence trước khi quyết role bằng
 * SQL. Khóa workspace bao trọn kiểm tra role, đọc/ghi nguồn và ghi operation tới commit.
 *
 * <p>Mọi đọc ghi chạm bảng giảng dạy (nằm trong schema tenant của workspace) chạy trong {@link
 * WorkspaceTransaction#inWorkspace} để context được đặt trước truy vấn đầu tiên; thiếu context thì
 * truy vấn không chạy trong schema tenant nên không thấy hàng nào, kể cả khi quyết quyền.
 */
@Component
public class TeachingRouteSupport {
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<String> CURRENT_STATES = List.of("PENDING", "ACTIVE");

    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator revocations;
    private final TeachingScopePreconditions preconditions;
    private final WorkspaceTransaction workspaceTransaction;
    private final ResourceMutationCoordinator coordinator;
    private final SchoolClassRepository schoolClasses;
    private final CourseRepository courses;
    private final CourseSubjectRepository courseSubjects;
    private final ClassStudentRepository classStudents;
    private final EnrollmentRepository enrollments;
    private final HomeroomAssignmentRepository homeroomAssignments;
    private final TeachingAssignmentRepository teachingAssignments;
    private final TeachingPageRepository pages;

    public TeachingRouteSupport(
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            SourceRevocationCoordinator revocations,
            TeachingScopePreconditions preconditions,
            WorkspaceTransaction workspaceTransaction,
            ResourceMutationCoordinator coordinator,
            SchoolClassRepository schoolClasses,
            CourseRepository courses,
            CourseSubjectRepository courseSubjects,
            ClassStudentRepository classStudents,
            EnrollmentRepository enrollments,
            HomeroomAssignmentRepository homeroomAssignments,
            TeachingAssignmentRepository teachingAssignments,
            TeachingPageRepository pages) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.revocations = revocations;
        this.preconditions = preconditions;
        this.workspaceTransaction = workspaceTransaction;
        this.coordinator = coordinator;
        this.schoolClasses = schoolClasses;
        this.courses = courses;
        this.courseSubjects = courseSubjects;
        this.classStudents = classStudents;
        this.enrollments = enrollments;
        this.homeroomAssignments = homeroomAssignments;
        this.teachingAssignments = teachingAssignments;
        this.pages = pages;
    }

    /** Một trang danh sách đã lọc quyền: chỉ các dòng actor được phép thấy, không lộ tổng số. */
    public record Page<T>(
            List<T> items, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** Actor của phiên hiện hành; phiên không gắn được tài khoản directory thì fail-closed. */
    public UUID actor(Authentication authentication) {
        return executor.actorUserId(authentication);
    }

    /** Eligibility qua ACCOUNT fence; khóa workspace trước mọi kiểm tra quyền và dữ kiện nguồn. */
    public <T> T withEligibleActor(
            Authentication authentication,
            HttpServletRequest request,
            UUID workspaceId,
            Function<UUID, T> work) {
        UUID actor = actor(authentication);
        return executor.executeForActor(
                authentication,
                request,
                List.of(
                        new ProtectedActionExecutor.Target(
                                NativePolicyTemplates.profileKey(actor),
                                List.of(
                                        PermissionCatalog.ProfileScope.WORKSPACE_LIST
                                                .scopeName()))),
                () ->
                        inWorkspace(
                                workspaceId,
                                () -> {
                                    coordinator.lockWorkspace(workspaceId);
                                    return work.apply(actor);
                                }));
    }

    /** Chạy một thao tác trong transaction có context workspace cho schema tenant. */
    private <T> T inWorkspace(UUID workspaceId, Supplier<T> work) {
        // Supplier của route luôn trả đáp án; inWorkspace chỉ trả null khi callback trả null.
        return Objects.requireNonNull(
                workspaceTransaction.inWorkspace(workspaceId, work), "route result");
    }

    /**
     * Actor có quyền quản trị giảng dạy của workspace: OWNER/MANAGER hiện hành của center, hoặc
     * chính chủ sở hữu của personal workspace — đúng hai nguồn quản trị mà projector chiếu.
     */
    public boolean isAdmin(UUID actorUserId, UUID workspaceId) {
        Optional<WorkspaceInfo> workspace = preconditions.workspaceInfo(workspaceId);
        if (workspace.isEmpty()) {
            return false;
        }
        WorkspaceInfo info = workspace.get();
        boolean personal = "PERSONAL".equals(info.kind());
        if (personal) {
            return actorUserId.equals(info.ownerUserId());
        }
        boolean center = "CENTER".equals(info.kind());
        if (!center) {
            return false;
        }
        return preconditions.hasActiveCenterMembershipWithRole(workspaceId, actorUserId, "OWNER")
                || preconditions.hasActiveCenterMembershipWithRole(
                        workspaceId, actorUserId, "MANAGER");
    }

    /** Đòi quyền quản trị giảng dạy; không có thì 403 như mọi deny quyền hiện hành. */
    public void requireAdmin(UUID actorUserId, UUID workspaceId) {
        if (!isAdmin(actorUserId, workspaceId)) {
            throw denied();
        }
    }

    /** Actor đang là chủ nhiệm hiện hành của ít nhất một lớp ACTIVE của workspace. */
    public boolean hasCurrentHomeroomClass(UUID workspaceId, UUID actorUserId) {
        return pages.hasCurrentHomeroomClass(workspaceId, actorUserId);
    }

    /** Quyền đọc một lớp: quản trị, hoặc chủ nhiệm hiện hành của chính lớp đó. */
    public boolean canReadClass(UUID actorUserId, UUID workspaceId, UUID classId) {
        return isAdmin(actorUserId, workspaceId)
                || homeroomAssignments
                        .findClassIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                                workspaceId, actorUserId, CURRENT_STATES)
                        .contains(classId);
    }

    /** Quyền đọc một khóa: quản trị, hoặc chủ nhiệm hiện hành của lớp chứa khóa. */
    public boolean canReadCourse(UUID actorUserId, UUID workspaceId, UUID classIdOfCourse) {
        return canReadClass(actorUserId, workspaceId, classIdOfCourse);
    }

    /**
     * Quyền đọc roster của một môn–khóa: quản trị, hoặc giáo viên đang được phân công đúng môn–khóa
     * đó.
     */
    public boolean canReadCourseSubjectRoster(
            UUID actorUserId, UUID workspaceId, UUID courseSubjectId) {
        return isAdmin(actorUserId, workspaceId)
                || teachingAssignments
                        .findCourseSubjectIdByWorkspaceIdAndTeacherUserIdAndStateIn(
                                workspaceId, actorUserId, CURRENT_STATES)
                        .contains(courseSubjectId);
    }

    /** Lớp có tồn tại trong đúng workspace (mọi trạng thái) — quyết 404 ở tầng route. */
    public boolean classExists(UUID workspaceId, UUID classId) {
        return schoolClasses.existsByWorkspaceIdAndId(workspaceId, classId);
    }

    /** Môn–khóa có tồn tại trong đúng workspace (mọi trạng thái) — quyết 404 ở tầng route. */
    public boolean courseSubjectExists(UUID workspaceId, UUID courseSubjectId) {
        return courseSubjects.existsByWorkspaceIdAndId(workspaceId, courseSubjectId);
    }

    /** Dòng roster có tồn tại trong đúng workspace (mọi trạng thái) — kiểm tra phạm vi của lô. */
    public boolean rosterEntryExists(UUID workspaceId, UUID classStudentId) {
        return classStudents.existsByWorkspaceIdAndId(workspaceId, classStudentId);
    }

    /** Mọi khóa thuộc một lớp trong workspace. */
    public List<UUID> courseIdsOfClass(UUID workspaceId, UUID classId) {
        return courses.findIdsByWorkspaceIdAndClassId(workspaceId, classId);
    }

    /** Mọi môn–khóa thuộc một lớp trong workspace. */
    public List<UUID> courseSubjectIdsOfClass(UUID workspaceId, UUID classId) {
        return courseSubjects.findIdsByWorkspaceIdAndClassId(workspaceId, classId);
    }

    /** Mọi môn–khóa thuộc một khóa trong workspace. */
    public List<UUID> courseSubjectIdsOfCourse(UUID workspaceId, UUID courseId) {
        return courseSubjects.findIdsByWorkspaceIdAndCourseId(workspaceId, courseId);
    }

    /** Các môn–khóa mà một dòng roster đang có ghi danh hiện hành. */
    public List<UUID> courseSubjectIdsOfRosterEntry(UUID workspaceId, UUID classStudentId) {
        return enrollments.findCourseSubjectIdsByClassStudentId(
                workspaceId, classStudentId, CURRENT_STATES);
    }

    /** Hash payload cho idempotency, cùng cách các controller thành viên đang dùng. */
    public String payloadHash(String method, String targetPath, @Nullable Object body) {
        return idempotency.payloadHash(
                method,
                targetPath,
                JSON.valueToTree(body == null ? JSON.createObjectNode() : body));
    }

    /** Operation đã ghi cho cùng actor/key/payload, nếu lời gọi này là replay. */
    public Optional<AuthorizationOperation> replay(
            UUID actorUserId, String key, String payloadHash) {
        idempotency.requireKey(key);
        return idempotency.findExisting(actorUserId, key, payloadHash);
    }

    /**
     * Ghi operation ghi lại bundle hiện hành của các target bị một mutation dữ kiện nguồn chạm tới
     * kèm toàn bộ bundle kết quả của workspace (thu hồi lớp/khóa/môn–khóa/roster đóng ghi danh và
     * phân công phụ thuộc, vốn là nguồn của bundle kết quả), trong cùng transaction của mutation —
     * đường ghi operation cho các service lớp/khóa vốn không tự ghi operation.
     */
    public AuthorizationOperation recordChange(
            UUID actorUserId,
            UUID workspaceId,
            String key,
            String payloadHash,
            List<String> targetKeys) {
        return revocations.recordTeachingRevocation(
                actorUserId, workspaceId, key, payloadHash, targetKeys);
    }

    /** Biên nhận operation theo hợp đồng OperationView: 202 khi chưa xong, 200 khi đã SUCCEEDED. */
    public ResponseEntity<IdempotencyService.OperationReceipt> receipt(
            AuthorizationOperation operation) {
        return idempotency.responseFor(operation);
    }

    /** Dùng trang đã giới hạn và lọc tại DB, giữ nguyên HTTP DTO và cache policy. */
    public <T> ResponseEntity<Page<T>> page(TeachingPageRepository.Page<T> page) {
        return ok(new Page<>(page.items(), page.nextCursor()));
    }

    /** Đáp án đọc được bảo vệ: không cache theo đúng hợp đồng HTTP. */
    public <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    public static HttpProblemException denied() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN,
                ErrorCodes.PERMISSION_DENIED,
                "Không có quyền thực hiện thao tác.");
    }

    /** Khóa target của một lớp theo template cố định của projector. */
    public static String classTarget(UUID workspaceId, UUID classId) {
        return NativePolicyTemplates.classKey(workspaceId, classId);
    }

    /** Khóa target của một khóa theo template cố định của projector. */
    public static String courseTarget(UUID workspaceId, UUID courseId) {
        return NativePolicyTemplates.courseKey(workspaceId, courseId);
    }

    /** Khóa target của một môn–khóa theo template cố định của projector. */
    public static String courseSubjectTarget(UUID workspaceId, UUID courseSubjectId) {
        return NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
    }
}
