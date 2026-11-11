package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpConstants;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.shared.http.Violation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của phân công: phân công/thu hồi giáo viên cho một môn–khóa và phân công/thu hồi chủ
 * nhiệm cho một lớp, kèm hai route đọc danh sách phân công hiện hành (nguồn assignmentId cho các
 * route thu hồi). Phân công là quyền quản trị của workspace nên route mutation đòi quản trị hiện
 * hành (owner/manager của center) theo dữ kiện nguồn — giáo viên của một môn và chủ nhiệm của lớp
 * đều không tự phân công được; các route này không áp dụng cho personal (service trả 404). Route
 * đọc quyết quyền theo nguồn đọc roster như danh sách ghi danh và roster của lớp. {@link
 * AssignmentService} tự kiểm tra tiền đề TEACHER của đúng center, tự ghi authorization operation
 * nên route chỉ quyết quyền và trả biên nhận.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class AssignmentController {
    private final AssignmentService assignments;
    private final TeachingRouteSupport support;

    public AssignmentController(AssignmentService assignments, TeachingRouteSupport support) {
        this.assignments = assignments;
        this.support = support;
    }

    public record TeacherAssignRequest(List<UUID> teacherUserIds) {
        public TeacherAssignRequest {
            teacherUserIds = teacherUserIds == null ? List.of() : List.copyOf(teacherUserIds);
        }
    }

    public record HomeroomAssignRequest(@NotNull @Nullable UUID teacherUserId) {}

    @GetMapping("/course-subjects/{courseSubjectId}/teachers")
    public ResponseEntity<TeachingRouteSupport.Page<AssignmentService.TeachingAssignmentView>>
            listTeacherAssignments(
                    @PathVariable UUID workspaceId,
                    @PathVariable UUID courseSubjectId,
                    @RequestParam(value = "cursor", required = false) @Nullable String cursor,
                    @RequestParam(value = "limit", required = false) @Nullable Integer limit,
                    Authentication authentication,
                    HttpServletRequest servletRequest) {
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    if (!support.courseSubjectExists(workspaceId, courseSubjectId)) {
                        throw new HttpProblemException(
                                HttpStatus.NOT_FOUND,
                                ErrorCodes.COURSE_SUBJECT_NOT_FOUND,
                                "Không tìm thấy môn–khóa trong workspace" + " này.");
                    }
                    if (!support.canReadCourseSubjectRoster(actor, workspaceId, courseSubjectId)) {
                        throw TeachingRouteSupport.denied();
                    }
                    return support.page(
                            assignments.pageTeachingAssignments(
                                    workspaceId, courseSubjectId, cursor, limit));
                });
    }

    @PostMapping("/course-subjects/{courseSubjectId}/teachers")
    public ResponseEntity<IdempotencyService.OperationReceipt> assignTeachers(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseSubjectId,
            @RequestBody @Valid @Nullable TeacherAssignRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        TeacherAssignRequest request = body == null ? new TeacherAssignRequest(List.of()) : body;
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + "/course-subjects/"
                        + courseSubjectId
                        + "/teachers";
        String hash = support.payloadHash("POST", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    return support.receipt(
                            assignments.assignTeachers(
                                    actor,
                                    workspaceId,
                                    courseSubjectId,
                                    request.teacherUserIds(),
                                    key,
                                    hash));
                });
    }

    @DeleteMapping("/course-subjects/{courseSubjectId}/teachers/{assignmentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeTeacherAssignment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseSubjectId,
            @PathVariable UUID assignmentId,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + "/course-subjects/"
                        + courseSubjectId
                        + "/teachers/"
                        + assignmentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    return support.receipt(
                            assignments.revokeTeacherAssignment(
                                    actor, workspaceId, courseSubjectId, assignmentId, key, hash));
                });
    }

    @GetMapping("/classes/{classId}/homeroom-assignments")
    public ResponseEntity<TeachingRouteSupport.Page<AssignmentService.HomeroomAssignmentView>>
            listHomeroomAssignments(
                    @PathVariable UUID workspaceId,
                    @PathVariable UUID classId,
                    @RequestParam(value = "cursor", required = false) @Nullable String cursor,
                    @RequestParam(value = "limit", required = false) @Nullable Integer limit,
                    Authentication authentication,
                    HttpServletRequest servletRequest) {
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    if (!support.canReadClass(actor, workspaceId, classId)) {
                        // Lớp không tồn tại trả 404; lớp tồn tại nhưng không được đọc trả 403.
                        if (!support.classExists(workspaceId, classId)) {
                            throw new HttpProblemException(
                                    HttpStatus.NOT_FOUND,
                                    ErrorCodes.CLASS_NOT_FOUND,
                                    "Không tìm thấy lớp trong workspace" + " này.");
                        }
                        throw TeachingRouteSupport.denied();
                    }
                    return support.page(
                            assignments.pageHomeroomAssignments(
                                    workspaceId, classId, cursor, limit));
                });
    }

    @PostMapping("/classes/{classId}/homeroom-assignments")
    public ResponseEntity<IdempotencyService.OperationReceipt> assignHomeroom(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestBody @Valid @Nullable HomeroomAssignRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        HomeroomAssignRequest request = body == null ? new HomeroomAssignRequest(null) : body;
        UUID teacherUserId = request.teacherUserId();
        if (teacherUserId == null) {
            throw HttpProblemException.validationFailed(
                    "Thiếu giáo viên được phân công chủ nhiệm.",
                    List.of(new Violation("teacherUserId", "NotNull", "Giá trị không hợp lệ.")));
        }
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + "/classes/"
                        + classId
                        + "/homeroom-assignments";
        String hash = support.payloadHash("POST", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    return support.receipt(
                            assignments.assignHomeroom(
                                    actor, workspaceId, classId, teacherUserId, key, hash));
                });
    }

    @DeleteMapping("/classes/{classId}/homeroom-assignments/{assignmentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeHomeroomAssignment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @PathVariable UUID assignmentId,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + "/classes/"
                        + classId
                        + "/homeroom-assignments/"
                        + assignmentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    return support.receipt(
                            assignments.revokeHomeroomAssignment(
                                    actor, workspaceId, classId, assignmentId, key, hash));
                });
    }
}
