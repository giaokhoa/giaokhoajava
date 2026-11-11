package com.giaokhoa.resources.service;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.ResourceCopies;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.ResourceRevocationContributor;
import com.giaokhoa.resources.SharePolicyProjector;
import com.giaokhoa.resources.model.LibraryItem;
import com.giaokhoa.resources.model.ResourceAssignment;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.CanonicalResourceRepository;
import com.giaokhoa.resources.repository.GrantRef;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceScopePreconditions;
import com.giaokhoa.resources.repository.ResourcesAuthorizationOperationRepository;
import com.giaokhoa.resources.repository.ResourcesWorkspaceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.model.SampleActionMutation;
import com.giaokhoa.verification.repository.SampleActionMutationRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service chia sẻ resource của US7: cấp (share), nhận (accept) và thu hồi (revoke) một version cho
 * một workspace nhận theo đúng hợp đồng authorization và hợp đồng HTTP. Cấp quyền chỉ diễn ra sau
 * quyết định PDP {@code share} trên target version tại workspace nguồn, và invariant của service
 * chỉ có thể thu hẹp thêm chứ không cấp thay PDP: lượt cấp không được rộng hơn nguồn — hành động
 * xin cấp phải nằm trong trần quyền nguồn của chính người cấp tại workspace này (chủ sở hữu thì
 * toàn catalog; người giữ version theo grant hoặc theo phân công thì bị trần bởi actions của
 * grant/phân công đó), và mọi thành phần mà version xin chia sẻ phụ thuộc qua một grant đều phải
 * cho phép chia tiếp tới đúng nơi nhận và phạm vi đó (FR-035, FR-041). Lượt cấp ghi ở trạng thái
 * {@code OFFERED}: nơi nhận chưa có quyền nào cho tới khi người có thẩm quyền của nơi nhận accept
 * qua quyết định {@code accept} trên target {@code grant:{id}}. Thu hồi chỉ áp cho mode {@code
 * REVOCABLE} đã {@code ACCEPTED} qua quyết định {@code revoke} của chính người cấp; grant {@code
 * LONG_TERM} không có nguồn revoke trong graph nên bị từ chối 403 và không nối membership/gói của
 * người cấp — người cấp rời đi hay hết gói không đụng được lượt cấp lâu dài (FR-037). Khi thu hồi,
 * toàn bộ dependency closure của grant (nơi nhận, bản đã lưu, bản fork phái sinh) được ghi lại
 * trong cùng operation qua {@link ResourceRevocationContributor} và action chỉ báo thành công khi
 * operation đó {@code SUCCEEDED}; lịch sử không bị xóa ở bất kỳ bước nào.
 *
 * <p>Accept một lượt cấp giữa hai tenant khác nhau kích hoạt copy-on-share: resource, version và
 * closure phụ thuộc được sao vào mặt phẳng của workspace nhận kèm cạnh provenance về nguồn (qua
 * {@link ResourceCopies}), và mục thư viện của phía nhận được tạo trỏ bản sao đó; từ thời điểm này
 * quyền của phía nhận sống trên bản sao và thu hồi tác động qua projector trên bản sao thay vì thu
 * hồi dây chuyền tức thì trên tham chiếu sống như mô hình trước đây. Lượt cấp từ hệ thống
 * (workspace sở hữu có kind SYSTEM) và lượt cấp trong cùng một tenant giữ nguyên tham chiếu sống.
 *
 * <p>Mọi action ghi biên nhận idempotency của chính mình trong cùng transaction với mutation (bảng
 * {@code sample_action_mutation}, cùng giao thức với service thư viện): replay cùng key và cùng
 * payload trả lại kết quả đầu tiên, khác payload trả 409. Trước khi hỏi PDP, service ghi lại bundle
 * hiện hành của target qua projector tương ứng bằng operation dùng chung và chờ chiếu xong — chờ
 * không xong thì fail-closed 503, không bao giờ báo quyền đã đồng bộ khi operation còn PENDING.
 */
@Service
public class ResourceShareService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String RECEIPT_TARGET_TYPE = "SHARE_GRANT";
    private static final List<String> MODES = List.of("REVOCABLE", "LONG_TERM");

    /** Giá trị cột workspace.kind cho không gian trung tâm. */
    private static final String CENTER_KIND = "CENTER";

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector projector;
    private final SharePolicyProjector shareProjector;
    private final ResourceRevocationContributor revocation;
    private final CanonicalResourceRepository repository;
    private final ShareGrantRepository grants;
    private final ResourceRepository resources;
    private final ResourceDependencyRepository dependencies;
    private final ResourceAssignmentRepository assignments;
    private final LibraryItemRepository libraryItems;
    private final ResourcesWorkspaceRepository workspaceDirectory;
    private final ResourceCopies copies;
    private final SampleActionMutationRepository receipts;
    private final ResourcesAuthorizationOperationRepository operationStates;
    private final ResourceScopePreconditions preconditions;
    private final IdempotencyService idempotency;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    public ResourceShareService(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector projector,
            SharePolicyProjector shareProjector,
            ResourceRevocationContributor revocation,
            IdempotencyService idempotency,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager,
            CanonicalResourceRepository repository,
            ShareGrantRepository grants,
            ResourceRepository resources,
            ResourceDependencyRepository dependencies,
            ResourceAssignmentRepository assignments,
            LibraryItemRepository libraryItems,
            ResourcesWorkspaceRepository workspaceDirectory,
            ResourceCopies copies,
            SampleActionMutationRepository receipts,
            ResourcesAuthorizationOperationRepository operationStates,
            ResourceScopePreconditions preconditions) {
        this.executor = executor;
        this.projector = projector;
        this.shareProjector = shareProjector;
        this.revocation = revocation;
        this.idempotency = idempotency;
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
        this.repository = repository;
        this.grants = grants;
        this.resources = resources;
        this.dependencies = dependencies;
        this.assignments = assignments;
        this.libraryItems = libraryItems;
        this.workspaceDirectory = workspaceDirectory;
        this.copies = copies;
        this.receipts = receipts;
        this.operationStates = operationStates;
        this.preconditions = preconditions;
    }

    /**
     * Chia sẻ một version cho một workspace nhận sau quyết định {@code share}: trả định danh của
     * lượt cấp vừa tạo ở trạng thái {@code OFFERED}.
     */
    public UUID shareVersion(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            @Nullable OffsetDateTime expiresAt,
            String idempotencyKey,
            String payloadHash) {
        validateSharePayload(mode, actions, allowedWorkspaceIds, recipientWorkspaceId, expiresAt);
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId);
        ensureVersionProjection(actorUserId, workspaceId, idempotencyKey + "#share", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.SHARE.scopeName()));
        UUID grantId =
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
                                                    return claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    candidateId)
                                                            .orElseGet(() ->
                                                                    applyShare(
                                                                            actorUserId,
                                                                            workspaceId,
                                                                            resourceId,
                                                                            versionId,
                                                                            recipientWorkspaceId,
                                                                            mode,
                                                                            actions,
                                                                            allowedWorkspaceIds,
                                                                            expiresAt,
                                                                            candidateId));
                                                }),
                                        "share grant"));
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#share-sync", grantId);
        return grantId;
    }

    private UUID applyShare(
            UUID actorUserId,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            @Nullable OffsetDateTime expiresAt,
            UUID grantId) {
        VersionMetadata metadata =
                repository
                        .metadata(resourceId, versionId)
                        .orElseThrow(ResourceShareService::notFound);
        if (!workspaceDirectory.existsById(recipientWorkspaceId)) {
            throw notFound();
        }
        enforceSourceCeiling(actorUserId, workspaceId, metadata, actions);
        enforceComponentConstraints(
                resourceId, versionId, actions, recipientWorkspaceId, allowedWorkspaceIds);
        grants.save(
                new ShareGrant(
                        grantId,
                        resourceId,
                        versionId,
                        actorUserId,
                        recipientWorkspaceId,
                        ShareGrant.Mode.valueOf(mode),
                        actions,
                        allowedWorkspaceIds,
                        Instant.now(),
                        expiresAt == null ? null : expiresAt.toInstant()));
        return grantId;
    }

    /**
     * Trần quyền nguồn của người cấp tại workspace nguồn (FR-035): chủ sở hữu của version thì toàn
     * catalog scope mức version; ngược lại, actions xin cấp (đã map sang scope) phải nằm trong hợp
     * của các scope mà các grant đã ACCEPTED còn trong cửa sổ hiệu lực giao version này cho
     * workspace và các phân công tường minh ACTIVE của chính người cấp cấp được. PDP đã cho qua
     * scope {@code share} không tự làm trần này rộng hơn dữ kiện nguồn.
     */
    private void enforceSourceCeiling(
            UUID actorUserId, UUID workspaceId, VersionMetadata metadata, List<String> actions) {
        List<String> requestedScopes = ResourcePolicyProjector.mapActions(actions);
        boolean derivedFromGrant =
                dependencies.existsGrantProvenance(metadata.resourceId(), metadata.versionId());
        if (!derivedFromGrant && metadata.ownerWorkspaceId().equals(workspaceId)) {
            return;
        }
        Set<String> ceiling = new HashSet<>();
        for (GrantRef grant :
                repository.acceptedGrantsInWindow(
                        workspaceId, metadata.resourceId(), metadata.versionId())) {
            ceiling.addAll(ResourcePolicyProjector.mapActions(grant.actions()));
        }
        boolean centerMembershipRequired = CENTER_KIND.equals(workspaceKind(workspaceId));
        if (!centerMembershipRequired
                || preconditions.centerMembershipActive(workspaceId, actorUserId)) {
            for (ResourceAssignment assignment :
                    assignments.findMatchingForUser(
                            workspaceId,
                            actorUserId,
                            ResourceAssignment.State.ACTIVE,
                            metadata.resourceId(),
                            metadata.subjectId())) {
                ceiling.addAll(ResourcePolicyProjector.mapActions(assignment.getActions()));
            }
        }
        if (!ceiling.containsAll(requestedScopes)) {
            throw forbidden();
        }
    }

    /**
     * Ràng buộc thành phần (FR-041): duyệt toàn bộ closure phụ thuộc của version xin chia sẻ theo
     * tầng (frontier loop) — mỗi tầng nạp các cạnh có đầu derived thuộc frontier hiện tại, các đầu
     * source chưa thăm thành frontier kế tiếp, tập đã thăm khóa vòng lặp; mọi cạnh mang một grant
     * trên các đỉnh đã thăm đều là nguồn giới hạn của version này. Grant đó phải còn {@code
     * ACCEPTED} và còn trong cửa sổ hiệu lực của nó, phải tự cho phép chia tiếp (scope {@code
     * share}), và phạm vi mới không được rộng hơn phạm vi của nó: nơi nhận phải nằm trong danh sách
     * workspace cho phép của grant thành phần khi danh sách đó không rỗng, và danh sách cho phép
     * của lượt cấp mới phải là tập con của nó; actions của lượt cấp mới cũng phải là tập con của
     * actions của grant thành phần.
     */
    private void enforceComponentConstraints(
            UUID resourceId,
            UUID versionId,
            List<String> actions,
            UUID recipientWorkspaceId,
            List<UUID> allowedWorkspaceIds) {
        List<String> requestedScopes = ResourcePolicyProjector.mapActions(actions);
        Set<UUID> governingGrantIds = governingGrantIds(resourceId, versionId);
        Instant now = Instant.now();
        for (ShareGrant grant : grants.findAllById(governingGrantIds)) {
            if (grant.getState() != ShareGrant.State.ACCEPTED) {
                throw forbidden();
            }
            // Hết hạn là trạng thái suy ra từ thời gian: grant điều phối đã qua cửa sổ hiệu lực
            // thì không còn cho chia tiếp, dù state trên dòng vẫn ACCEPTED. Grant không có mốc
            // bắt đầu hiệu lực thì không chứng minh được cửa sổ: chặn theo hướng fail-closed.
            Instant validFrom = grant.getValidFrom();
            Instant expiresAt = grant.getExpiresAt();
            if (validFrom == null
                    || validFrom.isAfter(now)
                    || (expiresAt != null && !expiresAt.isAfter(now))) {
                throw forbidden();
            }
            List<String> grantScopes = ResourcePolicyProjector.mapActions(grant.getActions());
            if (!grantScopes.contains(PermissionCatalog.ResourceVersionScope.SHARE.scopeName())
                    || !grantScopes.containsAll(requestedScopes)) {
                throw forbidden();
            }
            List<UUID> grantAllowed = grant.getAllowedWorkspaceIds();
            if (!grantAllowed.isEmpty()
                    && (!grantAllowed.contains(recipientWorkspaceId)
                            || allowedWorkspaceIds.isEmpty()
                            || !grantAllowed.containsAll(allowedWorkspaceIds))) {
                throw forbidden();
            }
        }
    }

    /** Một đỉnh của closure phụ thuộc: đúng cặp (resource, version) của một đầu cạnh. */
    private record VersionKey(UUID resourceId, UUID versionId) {}

    /**
     * Tập grant điều phối của một version: duyệt lặp theo tầng từ chính version đó qua các cạnh
     * dependency, thu grant của mọi cạnh mang grant trên các đỉnh đã thăm. Phép lọc theo cặp
     * (resource, version) trong bộ nhớ giữ đúng semantics của phép nối đệ quy cũ, vì phép nạp theo
     * tập version id của frontier có thể trả thêm cạnh của version trùng id ở resource khác (không
     * thể xảy ra với id duy nhất toàn cục, nhưng phép lọc giữ phép duyệt đúng theo cặp bất kể).
     */
    Set<UUID> governingGrantIds(UUID resourceId, UUID versionId) {
        Set<UUID> governing = new LinkedHashSet<>();
        Set<VersionKey> visited = new HashSet<>();
        Set<VersionKey> frontier = Set.of(new VersionKey(resourceId, versionId));
        visited.addAll(frontier);
        while (!frontier.isEmpty()) {
            List<UUID> frontierVersionIds = frontier.stream().map(key -> key.versionId()).toList();
            Set<VersionKey> next = new HashSet<>();
            for (ResourceDependency edge :
                    dependencies.findByDerivedVersionIdIn(frontierVersionIds)) {
                if (!frontier.contains(
                        new VersionKey(edge.getDerivedResourceId(), edge.getDerivedVersionId()))) {
                    continue;
                }
                if (edge.getShareGrantId() != null) {
                    governing.add(edge.getShareGrantId());
                }
                VersionKey source =
                        new VersionKey(edge.getSourceResourceId(), edge.getSourceVersionId());
                if (visited.add(source)) {
                    next.add(source);
                }
            }
            frontier = next;
        }
        return governing;
    }

    /**
     * Nhận một lượt cấp vào kho của workspace nhận sau quyết định {@code accept} trên target của
     * grant: route phải là đúng workspace nhận của grant và grant phải còn {@code OFFERED} trong
     * cửa sổ hiệu lực của nó. Trả định danh grant.
     */
    public UUID acceptShare(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID grantId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        GrantRow grant = grantRow(grantId).orElseThrow(ResourceShareService::notFound);
        if (!grant.grant().getRecipientWorkspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        String targetKey = NativePolicyTemplates.grantKey(grantId);
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#accept", grantId);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.GrantScope.ACCEPT.scopeName()));
        UUID accepted =
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
                                                        claimReceipt(
                                                                        actorUserId,
                                                                        workspaceId,
                                                                        idempotencyKey,
                                                                        payloadHash,
                                                                        grantId)
                                                                .orElseGet(
                                                                        () -> applyAccept(grant))),
                                        "share grant"));
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#accept-sync", grantId);
        return accepted;
    }

    private UUID applyAccept(GrantRow row) {
        ShareGrant grant = row.grant();
        // Hết hạn là trạng thái suy ra từ thời gian (không ghi state EXPIRED ở đường này), nên
        // một offer đã qua cửa sổ thì không còn nhận được dù state vẫn OFFERED trên dòng.
        if (grant.getExpiresAt() != null && !Instant.now().isBefore(grant.getExpiresAt())) {
            throw notAcceptable();
        }
        int updated =
                grants.acceptIfOffered(
                        grant.getId(),
                        Instant.now(),
                        ShareGrant.State.OFFERED,
                        ShareGrant.State.ACCEPTED);
        if (updated == 0) {
            throw notAcceptable();
        }
        materializeCopyIfTenantShare(row);
        return grant.getId();
    }

    /**
     * Vật chất hoá copy-on-share cho một grant vừa ACCEPTED giữa hai tenant: sao resource, version
     * và closure vào mặt phẳng của workspace nhận (người tạo của bản sao ghi là grantor, vì nội
     * dung là của phía cấp) và tạo mục thư viện của phía nhận trỏ bản sao. Lượt cấp từ hệ thống
     * hoặc trong cùng một tenant không sao — phía nhận giữ tham chiếu sống như mô hình trước. Phép
     * sao idempotent theo cạnh provenance: accept lại một grant đã có bản sao thì tái dùng đúng bản
     * sao và mục thư viện đó.
     */
    private void materializeCopyIfTenantShare(GrantRow row) {
        ShareGrant grant = row.grant();
        UUID recipientWorkspaceId = grant.getRecipientWorkspaceId();
        // Aggregate nguồn nằm ở mặt phẳng của workspace nguồn nên phép đọc phải ghim context
        // vào workspace đó thay vì chạy trong context của workspace nhận như trước đây; đọc
        // xong đặt lại context của workspace nhận cho các phép ghi bản sao phía dưới.
        VersionMetadata metadata =
                Objects.requireNonNull(
                                workspaces.inWorkspace(
                                        row.sourceWorkspaceId(),
                                        () ->
                                                repository.metadata(
                                                        grant.getSourceResourceId(),
                                                        grant.getSourceVersionId())),
                                "source metadata")
                        .orElseThrow(ResourceShareService::notFound);
        workspaces.applyContext(recipientWorkspaceId);
        if (metadata.ownerWorkspaceId().equals(recipientWorkspaceId)
                || "SYSTEM".equals(metadata.ownerKind())) {
            return;
        }
        ResourceCopies.CopyRef copy =
                copies.findCopy(
                                grant.getSourceResourceId(),
                                grant.getSourceVersionId(),
                                grant.getId(),
                                recipientWorkspaceId)
                        .orElseGet(() -> copySourceVersion(row, metadata));
        libraryItems
                .findFirstByWorkspaceIdAndResourceIdAndVersionIdOrderByCreatedAtAscIdAsc(
                        recipientWorkspaceId, copy.resourceId(), copy.versionId())
                .orElseGet(() ->
                        libraryItems.save(
                                new LibraryItem(
                                        UUID.randomUUID(),
                                        recipientWorkspaceId,
                                        copy.resourceId(),
                                        copy.versionId(),
                                        grant.getId(),
                                        LibraryItem.Custody.LICENSED)));
    }

    private ResourceCopies.CopyRef copySourceVersion(GrantRow row, VersionMetadata metadata) {
        ShareGrant grant = row.grant();
        // Thân bài, đáp án và dòng version nguồn cũng là aggregate nguồn: đọc ghim context
        // workspace nguồn rồi đặt lại context workspace nhận trước khi vật chất hoá bản sao.
        ResourceCopies.VersionView sourceVersion =
                copies.readVersion(metadata).orElseThrow(ResourceShareService::notFound);
        String usageConstraints =
                ResourcePolicyProjector.shareOriginMarker()
                        + ";grant="
                        + grant.getId()
                        + ";actions="
                        + String.join(",", grant.getActions())
                        + ";allowed="
                        + (grant.getAllowedWorkspaceIds().isEmpty()
                                ? "-"
                                : grant.getAllowedWorkspaceIds().stream()
                                        .map(id -> id.toString())
                                        .reduce((a, b) -> a + "," + b)
                                        .orElse("-"));
        return copies.copyVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                grant.getGrantorUserId(),
                grant.getRecipientWorkspaceId(),
                metadata,
                sourceVersion.label(),
                sourceVersion.body(),
                sourceVersion.answer(),
                sourceVersion.reviewState(),
                sourceVersion.lockedAt(),
                1,
                grant.getId(),
                usageConstraints);
    }

    /**
     * Thu hồi một lượt cấp {@code REVOCABLE} đã {@code ACCEPTED} sau quyết định {@code revoke} của
     * chính người cấp, từ route của workspace nguồn: trạng thái grant đổi trước, rồi toàn bộ
     * dependency closure được ghi lại và chờ đồng bộ xong mới trả thành công. Trả định danh grant.
     */
    public UUID revokeShare(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID grantId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        GrantRow grant = grantRow(grantId).orElseThrow(ResourceShareService::notFound);
        if (!grant.sourceWorkspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        String targetKey = NativePolicyTemplates.grantKey(grantId);
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#revoke", grantId);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.GrantScope.REVOKE.scopeName()));
        UUID revoked =
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
                                                        claimReceipt(
                                                                        actorUserId,
                                                                        workspaceId,
                                                                        idempotencyKey,
                                                                        payloadHash,
                                                                        grantId)
                                                                .orElseGet(
                                                                        () -> applyRevoke(grant))),
                                        "share grant"));
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        revocation.recordShareRevocation(
                                actorUserId,
                                workspaceId,
                                idempotencyKey + "#revoke-sync",
                                projectionPayloadHash(workspaceId, targetKey),
                                grantId));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
        return revoked;
    }

    private UUID applyRevoke(GrantRow row) {
        int updated =
                grants.revokeIfAcceptedRevocable(
                        row.grant().getId(),
                        Instant.now(),
                        ShareGrant.State.ACCEPTED,
                        ShareGrant.State.REVOKED,
                        ShareGrant.Mode.REVOCABLE);
        if (updated == 0) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "SHARE_NOT_REVOCABLE",
                    "Lượt cấp không ở trạng thái có thể thu hồi.");
        }
        return row.grant().getId();
    }

    // ---------- Dữ kiện grant và kiểm tra payload ----------

    /** Một grant kèm workspace sở hữu version nguồn của nó, cho các quyết định invariant. */
    private record GrantRow(ShareGrant grant, UUID sourceWorkspaceId) {}

    private Optional<GrantRow> grantRow(UUID grantId) {
        Optional<ShareGrant> grant = grants.findById(grantId);
        if (grant.isEmpty()) {
            return Optional.empty();
        }
        return resources
                .findById(grant.get().getSourceResourceId())
                .map(resource -> new GrantRow(grant.get(), resource.getOwnerWorkspaceId()));
    }

    private void validateSharePayload(
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            UUID recipientWorkspaceId,
            @Nullable OffsetDateTime expiresAt) {
        if (mode == null || !MODES.contains(mode)) {
            throw invalid();
        }
        if (actions == null
                || actions.isEmpty()
                || actions.stream().distinct().count() != actions.size()
                || actions.stream()
                        .anyMatch(action -> ResourcePolicyProjector.mapAction(action).isEmpty())) {
            throw invalid();
        }
        if (allowedWorkspaceIds == null
                || (!allowedWorkspaceIds.isEmpty()
                        && !allowedWorkspaceIds.contains(recipientWorkspaceId))) {
            throw invalid();
        }
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalid();
        }
    }

    private String workspaceKind(UUID workspaceId) {
        return workspaceDirectory
                .findById(workspaceId)
                .map(workspace -> workspace.getKind().name())
                .orElseThrow(ResourceShareService::notFound);
    }

    // ---------- Biên nhận idempotency của chính action ----------

    /**
     * Chèn biên nhận của action trong cùng transaction với mutation, cùng giao thức với service thư
     * viện: chèn được thì biên nhận chưa tồn tại và mutation chạy tiếp; đã có biên nhận cùng
     * payload thì trả định danh đích đầu tiên thay vì ghi lại; khác payload thì 409.
     */
    private Optional<UUID> claimReceipt(
            UUID actorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            UUID candidateTargetId) {
        int inserted =
                receipts.claim(
                        actorUserId,
                        idempotencyKey,
                        payloadHash,
                        workspaceId,
                        RECEIPT_TARGET_TYPE,
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

    // ---------- Ghi projection trước khi hỏi PDP ----------

    /** Ghi lại bundle hiện hành của target version qua operation dùng chung và chờ chiếu xong. */
    private void ensureVersionProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String targetKey) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        projector.recordResourceChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKey),
                                List.of(targetKey)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    /** Ghi lại bundle hiện hành của grant và của version tại nơi nhận, rồi chờ chiếu xong. */
    private void ensureShareProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, UUID grantId) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        shareProjector.recordShareChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(
                                        workspaceId, NativePolicyTemplates.grantKey(grantId)),
                                grantId));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    private String projectionPayloadHash(UUID workspaceId, String targetKey) {
        var node = JSON.createObjectNode();
        node.putArray("projectionTargets").add(targetKey);
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
                "Hành động mẫu thiếu hoặc sai trường bắt buộc.");
    }

    private static HttpProblemException notAcceptable() {
        return new HttpProblemException(
                HttpStatus.CONFLICT,
                "SHARE_NOT_ACCEPTABLE",
                "Lượt cấp không còn ở trạng thái chờ nhận hoặc đã hết cửa sổ hiệu lực.");
    }
}
