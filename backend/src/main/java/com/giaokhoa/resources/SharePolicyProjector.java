package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationPartitionRepository;
import com.giaokhoa.resources.repository.ResourcesUserAccountRepository;
import com.giaokhoa.resources.repository.ResourcesWorkspaceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.tenancy.model.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của share grant: nguồn sự thật duy nhất về desired native graph của các target {@code
 * grant:{id}} với hai scope cố định {@code accept} và {@code revoke} của hợp đồng authorization.
 * Bundle của một grant chỉ phụ thuộc trạng thái và mode của chính grant đó: khi còn {@code
 * OFFERED}, nguồn duy nhất là phía nhận — chủ sở hữu/quản lý của workspace nhận ở center, chủ sở
 * hữu ở personal — với scope {@code accept}; khi đã {@code ACCEPTED} và mode là {@code REVOCABLE},
 * nguồn duy nhất là chính người cấp với scope {@code revoke}. Grant {@code LONG_TERM} không có
 * nguồn revoke ở bất kỳ trạng thái nào, nên thu hồi đơn phương bị PDP từ chối ngay ở tầng graph,
 * đúng FR-037 và hợp đồng HTTP (REVOKE_SHARE chỉ dành cho REVOCABLE). Grant đã {@code REVOKED} hoặc
 * {@code EXPIRED} không còn nguồn nào: bundle rỗng nghĩa là target phải bị thu hồi.
 *
 * <p>Descriptor dùng lại đúng từ vựng canonical của họ resource ({@code RESOURCE_ACTOR} và {@code
 * RESOURCE_GROUP}) nên phép dựng gate native dùng chung một đường với bundle resource version.
 * Projection của grant nằm trên GRANT partition của chính grant — partition được tạo khi grant được
 * ghi lần đầu — còn projection của version nguồn tại workspace nhận mang dependency là partition đó
 * (do {@link ResourcePolicyProjector} tính), để fence của mọi protected request trên version đều
 * thấy grant đang đổi trạng thái. Mỗi lần ghi, projector ghi kèm desired projection của version
 * nguồn tại workspace nhận trên WORKSPACE partition của nơi nhận, và — khi grant đã được
 * copy-on-share — desired projection của các target bản sao tại workspace nhận, để quyền phái sinh
 * trên bản sao được dựng ngay trong cùng operation với trạng thái mới của grant.
 */
@Component
public class SharePolicyProjector {

    private static final String BUNDLE_SCOPE = PermissionCatalog.GrantScope.ACCEPT.scopeName();
    private static final String ACCEPT_SCOPE = PermissionCatalog.GrantScope.ACCEPT.scopeName();
    private static final String REVOKE_SCOPE = PermissionCatalog.GrantScope.REVOKE.scopeName();

    /** Giá trị cột workspace.kind cho không gian trung tâm. */
    private static final String CENTER_KIND = "CENTER";

    /** Giá trị cột workspace.kind cho không gian cá nhân. */
    private static final String PERSONAL_KIND = "PERSONAL";

    /** Trạng thái grant đang chờ phía nhận accept. */
    private static final String OFFERED_STATE = "OFFERED";

    private final AuthorizationOperationService operations;
    private final ResourcePolicyProjector resourceProjector;
    private final ShareGrantRepository grants;
    private final ResourceRepository resources;
    private final ResourcesWorkspaceRepository workspaces;
    private final ResourcesUserAccountRepository userAccounts;
    private final ResourcesAuthorizationPartitionRepository partitions;
    private final ResourceCopies copies;
    private final TransactionTemplate requiresNew;

    public SharePolicyProjector(
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager,
            ResourcePolicyProjector resourceProjector,
            ShareGrantRepository grants,
            ResourceRepository resources,
            ResourcesWorkspaceRepository workspaces,
            ResourcesUserAccountRepository userAccounts,
            ResourcesAuthorizationPartitionRepository partitions,
            ResourceCopies copies) {
        this.operations = operations;
        this.resourceProjector = resourceProjector;
        this.grants = grants;
        this.resources = resources;
        this.workspaces = workspaces;
        this.userAccounts = userAccounts;
        this.partitions = partitions;
        this.copies = copies;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle share grant. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một target grant, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.GrantScope.values());
    }

    /** Dữ kiện bền của một grant đủ để dựng bundle và xác định các partition liên quan. */
    public record GrantFacts(
            UUID id,
            UUID sourceResourceId,
            UUID sourceVersionId,
            UUID sourceWorkspaceId,
            UUID grantorUserId,
            String grantorSubject,
            UUID recipientWorkspaceId,
            String recipientKind,
            @Nullable UUID recipientOwnerUserId,
            @Nullable String recipientOwnerSubject,
            String mode,
            String state) {}

    /**
     * Toàn bộ descriptor của bundle của một target grant, dựng từ dữ kiện bền hiện hành. Rỗng khi
     * grant không còn tồn tại hoặc không nguồn nào còn hiệu lực — rỗng nghĩa là target phải bị thu
     * hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String grantTargetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(grantTargetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + grantTargetKey));
        if (type != NativePolicyTemplates.TargetType.GRANT) {
            throw new IllegalArgumentException("Not a grant target key: " + grantTargetKey);
        }
        UUID grantId = UUID.fromString(grantTargetKey.substring("grant:".length()));
        return loadGrant(grantId)
                .map(grant -> descriptorsFor(grant, grantTargetKey))
                .orElseGet(List::of);
    }

    /** Các descriptor của riêng một scope trong bundle của target, dựng lại từ dữ kiện bền. */
    public List<String> sourcesFor(String targetKey, String scope) {
        return bundleDescriptors(targetKey).stream()
                .filter(descriptor -> descriptor.endsWith("|scope=" + scope))
                .toList();
    }

    /**
     * Bundle thuần từ dữ kiện của grant: {@code OFFERED} chỉ có nguồn accept của phía nhận, {@code
     * ACCEPTED} + {@code REVOCABLE} chỉ có nguồn revoke của người cấp, mọi tổ hợp còn lại rỗng.
     */
    static List<String> descriptorsFor(GrantFacts grant, String targetKey) {
        List<String> descriptors = new ArrayList<>();
        if (OFFERED_STATE.equals(grant.state())) {
            if (CENTER_KIND.equals(grant.recipientKind())) {
                descriptors.add(
                        groupDescriptor(
                                grant.recipientWorkspaceId(),
                                NativePolicyTemplates.ownersGroup(grant.recipientWorkspaceId()),
                                targetKey,
                                ACCEPT_SCOPE));
                descriptors.add(
                        groupDescriptor(
                                grant.recipientWorkspaceId(),
                                NativePolicyTemplates.managersGroup(grant.recipientWorkspaceId()),
                                targetKey,
                                ACCEPT_SCOPE));
            } else if (PERSONAL_KIND.equals(grant.recipientKind())
                    && grant.recipientOwnerUserId() != null
                    && grant.recipientOwnerSubject() != null) {
                descriptors.add(
                        actorDescriptor(
                                grant.recipientWorkspaceId(),
                                grant.recipientOwnerUserId(),
                                grant.recipientOwnerSubject(),
                                targetKey,
                                ACCEPT_SCOPE));
            }
        }
        if ("ACCEPTED".equals(grant.state()) && "REVOCABLE".equals(grant.mode())) {
            descriptors.add(
                    actorDescriptor(
                            grant.sourceWorkspaceId(),
                            grant.grantorUserId(),
                            grant.grantorSubject(),
                            targetKey,
                            REVOKE_SCOPE));
        }
        return descriptors.stream().sorted().distinct().toList();
    }

    /**
     * Ghi operation cho trạng thái hiện hành của một grant vừa được ghi trong cùng luồng của
     * service gọi: tạo GRANT partition của grant nếu chưa có, bump nó với bundle hiện hành của
     * grant, và ghi kèm desired projection của version nguồn tại workspace nhận để dependency lên
     * GRANT partition được thiết lập ngay từ lượt cấp — cộng, khi grant đã có bản sao
     * copy-on-share, desired projection của từng target bản sao tại workspace nhận trên cùng
     * WORKSPACE partition của nơi nhận. Replay cùng idempotency key và payload trả lại operation đã
     * ghi mà không ghi lại gì, còn cùng key khác payload là 409 — cả hai do cơ chế operation dùng
     * chung xử lý. Caller bọc trong transaction của mình và chờ operation SUCCEEDED trước khi báo
     * thành công.
     */
    public AuthorizationOperation recordShareChange(
            UUID initiatorUserId,
            UUID routeWorkspaceId,
            String idempotencyKey,
            String payloadHash,
            UUID grantId) {
        GrantFacts grant =
                loadGrant(grantId)
                        .orElseThrow(() ->
                                new IllegalStateException("Share grant is missing: " + grantId));
        UUID grantPartition = ensureGrantPartition(grantId);
        String grantKey = NativePolicyTemplates.grantKey(grantId);
        String versionKey =
                NativePolicyTemplates.resourceVersionKey(
                        grant.recipientWorkspaceId(),
                        grant.sourceResourceId(),
                        grant.sourceVersionId());
        List<AuthorizationOperationService.PartitionChange> changes = new ArrayList<>();
        changes.add(
                new AuthorizationOperationService.PartitionChange(
                        grantPartition,
                        List.of(
                                new AuthorizationOperationService.DesiredProjection(
                                        grantKey,
                                        BUNDLE_SCOPE,
                                        bundleDescriptors(grantKey),
                                        List.of()))));
        List<AuthorizationOperationService.DesiredProjection> recipientProjections =
                new ArrayList<>();
        recipientProjections.add(
                resourceProjector.desiredProjection(grant.recipientWorkspaceId(), versionKey));
        for (ResourceCopies.CopyLocation location :
                copies.copiesOf(grant.sourceResourceId(), grant.sourceVersionId(), grantId)) {
            if (location.ownerWorkspaceId().equals(grant.recipientWorkspaceId())) {
                recipientProjections.add(
                        resourceProjector.desiredProjection(
                                grant.recipientWorkspaceId(),
                                NativePolicyTemplates.resourceVersionKey(
                                        grant.recipientWorkspaceId(),
                                        location.copy().resourceId(),
                                        location.copy().versionId())));
            }
        }
        changes.add(
                new AuthorizationOperationService.PartitionChange(
                        workspacePartition(grant.recipientWorkspaceId()), recipientProjections));
        return operations.applyDesiredState(
                initiatorUserId, routeWorkspaceId, idempotencyKey, payloadHash, changes);
    }

    /**
     * GRANT partition của một grant, tạo nếu chưa có: một grant có đúng một partition mang
     * owner_key {@code grant:{id}}, tạo lười ở lần ghi đầu tiên. Hàng mới mang đúng các giá trị mặc
     * định của schema (revision 0, state PENDING). Phép tạo chạy trong một transaction ngắn riêng
     * (REQUIRES_NEW) để hai lượt ghi đầu đồng thời thì lượt thua unique (kind, owner_key) chỉ mất
     * đơn vị riêng đó — transaction của caller không bị PostgreSQL đặt vào trạng thái aborted — rồi
     * đọc lại hàng của lượt thắng ở ngoài đơn vị riêng.
     */
    UUID ensureGrantPartition(UUID grantId) {
        String ownerKey = "grant:" + grantId;
        Optional<AuthorizationPartition> existing =
                partitions.findByKindAndOwnerKey(AuthorizationPartition.Kind.GRANT, ownerKey);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        UUID candidate = UUID.randomUUID();
        try {
            requiresNew.executeWithoutResult(_ ->
                    partitions.saveAndFlush(
                            new AuthorizationPartition(
                                    candidate,
                                    AuthorizationPartition.Kind.GRANT,
                                    ownerKey,
                                    0,
                                    0,
                                    AuthorizationPartition.State.PENDING)));
            return candidate;
        } catch (DataIntegrityViolationException race) {
            return partitions
                    .findByKindAndOwnerKey(AuthorizationPartition.Kind.GRANT, ownerKey)
                    .map(AuthorizationPartition::getId)
                    .orElseThrow(() ->
                            new IllegalStateException("Grant partition is missing: " + grantId));
        }
    }

    /** Dữ kiện bền của một grant theo định danh; rỗng khi grant không tồn tại. */
    public Optional<GrantFacts> loadGrant(UUID grantId) {
        Optional<ShareGrant> grant = grants.findById(grantId);
        if (grant.isEmpty()) {
            return Optional.empty();
        }
        ShareGrant row = grant.get();
        var source = resources.findById(row.getSourceResourceId());
        var grantor = userAccounts.findById(row.getGrantorUserId());
        var recipient = workspaces.findById(row.getRecipientWorkspaceId());
        if (source.isEmpty() || grantor.isEmpty() || recipient.isEmpty()) {
            return Optional.empty();
        }
        Workspace recipientWorkspace = recipient.get();
        UUID recipientOwnerUserId = recipientWorkspace.getOwnerUserId();
        String recipientOwnerSubject =
                recipientOwnerUserId == null
                        ? null
                        : userAccounts
                                .findById(recipientOwnerUserId)
                                .map(account -> account.getKeycloakSub())
                                .orElse(null);
        return Optional.of(
                new GrantFacts(
                        grantId,
                        row.getSourceResourceId(),
                        row.getSourceVersionId(),
                        source.get().getOwnerWorkspaceId(),
                        row.getGrantorUserId(),
                        grantor.get().getKeycloakSub(),
                        row.getRecipientWorkspaceId(),
                        recipientWorkspace.getKind().name(),
                        recipientOwnerUserId,
                        recipientOwnerSubject,
                        row.getMode().name(),
                        row.getState().name()));
    }

    private UUID workspacePartition(UUID workspaceId) {
        return partitions
                .findByKindAndOwnerKey(
                        AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId)
                .map(AuthorizationPartition::getId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Workspace authorization partition is missing: " + workspaceId));
    }

    // Descriptor dùng lại đúng định dạng canonical của ResourcePolicyProjector để phép dựng
    // gate native của reconciliation áp được cho cả hai họ bundle resource.
    private static String actorDescriptor(
            UUID workspaceId, UUID userId, String subject, String targetKey, String scope) {
        return "RESOURCE_ACTOR|workspace="
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + subject
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }

    private static String groupDescriptor(
            UUID workspaceId, String groupPath, String targetKey, String scope) {
        return "RESOURCE_GROUP|workspace="
                + workspaceId
                + "|group="
                + groupPath
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }
}
