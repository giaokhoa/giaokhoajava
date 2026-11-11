package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationPartitionRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Đóng góp phần closure tài nguyên vào operation thu hồi một share grant: khi một grant bị thu hồi,
 * quyền suy ra từ nó không chỉ nằm ở workspace nhận trực tiếp — bản đã lưu theo grant và mọi bản
 * phái sinh từ grant (bản fork và bản sao copy-on-share, kể cả phái sinh của phái sinh, vì phép sao
 * chép mang toàn bộ cạnh dependency của nguồn nên cạnh mang grant luôn là cạnh trực tiếp của bản
 * phái sinh) đều phải mất nguồn trong cùng một operation, và operation chỉ đạt {@code SUCCEEDED}
 * khi toàn bộ closure đó đã READY ở revision mới, đúng giao thức thu hồi của hợp đồng
 * authorization.
 *
 * <p>Contributor tính tập workspace bị ảnh hưởng từ dữ kiện bền: nơi nhận của grant, các kho đã lưu
 * theo grant, và workspace sở hữu các bản phái sinh mang cạnh của grant. Nó ghi trong một
 * operation: GRANT partition của grant với bundle đã rỗng, cộng WORKSPACE partition của từng
 * workspace bị ảnh hưởng với toàn bộ bundle resource version hiện hành của workspace đó. Bundle nào
 * không đổi thì hash khớp nguyên nên chỉ là ghi lại. Bundle nào mất nhánh license thì được thu hồi
 * đúng. Đây là cùng kỷ luật closure của {@code SourceRevocationCoordinator}: một projection đổi
 * nguồn mà desired hash không được ghi lại sẽ làm operation chết {@code DESIRED_SOURCE_MISMATCH}.
 *
 * <p>Lịch sử không bị xóa ở bất kỳ bước nào.
 */
@Component
public class ResourceRevocationContributor {

    private final AuthorizationOperationService operations;
    private final ResourcePolicyProjector resourceProjector;
    private final SharePolicyProjector shareProjector;
    private final ShareGrantRepository grants;
    private final LibraryItemRepository libraryItems;
    private final ResourceDependencyRepository dependencies;
    private final ResourceRepository resources;
    private final ResourcesAuthorizationPartitionRepository partitions;

    public ResourceRevocationContributor(
            AuthorizationOperationService operations,
            ResourcePolicyProjector resourceProjector,
            SharePolicyProjector shareProjector,
            ShareGrantRepository grants,
            LibraryItemRepository libraryItems,
            ResourceDependencyRepository dependencies,
            ResourceRepository resources,
            ResourcesAuthorizationPartitionRepository partitions) {
        this.operations = operations;
        this.resourceProjector = resourceProjector;
        this.shareProjector = shareProjector;
        this.grants = grants;
        this.libraryItems = libraryItems;
        this.dependencies = dependencies;
        this.resources = resources;
        this.partitions = partitions;
    }

    /**
     * Mọi workspace mà một projection resource version của chúng suy ra nguồn từ grant này: nơi
     * nhận trực tiếp, nơi đã lưu version theo grant và nơi sở hữu bản phái sinh mang cạnh truy vết
     * của grant. Thứ tự ổn định theo UUID để operation ghi nhất quán.
     */
    public List<UUID> affectedWorkspaces(UUID grantId) {
        Set<UUID> affected = new LinkedHashSet<>();
        grants.findRecipientWorkspaceIdById(grantId).ifPresent(affected::add);
        affected.addAll(libraryItems.findWorkspaceIdsByShareGrantId(grantId));
        List<UUID> derivedResourceIds =
                dependencies.findByShareGrantId(grantId).stream()
                        .map(dependency -> dependency.getDerivedResourceId())
                        .distinct()
                        .toList();
        if (!derivedResourceIds.isEmpty()) {
            affected.addAll(resources.findOwnerWorkspaceIdsByIdIn(derivedResourceIds));
        }
        return affected.stream().sorted(Comparator.comparing(id -> id.toString())).toList();
    }

    /**
     * Ghi operation thu hồi cho một grant vừa chuyển {@code REVOKED} trong luồng của service gọi.
     * GRANT partition của grant được bump với bundle hiện hành (đã rỗng sau thu hồi). Từng
     * workspace bị ảnh hưởng được bump với toàn bộ bundle resource version hiện hành của nó.
     *
     * <p>Caller bọc trong transaction của mình và chờ operation {@code SUCCEEDED} trước khi báo thu
     * hồi thành công. Chừng nào closure chưa READY đủ thì thu hồi chưa được ghi nhận. Replay cùng
     * idempotency key và payload trả lại operation đã ghi.
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
        return partitions
                .findByKindAndOwnerKey(
                        AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId)
                .map(partition -> partition.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Workspace authorization partition is missing: " + workspaceId));
    }
}
