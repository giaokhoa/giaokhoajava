package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
import jakarta.servlet.http.HttpServletRequest;
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
 * Bề mặt HTTP của ghi danh: ghi danh theo lô, thu hồi một ghi danh và roster của một môn–khóa.
 * Quyết quyền ở tầng route theo dữ kiện nguồn (xem {@link TeachingRouteSupport}): chỉ quản trị của
 * workspace được ghi danh/thu hồi; roster của môn–khóa chỉ quản trị và giáo viên đang được phân
 * công đúng môn–khóa đó được đọc — học sinh và chủ nhiệm không có nguồn đọc roster môn.
 *
 * <p>Lô ghi danh được route kiểm tra phạm vi trước khi gọi service, trong cùng transaction: phần tử
 * nằm ngoài workspace của route làm cả lô bị từ chối 400 như vi phạm invariant cùng lớp/workspace
 * của lô, thay vì lộ đáp án tra cứu đơn lẻ của tầng service. {@link EnrollmentService} tự ghi
 * authorization operation cho các mutation của nó nên route chỉ quyết quyền và trả biên nhận.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class EnrollmentController {
    private final EnrollmentService enrollments;
    private final TeachingRepository repository;
    private final TeachingRouteSupport support;

    public EnrollmentController(
            EnrollmentService enrollments,
            TeachingRepository repository,
            TeachingRouteSupport support) {
        this.enrollments = enrollments;
        this.repository = repository;
        this.support = support;
    }

    public record EnrollmentBatchRequest(List<UUID> classStudentIds, List<UUID> courseSubjectIds) {
        public EnrollmentBatchRequest {
            classStudentIds = classStudentIds == null ? List.of() : List.copyOf(classStudentIds);
            courseSubjectIds = courseSubjectIds == null ? List.of() : List.copyOf(courseSubjectIds);
        }
    }

    @GetMapping("/course-subjects/{courseSubjectId}/enrollments")
    public ResponseEntity<TeachingRouteSupport.Page<EnrollmentService.EnrollmentView>>
            listEnrollments(
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
                    repository
                            .findCourseSubjectScope(workspaceId, courseSubjectId)
                            .orElseThrow(() ->
                                    new HttpProblemException(
                                            HttpStatus.NOT_FOUND,
                                            "ENROLLMENT_TARGET_NOT_FOUND",
                                            "Không tìm thấy môn–khóa trong workspace" + " này."));
                    if (!support.canReadCourseSubjectRoster(actor, workspaceId, courseSubjectId)) {
                        throw TeachingRouteSupport.denied();
                    }
                    return support.page(
                            enrollments.pageEnrollments(
                                    workspaceId, courseSubjectId, cursor, limit));
                });
    }

    @PostMapping("/enrollments")
    public ResponseEntity<IdempotencyService.OperationReceipt> enrollBatch(
            @PathVariable UUID workspaceId,
            @RequestBody @Nullable EnrollmentBatchRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        EnrollmentBatchRequest request =
                body == null ? new EnrollmentBatchRequest(List.of(), List.of()) : body;
        String path = "/api/workspaces/" + workspaceId + "/enrollments";
        String hash = support.payloadHash("POST", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    requireBatchTargetsInWorkspace(
                            workspaceId, request.classStudentIds(), request.courseSubjectIds());
                    return support.receipt(
                            enrollments.enrollBatch(
                                    actor,
                                    workspaceId,
                                    request.classStudentIds(),
                                    request.courseSubjectIds(),
                                    key,
                                    hash));
                });
    }

    @DeleteMapping("/enrollments/{enrollmentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeEnrollment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID enrollmentId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String path = "/api/workspaces/" + workspaceId + "/enrollments/" + enrollmentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    return support.receipt(
                            enrollments.revokeEnrollment(
                                    actor, workspaceId, enrollmentId, key, hash));
                });
    }

    /**
     * Mọi phần tử của lô phải thuộc đúng workspace của route: phần tử của workspace khác (hoặc
     * không tồn tại) là vi phạm invariant phạm vi của lô nên cả lô bị từ chối 400 trước khi ghi,
     * khớp quy tắc mã lỗi của lô đã chốt ở hợp đồng.
     */
    private void requireBatchTargetsInWorkspace(
            UUID workspaceId, List<UUID> classStudentIds, List<UUID> courseSubjectIds) {
        for (UUID classStudentId : classStudentIds) {
            if (repository.findRosterEntryById(workspaceId, classStudentId).isEmpty()) {
                throw batchMismatch();
            }
        }
        for (UUID courseSubjectId : courseSubjectIds) {
            if (repository.findCourseSubjectScope(workspaceId, courseSubjectId).isEmpty()) {
                throw batchMismatch();
            }
        }
    }

    private static HttpProblemException batchMismatch() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "ENROLLMENT_CLASS_MISMATCH",
                "Mọi học sinh và môn–khóa trong một lô ghi danh phải thuộc cùng một workspace"
                        + " và cùng một lớp.");
    }
}
