package com.giaokhoa.verification;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Các sample action của hợp đồng verification. Mọi action chạy qua cùng pipeline manifest/fence/PDP
 * như business action và không bao giờ chấp nhận actor thay thế. CREATE_RESOURCE trả kết quả stub
 * vì chưa có bảng sample-resource. ASSIGN_ACTIVITY, SUBMIT_ACTIVITY, GRADE_RESULT và PUBLISH_RESULT
 * ghi thật vào các bảng mẫu với target và scope đúng hợp đồng HTTP; dữ kiện nguồn của một target có
 * thể chưa từng được chiếu khi chuỗi được seed thẳng, nên trước khi hỏi PDP mỗi action ghi lại
 * bundle hiện hành của target qua cơ chế operation dùng chung và chờ chiếu xong — chờ không xong
 * thì fail-closed 503 như mọi protected request khác. Action lạ bị từ chối, không bao giờ được lặng
 * lẽ cho qua.
 */
@RestController
public class SampleActionController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String OPERATION_FAILED = "FAILED";

    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final SourceRevocationCoordinator coordinator;
    private final ResultPolicyProjector resultProjector;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    public SampleActionController(
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            SourceRevocationCoordinator coordinator,
            ResultPolicyProjector resultProjector,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.resultProjector = resultProjector;
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record SampleActionRequest(
            String action,
            UUID courseSubjectId,
            UUID subjectId,
            String kind,
            String label,
            UUID resourceId,
            UUID versionId,
            OffsetDateTime dueAt,
            Integer attemptLimit,
            UUID activityId,
            String contentStub,
            UUID resultId,
            String scoreStub,
            String commentStub) {}

    public record SampleActionResult(
            UUID id, String state, boolean completed, String targetType, UUID targetId) {}

    @PostMapping("/api/workspaces/{workspaceId}/samples/actions")
    public ResponseEntity<SampleActionResult> act(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        idempotency.requireKey(idempotencyKey);
        if (request == null || request.action() == null) {
            throw unsupported();
        }
        SampleActionResult result =
                switch (request.action()) {
                    case "CREATE_RESOURCE" ->
                            createResource(workspaceId, request, authentication, httpRequest);
                    case "ASSIGN_ACTIVITY" ->
                            assignActivity(
                                    workspaceId,
                                    idempotencyKey,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "SUBMIT_ACTIVITY" ->
                            submitActivity(
                                    workspaceId,
                                    idempotencyKey,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "GRADE_RESULT" ->
                            gradeResult(
                                    workspaceId,
                                    idempotencyKey,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "PUBLISH_RESULT" ->
                            publishResult(
                                    workspaceId,
                                    idempotencyKey,
                                    request,
                                    authentication,
                                    httpRequest);
                    default -> throw unsupported();
                };
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(result);
    }

    // ---------- CREATE_RESOURCE ----------

    private SampleActionResult createResource(
            UUID workspaceId,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.subjectId() == null
                || request.kind() == null
                || request.kind().isBlank()
                || request.label() == null
                || request.label().isBlank()) {
            throw invalid();
        }
        return request.courseSubjectId() != null
                ? createForCourseSubject(workspaceId, request, authentication, httpRequest)
                : createForPersonalOwner(workspaceId, authentication, httpRequest);
    }

    private SampleActionResult createForCourseSubject(
            UUID workspaceId,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        var target =
                new ProtectedActionExecutor.Target(
                        NativePolicyTemplates.courseSubjectKey(
                                workspaceId, request.courseSubjectId()),
                        List.of(PermissionCatalog.CourseSubjectScope.RESOURCE_CREATE.scopeName()));
        return executor.execute(
                authentication, httpRequest, workspaceId, List.of(target), this::stubResult);
    }

    private SampleActionResult createForPersonalOwner(
            UUID workspaceId, Authentication authentication, HttpServletRequest httpRequest) {
        UUID actorUserId = executor.actorUserId(authentication);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT kind, owner_user_id FROM workspace WHERE id = ?", workspaceId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> workspace = rows.getFirst();
        if (!"PERSONAL".equals(workspace.get("kind"))
                || !actorUserId.equals(workspace.get("owner_user_id"))) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Không có quyền thực hiện thao tác.");
        }
        var target =
                new ProtectedActionExecutor.Target(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        List.of(PermissionCatalog.WorkspaceScope.ENTER.scopeName()));
        return executor.execute(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                () -> {
                    // Nhánh personal của hợp đồng là owner cộng một kỳ subscription còn hiệu
                    // lực, được kiểm tra bên trong fence transaction để một kỳ hết hạn
                    // giữa request không thể lọt một allow đã cũ qua.
                    Integer active =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM subscription_period"
                                            + " WHERE workspace_id = ? AND valid_from <= now()"
                                            + " AND expires_at > now()",
                                    Integer.class,
                                    workspaceId);
                    if (active == null || active == 0) {
                        throw new HttpProblemException(
                                HttpStatus.FORBIDDEN,
                                "SUBSCRIPTION_INACTIVE",
                                "Gói sử dụng không còn hiệu lực.");
                    }
                    return stubResult();
                });
    }

    private SampleActionResult stubResult() {
        UUID id = UUID.randomUUID();
        return new SampleActionResult(id, "SUCCEEDED", true, "RESOURCE", id);
    }

    // ---------- ASSIGN_ACTIVITY / SUBMIT_ACTIVITY ----------

    private SampleActionResult assignActivity(
            UUID workspaceId,
            String idempotencyKey,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.courseSubjectId() == null
                || request.resourceId() == null
                || request.versionId() == null
                || request.dueAt() == null
                || request.attemptLimit() == null
                || request.attemptLimit() < 1) {
            throw invalid();
        }
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.courseSubjectKey(workspaceId, request.courseSubjectId());
        ensureTeachingProjection(actorUserId, workspaceId, idempotencyKey + "#assign", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName()));
        return executor.execute(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                () ->
                        Objects.requireNonNull(
                                workspaces.inWorkspace(
                                        workspaceId,
                                        () -> insertActivity(workspaceId, actorUserId, request)),
                                "activity"));
    }

    private SampleActionResult insertActivity(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request) {
        List<Map<String, Object>> subjects =
                jdbc.queryForList(
                        "SELECT subject_id FROM course_subject WHERE workspace_id = ? AND id = ?",
                        workspaceId,
                        request.courseSubjectId());
        if (subjects.isEmpty()) {
            throw notFound();
        }
        UUID subjectId = (UUID) subjects.getFirst().get("subject_id");
        // Invariant của hợp đồng giao bài: version thuộc đúng resource, resource thuộc đúng
        // workspace và cùng môn với môn–khóa, và version đã ở trạng thái duyệt.
        Integer usable =
                jdbc.queryForObject(
                        "SELECT count(*) FROM resource_version v"
                                + " JOIN resource r ON r.id = v.resource_id"
                                + " WHERE v.id = ? AND v.resource_id = ?"
                                + " AND r.owner_workspace_id = ? AND r.subject_id = ?"
                                + " AND v.review_state = 'APPROVED'",
                        Integer.class,
                        request.versionId(),
                        request.resourceId(),
                        workspaceId,
                        subjectId);
        if (usable == null || usable == 0) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SAMPLE_ACTION",
                    "Version không thuộc resource của workspace hoặc chưa được duyệt.");
        }
        List<UUID> periods =
                jdbc.queryForList(
                        "SELECT id FROM subscription_period WHERE workspace_id = ?"
                                + " AND valid_from <= now() AND expires_at > now()"
                                + " ORDER BY valid_from DESC, id",
                        UUID.class,
                        workspaceId);
        if (periods.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng không còn hiệu lực.");
        }
        UUID activityId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sample_activity(id, workspace_id, course_subject_id, resource_id,"
                        + " version_id, assigned_by, assigned_at, due_at, attempt_limit,"
                        + " subscription_period_id, state)"
                        + " VALUES (?,?,?,?,?,?, now(), ?, ?, ?, 'ASSIGNED')",
                activityId,
                workspaceId,
                request.courseSubjectId(),
                request.resourceId(),
                request.versionId(),
                actorUserId,
                request.dueAt(),
                request.attemptLimit(),
                periods.getFirst());
        return new SampleActionResult(activityId, "SUCCEEDED", true, "ACTIVITY", activityId);
    }

    private SampleActionResult submitActivity(
            UUID workspaceId,
            String idempotencyKey,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.activityId() == null
                || request.contentStub() == null
                || request.contentStub().isBlank()) {
            throw invalid();
        }
        UUID actorUserId = executor.actorUserId(authentication);
        Map<String, Object> activity =
                Objects.requireNonNull(
                        workspaces.inWorkspace(
                                workspaceId, () -> activityRow(workspaceId, request.activityId())),
                        "activity");
        UUID courseSubjectId =
                Objects.requireNonNull((UUID) activity.get("course_subject_id"), "courseSubjectId");
        String targetKey = NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
        ensureTeachingProjection(actorUserId, workspaceId, idempotencyKey + "#submit", targetKey);
        // Cổng PDP của lượt nộp là nguồn ghi danh của chính actor trên môn–khóa của hoạt
        // động (scope read do projector giảng dạy chiếu cho học sinh đang ghi danh); hoạt
        // động cụ thể và lượt nộp được khóa tiếp bằng invariant và trigger của bảng mẫu.
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.CourseSubjectScope.READ.scopeName()));
        return executor.execute(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                () ->
                        Objects.requireNonNull(
                                workspaces.inWorkspace(
                                        workspaceId,
                                        () -> insertSubmission(workspaceId, actorUserId, request)),
                                "submission"));
    }

    private Map<String, Object> activityRow(UUID workspaceId, UUID activityId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT course_subject_id, attempt_limit, state FROM sample_activity"
                                + " WHERE workspace_id = ? AND id = ?",
                        workspaceId,
                        activityId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.getFirst();
    }

    private SampleActionResult insertSubmission(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request) {
        Map<String, Object> activity = activityRow(workspaceId, request.activityId());
        if (!"ASSIGNED".equals(activity.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "ACTIVITY_NOT_OPEN", "Hoạt động không còn nhận bài nộp.");
        }
        int attemptLimit =
                Objects.requireNonNull((Number) activity.get("attempt_limit"), "attemptLimit")
                        .intValue();
        Integer submitted =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sample_submission WHERE workspace_id = ?"
                                + " AND activity_id = ? AND student_user_id = ?",
                        Integer.class,
                        workspaceId,
                        request.activityId(),
                        actorUserId);
        int attemptNo = (submitted == null ? 0 : submitted) + 1;
        if (attemptNo > attemptLimit) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTEMPT_LIMIT_EXCEEDED",
                    "Đã hết số lượt nộp của hoạt động.");
        }
        UUID submissionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sample_submission(id, workspace_id, activity_id, student_user_id,"
                        + " attempt_no, submitted_at, content_stub, state)"
                        + " VALUES (?,?,?,?,?, now(), ?, 'SUBMITTED')",
                submissionId,
                workspaceId,
                request.activityId(),
                actorUserId,
                attemptNo,
                request.contentStub());
        return new SampleActionResult(submissionId, "SUCCEEDED", true, "SUBMISSION", submissionId);
    }

    // ---------- GRADE_RESULT / PUBLISH_RESULT ----------

    private SampleActionResult gradeResult(
            UUID workspaceId,
            String idempotencyKey,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resultId() == null
                || request.scoreStub() == null
                || request.scoreStub().isBlank()) {
            throw invalid();
        }
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey = NativePolicyTemplates.resultKey(workspaceId, request.resultId());
        ensureResultProjection(actorUserId, workspaceId, idempotencyKey + "#grade", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.ResultScope.GRADE.scopeName()));
        return executor.execute(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                () ->
                        Objects.requireNonNull(
                                workspaces.inWorkspace(
                                        workspaceId,
                                        () -> applyGrade(workspaceId, actorUserId, request)),
                                "grade"));
    }

    private SampleActionResult publishResult(
            UUID workspaceId,
            String idempotencyKey,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resultId() == null) {
            throw invalid();
        }
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey = NativePolicyTemplates.resultKey(workspaceId, request.resultId());
        ensureResultProjection(actorUserId, workspaceId, idempotencyKey + "#publish", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.ResultScope.PUBLISH.scopeName()));
        SampleActionResult result =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () -> applyPublish(workspaceId, request)),
                                        "publish"));
        // Nhánh đọc kết quả chỉ có hiệu lực khi graph đã chiếu trạng thái PUBLISHED:
        // ghi lại bundle kết quả và chờ chiếu xong trước khi báo thành công.
        ensureResultProjection(
                actorUserId, workspaceId, idempotencyKey + "#publish-sync", targetKey);
        return result;
    }

    private SampleActionResult applyGrade(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request) {
        Map<String, Object> result = resultRow(workspaceId, request.resultId());
        if (!"DRAFT".equals(result.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        jdbc.update(
                "UPDATE sample_result SET score_stub = ?, comment_stub = ?, graded_by = ?,"
                        + " updated_at = now() WHERE workspace_id = ? AND id = ?",
                request.scoreStub(),
                request.commentStub(),
                actorUserId,
                workspaceId,
                request.resultId());
        return new SampleActionResult(
                request.resultId(), "SUCCEEDED", true, "RESULT", request.resultId());
    }

    private SampleActionResult applyPublish(UUID workspaceId, SampleActionRequest request) {
        Map<String, Object> result = resultRow(workspaceId, request.resultId());
        if (!"DRAFT".equals(result.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        jdbc.update(
                "UPDATE sample_result SET state = 'PUBLISHED', published_at = now(),"
                        + " updated_at = now() WHERE workspace_id = ? AND id = ?",
                workspaceId,
                request.resultId());
        return new SampleActionResult(
                request.resultId(), "SUCCEEDED", true, "RESULT", request.resultId());
    }

    private Map<String, Object> resultRow(UUID workspaceId, UUID resultId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT state FROM sample_result WHERE workspace_id = ? AND id = ?",
                        workspaceId,
                        resultId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.getFirst();
    }

    // ---------- Ghi projection trước khi hỏi PDP ----------

    /** Ghi lại bundle giảng dạy hiện hành của target qua operation dùng chung và chờ chiếu xong. */
    private void ensureTeachingProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String targetKey) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        coordinator.recordTeachingRevocation(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKey),
                                List.of(targetKey)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    /** Ghi lại bundle kết quả hiện hành của target qua operation dùng chung và chờ chiếu xong. */
    private void ensureResultProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String targetKey) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        resultProjector.recordResultChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKey),
                                List.of(targetKey)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    private String projectionPayloadHash(UUID workspaceId, String targetKey) {
        return idempotency.payloadHash(
                "POST",
                "/api/workspaces/" + workspaceId + "/samples/actions",
                JSON.createObjectNode().put("projectionTarget", targetKey));
    }

    private void awaitProjection(AuthorizationOperation operation) {
        long deadline = System.nanoTime() + PROJECTION_WAIT.toNanos();
        while (true) {
            String state =
                    jdbc.queryForObject(
                            "SELECT state FROM authorization_operation WHERE id = ?",
                            String.class,
                            operation.getId());
            if (OPERATION_SUCCEEDED.equals(state)) {
                return;
            }
            if (OPERATION_FAILED.equals(state) || System.nanoTime() > deadline) {
                throw AuthorizationFence.notReady();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw AuthorizationFence.notReady(ex);
            }
        }
    }

    // ---------- Lỗi dùng chung ----------

    private static HttpProblemException unsupported() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_SAMPLE_ACTION",
                "Hành động mẫu chưa được hỗ trợ.");
    }

    private static HttpProblemException invalid() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SAMPLE_ACTION",
                "Hành động mẫu thiếu trường bắt buộc.");
    }

    private static HttpProblemException notFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    }
}
