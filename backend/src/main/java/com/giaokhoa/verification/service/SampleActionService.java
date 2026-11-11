package com.giaokhoa.verification.service;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.CorrectionService;
import com.giaokhoa.resources.ResourceLibraryService;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.ResourceShareService;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.ActivityPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import com.giaokhoa.verification.dto.CourseSubjectStateView;
import com.giaokhoa.verification.dto.SampleActionRequest;
import com.giaokhoa.verification.dto.SampleActionResult;
import com.giaokhoa.verification.model.SampleActionMutation;
import com.giaokhoa.verification.model.SampleActivity;
import com.giaokhoa.verification.model.SampleResult;
import com.giaokhoa.verification.model.SampleSubmission;
import com.giaokhoa.verification.repository.SampleActionMutationRepository;
import com.giaokhoa.verification.repository.SampleActivityRepository;
import com.giaokhoa.verification.repository.SampleResultRepository;
import com.giaokhoa.verification.repository.SampleSubmissionRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationOperationRepository;
import com.giaokhoa.verification.repository.VerificationCourseSubjectRepository;
import com.giaokhoa.verification.repository.VerificationEnrollmentRepository;
import com.giaokhoa.verification.repository.VerificationResourceDependencyRepository;
import com.giaokhoa.verification.repository.VerificationResourceVersionRepository;
import com.giaokhoa.verification.repository.VerificationSubscriptionPeriodRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nghiệp vụ của các sample action trong hợp đồng verification. Mọi action chạy qua cùng pipeline
 * manifest/fence/PDP như business action và không bao giờ chấp nhận actor thay thế. CREATE_RESOURCE
 * trả kết quả stub vì chưa có bảng sample-resource. ASSIGN_ACTIVITY, SUBMIT_ACTIVITY, GRADE_RESULT
 * và PUBLISH_RESULT ghi thật vào các bảng mẫu với target và scope đúng hợp đồng HTTP: giao bài còn
 * đòi quyết định {@code use} trên chính version được giao và mọi version thành phần trong closure
 * phụ thuộc đệ quy của nó (FR-041), và nộp bài được quyết bằng scope {@code submit} trên target
 * hoạt động do {@link ActivityPolicyProjector} chiếu cho học sinh đang ghi danh ở môn–khóa của hoạt
 * động; dữ kiện nguồn của một target có thể chưa từng được chiếu khi chuỗi được seed thẳng, nên
 * trước khi hỏi PDP mỗi action ghi lại bundle hiện hành của target qua cơ chế operation dùng chung
 * và chờ chiếu xong — chờ không xong thì fail-closed 503 như mọi protected request khác. Các action
 * thư viện SAVE_VERSION, FORK_VERSION, RECEIVE_UPDATE uỷ cho {@link ResourceLibraryService}, nơi
 * giữ pipeline và giao thức biên nhận của chúng; các action chia sẻ SHARE_VERSION, ACCEPT_SHARE,
 * REVOKE_SHARE uỷ cho {@link ResourceShareService} theo cùng mẫu; action phát hành đính chính
 * CORRECT_SOURCE uỷ cho {@link CorrectionService} và mang metadata của notice trong kết quả để phía
 * gọi hiển thị đúng thông báo vừa phát hành. Action lạ bị từ chối, không bao giờ được lặng lẽ cho
 * qua. Mọi action đều ghi biên nhận idempotency của chính mình trong cùng transaction với mutation
 * (bảng sample_action_mutation, theo tiền lệ recovery_mutation): retry cùng key và cùng payload trả
 * lại kết quả đầu tiên thay vì ghi thêm, cùng key khác payload trả 409 như quy tắc mutation chung
 * của hợp đồng HTTP.
 *
 * <p>Tầng dữ liệu của service chỉ đi qua các Spring Data repository: bảng mẫu qua repository của
 * chính module, dữ kiện của module khác (workspace, kỳ gói, môn–khóa, version, ghi danh) qua
 * repository trên entity của module sở tại hoặc entity chuyển tiếp của module đó.
 */
@Service
public class SampleActionService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String RESULT_TARGET = "RESULT";

    private final ProtectedActionExecutor executor;
    private final IdempotencyService idempotency;
    private final SourceRevocationCoordinator coordinator;
    private final ResultPolicyProjector resultProjector;
    private final ActivityPolicyProjector activityProjector;
    private final ResourcePolicyProjector resourceProjector;
    private final ResourceLibraryService library;
    private final ResourceShareService shareService;
    private final CorrectionService correctionService;
    private final WorkspaceTransaction workspaces;
    private final VerificationWorkspaceRepository workspaceDirectory;
    private final VerificationSubscriptionPeriodRepository periods;
    private final VerificationCourseSubjectRepository courseSubjects;
    private final VerificationResourceVersionRepository resourceVersions;
    private final VerificationResourceDependencyRepository resourceDependencies;
    private final VerificationEnrollmentRepository enrollments;
    private final SampleActivityRepository activities;
    private final SampleSubmissionRepository submissions;
    private final SampleResultRepository results;
    private final SampleActionMutationRepository mutations;
    private final VerificationAuthorizationOperationRepository operations;
    private final TransactionTemplate transactions;

    public SampleActionService(
            ProtectedActionExecutor executor,
            IdempotencyService idempotency,
            SourceRevocationCoordinator coordinator,
            ResultPolicyProjector resultProjector,
            ActivityPolicyProjector activityProjector,
            ResourcePolicyProjector resourceProjector,
            ResourceLibraryService library,
            ResourceShareService shareService,
            CorrectionService correctionService,
            WorkspaceTransaction workspaces,
            VerificationWorkspaceRepository workspaceDirectory,
            VerificationSubscriptionPeriodRepository periods,
            VerificationCourseSubjectRepository courseSubjects,
            VerificationResourceVersionRepository resourceVersions,
            VerificationResourceDependencyRepository resourceDependencies,
            VerificationEnrollmentRepository enrollments,
            SampleActivityRepository activities,
            SampleSubmissionRepository submissions,
            SampleResultRepository results,
            SampleActionMutationRepository mutations,
            VerificationAuthorizationOperationRepository operations,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.coordinator = coordinator;
        this.resultProjector = resultProjector;
        this.activityProjector = activityProjector;
        this.resourceProjector = resourceProjector;
        this.library = library;
        this.shareService = shareService;
        this.correctionService = correctionService;
        this.workspaces = workspaces;
        this.workspaceDirectory = workspaceDirectory;
        this.periods = periods;
        this.courseSubjects = courseSubjects;
        this.resourceVersions = resourceVersions;
        this.resourceDependencies = resourceDependencies;
        this.enrollments = enrollments;
        this.activities = activities;
        this.submissions = submissions;
        this.results = results;
        this.mutations = mutations;
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Phân tuyến một sample action theo tên; action lạ bị từ chối ngay. */
    public SampleActionResult act(
            UUID workspaceId,
            String idempotencyKey,
            SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        idempotency.requireKey(idempotencyKey);
        if (request == null || request.action() == null) {
            throw unsupported();
        }
        String payloadHash = actionPayloadHash(workspaceId, request);
        return switch (request.action()) {
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
        Workspace workspace =
                workspaceDirectory.findById(workspaceId).orElseThrow(() -> notFound());
        if (workspace.getKind() != Workspace.Kind.PERSONAL
                || !actorUserId.equals(workspace.getOwnerUserId())) {
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
                    if (periods.countCurrent(workspaceId) == 0) {
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
        List<String> versionTargetKeys =
                versionUseTargetKeys(workspaceId, request.resourceId(), request.versionId());
        ensureResourceProjection(
                actorUserId, workspaceId, idempotencyKey + "#assign-use", versionTargetKeys);
        List<ProtectedActionExecutor.Target> targets = new ArrayList<>();
        targets.add(
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName())));
        for (String versionTargetKey : versionTargetKeys) {
            targets.add(
                    new ProtectedActionExecutor.Target(
                            versionTargetKey,
                            List.of(PermissionCatalog.ResourceVersionScope.USE.scopeName())));
        }
        SampleActionResult result =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        targets,
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
        // Hoạt động ASSIGNED vừa ghi là một nguồn của bundle của chính version được giao
        // (nhánh học sinh đọc nội dung của ResourcePolicyProjector), nên bundle ghi trước
        // quyết định không còn khớp nguồn; ghi lại bundle sau khi hoạt động đã bền để các
        // lượt reconcile sau của partition không lệch hash.
        ensureResourceProjection(
                actorUserId,
                workspaceId,
                idempotencyKey + "#assign-use-refresh",
                versionTargetKeys);
        return result;
    }

    /**
     * Target {@code use} của lượt giao: chính version được giao và mọi version thành phần trong
     * closure phụ thuộc đệ quy của nó (cạnh {@code resource_dependency} từ bản phái sinh về nguồn),
     * tất cả tại workspace của route — quyết định dùng một version tổng hợp phải thỏa quyền dùng
     * của từng thành phần cần dùng theo FR-041.
     */
    private List<String> versionUseTargetKeys(UUID workspaceId, UUID resourceId, UUID versionId) {
        List<String> targetKeys = new ArrayList<>();
        targetKeys.add(
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId));
        targetKeys.addAll(
                Objects.requireNonNull(
                        workspaces.inWorkspace(
                                workspaceId, () -> closureComponentKeys(workspaceId, versionId)),
                        "components"));
        return targetKeys;
    }

    /** Closure duyệt theo tầng qua repository với tập đã thăm chống vòng, như module resources. */
    private List<String> closureComponentKeys(UUID workspaceId, UUID versionId) {
        List<String> keys = new ArrayList<>();
        Set<UUID> visited = new LinkedHashSet<>();
        visited.add(versionId);
        List<UUID> frontier = List.of(versionId);
        while (!frontier.isEmpty()) {
            List<UUID> next = new ArrayList<>();
            for (ResourceDependency dependency :
                    resourceDependencies.findByDerivedVersionIdIn(frontier)) {
                if (visited.add(dependency.getSourceVersionId())) {
                    next.add(dependency.getSourceVersionId());
                    keys.add(
                            NativePolicyTemplates.resourceVersionKey(
                                    workspaceId,
                                    dependency.getSourceResourceId(),
                                    dependency.getSourceVersionId()));
                }
            }
            frontier = next;
        }
        return keys;
    }

    private SampleActionResult insertActivity(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request, UUID activityId) {
        CourseSubjectStateView subject =
                courseSubjects
                        .findStateView(workspaceId, request.courseSubjectId())
                        .orElseThrow(() -> notFound());
        // Cùng mẫu AssignmentService: chỉ giao bài khi môn–khóa và lớp/khóa chứa nó còn hiệu lực.
        if (!"PENDING".equals(subject.subjectState()) && !"ACTIVE".equals(subject.subjectState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "COURSE_SUBJECT_NOT_ACTIVE",
                    "Môn–khóa đã bị gỡ khỏi khóa, không giao bài được nữa.");
        }
        if ("REVOKED".equals(subject.classState()) || "REVOKED".equals(subject.courseState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "TEACHING_CONTEXT_NOT_ACTIVE",
                    "Lớp hoặc khóa của môn–khóa đã bị thu hồi, không giao bài được nữa.");
        }
        // Invariant của hợp đồng giao bài: version thuộc đúng resource, resource thuộc đúng
        // workspace và cùng môn với môn–khóa, và version đã ở trạng thái duyệt.
        long usable =
                resourceVersions.countUsableVersion(
                        request.versionId(),
                        request.resourceId(),
                        workspaceId,
                        subject.subjectId(),
                        ResourceVersion.ReviewState.APPROVED);
        if (usable == 0) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SAMPLE_ACTION",
                    "Version không thuộc resource của workspace hoặc chưa được duyệt.");
        }
        List<UUID> currentPeriods = periods.findCurrentPeriodIds(workspaceId);
        if (currentPeriods.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "SUBSCRIPTION_INACTIVE",
                    "Gói sử dụng không còn hiệu lực.");
        }
        activities.save(
                new SampleActivity(
                        activityId,
                        workspaceId,
                        request.courseSubjectId(),
                        request.resourceId(),
                        request.versionId(),
                        actorUserId,
                        Instant.now(),
                        request.dueAt().toInstant(),
                        request.attemptLimit(),
                        currentPeriods.getFirst(),
                        "ASSIGNED"));
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
        // Đọc dòng hoạt động trong context của workspace trước quyết định: hoạt động không
        // thuộc workspace của route thì 404, không lộ qua quyết định PDP trên target lạ.
        Objects.requireNonNull(
                workspaces.inWorkspace(
                        workspaceId, () -> activityRow(workspaceId, request.activityId())),
                "activity");
        String targetKey = NativePolicyTemplates.activityKey(workspaceId, request.activityId());
        ensureActivityProjection(actorUserId, workspaceId, idempotencyKey + "#submit", targetKey);
        // Cổng PDP của lượt nộp là scope submit trên chính target hoạt động, do
        // ActivityPolicyProjector chiếu cho học sinh đang ghi danh ở môn–khóa của hoạt
        // động; hoạt động cụ thể và lượt nộp được khóa tiếp bằng invariant và trigger
        // của bảng mẫu.
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.ActivityScope.SUBMIT.scopeName()));
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

    private SampleActivity activityRow(UUID workspaceId, UUID activityId) {
        return activities
                .findByWorkspaceIdAndId(workspaceId, activityId)
                .orElseThrow(() -> notFound());
    }

    private SampleActionResult insertSubmission(
            UUID workspaceId, UUID actorUserId, SampleActionRequest request, UUID submissionId) {
        SampleActivity activity = activityRow(workspaceId, request.activityId());
        if (!"ASSIGNED".equals(activity.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "ACTIVITY_NOT_OPEN", "Hoạt động không còn nhận bài nộp.");
        }
        // Tiền kiểm tra đúng điều kiện của trigger validate_sample_submission: actor phải còn
        // ghi danh hiện hành vào môn–khóa của hoạt động, nếu không lỗi là 403 thay vì 409 của DB.
        long enrolled =
                enrollments.countCurrentEnrollment(
                        workspaceId, activity.getCourseSubjectId(), actorUserId);
        if (enrolled == 0) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "Không có quyền thực hiện thao tác.");
        }
        long submitted =
                submissions.countByWorkspaceIdAndActivityIdAndStudentUserId(
                        workspaceId, request.activityId(), actorUserId);
        int attemptNo = (int) submitted + 1;
        if (attemptNo > activity.getAttemptLimit()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTEMPT_LIMIT_EXCEEDED",
                    "Đã hết số lượt nộp của hoạt động.");
        }
        submissions.save(
                new SampleSubmission(
                        submissionId,
                        workspaceId,
                        request.activityId(),
                        actorUserId,
                        attemptNo,
                        Instant.now(),
                        request.contentStub(),
                        "SUBMITTED"));
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
                                                                RESULT_TARGET,
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
                                                                        RESULT_TARGET,
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
        SampleResult result = resultForUpdate(workspaceId, request.resultId());
        if (!"DRAFT".equals(result.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        result.grade(request.scoreStub(), request.commentStub(), actorUserId);
        results.flush();
        return new SampleActionResult(
                request.resultId(),
                OPERATION_SUCCEEDED,
                true,
                RESULT_TARGET,
                request.resultId(),
                null);
    }

    private SampleActionResult applyPublish(UUID workspaceId, SampleActionRequest request) {
        SampleResult result = resultForUpdate(workspaceId, request.resultId());
        if (!"DRAFT".equals(result.getState())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "RESULT_NOT_DRAFT", "Kết quả đã công bố không sửa được.");
        }
        result.publish(Instant.now());
        results.flush();
        return new SampleActionResult(
                request.resultId(),
                OPERATION_SUCCEEDED,
                true,
                RESULT_TARGET,
                request.resultId(),
                null);
    }

    private SampleResult resultForUpdate(UUID workspaceId, UUID resultId) {
        return results.findByWorkspaceIdAndIdForUpdate(workspaceId, resultId)
                .orElseThrow(() -> notFound());
    }

    // ---------- Thư viện resource: lưu, fork, nhận cập nhật ----------

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
                mutations.claim(
                        actorUserId,
                        idempotencyKey,
                        payloadHash,
                        workspaceId,
                        targetType,
                        targetId);
        if (inserted != 0) {
            return Optional.empty();
        }
        SampleActionMutation previous =
                mutations
                        .findById(new SampleActionMutation.Id(actorUserId, idempotencyKey))
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Sample action receipt vanished after claim: "
                                                + idempotencyKey));
        if (!payloadHash.equals(previous.getPayloadHash())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of(
                new SampleActionResult(
                        previous.getTargetId(),
                        OPERATION_SUCCEEDED,
                        true,
                        previous.getTargetType(),
                        previous.getTargetId(),
                        null));
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
        awaitRecordedProjection(operation);
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
        awaitRecordedProjection(operation);
    }

    /** Ghi lại bundle hoạt động hiện hành của target qua operation dùng chung và chờ chiếu xong. */
    private void ensureActivityProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String targetKey) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        activityProjector.recordActivityChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKey),
                                List.of(targetKey)));
        awaitRecordedProjection(operation);
    }

    /** Ghi lại bundle resource version hiện hành của các target và chờ chiếu xong. */
    private void ensureResourceProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, List<String> targetKeys) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        resourceProjector.recordResourceChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKeys),
                                targetKeys));
        awaitRecordedProjection(operation);
    }

    private String projectionPayloadHash(UUID workspaceId, List<String> targetKeys) {
        var node = JSON.createObjectNode();
        var targets = node.putArray("projectionTargets");
        targetKeys.forEach(targets::add);
        return idempotency.payloadHash(
                "POST", "/api/workspaces/" + workspaceId + "/samples/actions", node);
    }

    private String projectionPayloadHash(UUID workspaceId, String targetKey) {
        return idempotency.payloadHash(
                "POST",
                "/api/workspaces/" + workspaceId + "/samples/actions",
                JSON.createObjectNode().put("projectionTarget", targetKey));
    }

    /** Chờ operation ghi projection hoàn tất chiếu; operation không được null. */
    private void awaitRecordedProjection(AuthorizationOperation operation) {
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    private void awaitProjection(AuthorizationOperation operation) {
        long deadline = System.nanoTime() + PROJECTION_WAIT.toNanos();
        while (true) {
            AuthorizationOperation.State state =
                    operations.findStateById(operation.getId()).orElse(null);
            if (state == AuthorizationOperation.State.SUCCEEDED) {
                return;
            }
            if (state == AuthorizationOperation.State.FAILED || System.nanoTime() > deadline) {
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
