package com.giaokhoa.verification.web;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceAssignment;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.model.Subject;
import com.giaokhoa.tenancy.WorkspaceDirectoryService;
import com.giaokhoa.verification.repository.VerificationAuthorizationOperationRepository;
import com.giaokhoa.verification.repository.VerificationSubjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tạo dữ kiện mẫu cho môi trường kiểm chứng theo hợp đồng kiểm chứng quyền: route này chỉ được đăng
 * ký khi profile {@code test} hoặc {@code review} đang bật — production không bật hai profile đó
 * nên route không tồn tại ở production, và không có đường nào qua route này để mở quyền học thuật
 * đại trà. Tạo fixture vẫn là một protected action bình thường: actor lấy từ phiên, quyết định bằng
 * scope platform {@code fixture.manage} qua PDP, không nhận actor thay thế hay quyền tự khai. Bộ dữ
 * kiện thuộc danh mục cố định; bộ duy nhất hiện có tạo trong personal workspace của chính actor một
 * resource mẫu ở trạng thái đã duyệt kèm một phân công tường minh cho chính actor, rồi ghi lại
 * bundle của version và chờ chiếu xong mới trả định danh, để các action mẫu dùng được ngay dữ kiện
 * vừa tạo qua đúng nguồn quyền vừa ghi.
 */
@Profile({"test", "review"})
@RestController
public class VerificationFixtureController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String RESOURCE_ACCESS_SET = "resource-access";

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector resourceProjector;
    private final IdempotencyService idempotency;
    private final WorkspaceDirectoryService directory;
    private final WorkspaceTransaction workspaces;
    private final ResourceRepository resources;
    private final ResourceVersionRepository versions;
    private final ResourceAssignmentRepository assignments;
    private final VerificationSubjectRepository subjects;
    private final VerificationAuthorizationOperationRepository operations;
    private final TransactionTemplate transactions;

    public VerificationFixtureController(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector resourceProjector,
            IdempotencyService idempotency,
            WorkspaceDirectoryService directory,
            WorkspaceTransaction workspaces,
            ResourceRepository resources,
            ResourceVersionRepository versions,
            ResourceAssignmentRepository assignments,
            VerificationSubjectRepository subjects,
            VerificationAuthorizationOperationRepository operations,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.resourceProjector = resourceProjector;
        this.idempotency = idempotency;
        this.directory = directory;
        this.workspaces = workspaces;
        this.resources = resources;
        this.versions = versions;
        this.assignments = assignments;
        this.subjects = subjects;
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record FixtureRequest(String scenarioSet) {}

    public record FixtureView(UUID resourceId, UUID versionId, UUID assignmentId) {}

    @PostMapping("/api/verification/fixtures")
    public ResponseEntity<FixtureView> createFixtures(
            @RequestBody FixtureRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (request == null || !RESOURCE_ACCESS_SET.equals(request.scenarioSet())) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FIXTURE_SET",
                    "Bộ dữ kiện mẫu không thuộc danh mục cố định.");
        }
        UUID actorUserId = executor.actorUserId(authentication);
        UUID personalWorkspace =
                directory
                        .personalWorkspace(actorUserId)
                        .orElseThrow(VerificationFixtureController::notFound);
        var target =
                new ProtectedActionExecutor.Target(
                        NativePolicyTemplates.platformKey(),
                        List.of(PermissionCatalog.PlatformScope.FIXTURE_MANAGE.scopeName()));
        FixtureView view =
                executor.executeForActor(
                        authentication,
                        httpRequest,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                personalWorkspace,
                                                () ->
                                                        insertFixtureSet(
                                                                actorUserId, personalWorkspace)),
                                        "fixture"));
        // Phân công vừa ghi là một nguồn quyền: bundle của version phải chiếu xong thì dữ kiện
        // mới dùng được qua các action mẫu, theo cùng kỷ luật chờ chiếu của các protected action.
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(
                        personalWorkspace, view.resourceId(), view.versionId());
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        resourceProjector.recordResourceChange(
                                actorUserId,
                                personalWorkspace,
                                "fixture-" + UUID.randomUUID(),
                                projectionPayloadHash(targetKey),
                                List.of(targetKey)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(view);
    }

    private FixtureView insertFixtureSet(UUID actorUserId, UUID personalWorkspace) {
        UUID subjectId =
                subjects.findFirstByOrderByCodeAsc()
                        .map(Subject::getId)
                        .orElseThrow(
                                () -> new IllegalStateException("No subject exists for fixtures"));
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        resources.save(
                new Resource(
                        resourceId,
                        personalWorkspace,
                        actorUserId,
                        Resource.Kind.QUESTION,
                        subjectId,
                        "ACTIVE"));
        versions.save(
                new ResourceVersion(
                        versionId,
                        resourceId,
                        1,
                        "Ban mau kiem chung",
                        "THAN-BAI-MAU-KIEM-CHUNG",
                        "DAP-AN-MAU-KIEM-CHUNG",
                        ResourceVersion.ReviewState.APPROVED,
                        null));
        ResourceAssignment assignment =
                new ResourceAssignment(
                        assignmentId,
                        personalWorkspace,
                        actorUserId,
                        resourceId,
                        null,
                        List.of(
                                "draft.edit",
                                "review",
                                "use",
                                "answer.read",
                                "share",
                                "content.read"));
        assignment.activate();
        assignments.save(assignment);
        return new FixtureView(resourceId, versionId, assignmentId);
    }

    private String projectionPayloadHash(String targetKey) {
        var node = JSON.createObjectNode();
        node.putArray("projectionTargets").add(targetKey);
        return idempotency.payloadHash("POST", "/api/verification/fixtures", node);
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

    private static HttpProblemException notFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    }
}
