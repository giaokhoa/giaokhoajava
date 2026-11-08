package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của phân công: phân công/thu hồi giáo viên cho một môn–khóa và phân công/thu hồi chủ
 * nhiệm cho một lớp. Phân công là quyền quản trị của workspace nên route đòi quản trị hiện hành
 * (owner/manager của center) theo dữ kiện nguồn — giáo viên của một môn và chủ nhiệm của lớp đều
 * không tự phân công được; các route này không áp dụng cho personal (service trả 404). {@link
 * AssignmentService} tự kiểm tra tiền đề TEACHER của đúng center, tự ghi authorization operation
 * nên route chỉ quyết quyền và trả biên nhận.
 */
@RestController
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

    public record HomeroomAssignRequest(@Nullable UUID teacherUserId) {}

    @PostMapping("/api/workspaces/{workspaceId}/course-subjects/{courseSubjectId}/teachers")
    public ResponseEntity<IdempotencyService.OperationReceipt> assignTeachers(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseSubjectId,
            @RequestBody @Nullable TeacherAssignRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        support.requireAdmin(actor, workspaceId);
        TeacherAssignRequest request = body == null ? new TeacherAssignRequest(List.of()) : body;
        String path =
                "/api/workspaces/"
                        + workspaceId
                        + "/course-subjects/"
                        + courseSubjectId
                        + "/teachers";
        String hash = support.payloadHash("POST", path, request);
        return support.receipt(
                assignments.assignTeachers(
                        actor, workspaceId, courseSubjectId, request.teacherUserIds(), key, hash));
    }

    @DeleteMapping(
            "/api/workspaces/{workspaceId}/course-subjects/{courseSubjectId}/teachers/{assignmentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeTeacherAssignment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseSubjectId,
            @PathVariable UUID assignmentId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        support.requireAdmin(actor, workspaceId);
        String path =
                "/api/workspaces/"
                        + workspaceId
                        + "/course-subjects/"
                        + courseSubjectId
                        + "/teachers/"
                        + assignmentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.receipt(
                assignments.revokeTeacherAssignment(
                        actor, workspaceId, courseSubjectId, assignmentId, key, hash));
    }

    @PostMapping("/api/workspaces/{workspaceId}/classes/{classId}/homeroom-assignments")
    public ResponseEntity<IdempotencyService.OperationReceipt> assignHomeroom(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestBody @Nullable HomeroomAssignRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        support.requireAdmin(actor, workspaceId);
        HomeroomAssignRequest request = body == null ? new HomeroomAssignRequest(null) : body;
        UUID teacherUserId = request.teacherUserId();
        if (teacherUserId == null) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Thiếu giáo viên được phân công chủ nhiệm.");
        }
        String path =
                "/api/workspaces/" + workspaceId + "/classes/" + classId + "/homeroom-assignments";
        String hash = support.payloadHash("POST", path, request);
        return support.receipt(
                assignments.assignHomeroom(actor, workspaceId, classId, teacherUserId, key, hash));
    }

    @DeleteMapping(
            "/api/workspaces/{workspaceId}/classes/{classId}/homeroom-assignments/{assignmentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeHomeroomAssignment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @PathVariable UUID assignmentId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        support.requireAdmin(actor, workspaceId);
        String path =
                "/api/workspaces/"
                        + workspaceId
                        + "/classes/"
                        + classId
                        + "/homeroom-assignments/"
                        + assignmentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.receipt(
                assignments.revokeHomeroomAssignment(
                        actor, workspaceId, classId, assignmentId, key, hash));
    }
}
