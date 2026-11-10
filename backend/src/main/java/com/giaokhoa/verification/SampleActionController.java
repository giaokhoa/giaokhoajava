package com.giaokhoa.verification;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.resources.CorrectionService;
import com.giaokhoa.resources.ResourceLibraryService;
import com.giaokhoa.resources.ResourceShareService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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
 * thì fail-closed 503 như mọi protected request khác. Các action thư viện SAVE_VERSION,
 * FORK_VERSION, RECEIVE_UPDATE và hai route đọc thân bài/đáp án của resource version uỷ cho {@link
 * ResourceLibraryService}, nơi giữ pipeline và giao thức biên nhận của chúng; các action chia sẻ
 * SHARE_VERSION, ACCEPT_SHARE, REVOKE_SHARE uỷ cho {@link ResourceShareService} theo cùng mẫu;
 * action phát hành đính chính CORRECT_SOURCE uỷ cho {@link CorrectionService} và mang metadata của
 * notice trong kết quả để phía gọi hiển thị đúng thông báo vừa phát hành. Action lạ bị từ chối,
 * không bao giờ được lặng lẽ cho qua. Mọi action đều ghi biên nhận idempotency của chính mình trong
 * cùng transaction với mutation (bảng sample_action_mutation, theo tiền lệ recovery_mutation):
 * retry cùng key và cùng payload trả lại kết quả đầu tiên thay vì ghi thêm, cùng key khác payload
 * trả 409 như quy tắc mutation chung của hợp đồng HTTP.
 */
@RestController
public class SampleActionController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String OPERATION_FAILED = "FAILED";
    private static final int SINGLE_ROW = 1;

    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final SourceRevocationCoordinator coordinator;
    private final ResultPolicyProjector resultProjector;
    private final ResourceLibraryService library;
    private final ResourceShareService shareService;
    private final CorrectionService correctionService;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    public SampleActionController(
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            SourceRevocationCoordinator coordinator,
            ResultPolicyProjector resultProjector,
            ResourceLibraryService library,
            ResourceShareService shareService,
            CorrectionService correctionService,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.resultProjector = resultProjector;
        this.library = library;
        this.shareService = shareService;
        this.correctionService = correctionService;
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
            String commentStub,
            UUID libraryItemId,
            UUID replacementVersionId,
            UUID grantId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            OffsetDateTime expiresAt,
            UUID affectedVersionId,
            String reason) {
        // Sao chép phòng thủ theo mẫu CapabilitiesController: hai danh sách là dữ kiện đầu vào
        // từ JSON, không cho caller giữ tham chiếu mutable vào trạng thái nội bộ của request.
        // Chuẩn hoá null về danh sách rỗng: controller từ chối null và rỗng giống nhau
        // ở shareVersion nên không đổi hành vi quan sát được.
        public SampleActionRequest {
            actions = actions == null ? List.of() : List.copyOf(actions);
            allowedWorkspaceIds =
                    allowedWorkspaceIds == null ? List.of() : List.copyOf(allowedWorkspaceIds);
        }
    }

    public record SampleActionResult(
            UUID id,
            String state,
            boolean completed,
            String targetType,
            UUID targetId,
            CorrectionService.@Nullable CorrectionNoticeView correctionNotice) {}

    /** Thân bài của một resource version: đáp án không bao giờ nằm trong đáp án của route này. */
    public record ResourceBodyView(UUID resourceId, UUID versionId, String body) {}

    /** Đáp án của một resource version qua route riêng với scope riêng. */
    public record ResourceAnswerView(UUID resourceId, UUID versionId, @Nullable String answer) {}

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
        String payloadHash = actionPayloadHash(workspaceId, request);
        SampleActionResult result =
                switch (request.action()) {
                    case "CREATE_RESOURCE" ->
                            createResource(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "ASSIGN_ACTIVITY" ->
                            assignActivity(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "SUBMIT_ACTIVITY" ->
                            submitActivity(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "GRADE_RESULT" ->
                            gradeResult(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "PUBLISH_RESULT" ->
                            publishResult(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "SAVE_VERSION" ->
                            saveVersion(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "FORK_VERSION" ->
                            forkVersion(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "RECEIVE_UPDATE" ->
                            receiveUpdate(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "SHARE_VERSION" ->
                            shareVersion(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "ACCEPT_SHARE" ->
                            acceptShare(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "REVOKE_SHARE" ->
                            revokeShare(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
                                    request,
                                    authentication,
                                    httpRequest);
                    case "CORRECT_SOURCE" ->
                            correctSource(
                                    workspaceId,
                                    idempotencyKey,
                                    payloadHash,
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
            String idempotencyKey,
            String payloadHash,
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
                ? createForCourseSubject(
                        workspaceId,
                        idempotencyKey,
                        payloadHash,
                        request,
                        authentication,
                        httpRequest)
                : createForPersonalOwner(
                        workspaceId, idempotencyKey, payloadHash, authentication, httpRequest);
    }

    private SampleActionResult createForCourseSubject(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID actorUserId = executor.actorUserId(authentication);
        var target =
                new ProtectedActionExecutor.Target(
                        NativePolicyTemplates.courseSubjectKey(
                                workspaceId, request.courseSubjectId()),
                        List.of(PermissionCatalog.CourseSubjectScope.RESOURCE_CREATE.scopeName()));
        return executor.execute(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                () -> stubResult(actorUserId, workspaceId, idempotencyKey, payloadHash));
    }

    private SampleActionResult createForPersonalOwner(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            Authentication authentication,
            HttpServletRequest httpRequest) {
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
                    return stubResult(actorUserId, workspaceId, idempotencyKey, payloadHash);
                });
    }

    private SampleActionResult stubResult(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String payloadHash) {
        UUID id = UUID.randomUUID();
        return claimActionReceipt(
                        actorUserId, workspaceId, idempotencyKey, payloadHash, "RESOURCE", id)
                .orElseGet(() ->
                        new SampleActionResult(
                                id, OPERATION_SUCCEEDED, true, "RESOURCE", id, null));
    }

    // ---------- ASSIGN_ACTIVITY / SUBMIT_ACTIVITY ----------

    private SampleActionResult assignActivity(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
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
                                        () -> {
                                            UUID activityId = UUID.randomUUID();
                                            return claimActionReceipt(
                                                            actorUserId,
                                                            workspaceId,
                                                            idempotencyKey,
                                                            payloadHash,
                                                            "ACTIVITY",
                                                            activityId)
                                                    .orElseGet(() ->
                                                            insertActivity(
                                                                    workspaceId,
                                                                    actorUserId,
                                                                    request,
                                                                    activityId));
                                        }),
                                "activity"));
    }

    private SampleActionResult insertActivity(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request, UUID activityId) {
        List<Map<String, Object>> subjects =
                jdbc.queryForList(
                        "SELECT cs.subject_id, cs.state AS subject_state, c.state AS class_state,"
                                + " co.state AS course_state FROM course_subject cs"
                                + " JOIN school_class c ON c.workspace_id = cs.workspace_id"
                                + " AND c.id = cs.class_id"
                                + " JOIN course co ON co.workspace_id = cs.workspace_id"
                                + " AND co.id = cs.course_id"
                                + " WHERE cs.workspace_id = ? AND cs.id = ?",
                        workspaceId,
                        request.courseSubjectId());
        if (subjects.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> subject = subjects.getFirst();
        // Cùng mẫu AssignmentService: chỉ giao bài khi môn–khóa và lớp/khóa chứa nó còn hiệu lực.
        String subjectState = (String) subject.get("subject_state");
        if (!"PENDING".equals(subjectState) && !"ACTIVE".equals(subjectState)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_SUBJECT_NOT_ACTIVE",
                    "Môn–khóa đã bị gỡ khỏi khóa, không giao bài được nữa.");
        }
        if ("REVOKED".equals(subject.get("class_state"))
                || "REVOKED".equals(subject.get("course_state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_CONTEXT_NOT_ACTIVE",
                    "Lớp hoặc khóa của môn–khóa đã bị thu hồi, không giao bài được nữa.");
        }
        UUID subjectId = (UUID) subject.get("subject_id");
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
        return new SampleActionResult(
                activityId, OPERATION_SUCCEEDED, true, "ACTIVITY", activityId, null);
    }

    private SampleActionResult submitActivity(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
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
                                        () -> {
                                            UUID submissionId = UUID.randomUUID();
                                            return claimActionReceipt(
                                                            actorUserId,
                                                            workspaceId,
                                                            idempotencyKey,
                                                            payloadHash,
                                                            "SUBMISSION",
                                                            submissionId)
                                                    .orElseGet(() ->
                                                            insertSubmission(
                                                                    workspaceId,
                                                                    actorUserId,
                                                                    request,
                                                                    submissionId));
                                        }),
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
            UUID workspaceId, UUID actorUserId, SampleActionRequest request, UUID submissionId) {
        Map<String, Object> activity = activityRow(workspaceId, request.activityId());
        if (!"ASSIGNED".equals(activity.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "ACTIVITY_NOT_OPEN", "Hoạt động không còn nhận bài nộp.");
        }
        // Tiền kiểm tra đúng điều kiện của trigger validate_sample_submission: actor phải còn
        // ghi danh hiện hành vào môn–khóa của hoạt động, nếu không lỗi là 403 thay vì 409 của DB.
        Integer enrolled =
                jdbc.queryForObject(
                        "SELECT count(*) FROM enrollment e"
                                + " JOIN class_student cs ON cs.id = e.class_student_id"
                                + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                                + " AND e.state IN ('PENDING', 'ACTIVE')"
                                + " AND cs.workspace_id = ? AND cs.student_user_id = ?",
                        Integer.class,
                        workspaceId,
                        activity.get("course_subject_id"),
                        workspaceId,
                        actorUserId);
        if (enrolled == null || enrolled == 0) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Không có quyền thực hiện thao tác.");
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
        return new SampleActionResult(
                submissionId, OPERATION_SUCCEEDED, true, "SUBMISSION", submissionId, null);
    }

    // ---------- GRADE_RESULT / PUBLISH_RESULT ----------

    private SampleActionResult gradeResult(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
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
                                        () ->
                                                claimActionReceipt(
                                                                actorUserId,
                                                                workspaceId,
                                                                idempotencyKey,
                                                                payloadHash,
                                                                "RESULT",
                                                                request.resultId())
                                                        .orElseGet(() ->
                                                                applyGrade(
                                                                        workspaceId,
                                                                        actorUserId,
                                                                        request))),
                                "grade"));
    }

    private SampleActionResult publishResult(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
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
                                                () ->
                                                        claimActionReceipt(
                                                                        actorUserId,
                                                                        workspaceId,
                                                                        idempotencyKey,
                                                                        payloadHash,
                                                                        "RESULT",
                                                                        request.resultId())
                                                                .orElseGet(() ->
                                                                        applyPublish(
                                                                                workspaceId,
                                                                                request))),
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
        int graded =
                jdbc.update(
                        "UPDATE sample_result SET score_stub = ?, comment_stub = ?, graded_by = ?,"
                                + " version = version + 1, updated_at = now()"
                                + " WHERE workspace_id = ? AND id = ? AND state = 'DRAFT'",
                        request.scoreStub(),
                        request.commentStub(),
                        actorUserId,
                        workspaceId,
                        request.resultId());
        if (graded != SINGLE_ROW) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        return new SampleActionResult(
                request.resultId(), OPERATION_SUCCEEDED, true, "RESULT", request.resultId(), null);
    }

    private SampleActionResult applyPublish(UUID workspaceId, SampleActionRequest request) {
        Map<String, Object> result = resultRow(workspaceId, request.resultId());
        if (!"DRAFT".equals(result.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        int published =
                jdbc.update(
                        "UPDATE sample_result SET state = 'PUBLISHED', published_at = now(),"
                                + " version = version + 1, updated_at = now()"
                                + " WHERE workspace_id = ? AND id = ? AND state = 'DRAFT'",
                        workspaceId,
                        request.resultId());
        if (published != SINGLE_ROW) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        return new SampleActionResult(
                request.resultId(), OPERATION_SUCCEEDED, true, "RESULT", request.resultId(), null);
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

    // ---------- Thư viện resource: lưu, fork, nhận cập nhật và đọc contextual ----------

    private SampleActionResult saveVersion(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resourceId() == null || request.versionId() == null) {
            throw invalid();
        }
        UUID itemId =
                library.saveVersion(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.resourceId(),
                        request.versionId(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                itemId, OPERATION_SUCCEEDED, true, "LIBRARY_ITEM", itemId, null);
    }

    private SampleActionResult forkVersion(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resourceId() == null
                || request.versionId() == null
                || request.label() == null
                || request.label().isBlank()) {
            throw invalid();
        }
        UUID forkResourceId =
                library.forkVersion(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.resourceId(),
                        request.versionId(),
                        request.label(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                forkResourceId, OPERATION_SUCCEEDED, true, "RESOURCE", forkResourceId, null);
    }

    private SampleActionResult receiveUpdate(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.libraryItemId() == null || request.replacementVersionId() == null) {
            throw invalid();
        }
        UUID itemId =
                library.receiveUpdate(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.libraryItemId(),
                        request.replacementVersionId(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                itemId, OPERATION_SUCCEEDED, true, "LIBRARY_ITEM", itemId, null);
    }

    // ---------- Chia sẻ resource: cấp, nhận và thu hồi lượt cấp ----------

    private SampleActionResult shareVersion(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resourceId() == null
                || request.versionId() == null
                || request.recipientWorkspaceId() == null
                || request.mode() == null
                || request.mode().isBlank()
                || request.actions() == null
                || request.actions().isEmpty()) {
            throw invalid();
        }
        UUID grantId =
                shareService.shareVersion(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.resourceId(),
                        request.versionId(),
                        request.recipientWorkspaceId(),
                        request.mode(),
                        request.actions(),
                        request.allowedWorkspaceIds() == null
                                ? List.of()
                                : request.allowedWorkspaceIds(),
                        request.expiresAt(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                grantId, OPERATION_SUCCEEDED, true, "SHARE_GRANT", grantId, null);
    }

    private SampleActionResult acceptShare(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.grantId() == null) {
            throw invalid();
        }
        UUID grantId =
                shareService.acceptShare(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.grantId(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                grantId, OPERATION_SUCCEEDED, true, "SHARE_GRANT", grantId, null);
    }

    private SampleActionResult revokeShare(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.grantId() == null) {
            throw invalid();
        }
        UUID grantId =
                shareService.revokeShare(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.grantId(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                grantId, OPERATION_SUCCEEDED, true, "SHARE_GRANT", grantId, null);
    }

    // ---------- Đính chính nguồn: phát hành correction và thông báo nơi nhận ----------

    private SampleActionResult correctSource(
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request.resourceId() == null
                || request.affectedVersionId() == null
                || request.replacementVersionId() == null
                || request.reason() == null
                || request.reason().isBlank()) {
            throw invalid();
        }
        CorrectionService.CorrectionNoticeView notice =
                correctionService.correctSource(
                        authentication,
                        httpRequest,
                        workspaceId,
                        request.resourceId(),
                        request.affectedVersionId(),
                        request.replacementVersionId(),
                        request.reason(),
                        idempotencyKey,
                        payloadHash);
        return new SampleActionResult(
                notice.id(), OPERATION_SUCCEEDED, true, "CORRECTION_NOTICE", notice.id(), notice);
    }

    @GetMapping("/api/workspaces/{workspaceId}/samples/resources/{resourceId}/versions/{versionId}")
    public ResponseEntity<ResourceBodyView> readResourceBody(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @PathVariable UUID versionId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String body =
                library.readBody(authentication, httpRequest, workspaceId, resourceId, versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ResourceBodyView(resourceId, versionId, body));
    }

    @GetMapping(
            "/api/workspaces/{workspaceId}/samples/resources/{resourceId}"
                    + "/versions/{versionId}/answer")
    public ResponseEntity<ResourceAnswerView> readResourceAnswer(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @PathVariable UUID versionId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String answer =
                library.readAnswer(authentication, httpRequest, workspaceId, resourceId, versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ResourceAnswerView(resourceId, versionId, answer));
    }

    // ---------- Biên nhận idempotency của chính action ----------

    private String actionPayloadHash(UUID workspaceId, SampleActionRequest request) {
        return idempotency.payloadHash(
                "POST",
                "/api/workspaces/" + workspaceId + "/samples/actions",
                JSON.convertValue(request, tools.jackson.databind.JsonNode.class));
    }

    /**
     * Chèn biên nhận của action trong cùng transaction với mutation, theo đúng mẫu {@code
     * RecoveryService.replay}: chèn được thì biên nhận chưa tồn tại và mutation chạy tiếp; đã có
     * biên nhận cùng payload thì trả kết quả đầu tiên thay vì ghi lại; khác payload thì 409.
     * Mutation ném lỗi sẽ rollback cả biên nhận nên một lần thử thất bại không đốt key.
     */
    private Optional<SampleActionResult> claimActionReceipt(
            UUID actorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            String targetType,
            UUID targetId) {
        int inserted =
                jdbc.update(
                        "INSERT INTO sample_action_mutation(actor_user_id, idempotency_key,"
                                + " payload_hash, workspace_id, target_type, target_id)"
                                + " VALUES (?,?,?,?,?,?)"
                                + " ON CONFLICT (actor_user_id, idempotency_key) DO NOTHING",
                        actorUserId,
                        idempotencyKey,
                        payloadHash,
                        workspaceId,
                        targetType,
                        targetId);
        if (inserted != 0) {
            return Optional.empty();
        }
        Map<String, Object> previous =
                jdbc.queryForMap(
                        "SELECT payload_hash, target_type, target_id FROM sample_action_mutation"
                                + " WHERE actor_user_id = ? AND idempotency_key = ?",
                        actorUserId,
                        idempotencyKey);
        if (!payloadHash.equals(previous.get("payload_hash"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        UUID previousId = (UUID) Objects.requireNonNull(previous.get("target_id"), "target_id");
        String previousType =
                Objects.requireNonNull((String) previous.get("target_type"), "target_type");
        return Optional.of(
                new SampleActionResult(
                        previousId, OPERATION_SUCCEEDED, true, previousType, previousId, null));
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
