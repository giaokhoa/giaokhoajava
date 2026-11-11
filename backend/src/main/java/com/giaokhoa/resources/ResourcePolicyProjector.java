package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.resources.model.LibraryItem;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceAssignment;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceScopePreconditions;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationPartitionRepository;
import com.giaokhoa.resources.repository.ResourcesSampleActivityRepository;
import com.giaokhoa.resources.repository.ResourcesUserAccountRepository;
import com.giaokhoa.resources.repository.ResourcesWorkspaceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.tenancy.model.Workspace;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của resource version: nguồn sự thật duy nhất về desired native graph của các target
 * version (chỉ {@code content.read}, không bao giờ {@code answer.read}). Version hệ thống đã LOCKED
 * cho {@code practice} với mọi actor đã đủ điều kiện danh tính, chỉ cho {@code library.save} trước
 * khi lưu và cho đọc/dùng/fork sau khi đã có mục thư viện không
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace ngữ cảnh với scope danh nghĩa {@code content.read}; projection mang dependency là GRANT
 * partition của các grant làm nguồn cho nó để fence của protected request và phép đóng phụ thuộc
 * luôn thấy grant đang đổi trạng thái. Projector vừa tính bundle khi ghi (qua {@link
 * #recordResourceChange}), vừa dựng lại đúng bundle đó khi {@code ProjectionReconciler} đối chiếu
 * desired hash. Các bảng thư viện/phân công/hoạt động nằm dưới RLS theo workspace ngữ cảnh nên mọi
 * phép dựng bundle tự bọc trong một transaction ngắn có đặt context của đúng workspace trước truy
 * vấn đầu tiên, như các projector bundle khác.
 */
@Component
public class ResourcePolicyProjector {

    private static final String ACTOR_DESCRIPTOR = "RESOURCE_ACTOR";
    private static final String ACTOR_PERIOD_DESCRIPTOR = "RESOURCE_ACTOR_PERIOD";
    private static final String GROUP_DESCRIPTOR = "RESOURCE_GROUP";
    private static final String GROUP_PERIOD_DESCRIPTOR = "RESOURCE_GROUP_PERIOD";
    private static final String LICENSE_DESCRIPTOR = "RESOURCE_LICENSE";
    private static final String BUNDLE_SCOPE =
            PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
    private static final String FORK_ORIGIN_MARKER = "origin=FORK";
    private static final String SHARE_ORIGIN_MARKER = "origin=SHARE";

    /** Đoạn phân cách workspace trong canonical descriptor. */
    private static final String WORKSPACE_MARKER = "|workspace=";

    /** Đoạn phân cách target trong canonical descriptor. */
    private static final String TARGET_MARKER = "|target=";

    /** Giá trị cột workspace.kind cho không gian cá nhân. */
    private static final String PERSONAL_KIND = "PERSONAL";

    /** Giá trị cột workspace.kind cho không gian trung tâm. */
    private static final String CENTER_KIND = "CENTER";

    /** Số phần tối thiểu của một canonical descriptor (loại nguồn và ít nhất một cặp khóa). */
    private static final int MIN_DESCRIPTOR_PARTS = 2;

    /** Các scope đọc mà chủ sở hữu native có không cần gate gói (hết gói vẫn xem lại được). */
    private static final List<String> OWNER_READ_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.PRACTICE.scopeName());

    /** Các scope mở rộng của chủ sở hữu native/tác giả: phải gate theo từng kỳ gói hiện hành. */
    private static final List<String> OWNER_EXPANSION_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.USE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.FORK.scopeName(),
                    PermissionCatalog.ResourceVersionScope.SHARE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.REVIEW.scopeName(),
                    PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName());

    /** Các scope đọc của tác giả tại center (không có practice trên target version). */
    private static final List<String> AUTHOR_READ_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName());

    /** Scope mà một bản phái sinh theo license còn giữ được từ grant gốc: chỉ đọc/dùng/duyệt. */
    private static final List<String> DERIVED_LICENSE_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.USE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.REVIEW.scopeName());

    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;
    private final ResourceVersionRepository versions;
    private final ResourceRepository resources;
    private final LibraryItemRepository libraryItems;
    private final ShareGrantRepository grants;
    private final ResourceDependencyRepository dependencies;
    private final ResourceAssignmentRepository assignments;
    private final ResourcesWorkspaceRepository workspaces;
    private final ResourcesUserAccountRepository userAccounts;
    private final ResourcesSampleActivityRepository activities;
    private final ResourcesAuthorizationPartitionRepository partitions;
    private final ResourceCopies copies;
    private final ResourceScopePreconditions preconditions;

    public ResourcePolicyProjector(
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager,
            ResourceVersionRepository versions,
            ResourceRepository resources,
            LibraryItemRepository libraryItems,
            ShareGrantRepository grants,
            ResourceDependencyRepository dependencies,
            ResourceAssignmentRepository assignments,
            ResourcesWorkspaceRepository workspaces,
            ResourcesUserAccountRepository userAccounts,
            ResourcesSampleActivityRepository activities,
            ResourcesAuthorizationPartitionRepository partitions,
            ResourceCopies copies,
            ResourceScopePreconditions preconditions) {
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.versions = versions;
        this.resources = resources;
        this.libraryItems = libraryItems;
        this.grants = grants;
        this.dependencies = dependencies;
        this.assignments = assignments;
        this.workspaces = workspaces;
        this.userAccounts = userAccounts;
        this.activities = activities;
        this.partitions = partitions;
        this.copies = copies;
        this.preconditions = preconditions;
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle resource version. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một target resource version, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.ResourceVersionScope.values());
    }

    /** Dấu mở đầu của {@code usage_constraints} trên cạnh provenance do fork ghi. */
    public static String forkOriginMarker() {
        return FORK_ORIGIN_MARKER;
    }

    /** Dấu mở đầu của {@code usage_constraints} trên cạnh provenance do copy-on-share ghi. */
    public static String shareOriginMarker() {
        return SHARE_ORIGIN_MARKER;
    }

    /**
     * Map một token action lưu ở tầng grant/assignment sang scope mức version tương ứng: {@code
     * draft.edit} trên target version nghĩa là tạo bản chỉnh sửa, tức scope {@code fork}; các token
     * trùng tên scope thì giữ nguyên; token lạ không cho scope nào.
     */
    public static Optional<String> mapAction(String action) {
        return switch (action) {
            case "content.read" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName());
            case "answer.read" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName());
            case "use" -> Optional.of(PermissionCatalog.ResourceVersionScope.USE.scopeName());
            case "review" -> Optional.of(PermissionCatalog.ResourceVersionScope.REVIEW.scopeName());
            case "share" -> Optional.of(PermissionCatalog.ResourceVersionScope.SHARE.scopeName());
            case "fork" -> Optional.of(PermissionCatalog.ResourceVersionScope.FORK.scopeName());
            case "library.save" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName());
            case "draft.edit" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.FORK.scopeName());
            default -> Optional.empty();
        };
    }

    /** Các scope mức version mà một danh sách token action lưu trữ cấp được. */
    public static List<String> mapActions(List<String> actions) {
        return actions.stream()
                .map(ResourcePolicyProjector::mapAction)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Toàn bộ descriptor của bundle của một target resource version, dựng từ dữ kiện nguồn hiện
     * hành. Rỗng khi version không còn tồn tại hoặc không nguồn nào còn hiệu lực — rỗng nghĩa là
     * target đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String targetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        if (type != NativePolicyTemplates.TargetType.RESOURCE_VERSION) {
            throw new IllegalArgumentException("Not a resource version target key: " + targetKey);
        }
        String[] segments = targetKey.split(":");
        UUID workspaceId = UUID.fromString(segments[1]);
        UUID resourceId = UUID.fromString(segments[3]);
        UUID versionId = UUID.fromString(segments[5]);
        List<String> bundle =
                transactions.execute(
                        _ -> buildBundle(workspaceId, resourceId, versionId, targetKey));
        return Objects.requireNonNull(bundle, "bundle");
    }

    /** Các descriptor của riêng một scope trong bundle của target, dựng lại từ dữ kiện nguồn. */
    public List<String> sourcesFor(String targetKey, String scope) {
        return bundleDescriptors(targetKey).stream()
                .filter(descriptor -> descriptor.endsWith(PolicyGraph.SCOPE_MARKER + scope))
                .toList();
    }

    /**
     * Mọi target resource version có thể có nguồn tại workspace: mọi version của resource thuộc
     * workspace, mọi version đã lưu/giao/được cấp grant/phân công tại workspace và mọi version phái
     * sinh của resource thuộc workspace.
     */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    Set<String> keys = new TreeSet<>();
                    List<UUID> ownedResourceIds = resources.findIdsByOwnerWorkspaceId(workspaceId);
                    for (ResourceVersion version : versionsOf(ownedResourceIds)) {
                        keys.add(targetKey(workspaceId, version));
                    }
                    for (LibraryItem item : libraryItems.findByWorkspaceId(workspaceId)) {
                        keys.add(
                                NativePolicyTemplates.resourceVersionKey(
                                        workspaceId, item.getResourceId(), item.getVersionId()));
                    }
                    for (ShareGrant grant : grants.findByRecipientWorkspaceId(workspaceId)) {
                        keys.add(
                                NativePolicyTemplates.resourceVersionKey(
                                        workspaceId,
                                        grant.getSourceResourceId(),
                                        grant.getSourceVersionId()));
                    }
                    for (ResourcesSampleActivityRepository.ActivityPair pair :
                            activities.findDistinctByWorkspaceId(workspaceId)) {
                        keys.add(
                                NativePolicyTemplates.resourceVersionKey(
                                        workspaceId, pair.getResourceId(), pair.getVersionId()));
                    }
                    if (!ownedResourceIds.isEmpty()) {
                        for (ResourceDependency dependency :
                                dependencies.findByDerivedResourceIdIn(ownedResourceIds)) {
                            keys.add(
                                    NativePolicyTemplates.resourceVersionKey(
                                            workspaceId,
                                            dependency.getDerivedResourceId(),
                                            dependency.getDerivedVersionId()));
                        }
                    }
                    Set<UUID> assignedResourceIds = new LinkedHashSet<>();
                    for (ResourceAssignment assignment :
                            assignments.findByWorkspaceIdAndStateAndResourceIdIsNotNull(
                                    workspaceId, ResourceAssignment.State.ACTIVE)) {
                        assignedResourceIds.add(
                                Objects.requireNonNull(assignment.getResourceId(), "resource_id"));
                    }
                    for (ResourceVersion version : versionsOf(List.copyOf(assignedResourceIds))) {
                        keys.add(targetKey(workspaceId, version));
                    }
                    Set<UUID> assignedSubjectIds = new LinkedHashSet<>();
                    for (ResourceAssignment assignment :
                            assignments.findByWorkspaceIdAndStateAndResourceIdIsNull(
                                    workspaceId, ResourceAssignment.State.ACTIVE)) {
                        assignedSubjectIds.add(
                                Objects.requireNonNull(assignment.getSubjectId(), "subject_id"));
                    }
                    if (!assignedSubjectIds.isEmpty()) {
                        for (ResourceVersion version :
                                versionsOf(resources.findIdsBySubjectIdIn(assignedSubjectIds))) {
                            keys.add(targetKey(workspaceId, version));
                        }
                    }
                    return List.copyOf(keys);
                });
        return Objects.requireNonNull(targets, "targets");
    }

    private List<ResourceVersion> versionsOf(List<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return versions.findByResourceIdIn(resourceIds);
    }

    private static String targetKey(UUID workspaceId, ResourceVersion version) {
        return NativePolicyTemplates.resourceVersionKey(
                workspaceId, version.getResourceId(), version.getId());
    }

    /**
     * Desired projection của mọi bundle resource version hiện có của workspace, để gắn vào một
     * operation ghi kèm khi dữ kiện tiền đề của bundle (membership, kỳ gói, phân công giảng dạy,
     * hoạt động) thay đổi.
     */
    public List<AuthorizationOperationService.DesiredProjection> allBundleProjections(
            UUID workspaceId) {
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : allBundleTargetKeys(workspaceId)) {
            projections.add(desiredProjection(workspaceId, targetKey));
        }
        return List.copyOf(projections);
    }

    /**
     * Ghi operation cho một thay đổi làm đổi nguồn của các bundle resource version vừa được ghi
     * trong cùng transaction của service gọi: bump desired revision của WORKSPACE partition của
     * workspace, fence nó và lưu bundle hiện hành của từng target bị ảnh hưởng. Replay cùng
     * idempotency key và payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác
     * payload là 409 — cả hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordResourceChange(
            UUID initiatorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            List<String> targetKeys) {
        AuthorizationPartition partition =
                partitions
                        .findByKindAndOwnerKey(
                                AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Workspace authorization partition is missing: "
                                                + workspaceId));
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : targetKeys) {
            projections.add(desiredProjection(workspaceId, targetKey));
        }
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partition.getId(), List.copyOf(projections))));
    }

    /**
     * Các gate native của một canonical descriptor resource: actor cụ thể cho descriptor actor,
     * group nguồn cho descriptor group, mỗi dạng kèm time policy khi descriptor mang cửa sổ kỳ
     * gói/grant; descriptor license mang đúng một audience (actor của owner ở personal hoặc group
     * thành viên ở center) AND cửa sổ hiệu lực của grant. Dùng bởi reconciliation để dựng lại đúng
     * graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < MIN_DESCRIPTOR_PARTS) {
            throw new IllegalArgumentException("Unsupported resource descriptor: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        switch (parts[0]) {
            case ACTOR_DESCRIPTOR ->
                    gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
            case ACTOR_PERIOD_DESCRIPTOR -> {
                gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
                gates.add(validityGate(parts, descriptor, true));
            }
            case GROUP_DESCRIPTOR ->
                    gates.add(
                            new PolicyGraph.Gate.GroupMembership(requireGroup(parts, descriptor)));
            case GROUP_PERIOD_DESCRIPTOR -> {
                gates.add(new PolicyGraph.Gate.GroupMembership(requireGroup(parts, descriptor)));
                gates.add(validityGate(parts, descriptor, true));
            }
            case LICENSE_DESCRIPTOR -> {
                String group = partValue(parts, "group=");
                if (group != null && !group.isBlank()) {
                    gates.add(new PolicyGraph.Gate.GroupMembership(group));
                } else {
                    gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
                }
                gates.add(validityGate(parts, descriptor, false));
            }
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported resource descriptor: " + descriptor);
        }
        return List.copyOf(gates);
    }

    // ---------- Dựng nguồn ----------

    private List<String> buildBundle(
            UUID workspaceId, UUID resourceId, UUID versionId, String targetKey) {
        Optional<ResourceVersion> version = versions.findByResourceIdAndId(resourceId, versionId);
        Optional<Resource> resource = resources.findById(resourceId);
        if (version.isEmpty() || resource.isEmpty()) {
            return List.of();
        }
        Optional<Workspace> ownerWorkspace =
                workspaces.findById(resource.get().getOwnerWorkspaceId());
        Optional<Workspace> contextWorkspace = workspaces.findById(workspaceId);
        if (ownerWorkspace.isEmpty() || contextWorkspace.isEmpty()) {
            return List.of();
        }
        UUID ownerWorkspaceId = resource.get().getOwnerWorkspaceId();
        UUID createdByUserId = resource.get().getCreatedByUserId();
        UUID subjectId = resource.get().getSubjectId();
        String reviewState = version.get().getReviewState().name();
        String ownerKind = ownerWorkspace.get().getKind().name();
        String contextKind = contextWorkspace.get().getKind().name();

        List<String> descriptors = new ArrayList<>();
        Optional<ShareGrant> derivedGrant = derivedGrant(resourceId, versionId);
        if (derivedGrant.isPresent()) {
            derivedLicenseSources(
                    descriptors, workspaceId, contextKind, derivedGrant.get(), targetKey);
            return descriptors.stream().sorted().distinct().toList();
        }
        boolean ownerBranch = ownerWorkspaceId.equals(workspaceId);
        boolean systemBranch = "SYSTEM".equals(ownerKind) && "LOCKED".equals(reviewState);
        // Kỳ gói của workspace ngữ cảnh nạp một lần cho mọi nhánh nguồn của bundle này, thay vì
        // mỗi nhánh tự nạp lại cùng một danh sách.
        List<ResourceScopePreconditions.SubscriptionPeriod> periods =
                ownerBranch || (systemBranch && PERSONAL_KIND.equals(contextKind))
                        ? preconditions.subscriptionPeriods(workspaceId)
                        : List.of();
        if (ownerBranch) {
            if (PERSONAL_KIND.equals(contextKind)) {
                personalOwnerSources(descriptors, workspaceId, targetKey, periods);
            } else if (CENTER_KIND.equals(contextKind)) {
                centerAuthorSources(descriptors, workspaceId, createdByUserId, targetKey, periods);
            }
        }
        assignmentSources(descriptors, workspaceId, contextKind, resourceId, subjectId, targetKey);
        activitySources(descriptors, workspaceId, contextKind, resourceId, versionId, targetKey);
        licenseSources(descriptors, workspaceId, contextKind, resourceId, versionId, targetKey);
        if (systemBranch) {
            systemSources(
                    descriptors,
                    workspaceId,
                    contextKind,
                    resourceId,
                    versionId,
                    targetKey,
                    periods);
        }
        return descriptors.stream().sorted().distinct().toList();
    }

    /**
     * Grant làm căn cứ phái sinh của version khi version này là bản sao theo license: cạnh
     * provenance mang grant và dấu origin do fork hoặc copy-on-share ghi, ở bất kỳ trạng thái nào
     * của grant — cạnh tồn tại là đủ để version này là bản phái sinh bị giới hạn bởi grant đó. Rỗng
     * khi version không phái sinh từ grant nào (bản gốc hoặc fork theo quyền owner/phân công).
     */
    private Optional<ShareGrant> derivedGrant(UUID resourceId, UUID versionId) {
        return dependencies
                .findGrantProvenance(resourceId, versionId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .flatMap(edge ->
                        grants.findById(
                                Objects.requireNonNull(edge.getShareGrantId(), "share_grant_id")));
    }

    /**
     * Nhánh license phái sinh: audience là phía sở hữu bản phái sinh trong giới hạn workspace cho
     * phép của grant; scope là các scope đọc/dùng mà grant gốc cấp. Khi grant không còn ACCEPTED
     * hoặc workspace không còn trong phạm vi cho phép thì không còn nguồn nào — toàn bundle rỗng,
     * nhánh owner/tác giả không hồi lại trên bản phái sinh.
     */
    private void derivedLicenseSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            ShareGrant grant,
            String targetKey) {
        if (grant.getState() != ShareGrant.State.ACCEPTED || !allowedHere(grant, workspaceId)) {
            return;
        }
        for (String scope : mapActions(grant.getActions())) {
            if (DERIVED_LICENSE_SCOPES.contains(scope)) {
                descriptors.add(
                        licenseDescriptor(workspaceId, contextKind, grant, targetKey, scope));
            }
        }
    }

    /** Nhánh chủ sở hữu native ở personal: owner đọc không gate gói, mở rộng gate theo kỳ gói. */
    private void personalOwnerSources(
            List<String> descriptors,
            UUID workspaceId,
            String targetKey,
            List<ResourceScopePreconditions.SubscriptionPeriod> periods) {
        Optional<OwnerRow> owner = personalOwner(workspaceId);
        if (owner.isEmpty()) {
            return;
        }
        for (String scope : OWNER_READ_SCOPES) {
            descriptors.add(
                    actorDescriptor(
                            workspaceId,
                            owner.get().userId(),
                            owner.get().subject(),
                            targetKey,
                            scope));
        }
        for (ResourceScopePreconditions.SubscriptionPeriod period : periods) {
            for (String scope : OWNER_EXPANSION_SCOPES) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                scope));
            }
        }
    }

    /**
     * Nhánh tác giả ở center: người tạo resource chỉ còn là nguồn khi membership của họ ở center
     * còn ACTIVE (membership đổi thì đường ghi membership ghi lại bundle này); scope mở rộng gate
     * theo kỳ gói của center, không liên quan gói cá nhân của tác giả.
     */
    private void centerAuthorSources(
            List<String> descriptors,
            UUID workspaceId,
            UUID authorUserId,
            String targetKey,
            List<ResourceScopePreconditions.SubscriptionPeriod> periods) {
        Optional<UserAccount> author = userAccounts.findById(authorUserId);
        if (author.isEmpty() || !preconditions.centerMembershipActive(workspaceId, authorUserId)) {
            return;
        }
        String subject = author.get().getKeycloakSub();
        for (String scope : AUTHOR_READ_SCOPES) {
            descriptors.add(actorDescriptor(workspaceId, authorUserId, subject, targetKey, scope));
        }
        for (ResourceScopePreconditions.SubscriptionPeriod period : periods) {
            for (String scope : OWNER_EXPANSION_SCOPES) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId, authorUserId, subject, period, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh phân công tường minh: assignment ACTIVE của đúng workspace ngữ cảnh khớp resource hoặc
     * môn của resource; ở center người được phân công phải còn membership ACTIVE. Token lưu trữ map
     * sang scope mức version qua {@link #mapAction} — {@code draft.edit} thành {@code fork}, {@code
     * content.read} giữ nguyên nhờ catalog đã nới ở migration V028.
     */
    private void assignmentSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID subjectId,
            String targetKey) {
        List<ResourceAssignment> matching =
                assignments.findMatching(
                        workspaceId, ResourceAssignment.State.ACTIVE, resourceId, subjectId);
        Map<UUID, String> subjects =
                userAccounts
                        .findByIdIn(
                                matching.stream()
                                        .map(ResourceAssignment::getUserId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(UserAccount::getId, UserAccount::getKeycloakSub));
        Set<UUID> activeMembers =
                CENTER_KIND.equals(contextKind)
                        ? preconditions.centerMembershipActiveUserIds(
                                workspaceId, subjects.keySet())
                        : Set.of();
        for (ResourceAssignment assignment : matching) {
            if (CENTER_KIND.equals(contextKind)
                    && !activeMembers.contains(assignment.getUserId())) {
                continue;
            }
            String subject = subjects.get(assignment.getUserId());
            if (subject == null) {
                continue;
            }
            for (String scope : mapActions(assignment.getActions())) {
                descriptors.add(
                        actorDescriptor(
                                workspaceId, assignment.getUserId(), subject, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh học sinh theo hoạt động: hoạt động ASSIGNED trên đúng version tại workspace cho học
     * sinh của môn–khóa đó đọc nội dung — ở center qua group học sinh của môn–khóa (membership thật
     * do projector giảng dạy đồng bộ), ở personal qua actor của từng học sinh còn ghi danh (đúng
     * hình nguồn ghi danh của projector giảng dạy personal, vì personal không có group nguồn được
     * đồng bộ). Không scope nào khác — đáp án có route và scope riêng mà nhánh này không bao giờ
     * cấp.
     */
    private void activitySources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey) {
        String readScope = PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
        for (UUID courseSubjectId :
                activities.findAssignedCourseSubjectIds(workspaceId, resourceId, versionId)) {
            if (CENTER_KIND.equals(contextKind)) {
                descriptors.add(
                        groupDescriptor(
                                workspaceId,
                                NativePolicyTemplates.courseSubjectStudentsGroup(
                                        workspaceId, courseSubjectId),
                                targetKey,
                                readScope));
            } else if (PERSONAL_KIND.equals(contextKind)) {
                for (ResourceScopePreconditions.EnrolledStudent student :
                        preconditions.enrolledStudents(workspaceId, courseSubjectId)) {
                    descriptors.add(
                            actorDescriptor(
                                    workspaceId,
                                    student.userId(),
                                    student.keycloakSub(),
                                    targetKey,
                                    readScope));
                }
            }
        }
    }

    /**
     * Nhánh license: mỗi grant ACCEPTED cho workspace nhận trên đúng version cấp các scope do
     * actions của grant quyết định, trong cửa sổ hiệu lực của grant và chỉ khi workspace nhận nằm
     * trong phạm vi workspace cho phép (rỗng nghĩa là không giới hạn). Ngoại lệ copy-on-share:
     * grant đã có bản sao tại workspace nhận thì chỉ còn scope {@code library.save} trên target của
     * version nguồn — quyền đọc/dùng sống trên target của bản sao.
     */
    private void licenseSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey) {
        String saveScope = PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName();
        List<ShareGrant> applicable =
                grants
                        .findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionIdAndStateOrderByCreatedAtAscIdAsc(
                                workspaceId, resourceId, versionId, ShareGrant.State.ACCEPTED)
                        .stream()
                        .filter(grant -> allowedHere(grant, workspaceId))
                        .toList();
        Set<UUID> copiedGrantIds =
                copies.grantsWithCopyAt(
                        resourceId,
                        versionId,
                        applicable.stream().map(ShareGrant::getId).toList(),
                        workspaceId);
        for (ShareGrant grant : applicable) {
            List<String> scopes = mapActions(grant.getActions());
            boolean copied = copiedGrantIds.contains(grant.getId());
            if (copied) {
                if (scopes.contains(saveScope)) {
                    descriptors.add(
                            licenseDescriptor(
                                    workspaceId, contextKind, grant, targetKey, saveScope));
                }
                continue;
            }
            for (String scope : scopes) {
                descriptors.add(
                        licenseDescriptor(workspaceId, contextKind, grant, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh version hệ thống đã công bố: {@code practice} cho mọi actor đã đủ điều kiện danh tính
     * (group điều kiện danh tính là nguồn duy nhất — làm đề hệ thống không cần gói, ghi danh hay
     * mục thư viện theo bảng nguồn của hợp đồng authorization, và cổng điều kiện danh tính chung
     * ngoài OR vẫn áp như mọi permission); {@code library.save} cho phía nhận đủ điều kiện của giấy
     * phép nền tảng trước cả khi lưu — ở personal là owner kèm gate kỳ gói cá nhân (điều kiện nhận
     * là cá nhân có gói), ở center là group thành viên không kèm kỳ gói của center (giấy phép nội
     * dung nền tảng không gắn gói center, theo bảng nguồn của hợp đồng authorization);
     * đọc/dùng/fork chỉ sau khi workspace đã có mục thư viện của chính version này không gắn grant
     * — bản lưu đứng yên ở version đã lưu, version mới của nguồn chưa được nhận thì không có nhánh
     * đọc.
     */
    private void systemSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey,
            List<ResourceScopePreconditions.SubscriptionPeriod> periods) {
        if (PERSONAL_KIND.equals(contextKind) || CENTER_KIND.equals(contextKind)) {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.ELIGIBILITY_GROUP,
                            targetKey,
                            PermissionCatalog.ResourceVersionScope.PRACTICE.scopeName()));
        }
        String saveScope = PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName();
        if (PERSONAL_KIND.equals(contextKind)) {
            Optional<OwnerRow> owner = personalOwner(workspaceId);
            if (owner.isEmpty()) {
                return;
            }
            for (ResourceScopePreconditions.SubscriptionPeriod period : periods) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                saveScope));
            }
        } else if (CENTER_KIND.equals(contextKind)) {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            saveScope));
        } else {
            return;
        }
        boolean saved =
                libraryItems.existsByWorkspaceIdAndResourceIdAndVersionIdAndShareGrantIdIsNull(
                        workspaceId, resourceId, versionId);
        if (!saved) {
            return;
        }
        String readScope = PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
        String useScope = PermissionCatalog.ResourceVersionScope.USE.scopeName();
        String forkScope = PermissionCatalog.ResourceVersionScope.FORK.scopeName();
        if (PERSONAL_KIND.equals(contextKind)) {
            Optional<OwnerRow> owner = personalOwner(workspaceId);
            if (owner.isEmpty()) {
                return;
            }
            descriptors.add(
                    actorDescriptor(
                            workspaceId,
                            owner.get().userId(),
                            owner.get().subject(),
                            targetKey,
                            readScope));
            for (ResourceScopePreconditions.SubscriptionPeriod period : periods) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                useScope));
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                forkScope));
            }
        } else {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            readScope));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            useScope));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            forkScope));
        }
    }

    // ---------- Dữ kiện nguồn dùng chung ----------

    private record OwnerRow(UUID userId, String subject) {}

    private Optional<OwnerRow> personalOwner(UUID workspaceId) {
        return workspaces
                .findById(workspaceId)
                .filter(workspace -> workspace.getKind() == Workspace.Kind.PERSONAL)
                .flatMap(workspace ->
                        workspace.getOwnerUserId() == null
                                ? Optional.empty()
                                : userAccounts
                                        .findById(workspace.getOwnerUserId())
                                        .map(owner ->
                                                new OwnerRow(
                                                        owner.getId(), owner.getKeycloakSub())));
    }

    private boolean allowedHere(ShareGrant grant, UUID workspaceId) {
        List<UUID> allowed = grant.getAllowedWorkspaceIds();
        return allowed.isEmpty() || allowed.contains(workspaceId);
    }

    AuthorizationOperationService.DesiredProjection desiredProjection(
            UUID workspaceId, String targetKey) {
        String[] segments = targetKey.split(":");
        UUID resourceId = UUID.fromString(segments[3]);
        UUID versionId = UUID.fromString(segments[5]);
        return new AuthorizationOperationService.DesiredProjection(
                targetKey,
                BUNDLE_SCOPE,
                bundleDescriptors(targetKey),
                grantDependencyPartitions(workspaceId, resourceId, versionId));
    }

    /**
     * GRANT partition của các grant làm nguồn cho target này (grant trực tiếp tới workspace nhận và
     * grant của cạnh phái sinh), chỉ khi partition đó đã tồn tại: projection phụ thuộc vào nó để
     * thu hồi/revoke của grant fence đúng các request đọc/ghi trên target.
     */
    private List<UUID> grantDependencyPartitions(
            UUID workspaceId, UUID resourceId, UUID versionId) {
        Set<UUID> grantIds = new LinkedHashSet<>();
        for (ShareGrant grant :
                grants.findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionId(
                        workspaceId, resourceId, versionId)) {
            grantIds.add(grant.getId());
        }
        for (ResourceDependency dependency :
                dependencies.findByDerivedResourceIdAndDerivedVersionId(resourceId, versionId)) {
            if (dependency.getShareGrantId() != null) {
                grantIds.add(dependency.getShareGrantId());
            }
        }
        if (grantIds.isEmpty()) {
            return List.of();
        }
        List<String> ownerKeys = grantIds.stream().map(grantId -> "grant:" + grantId).toList();
        return partitions
                .findByKindAndOwnerKeyIn(AuthorizationPartition.Kind.GRANT, ownerKeys)
                .stream()
                .map(AuthorizationPartition::getId)
                .toList();
    }

    // ---------- Tiện ích dựng descriptor ----------

    private static String actorDescriptor(
            UUID workspaceId, UUID userId, String subject, String targetKey, String scope) {
        return ACTOR_DESCRIPTOR
                + WORKSPACE_MARKER
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + subject
                + TARGET_MARKER
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static String actorPeriodDescriptor(
            UUID workspaceId,
            UUID userId,
            String subject,
            ResourceScopePreconditions.SubscriptionPeriod period,
            String targetKey,
            String scope) {
        return ACTOR_PERIOD_DESCRIPTOR
                + WORKSPACE_MARKER
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + subject
                + "|period="
                + period.id()
                + "|from="
                + period.validFrom()
                + "|until="
                + period.expiresAt()
                + TARGET_MARKER
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static String groupDescriptor(
            UUID workspaceId, String groupPath, String targetKey, String scope) {
        return GROUP_DESCRIPTOR
                + WORKSPACE_MARKER
                + workspaceId
                + "|group="
                + groupPath
                + TARGET_MARKER
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    /**
     * Descriptor license: audience theo loại workspace nhận (actor của owner ở personal, group
     * thành viên ở center), grant và cửa sổ hiệu lực của nó; {@code until=-} khi grant không có mốc
     * hết hạn.
     */
    private String licenseDescriptor(
            UUID workspaceId,
            String contextKind,
            ShareGrant grant,
            String targetKey,
            String scope) {
        // Time policy native chỉ có độ chính xác tới giây: cắt tròn cửa sổ của grant về tròn
        // giây ngay khi dựng descriptor để giá trị được chiếu là đúng giá trị gate kiểm.
        Instant validFrom = grant.getValidFrom().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = grant.getExpiresAt();
        StringBuilder descriptor =
                new StringBuilder(LICENSE_DESCRIPTOR).append(WORKSPACE_MARKER).append(workspaceId);
        if (PERSONAL_KIND.equals(contextKind)) {
            OwnerRow owner =
                    personalOwner(workspaceId)
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Personal workspace has no owner: " + workspaceId));
            descriptor
                    .append("|user=")
                    .append(owner.userId())
                    .append("|subject=")
                    .append(owner.subject());
        } else {
            descriptor.append("|group=").append(NativePolicyTemplates.membersGroup(workspaceId));
        }
        return descriptor
                .append("|grant=")
                .append(grant.getId())
                .append("|from=")
                .append(validFrom)
                .append("|until=")
                .append(
                        expiresAt == null
                                ? "-"
                                : expiresAt.truncatedTo(ChronoUnit.SECONDS).toString())
                .append("|target=")
                .append(targetKey)
                .append(PolicyGraph.SCOPE_MARKER)
                .append(scope)
                .toString();
    }

    private static PolicyGraph.Gate.Validity validityGate(
            String[] parts, String descriptor, boolean bounded) {
        String from = partValue(parts, "from=");
        String until = partValue(parts, "until=");
        if (from == null || from.isBlank() || "-".equals(from)) {
            throw new IllegalArgumentException(
                    "Resource descriptor has no validity start: " + descriptor);
        }
        Instant validFrom = Instant.parse(from);
        Instant expiresAt = until == null || "-".equals(until) ? null : Instant.parse(until);
        if (bounded && expiresAt == null) {
            throw new IllegalArgumentException(
                    "Resource descriptor has no validity end: " + descriptor);
        }
        return new PolicyGraph.Gate.Validity(validFrom, expiresAt);
    }

    private static String requireSubject(String[] parts, String descriptor) {
        String subject = partValue(parts, "subject=");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Resource descriptor has no subject: " + descriptor);
        }
        return subject;
    }

    private static String requireGroup(String[] parts, String descriptor) {
        String group = partValue(parts, "group=");
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("Resource descriptor has no group: " + descriptor);
        }
        return group;
    }

    private static @Nullable String partValue(String[] parts, String prefix) {
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }
}
