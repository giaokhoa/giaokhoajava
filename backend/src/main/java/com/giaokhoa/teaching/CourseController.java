package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpConstants;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của khóa, môn của khóa và danh mục môn: tạo/đọc/đổi tên/thu hồi khóa dưới một lớp,
 * gắn/gỡ môn của khóa, và danh mục môn tham chiếu dùng chung. Quyết quyền ở tầng route theo dữ kiện
 * nguồn (xem {@link TeachingRouteSupport}): quản trị của workspace cho mọi mutation; đọc khóa theo
 * quyền đọc lớp chứa khóa. Như ở {@link ClassController}, mutation của service dữ kiện nguồn được
 * route ghi kèm authorization operation cho bundle của các target bị chạm tới trong cùng
 * transaction. Danh mục môn không chứa dữ liệu riêng của workspace nào nên chỉ đòi phiên có tài
 * khoản hợp lệ.
 */
@RestController
@RequestMapping("/api")
public class CourseController {
    private static final String COURSES_SEGMENT = "/courses/";

    private final CourseService courses;
    private final TeachingRouteSupport support;

    public CourseController(CourseService courses, TeachingRouteSupport support) {
        this.courses = courses;
        this.support = support;
    }

    public record CourseCreateRequest(
            @NotBlank @Size(max = 255) String name, List<UUID> subjectIds) {
        public CourseCreateRequest {
            // Tên vắng mặt trong body được đưa về chuỗi rỗng để service trả đúng 400 tên rỗng.
            name = name == null ? "" : name;
            subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
        }
    }

    public record NameRequest(@NotBlank @Size(max = 255) String name) {
        public NameRequest {
            name = name == null ? "" : name;
        }
    }

    public record SubjectAddRequest(List<UUID> subjectIds) {
        public SubjectAddRequest {
            subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
        }
    }

    @GetMapping("/subjects")
    public ResponseEntity<TeachingRouteSupport.Page<CourseService.SubjectView>> listSubjects(
            Authentication authentication) {
        support.actor(authentication);
        return support.ok(new TeachingRouteSupport.Page<>(courses.listSubjects(), null));
    }

    @GetMapping("/workspaces/{workspaceId}/classes/{classId}/courses")
    public ResponseEntity<TeachingRouteSupport.Page<CourseService.CourseView>> listCourses(
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
                            throw classNotFound();
                        }
                        throw TeachingRouteSupport.denied();
                    }
                    return support.page(courses.pageCourses(workspaceId, classId, cursor, limit));
                });
    }

    @PostMapping("/workspaces/{workspaceId}/classes/{classId}/courses")
    public ResponseEntity<IdempotencyService.OperationReceipt> createCourse(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestBody @Valid @Nullable CourseCreateRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        CourseCreateRequest request = body == null ? new CourseCreateRequest("", List.of()) : body;
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + "/classes/"
                        + classId
                        + "/courses";
        String hash = support.payloadHash("POST", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    CourseService.CourseView view =
                            courses.createCourse(
                                    workspaceId, classId, request.name(), request.subjectIds());
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    courseTargets(workspaceId, view)));
                });
    }

    @GetMapping("/workspaces/{workspaceId}/courses/{courseId}")
    public ResponseEntity<CourseService.CourseView> getCourse(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseId,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    CourseService.CourseView view = courses.getCourse(workspaceId, courseId);
                    if (!support.canReadCourse(actor, workspaceId, view.classId())) {
                        throw courseNotFound();
                    }
                    return support.ok(view);
                });
    }

    @PatchMapping("/workspaces/{workspaceId}/courses/{courseId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> renameCourse(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseId,
            @RequestBody @Valid @Nullable NameRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        NameRequest request = body == null ? new NameRequest("") : body;
        String path = HttpConstants.WORKSPACE_API_PREFIX + workspaceId + COURSES_SEGMENT + courseId;
        String hash = support.payloadHash("PATCH", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    courses.renameCourse(workspaceId, courseId, request.name(), ifMatch);
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    List.of(
                                            TeachingRouteSupport.courseTarget(
                                                    workspaceId, courseId))));
                });
    }

    @DeleteMapping("/workspaces/{workspaceId}/courses/{courseId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeCourse(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseId,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String path = HttpConstants.WORKSPACE_API_PREFIX + workspaceId + COURSES_SEGMENT + courseId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    // Thu hồi khóa đóng ghi danh/phân công phụ thuộc các môn–khóa của khóa:
                    // bundle của khóa và mọi môn–khóa ghi lại trong cùng operation.
                    List<String> targets = new ArrayList<>();
                    targets.add(TeachingRouteSupport.courseTarget(workspaceId, courseId));
                    support.courseSubjectIdsOfCourse(workspaceId, courseId)
                            .forEach(courseSubjectId ->
                                    targets.add(
                                            TeachingRouteSupport.courseSubjectTarget(
                                                    workspaceId, courseSubjectId)));
                    courses.revokeCourse(workspaceId, courseId);
                    return support.receipt(
                            support.recordChange(actor, workspaceId, key, hash, targets));
                });
    }

    @PostMapping("/workspaces/{workspaceId}/courses/{courseId}/subjects")
    public ResponseEntity<IdempotencyService.OperationReceipt> addSubjects(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseId,
            @RequestBody @Valid @Nullable SubjectAddRequest body,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        SubjectAddRequest request = body == null ? new SubjectAddRequest(List.of()) : body;
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + COURSES_SEGMENT
                        + courseId
                        + "/subjects";
        String hash = support.payloadHash("POST", path, request);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    CourseService.CourseView view =
                            courses.addSubjects(workspaceId, courseId, request.subjectIds());
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    courseTargets(workspaceId, view)));
                });
    }

    @DeleteMapping("/workspaces/{workspaceId}/courses/{courseId}/subjects/{courseSubjectId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeCourseSubject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID courseId,
            @PathVariable UUID courseSubjectId,
            @RequestHeader(HttpConstants.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String path =
                HttpConstants.WORKSPACE_API_PREFIX
                        + workspaceId
                        + COURSES_SEGMENT
                        + courseId
                        + "/subjects/"
                        + courseSubjectId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.withEligibleActor(
                authentication,
                servletRequest,
                workspaceId,
                actor -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    courses.revokeCourseSubject(workspaceId, courseId, courseSubjectId);
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    List.of(
                                            TeachingRouteSupport.courseSubjectTarget(
                                                    workspaceId, courseSubjectId))));
                });
    }

    /** Bundle bị chạm tới khi một khóa đổi: chính khóa và mọi môn–khóa hiện hành của nó. */
    private static List<String> courseTargets(UUID workspaceId, CourseService.CourseView view) {
        List<String> targets = new ArrayList<>();
        targets.add(TeachingRouteSupport.courseTarget(workspaceId, view.id()));
        view.subjects()
                .forEach(subject ->
                        targets.add(
                                TeachingRouteSupport.courseSubjectTarget(
                                        workspaceId, subject.id())));
        return targets;
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.CLASS_NOT_FOUND,
                "Không tìm thấy lớp trong workspace này.");
    }

    private static HttpProblemException courseNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.COURSE_NOT_FOUND,
                "Không tìm thấy khóa trong workspace này.");
    }
}
