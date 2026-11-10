package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * nguồn tại workspace nhận trên WORKSPACE partition của nơi nhận.
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

    private static final int SINGLE_ROW = 1;

    private final JdbcTemplate jdbc;
    private final AuthorizationOperationService operations;
    private final ResourcePolicyProjector resourceProjector;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} dựng sẵn: template được tạo trong
     * constructor nên projector không giữ tham chiếu tới đối tượng mutable từ bên ngoài (SpotBugs
     * EI_EXPOSE_REP2), cùng mẫu với {@link ResourcePolicyProjector}.
     */
    public SharePolicyProjector(
            DataSource dataSource,
            AuthorizationOperationService operations,
            ResourcePolicyProjector resourceProjector) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.operations = operations;
        this.resourceProjector = resourceProjector;
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
     * GRANT partition được thiết lập ngay từ lượt cấp. Replay cùng idempotency key và payload trả
     * lại operation đã ghi mà không ghi lại gì, còn cùng key khác payload là 409 — cả hai do cơ chế
     * operation dùng chung xử lý. Caller bọc trong transaction của mình và chờ operation SUCCEEDED
     * trước khi báo thành công.
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
        changes.add(
                new AuthorizationOperationService.PartitionChange(
                        workspacePartition(grant.recipientWorkspaceId()),
                        List.of(
                                resourceProjector.desiredProjection(
                                        grant.recipientWorkspaceId(), versionKey))));
        return operations.applyDesiredState(
                initiatorUserId, routeWorkspaceId, idempotencyKey, payloadHash, changes);
    }

    /**
     * GRANT partition của một grant, tạo nếu chưa có: một grant có đúng một partition mang
     * owner_key {@code grant:{id}}, tạo lười ở lần ghi đầu tiên theo cùng mẫu ON CONFLICT của các
     * partition được seed khác.
     */
    UUID ensureGrantPartition(UUID grantId) {
        String ownerKey = "grant:" + grantId;
        List<UUID> existing =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'GRANT' AND owner_key = ?",
                        UUID.class,
                        ownerKey);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID candidate = UUID.randomUUID();
        int inserted =
                jdbc.update(
                        "INSERT INTO authz_partition(id, kind, owner_key)"
                                + " VALUES (?, 'GRANT', ?) ON CONFLICT (kind, owner_key)"
                                + " DO NOTHING",
                        candidate,
                        ownerKey);
        if (inserted == SINGLE_ROW) {
            return candidate;
        }
        return jdbc
                .queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'GRANT' AND owner_key = ?",
                        UUID.class,
                        ownerKey)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("Grant partition is missing: " + grantId));
    }

    /** Dữ kiện bền của một grant theo định danh; rỗng khi grant không tồn tại. */
    public Optional<GrantFacts> loadGrant(UUID grantId) {
        return jdbc
                .queryForList(
                        "SELECT g.source_resource_id, g.source_version_id,"
                                + " r.owner_workspace_id AS source_workspace_id,"
                                + " g.grantor_user_id, gu.keycloak_sub AS grantor_subject,"
                                + " g.recipient_workspace_id, rw.kind AS recipient_kind,"
                                + " rw.owner_user_id AS recipient_owner_id,"
                                + " ou.keycloak_sub AS recipient_owner_subject,"
                                + " g.mode, g.state FROM share_grant g"
                                + " JOIN resource r ON r.id = g.source_resource_id"
                                + " JOIN user_account gu ON gu.id = g.grantor_user_id"
                                + " JOIN workspace rw ON rw.id = g.recipient_workspace_id"
                                + " LEFT JOIN user_account ou ON ou.id = rw.owner_user_id"
                                + " WHERE g.id = ?",
                        grantId)
                .stream()
                .findFirst()
                .map(row ->
                        new GrantFacts(
                                grantId,
                                Objects.requireNonNull(
                                        (UUID) row.get("source_resource_id"), "source_resource_id"),
                                Objects.requireNonNull(
                                        (UUID) row.get("source_version_id"), "source_version_id"),
                                Objects.requireNonNull(
                                        (UUID) row.get("source_workspace_id"),
                                        "source_workspace_id"),
                                Objects.requireNonNull(
                                        (UUID) row.get("grantor_user_id"), "grantor_user_id"),
                                Objects.requireNonNull(
                                        (String) row.get("grantor_subject"), "grantor_subject"),
                                Objects.requireNonNull(
                                        (UUID) row.get("recipient_workspace_id"),
                                        "recipient_workspace_id"),
                                Objects.requireNonNull(
                                        (String) row.get("recipient_kind"), "recipient_kind"),
                                (UUID) row.get("recipient_owner_id"),
                                (String) row.get("recipient_owner_subject"),
                                Objects.requireNonNull((String) row.get("mode"), "mode"),
                                Objects.requireNonNull((String) row.get("state"), "state")));
    }

    private UUID workspacePartition(UUID workspaceId) {
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace authorization partition is missing: " + workspaceId);
        }
        return partitions.getFirst();
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
