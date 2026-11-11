package com.giaokhoa.resources;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.model.LibraryItem;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.repository.CanonicalResourceRepository;
import com.giaokhoa.resources.repository.GrantRef;
import com.giaokhoa.resources.repository.LibraryItemRef;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationOperationRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.model.SampleActionMutation;
import com.giaokhoa.verification.repository.SampleActionMutationRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
 * hệ thống hoặc một grant. Ngoại lệ duy nhất là copy-on-share: version đến từ một grant
 * tenant–tenant đã được vật chất hoá thành bản sao trong mặt phẳng của workspace nhận thì mục thư
 * viện trỏ bản sao đó (vẫn LICENSED theo grant), vì phía nhận giữ dữ liệu ở mặt phẳng của mình thay
 * vì tham chiếu sống sang mặt phẳng của tenant khác. Fork tạo resource mới thuộc workspace nhận với
 * người tạo là actor, giữ đúng môn và nội dung của version nguồn, ghi cạnh provenance về version
 * nguồn kèm grant đã cấp quyền fork (nếu căn cứ là license) và sao toàn bộ dependency của version
 * nguồn; fork không bao giờ ghi đè quyền của chủ sở hữu nguồn. Nhận cập nhật tạo mục thư viện mới
 * trỏ version thay thế của cùng resource, giữ nguyên custody và grant của mục gốc, không đụng hoạt
 * động hay lịch sử đã giao trên version cũ — riêng mục trỏ bản sao copy-on-share thì bản cập nhật
 * được ghi thành một version sao mới trên resource sao, để phía nhận không bao giờ phải trỏ sang
 * version sống của tenant nguồn.
 */
@Service
public class ResourceLibraryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector projector;
    private final CorrectionNotificationService notifications;
    private final CanonicalResourceRepository repository;
    private final ResourceVersionRepository versions;
    private final ResourceCopies copies;
    private final SampleActionMutationRepository receipts;
    private final ResourcesAuthorizationOperationRepository operationStates;
    private final IdempotencyService idempotency;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    public ResourceLibraryService(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector projector,
            CorrectionNotificationService notifications,
            IdempotencyService idempotency,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager,
            CanonicalResourceRepository repository,
            ResourceVersionRepository versions,
            ResourceCopies copies,
            SampleActionMutationRepository receipts,
            ResourcesAuthorizationOperationRepository operationStates) {
        this.executor = executor;
        this.projector = projector;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
        this.repository = repository;
        this.versions = versions;
        this.copies = copies;
        this.receipts = receipts;
        this.operationStates = operationStates;
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
                    return workspaces.inWorkspace(
                            workspaceId,
                            () -> {
                                if (body) {
                                    return repository
                                            .body(resourceId, versionId)
                                            .orElseThrow(ResourceLibraryService::notFound);
                                }
                                if (!versions.existsByResourceIdAndId(resourceId, versionId)) {
                                    throw notFound();
                                }
                                return repository.answer(resourceId, versionId);
                            });
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
                                                    VersionMetadata metadata =
                                                            repository
                                                                    .metadata(resourceId, versionId)
                                                                    .orElseThrow(
                                                                            ResourceLibraryService
                                                                                    ::notFound);
                                                    SaveResolution resolution =
                                                            resolveSaveTarget(
                                                                    workspaceId, metadata);
                                                    // Định danh đích phải chốt trước khi ghi biên
                                                    // nhận: mục đã có thì biên nhận trỏ đúng mục
                                                    // đó, chưa có thì sinh trước để applySave
                                                    // chèn đúng định danh này — replay mới trả
                                                    // lại đúng kết quả đầu tiên.
                                                    UUID targetItemId =
                                                            repository
                                                                    .findLibraryItem(
                                                                            workspaceId,
                                                                            resolution.resourceId(),
                                                                            resolution.versionId())
                                                                    .orElseGet(UUID::randomUUID);
                                                    return claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    "LIBRARY_ITEM",
                                                                    targetItemId)
                                                            .orElseGet(() -> {
                                                                UUID savedId =
                                                                        applySave(
                                                                                workspaceId,
                                                                                metadata,
                                                                                resolution,
                                                                                targetItemId);
                                                                reconcileReceiptTarget(
                                                                        actorUserId,
                                                                        idempotencyKey,
                                                                        targetItemId,
                                                                        savedId);
                                                                return savedId;
                                                            });
                                                }),
                                        "library item"));
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#save-sync", targetKey);
        return itemId;
    }

    /**
     * Đích hiệu lực của một lượt lưu: cặp (resource, version) mà mục thư viện phải trỏ tới và căn
     * cứ quyền của nó. Version nguồn là đích mặc định; riêng grant tenant–tenant đã có bản sao
     * copy-on-share tại workspace nhận thì đích là chính bản sao đó theo grant đã sao.
     */
    private record SaveResolution(
            UUID resourceId,
            UUID versionId,
            @Nullable UUID shareGrantId,
            boolean owned,
            boolean grantBased) {}

    private SaveResolution resolveSaveTarget(UUID workspaceId, VersionMetadata metadata) {
        boolean owned = metadata.ownerWorkspaceId().equals(workspaceId);
        List<GrantRef> grants =
                repository.acceptedGrants(workspaceId, metadata.resourceId(), metadata.versionId());
        if (!owned) {
            for (GrantRef grant : grants) {
                Optional<ResourceCopies.CopyRef> copy =
                        copies.findCopy(
                                metadata.resourceId(),
                                metadata.versionId(),
                                grant.id(),
                                workspaceId);
                if (copy.isPresent()) {
                    return new SaveResolution(
                            copy.get().resourceId(),
                            copy.get().versionId(),
                            grant.id(),
                            false,
                            true);
                }
            }
        }
        UUID shareGrantId = grants.isEmpty() ? null : grants.getFirst().id();
        return new SaveResolution(
                metadata.resourceId(),
                metadata.versionId(),
                shareGrantId,
                owned,
                !grants.isEmpty());
    }

    private UUID applySave(
            UUID workspaceId,
            VersionMetadata metadata,
            SaveResolution resolution,
            UUID candidateItemId) {
        Optional<UUID> existing =
                repository.findLibraryItem(
                        workspaceId, resolution.resourceId(), resolution.versionId());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!resolution.owned()
                && !"SYSTEM".equals(metadata.ownerKind())
                && !resolution.grantBased()) {
            // PDP đã cho qua nhưng không có căn cứ license tường minh cho version này tại
            // workspace nhận: fail-closed thay vì ghi mục thư viện không nguồn.
            throw forbidden();
        }
        return repository.insertLibraryItem(
                candidateItemId,
                workspaceId,
                resolution.resourceId(),
                resolution.versionId(),
                resolution.shareGrantId(),
                resolution.owned() ? LibraryItem.Custody.OWNED : LibraryItem.Custody.LICENSED);
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
        VersionMetadata metadata =
                repository
                        .metadata(resourceId, versionId)
                        .orElseThrow(ResourceLibraryService::notFound);
        String body =
                repository
                        .body(resourceId, versionId)
                        .orElseThrow(ResourceLibraryService::notFound);
        // Căn cứ license của fork: grant ACCEPTED đầu tiên cấp scope fork cho workspace nhận và
        // cho phép dùng tại chính workspace này; provenance và giới hạn của bản fork bám grant đó.
        GrantRef authorizingGrant = null;
        for (GrantRef grant : repository.acceptedGrants(workspaceId, resourceId, versionId)) {
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
        ResourceCopies.CopyRef forked =
                copies.copyVersion(
                        forkResourceId,
                        forkVersionId,
                        actorUserId,
                        workspaceId,
                        metadata,
                        label,
                        body,
                        repository.answer(resourceId, versionId),
                        ResourceVersion.ReviewState.DRAFT,
                        null,
                        1,
                        authorizingGrant == null ? null : authorizingGrant.id(),
                        usageConstraints);
        return new ForkCreated(forked.resourceId(), forked.versionId());
    }

    /**
     * Nhận cập nhật cho một mục đã lưu: mục phải thuộc đúng workspace của route (mục của workspace
     * khác thì 403), version thay thế phải thuộc cùng resource với version của mục, và quyết định
     * {@code library.save} trên target của version thay thế phải cho phép. Trả định danh mục thư
     * viện mới trỏ version thay thế, giữ custody và grant của mục gốc. Riêng mục trỏ bản sao
     * copy-on-share: "cùng resource" được kiểm theo resource nguồn của cạnh provenance, và bản cập
     * nhật được vật chất hoá thành một version sao mới trên resource sao thay vì trỏ sang version
     * sống của tenant nguồn. Nếu version thay thế là bản đính chính của một notice, việc nhận này
     * cũng ghi mốc xác nhận đã biết đính chính cho workspace trên receipt của notice đó, trong cùng
     * transaction.
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
        LibraryItemRef item =
                repository.libraryItem(libraryItemId).orElseThrow(ResourceLibraryService::notFound);
        if (!item.workspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        Optional<ResourceDependency> provenance =
                copies.shareProvenanceOf(item.resourceId(), item.versionId());
        UUID sourceResourceId =
                provenance.map(ResourceDependency::getSourceResourceId).orElse(item.resourceId());
        VersionMetadata replacement =
                repository
                        .metadata(sourceResourceId, replacementVersionId)
                        .orElseThrow(ResourceLibraryService::invalid);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(
                        workspaceId, sourceResourceId, replacementVersionId);
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
                                                    UUID receivedId =
                                                            provenance.isPresent()
                                                                    ? applyCopyUpdate(
                                                                            actorUserId,
                                                                            workspaceId,
                                                                            item,
                                                                            provenance.get(),
                                                                            replacement,
                                                                            idempotencyKey,
                                                                            payloadHash)
                                                                    : applyLiveUpdate(
                                                                            actorUserId,
                                                                            workspaceId,
                                                                            item,
                                                                            replacementVersionId,
                                                                            idempotencyKey,
                                                                            payloadHash);
                                                    notifications.acknowledgeReceivedUpdate(
                                                            workspaceId,
                                                            sourceResourceId,
                                                            replacementVersionId);
                                                    return receivedId;
                                                }),
                                        "library item"));
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#update-sync", targetKey);
        return updatedItemId;
    }

    /** Nhận cập nhật trên mục tham chiếu sống: mục mới trỏ thẳng version thay thế của nguồn. */
    private UUID applyLiveUpdate(
            UUID actorUserId,
            UUID workspaceId,
            LibraryItemRef item,
            UUID replacementVersionId,
            String idempotencyKey,
            String payloadHash) {
        // Định danh đích chốt trước khi ghi biên nhận như ở saveVersion, để replay trả
        // đúng kết quả đầu tiên.
        UUID targetItemId =
                repository
                        .findLibraryItem(workspaceId, item.resourceId(), replacementVersionId)
                        .orElseGet(UUID::randomUUID);
        return claimReceipt(
                        actorUserId,
                        workspaceId,
                        idempotencyKey,
                        payloadHash,
                        "LIBRARY_ITEM",
                        targetItemId)
                .orElseGet(() -> {
                    UUID savedId =
                            repository
                                    .findLibraryItem(
                                            workspaceId, item.resourceId(), replacementVersionId)
                                    .orElseGet(() ->
                                            repository.insertLibraryItem(
                                                    targetItemId,
                                                    workspaceId,
                                                    item.resourceId(),
                                                    replacementVersionId,
                                                    item.shareGrantId(),
                                                    LibraryItem.Custody.valueOf(item.custody())));
                    reconcileReceiptTarget(actorUserId, idempotencyKey, targetItemId, savedId);
                    return savedId;
                });
    }

    /**
     * Nhận cập nhật trên mục trỏ bản sao copy-on-share: version thay thế của nguồn được sao thành
     * một version sao mới trên resource sao (kèm provenance và closure của chính nó), mục mới trỏ
     * version sao đó. Version thay thế đã từng được sao thì tái dùng đúng version sao và mục của
     * nó, không vật chất hoá trùng.
     */
    private UUID applyCopyUpdate(
            UUID actorUserId,
            UUID workspaceId,
            LibraryItemRef item,
            ResourceDependency provenance,
            VersionMetadata replacement,
            String idempotencyKey,
            String payloadHash) {
        UUID grantId = Objects.requireNonNull(provenance.getShareGrantId(), "share_grant_id");
        Optional<UUID> existingCopyVersionId =
                copies.copyVersionIdForSourceVersion(
                        item.resourceId(),
                        provenance.getSourceResourceId(),
                        replacement.versionId(),
                        grantId);
        UUID targetItemId =
                existingCopyVersionId
                        .flatMap(copyVersionId ->
                                repository.findLibraryItem(
                                        workspaceId, item.resourceId(), copyVersionId))
                        .orElseGet(UUID::randomUUID);
        return claimReceipt(
                        actorUserId,
                        workspaceId,
                        idempotencyKey,
                        payloadHash,
                        "LIBRARY_ITEM",
                        targetItemId)
                .orElseGet(() -> {
                    Optional<UUID> existingItem =
                            existingCopyVersionId.flatMap(copyVersionId ->
                                    repository.findLibraryItem(
                                            workspaceId, item.resourceId(), copyVersionId));
                    if (existingItem.isPresent()) {
                        return existingItem.get();
                    }
                    UUID copyVersionId =
                            existingCopyVersionId.orElseGet(() -> {
                                ResourceCopies.VersionView sourceVersion =
                                        copies.readVersion(replacement)
                                                .orElseThrow(ResourceLibraryService::notFound);
                                UUID newCopyVersionId = UUID.randomUUID();
                                copies.appendVersion(
                                        item.resourceId(),
                                        newCopyVersionId,
                                        replacement,
                                        sourceVersion.label(),
                                        sourceVersion.body(),
                                        sourceVersion.answer(),
                                        sourceVersion.reviewState(),
                                        sourceVersion.lockedAt(),
                                        copies.nextVersionNo(item.resourceId()),
                                        grantId,
                                        provenance.getUsageConstraints());
                                return newCopyVersionId;
                            });
                    UUID savedId =
                            repository.insertLibraryItem(
                                    targetItemId,
                                    workspaceId,
                                    item.resourceId(),
                                    copyVersionId,
                                    item.shareGrantId(),
                                    LibraryItem.Custody.valueOf(item.custody()));
                    reconcileReceiptTarget(actorUserId, idempotencyKey, targetItemId, savedId);
                    return savedId;
                });
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
                receipts.claim(
                        actorUserId,
                        idempotencyKey,
                        payloadHash,
                        workspaceId,
                        targetType,
                        candidateTargetId);
        if (inserted != 0) {
            return Optional.empty();
        }
        SampleActionMutation previous =
                receipts.findById(new SampleActionMutation.Id(actorUserId, idempotencyKey))
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency receipt is missing after a claim conflict"));
        if (!payloadHash.equals(previous.getPayloadHash())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of(previous.getTargetId());
    }

    /**
     * Khe đua hẹp giữa hai lần tra mục thư viện: applySave (hoặc nhánh chèn của nhận cập nhật) kiểm
     * tra lại trước khi chèn, nên một giao dịch khác commit mục cho cùng bộ ba trong khoảng giữa
     * thì id trả về khác định danh đã chốt trong biên nhận; sửa biên nhận về đúng id thật trong
     * cùng transaction để replay sau này không trả định danh chưa từng được chèn. Chỉ áp cho nhánh
     * claim mới — replay trả đúng id đã lưu trong biên nhận.
     */
    private void reconcileReceiptTarget(
            UUID actorUserId, String idempotencyKey, UUID targetItemId, UUID savedId) {
        if (!savedId.equals(targetItemId)) {
            receipts.retarget(actorUserId, idempotencyKey, savedId);
        }
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
            AuthorizationOperation.State state =
                    operationStates
                            .findStateById(operation.getId())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Authorization operation is missing: "
                                                    + operation.getId()));
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
