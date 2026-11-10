package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Đóng góp phần closure tài nguyên vào operation thu hồi một share grant: khi một grant bị thu hồi,
 * quyền suy ra từ nó không chỉ nằm ở workspace nhận trực tiếp — bản đã lưu theo grant và mọi bản
 * fork phái sinh từ grant (kể cả fork của fork, vì fork sao toàn bộ cạnh dependency của nguồn nên
 * cạnh mang grant luôn là cạnh trực tiếp của bản phái sinh) đều phải mất nguồn trong cùng một
 * operation, và operation chỉ đạt {@code SUCCEEDED} khi toàn bộ closure đó đã READY ở revision mới,
 * đúng giao thức thu hồi của hợp đồng authorization. Contributor tính tập workspace bị ảnh hưởng từ
 * dữ kiện bền (nơi nhận của grant, các kho đã lưu theo grant, workspace sở hữu các bản phái sinh
 * mang cạnh của grant), rồi ghi trong một operation: GRANT partition của grant với bundle đã rỗng,
 * cộng WORKSPACE partition của từng workspace bị ảnh hưởng với toàn bộ bundle resource version hiện
 * hành của workspace đó — bundle nào không đổi thì hash khớp nguyên nên chỉ là ghi lại, bundle nào
 * mất nhánh license thì được thu hồi đúng, theo cùng kỷ luật closure của {@code
 * SourceRevocationCoordinator} (một projection đổi nguồn mà desired hash không được ghi lại sẽ làm
 * operation chết {@code DESIRED_SOURCE_MISMATCH}). Lịch sử không bị đụng: grant chỉ chuyển trạng
 * thái, mục thư viện, cạnh truy vết và dữ liệu học tập đã phát sinh đều giữ nguyên.
 */
@Component
public class ResourceRevocationContributor {

    private final JdbcTemplate jdbc;
    private final AuthorizationOperationService operations;
    private final ResourcePolicyProjector resourceProjector;
    private final SharePolicyProjector shareProjector;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} dựng sẵn, cùng mẫu EI_EXPOSE_REP2 của
     * các projector họ resource.
     */
    public ResourceRevocationContributor(
            DataSource dataSource,
            AuthorizationOperationService operations,
            ResourcePolicyProjector resourceProjector,
            SharePolicyProjector shareProjector) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.operations = operations;
        this.resourceProjector = resourceProjector;
        this.shareProjector = shareProjector;
    }

    /**
     * Mọi workspace mà một projection resource version của chúng suy ra nguồn từ grant này: nơi
     * nhận trực tiếp, nơi đã lưu version theo grant và nơi sở hữu bản phái sinh mang cạnh truy vết
     * của grant. Thứ tự ổn định theo UUID để operation ghi nhất quán.
     */
    public List<UUID> affectedWorkspaces(UUID grantId) {
        return jdbc
                .queryForList(
                        "SELECT recipient_workspace_id AS workspace_id FROM share_grant"
                                + " WHERE id = ?"
                                + " UNION SELECT workspace_id FROM library_item"
                                + " WHERE share_grant_id = ?"
                                + " UNION SELECT r.owner_workspace_id FROM resource_dependency d"
                                + " JOIN resource r ON r.id = d.derived_resource_id"
                                + " WHERE d.share_grant_id = ?",
                        UUID.class,
                        grantId,
                        grantId,
                        grantId)
                .stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
    }

    /**
     * Ghi operation thu hồi cho một grant vừa chuyển {@code REVOKED} trong luồng của service gọi:
     * GRANT partition của grant được bump với bundle hiện hành (đã rỗng sau thu hồi), từng
     * workspace bị ảnh hưởng được bump với toàn bộ bundle resource version hiện hành của nó. Caller
     * bọc trong transaction của mình và chờ operation {@code SUCCEEDED} trước khi báo thu hồi thành
     * công — chừng nào closure chưa READY đủ thì thu hồi chưa được ghi nhận, đúng FR-049/FR-052.
     * Replay cùng idempotency key và payload trả lại operation đã ghi.
     */
    public AuthorizationOperation recordShareRevocation(
            UUID initiatorUserId,
            UUID routeWorkspaceId,
            String idempotencyKey,
            String payloadHash,
            UUID grantId) {
        String grantKey = NativePolicyTemplates.grantKey(grantId);
        List<AuthorizationOperationService.PartitionChange> changes = new ArrayList<>();
        changes.add(
                new AuthorizationOperationService.PartitionChange(
                        shareProjector.ensureGrantPartition(grantId),
                        List.of(
                                new AuthorizationOperationService.DesiredProjection(
                                        grantKey,
                                        SharePolicyProjector.bundleScope(),
                                        shareProjector.bundleDescriptors(grantKey),
                                        List.of()))));
        for (UUID workspaceId : affectedWorkspaces(grantId)) {
            changes.add(
                    new AuthorizationOperationService.PartitionChange(
                            workspacePartition(workspaceId),
                            resourceProjector.allBundleProjections(workspaceId)));
        }
        return operations.applyDesiredState(
                initiatorUserId, routeWorkspaceId, idempotencyKey, payloadHash, changes);
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
}
