package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.data.TeachingRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của lớp và roster: tạo/đọc/đổi tên/thu hồi lớp, xem và thay roster của lớp. Quyết
 * quyền ở tầng route theo dữ kiện nguồn (xem {@link TeachingRouteSupport}): quản trị của workspace
 * (owner/manager của center, chủ sở hữu của personal) cho mọi mutation và danh sách lớp; chủ nhiệm
 * hiện hành đọc được lớp và roster của lớp mình. GET đơn lẻ không được đọc trả 404 để không tiết lộ
 * sự tồn tại, GET danh sách bị cấm trả 403.
 *
 * <p>Các mutation của {@link ClassService} chỉ ghi dữ kiện nguồn nên route ghi kèm authorization
 * operation cho bundle của các target bị chạm tới trong cùng transaction, và chỉ trả biên nhận
 * operation: nghiệp vụ chỉ hoàn tất khi operation SUCCEEDED.
 */
@RestController
public class ClassController {
    private final ClassService classes;
    private final TeachingRepository repository;
    private final TeachingRouteSupport support;

    public ClassController(
            ClassService classes, TeachingRepository repository, TeachingRouteSupport support) {
        this.classes = classes;
        this.repository = repository;
        this.support = support;
    }

    public record NameRequest(String name) {
        public NameRequest {
            // Tên vắng mặt trong body được đưa về chuỗi rỗng để service trả đúng 400 tên rỗng.
            name = name == null ? "" : name;
        }
    }

    public record RosterAddRequest(List<UUID> studentUserIds) {
        public RosterAddRequest {
            studentUserIds = studentUserIds == null ? List.of() : List.copyOf(studentUserIds);
        }
    }

    @GetMapping("/api/workspaces/{workspaceId}/classes")
    public ResponseEntity<TeachingRouteSupport.Page<ClassService.ClassView>> listClasses(
            @PathVariable UUID workspaceId,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    List<ClassService.ClassView> all = classes.listClasses(workspaceId);
                    if (!support.isAdmin(actor, workspaceId)) {
                        // Danh sách chỉ chứa các lớp actor đọc được: chủ nhiệm thấy lớp của
                        // mình; actor không đọc được lớp nào thì danh sách bị cấm hẳn.
                        List<UUID> homeroom =
                                repository.currentHomeroomClassIdsOfUser(workspaceId, actor);
                        all = all.stream().filter(view -> homeroom.contains(view.id())).toList();
                        if (all.isEmpty()) {
                            throw TeachingRouteSupport.denied();
                        }
                    }
                    return support.ok(support.page(all, cursor, limit, ClassService.ClassView::id));
                });
    }

    @PostMapping("/api/workspaces/{workspaceId}/classes")
    public ResponseEntity<IdempotencyService.OperationReceipt> createClass(
            @PathVariable UUID workspaceId,
            @RequestBody @Nullable NameRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        NameRequest request = body == null ? new NameRequest("") : body;
        String hash =
                support.payloadHash("POST", "/api/workspaces/" + workspaceId + "/classes", request);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    ClassService.ClassView view = classes.createClass(workspaceId, request.name());
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    List.of(
                                            TeachingRouteSupport.classTarget(
                                                    workspaceId, view.id()))));
                });
    }

    @GetMapping("/api/workspaces/{workspaceId}/classes/{classId}")
    public ResponseEntity<ClassService.ClassView> getClass(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    ClassService.ClassView view = classes.getClass(workspaceId, classId);
                    if (!support.canReadClass(actor, workspaceId, classId)) {
                        throw classNotFound();
                    }
                    return support.ok(view);
                });
    }

    @PatchMapping("/api/workspaces/{workspaceId}/classes/{classId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> renameClass(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestBody @Nullable NameRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        NameRequest request = body == null ? new NameRequest("") : body;
        String path = "/api/workspaces/" + workspaceId + "/classes/" + classId;
        String hash = support.payloadHash("PATCH", path, request);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    classes.renameClass(workspaceId, classId, request.name());
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    List.of(
                                            TeachingRouteSupport.classTarget(
                                                    workspaceId, classId))));
                });
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/classes/{classId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeClass(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        String path = "/api/workspaces/" + workspaceId + "/classes/" + classId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    // Thu hồi lớp đóng ghi danh/phân công phụ thuộc: bundle của lớp, mọi khóa
                    // và mọi môn–khóa của lớp phải được ghi lại trong cùng operation.
                    List<String> targets = new ArrayList<>();
                    targets.add(TeachingRouteSupport.classTarget(workspaceId, classId));
                    repository
                            .courseIdsOfClass(workspaceId, classId)
                            .forEach(courseId ->
                                    targets.add(
                                            TeachingRouteSupport.courseTarget(
                                                    workspaceId, courseId)));
                    repository
                            .courseSubjectIdsOfClass(workspaceId, classId)
                            .forEach(courseSubjectId ->
                                    targets.add(
                                            TeachingRouteSupport.courseSubjectTarget(
                                                    workspaceId, courseSubjectId)));
                    classes.revokeClass(workspaceId, classId);
                    return support.receipt(
                            support.recordChange(actor, workspaceId, key, hash, targets));
                });
    }

    @GetMapping("/api/workspaces/{workspaceId}/classes/{classId}/students")
    public ResponseEntity<TeachingRouteSupport.Page<ClassService.RosterEntryView>> listStudents(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    if (!support.canReadClass(actor, workspaceId, classId)) {
                        // Lớp không tồn tại trả 404; lớp tồn tại nhưng không được đọc trả 403.
                        classes.getClass(workspaceId, classId);
                        throw TeachingRouteSupport.denied();
                    }
                    return support.ok(
                            support.page(
                                    classes.listStudents(workspaceId, classId),
                                    cursor,
                                    limit,
                                    ClassService.RosterEntryView::id));
                });
    }

    @PostMapping("/api/workspaces/{workspaceId}/classes/{classId}/students")
    public ResponseEntity<IdempotencyService.OperationReceipt> addStudents(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @RequestBody @Nullable RosterAddRequest body,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        RosterAddRequest request = body == null ? new RosterAddRequest(List.of()) : body;
        String path = "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students";
        String hash = support.payloadHash("POST", path, request);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    classes.addStudents(workspaceId, classId, request.studentUserIds());
                    return support.receipt(
                            support.recordChange(
                                    actor,
                                    workspaceId,
                                    key,
                                    hash,
                                    List.of(
                                            TeachingRouteSupport.classTarget(
                                                    workspaceId, classId))));
                });
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/classes/{classId}/students/{classStudentId}")
    public ResponseEntity<IdempotencyService.OperationReceipt> revokeStudent(
            @PathVariable UUID workspaceId,
            @PathVariable UUID classId,
            @PathVariable UUID classStudentId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        UUID actor = support.actor(authentication);
        String path =
                "/api/workspaces/"
                        + workspaceId
                        + "/classes/"
                        + classId
                        + "/students/"
                        + classStudentId;
        String hash = support.payloadHash("DELETE", path, null);
        return support.inWorkspace(
                workspaceId,
                () -> {
                    support.requireAdmin(actor, workspaceId);
                    var existing = support.replay(actor, key, hash);
                    if (existing.isPresent()) {
                        return support.receipt(existing.get());
                    }
                    // Ghi danh phụ thuộc bị thu hồi cùng dòng roster: bundle của các môn–khóa
                    // đó phải được ghi lại trong cùng operation, tính trước khi service đóng.
                    List<String> targets = new ArrayList<>();
                    targets.add(TeachingRouteSupport.classTarget(workspaceId, classId));
                    repository
                            .courseSubjectIdsOfRosterEntry(workspaceId, classStudentId)
                            .forEach(courseSubjectId ->
                                    targets.add(
                                            TeachingRouteSupport.courseSubjectTarget(
                                                    workspaceId, courseSubjectId)));
                    classes.revokeStudent(workspaceId, classId, classStudentId);
                    return support.receipt(
                            support.recordChange(actor, workspaceId, key, hash, targets));
                });
    }

    private static HttpProblemException classNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND", "Không tìm thấy lớp trong workspace này.");
    }
}
