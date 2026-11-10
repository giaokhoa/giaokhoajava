package com.giaokhoa.resources;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.resources.data.CanonicalResourceRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service thư viện resource của US7: đọc contextual (thân bài/đáp án), lưu version về kho, fork và
 * nhận cập nhật cho bản đã lưu — mọi thao tác đều qua canonical repository và chỉ sau quyết định
 * PDP trên target version tại workspace ngữ cảnh, theo đúng giao thức của hợp đồng authorization:
 * trước khi hỏi PDP, service ghi lại bundle hiện hành của target qua {@link
 * ResourcePolicyProjector} bằng operation dùng chung và chờ chiếu xong (chờ không xong thì
 * fail-closed 503); mutation ghi biên nhận idempotency của chính action trong cùng transaction với
 * mutation (bảng {@code sample_action_mutation}, cùng giao thức với các sample action khác) và ghi
 * lại bundle sau khi đổi dữ kiện nguồn để graph phản ánh trạng thái mới.
 *
 * <p>Lưu không nhân bản resource/version và không đổi chủ sở hữu canonical: mục thư viện trỏ về
 * đúng version nguồn, custody OWNED khi workspace nhận là chủ sở hữu, LICENSED khi version đến từ
 * hệ thống hoặc một grant. Fork tạo resource mới thuộc workspace nhận với người tạo là actor, giữ
 * đúng môn và nội dung của version nguồn, ghi cạnh provenance về version nguồn kèm grant đã cấp
 * quyền fork (nếu căn cứ là license) và sao toàn bộ dependency của version nguồn; fork không bao
 * giờ ghi đè quyền của chủ sở hữu nguồn. Nhận cập nhật tạo mục thư viện mới trỏ version thay thế
 * của cùng resource, giữ nguyên custody và grant của mục gốc, không đụng hoạt động hay lịch sử đã
 * giao trên version cũ.
 */
@Service
public class ResourceLibraryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String OPERATION_FAILED = "FAILED";

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector projector;
    private final CanonicalResourceRepository repository;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} và repository dựng sẵn: template và
     * repository đều được tạo trong constructor nên service không giữ tham chiếu tới đối tượng
     * mutable từ bên ngoài (SpotBugs EI_EXPOSE_REP2); {@code DataSource} là singleton do Spring
     * quản lý, repository không trạng thái và chỉ service này dùng.
     */
    public ResourceLibraryService(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector projector,
            IdempotencyService idempotency,
            DataSource dataSource,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.projector = projector;
        this.repository = new CanonicalResourceRepository(dataSource);
        this.idempotency = idempotency;
        this.jdbc = new JdbcTemplate(dataSource);
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Đọc thân bài của version tại workspace ngữ cảnh sau quyết định {@code content.read}. */
    public String readBody(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId) {
        return Objects.requireNonNull(
                readVersion(authentication, httpRequest, workspaceId, resourceId, versionId, true),
                "body");
    }

    /** Đọc đáp án của version tại workspace ngữ cảnh sau quyết định {@code answer.read} riêng. */
    public @Nullable String readAnswer(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId) {
        return readVersion(authentication, httpRequest, workspaceId, resourceId, versionId, false);
    }

    private @Nullable String readVersion(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            boolean body) {
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId);
        String scope =
                body
                        ? PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName()
                        : PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName();
        ensureProjection(actorUserId, workspaceId, "resource-read-" + UUID.randomUUID(), targetKey);
        var target = new ProtectedActionExecutor.Target(targetKey, List.of(scope));
        return executor.evaluateAndRead(
                authentication,
                httpRequest,
                workspaceId,
                List.of(target),
                decisions -> {
                    if (!decisions.allowed(targetKey, scope)) {
                        throw forbidden();
                    }
                    CanonicalResourceRepository.VersionContent content =
                            Objects.requireNonNull(
                                            workspaces.inWorkspace(
                                                    workspaceId,
                                                    () ->
                                                            repository.content(
                                                                    resourceId, versionId)),
                                            "content")
                                    .orElseThrow(ResourceLibraryService::notFound);
                    return body ? content.body() : content.answer();
                });
    }

    /**
     * Lưu version về kho của workspace nhận sau quyết định {@code library.save}: trả định danh mục
     * thư viện (mục đã có của đúng bộ ba thì tái dùng, không tạo trùng).
     */
    public UUID saveVersion(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId);
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#save", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName()));
        UUID itemId =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () -> {
                                                    // Định danh đích phải chốt trước khi ghi biên
                                                    // nhận: mục đã có thì biên nhận trỏ đúng mục
                                                    // đó, chưa có thì sinh trước để applySave
                                                    // chèn đúng định danh này — replay mới trả
                                                    // lại đúng kết quả đầu tiên.
                                                    UUID targetItemId =
                                                            repository
                                                                    .findLibraryItem(
                                                                            workspaceId,
                                                                            resourceId,
                                                                            versionId)
                                                                    .orElseGet(UUID::randomUUID);
                                                    return claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    "LIBRARY_ITEM",
                                                                    targetItemId)
                                                            .orElseGet(() ->
                                                                    applySave(
                                                                            workspaceId,
                                                                            resourceId,
                                                                            versionId,
                                                                            targetItemId));
                                                }),
                                        "library item"));
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#save-sync", targetKey);
        return itemId;
    }

    private UUID applySave(
            UUID workspaceId, UUID resourceId, UUID versionId, UUID candidateItemId) {
        CanonicalResourceRepository.VersionMetadata metadata =
                repository
                        .metadata(resourceId, versionId)
                        .orElseThrow(ResourceLibraryService::notFound);
        Optional<UUID> existing = repository.findLibraryItem(workspaceId, resourceId, versionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean owned = metadata.ownerWorkspaceId().equals(workspaceId);
        List<CanonicalResourceRepository.GrantRef> grants =
                repository.acceptedGrants(workspaceId, resourceId, versionId);
        UUID shareGrantId = grants.isEmpty() ? null : grants.getFirst().id();
        if (!owned && !"SYSTEM".equals(metadata.ownerKind()) && grants.isEmpty()) {
            // PDP đã cho qua nhưng không có căn cứ license tường minh cho version này tại
            // workspace nhận: fail-closed thay vì ghi mục thư viện không nguồn.
            throw forbidden();
        }
        return repository.insertLibraryItem(
                candidateItemId,
                workspaceId,
                resourceId,
                versionId,
                shareGrantId,
                owned ? "OWNED" : "LICENSED");
    }

    /**
     * Fork version tại workspace nhận sau quyết định {@code fork}: trả định danh resource mới.
     * Người chỉ có quyền dùng (không có scope fork ở bất kỳ nhánh nguồn nào) bị PDP từ chối 403
     * trước khi bất kỳ dòng nào được ghi.
     */
    public UUID forkVersion(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            String label,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId);
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#fork", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.FORK.scopeName()));
        ForkCreated fork =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () -> {
                                                    UUID candidateId = UUID.randomUUID();
                                                    Optional<UUID> previous =
                                                            claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    "RESOURCE",
                                                                    candidateId);
                                                    if (previous.isPresent()) {
                                                        return new ForkCreated(
                                                                previous.get(),
                                                                repository
                                                                        .findForkVersionId(
                                                                                previous.get())
                                                                        .orElseThrow(
                                                                                ResourceLibraryService
                                                                                        ::notFound));
                                                    }
                                                    return applyFork(
                                                            actorUserId,
                                                            workspaceId,
                                                            resourceId,
                                                            versionId,
                                                            label,
                                                            candidateId);
                                                }),
                                        "fork"));
        String forkTargetKey =
                NativePolicyTemplates.resourceVersionKey(
                        workspaceId, fork.resourceId(), fork.versionId());
        ensureProjection(
                actorUserId, workspaceId, idempotencyKey + "#fork-sync", targetKey, forkTargetKey);
        return fork.resourceId();
    }

    /** Resource fork vừa tạo: định danh resource mới và version đầu của nó. */
    private record ForkCreated(UUID resourceId, UUID versionId) {}

    private ForkCreated applyFork(
            UUID actorUserId,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            String label,
            UUID forkResourceId) {
        CanonicalResourceRepository.VersionMetadata metadata =
                repository
                        .metadata(resourceId, versionId)
                        .orElseThrow(ResourceLibraryService::notFound);
        CanonicalResourceRepository.VersionContent content =
                repository
                        .content(resourceId, versionId)
                        .orElseThrow(ResourceLibraryService::notFound);
        // Căn cứ license của fork: grant ACCEPTED đầu tiên cấp scope fork cho workspace nhận và
        // cho phép dùng tại chính workspace này; provenance và giới hạn của bản fork bám grant đó.
        CanonicalResourceRepository.GrantRef authorizingGrant = null;
        for (CanonicalResourceRepository.GrantRef grant :
                repository.acceptedGrants(workspaceId, resourceId, versionId)) {
            boolean grantsFork =
                    ResourcePolicyProjector.mapActions(grant.actions())
                            .contains(PermissionCatalog.ResourceVersionScope.FORK.scopeName());
            boolean allowedHere =
                    grant.allowedWorkspaceIds().isEmpty()
                            || grant.allowedWorkspaceIds().contains(workspaceId);
            if (grantsFork && allowedHere) {
                authorizingGrant = grant;
                break;
            }
        }
        String usageConstraints =
                authorizingGrant == null
                        ? ResourcePolicyProjector.forkOriginMarker()
                                + ";grant=-;actions=-;allowed=-"
                        : ResourcePolicyProjector.forkOriginMarker()
                                + ";grant="
                                + authorizingGrant.id()
                                + ";actions="
                                + String.join(",", authorizingGrant.actions())
                                + ";allowed="
                                + (authorizingGrant.allowedWorkspaceIds().isEmpty()
                                        ? "-"
                                        : authorizingGrant.allowedWorkspaceIds().stream()
                                                .map(UUID::toString)
                                                .reduce((a, b) -> a + "," + b)
                                                .orElse("-"));
        UUID forkVersionId = UUID.randomUUID();
        repository.insertFork(
                forkResourceId,
                forkVersionId,
                actorUserId,
                workspaceId,
                metadata,
                label,
                content,
                authorizingGrant == null ? null : authorizingGrant.id(),
                usageConstraints);
        return new ForkCreated(forkResourceId, forkVersionId);
    }

    /**
     * Nhận cập nhật cho một mục đã lưu: mục phải thuộc đúng workspace của route (mục của workspace
     * khác thì 403), version thay thế phải thuộc cùng resource với version của mục, và quyết định
     * {@code library.save} trên target của version thay thế phải cho phép. Trả định danh mục thư
     * viện mới trỏ version thay thế, giữ custody và grant của mục gốc.
     */
    public UUID receiveUpdate(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID libraryItemId,
            UUID replacementVersionId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        CanonicalResourceRepository.LibraryItemRef item =
                repository.libraryItem(libraryItemId).orElseThrow(ResourceLibraryService::notFound);
        if (!item.workspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        CanonicalResourceRepository.VersionMetadata replacement =
                repository
                        .metadata(item.resourceId(), replacementVersionId)
                        .orElseThrow(ResourceLibraryService::invalid);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(
                        workspaceId, replacement.resourceId(), replacement.versionId());
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#update", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName()));
        UUID updatedItemId =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () -> {
                                                    // Định danh đích chốt trước khi ghi biên
                                                    // nhận như ở saveVersion, để replay trả
                                                    // đúng kết quả đầu tiên.
                                                    UUID targetItemId =
                                                            repository
                                                                    .findLibraryItem(
                                                                            workspaceId,
                                                                            item.resourceId(),
                                                                            replacementVersionId)
                                                                    .orElseGet(UUID::randomUUID);
                                                    return claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    "LIBRARY_ITEM",
                                                                    targetItemId)
                                                            .orElseGet(() ->
                                                                    repository
                                                                            .findLibraryItem(
                                                                                    workspaceId,
                                                                                    item
                                                                                            .resourceId(),
                                                                                    replacementVersionId)
                                                                            .orElseGet(() ->
                                                                                    repository
                                                                                            .insertLibraryItem(
                                                                                                    targetItemId,
                                                                                                    workspaceId,
                                                                                                    item
                                                                                                            .resourceId(),
                                                                                                    replacementVersionId,
                                                                                                    item
                                                                                                            .shareGrantId(),
                                                                                                    item
                                                                                                            .custody())));
                                                }),
                                        "library item"));
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#update-sync", targetKey);
        return updatedItemId;
    }

    // ---------- Biên nhận idempotency của chính action ----------

    /**
     * Chèn biên nhận của action trong cùng transaction với mutation, cùng giao thức với các sample
     * action khác: chèn được thì biên nhận chưa tồn tại và mutation chạy tiếp; đã có biên nhận cùng
     * payload thì trả định danh đích đầu tiên thay vì ghi lại; khác payload thì 409.
     */
    private Optional<UUID> claimReceipt(
            UUID actorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            String targetType,
            UUID candidateTargetId) {
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
                        candidateTargetId);
        if (inserted != 0) {
            return Optional.empty();
        }
        Map<String, Object> previous =
                jdbc.queryForMap(
                        "SELECT payload_hash, target_id FROM sample_action_mutation"
                                + " WHERE actor_user_id = ? AND idempotency_key = ?",
                        actorUserId,
                        idempotencyKey);
        if (!payloadHash.equals(previous.get("payload_hash"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of(Objects.requireNonNull((UUID) previous.get("target_id"), "target_id"));
    }

    // ---------- Ghi projection trước khi hỏi PDP ----------

    /** Ghi lại bundle hiện hành của các target qua operation dùng chung và chờ chiếu xong. */
    private void ensureProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String... targetKeys) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        projector.recordResourceChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, List.of(targetKeys)),
                                List.of(targetKeys)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    private String projectionPayloadHash(UUID workspaceId, List<String> targetKeys) {
        var node = JSON.createObjectNode();
        var targets = node.putArray("projectionTargets");
        targetKeys.forEach(targets::add);
        return idempotency.payloadHash(
                "POST", "/api/workspaces/" + workspaceId + "/samples/actions", node);
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

    private static HttpProblemException forbidden() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Không có quyền thực hiện thao tác.");
    }

    private static HttpProblemException notFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    }

    private static HttpProblemException invalid() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SAMPLE_ACTION",
                "Version thay thế không thuộc resource của mục đã lưu.");
    }
}
