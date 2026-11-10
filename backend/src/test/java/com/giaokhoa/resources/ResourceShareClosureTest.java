package com.giaokhoa.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.repository.CanonicalResourceRepository;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceActionReceipts;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceScopePreconditions;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationOperationRepository;
import com.giaokhoa.resources.repository.ResourcesWorkspaceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Khóa phép duyệt closure phụ thuộc của ràng buộc thành phần (FR-041) sau khi thay truy vấn đệ quy
 * bằng duyệt lặp theo tầng: grant điều phối phải thu đủ qua nhiều tầng, cạnh không mang grant không
 * đóng góp gì, và vòng lặp trong đồ thị phụ thuộc không làm phép duyệt treo hay thu trùng.
 */
class ResourceShareClosureTest {

    private static final UUID RESOURCE_A = UUID.randomUUID();
    private static final UUID RESOURCE_B = UUID.randomUUID();
    private static final UUID RESOURCE_C = UUID.randomUUID();
    private static final UUID RESOURCE_D = UUID.randomUUID();
    private static final UUID VERSION_A = UUID.randomUUID();
    private static final UUID VERSION_B = UUID.randomUUID();
    private static final UUID VERSION_C = UUID.randomUUID();
    private static final UUID VERSION_D = UUID.randomUUID();
    private static final UUID GRANT_ONE = UUID.randomUUID();
    private static final UUID GRANT_TWO = UUID.randomUUID();
    private static final UUID GRANT_THREE = UUID.randomUUID();

    @DisplayName("Duyệt closure thu grant điều phối qua nhiều tầng và dừng được trước vòng lặp")
    @Test
    void frontierTraversalCollectsGoverningGrantsAndStopsAtCycles() {
        List<ResourceDependency> edges =
                List.of(
                        edge(RESOURCE_A, VERSION_A, RESOURCE_B, VERSION_B, GRANT_ONE),
                        edge(RESOURCE_A, VERSION_A, RESOURCE_C, VERSION_C, null),
                        edge(RESOURCE_B, VERSION_B, RESOURCE_D, VERSION_D, GRANT_TWO),
                        edge(RESOURCE_C, VERSION_C, RESOURCE_D, VERSION_D, null),
                        // Vòng lặp D -> B: B đã thăm ở tầng trước nên phép duyệt phải dừng.
                        edge(RESOURCE_D, VERSION_D, RESOURCE_B, VERSION_B, GRANT_THREE));
        ResourceShareService service = serviceWithEdges(edges);

        assertThat(service.governingGrantIds(RESOURCE_A, VERSION_A))
                .containsExactlyInAnyOrder(GRANT_ONE, GRANT_TWO, GRANT_THREE);
        // Từ C vẫn tới được D và B qua các cạnh không mang grant, nên hai grant ở tầng sâu vẫn
        // điều phối C; một version không có cạnh nào thì không có grant điều phối.
        assertThat(service.governingGrantIds(RESOURCE_C, VERSION_C))
                .containsExactlyInAnyOrder(GRANT_TWO, GRANT_THREE);
        assertThat(service.governingGrantIds(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    private static ResourceDependency edge(
            UUID derivedResourceId,
            UUID derivedVersionId,
            UUID sourceResourceId,
            UUID sourceVersionId,
            UUID grantId) {
        return new ResourceDependency(
                UUID.randomUUID(),
                derivedResourceId,
                derivedVersionId,
                sourceResourceId,
                sourceVersionId,
                grantId,
                "origin=COMPONENT");
    }

    private static ResourceShareService serviceWithEdges(List<ResourceDependency> edges) {
        ResourceDependencyRepository dependencies = mock(ResourceDependencyRepository.class);
        when(dependencies.findByDerivedVersionIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<UUID> versionIds = invocation.getArgument(0);
                    return edges.stream()
                            .filter(edge -> versionIds.contains(edge.getDerivedVersionId()))
                            .toList();
                });
        return new ResourceShareService(
                mock(ProtectedActionExecutor.class),
                mock(ResourcePolicyProjector.class),
                mock(SharePolicyProjector.class),
                mock(ResourceRevocationContributor.class),
                mock(IdempotencyService.class),
                mock(WorkspaceTransaction.class),
                mock(PlatformTransactionManager.class),
                mock(CanonicalResourceRepository.class),
                mock(ShareGrantRepository.class),
                mock(ResourceRepository.class),
                mock(ResourceVersionRepository.class),
                dependencies,
                mock(ResourceAssignmentRepository.class),
                mock(LibraryItemRepository.class),
                mock(ResourcesWorkspaceRepository.class),
                mock(ResourceCopies.class),
                mock(ResourceActionReceipts.class),
                mock(ResourcesAuthorizationOperationRepository.class),
                mock(ResourceScopePreconditions.class));
    }
}
