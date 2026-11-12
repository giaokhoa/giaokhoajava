package com.giaokhoa.guardians;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.guardians.model.GuardianLink;
import com.giaokhoa.guardians.repository.GuardianAuthorizationPartitionRepository;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.guardians.repository.GuardianUserAccountRepository;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.service.MembershipService;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GuardianPolicyProjectorTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private final GuardianLinkRepository links = mock(GuardianLinkRepository.class);
    private final GuardianUserAccountRepository userAccounts =
            mock(GuardianUserAccountRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final AuthorizationProvisioningClient directory =
            mock(AuthorizationProvisioningClient.class);

    @DisplayName(
            "Liên kết lịch sử đọc một live snapshot cho mỗi phụ huynh thay vì cho từng cặp phụ huynh–học sinh")
    @Test
    void historyReadsOneSnapshotPerParent() {
        List<UUID> students = IntStream.range(0, 100).mapToObj(_ -> UUID.randomUUID()).toList();
        Map<UUID, String> parents =
                IntStream.range(0, 100)
                        .boxed()
                        .collect(Collectors.toMap(_ -> UUID.randomUUID(), i -> "parent-" + i));
        sources(students, parents);
        when(links.findByWorkspaceIdAndParentUserIdAndState(
                        eq(WORKSPACE), any(UUID.class), eq(GuardianLink.APPROVED)))
                .thenReturn(List.of());
        when(directory.realmGroupPaths(anyString())).thenReturn(Set.of());

        projector().syncGroupMemberships(WORKSPACE);

        verify(directory, times(100)).realmGroupPaths(anyString());
        verify(directory, never()).isRealmGroupMember(anyString(), anyString());
        verify(directory, never()).setRealmGroupMembership(anyString(), anyString(), anyBoolean());
    }

    @DisplayName(
            "Sync chỉ cập nhật các khác biệt theo workspace và đọc membership mới ở lần sync tiếp theo")
    @Test
    void syncUpdatesOnlyWorkspaceDiffs() {
        UUID parent = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstGroup = NativePolicyTemplates.guardiansGroup(WORKSPACE, first);
        String secondGroup = NativePolicyTemplates.guardiansGroup(WORKSPACE, second);
        String otherWorkspaceGroup = NativePolicyTemplates.guardiansGroup(UUID.randomUUID(), first);
        sources(List.of(first, second), Map.of(parent, "parent"));
        when(memberships.activeRoles(parent, WORKSPACE))
                .thenReturn(Set.of(MembershipRole.Role.PARENT));
        when(memberships.activeRoles(first, WORKSPACE))
                .thenReturn(Set.of(MembershipRole.Role.STUDENT));
        when(memberships.activeRoles(second, WORKSPACE))
                .thenReturn(Set.of(MembershipRole.Role.STUDENT));
        when(links.findByWorkspaceIdAndParentUserIdAndState(
                        WORKSPACE, parent, GuardianLink.APPROVED))
                .thenReturn(List.of(approvedLink(parent, first)))
                .thenReturn(List.of(approvedLink(parent, second)));
        when(directory.realmGroupPaths("parent"))
                .thenReturn(Set.of(secondGroup, otherWorkspaceGroup))
                .thenReturn(Set.of(firstGroup, otherWorkspaceGroup));
        GuardianPolicyProjector projector = projector();

        projector.syncGroupMemberships(WORKSPACE);
        verify(directory).setRealmGroupMembership("parent", secondGroup, false);
        verify(directory).setRealmGroupMembership("parent", firstGroup, true);
        projector.syncGroupMemberships(WORKSPACE);
        verify(directory).setRealmGroupMembership("parent", firstGroup, false);
        verify(directory).setRealmGroupMembership("parent", secondGroup, true);
        verify(directory, times(2)).realmGroupPaths("parent");
        verify(directory, never())
                .setRealmGroupMembership(eq("parent"), eq(otherWorkspaceGroup), anyBoolean());
    }

    private void sources(List<UUID> students, Map<UUID, String> parents) {
        when(links.findDistinctStudentUserIds(WORKSPACE)).thenReturn(students);
        when(links.findDistinctParentUserIds(WORKSPACE)).thenReturn(List.copyOf(parents.keySet()));
        parents.forEach((parentId, subject) -> {
            UserAccount account = mock(UserAccount.class);
            when(account.getKeycloakSub()).thenReturn(subject);
            when(userAccounts.findById(parentId)).thenReturn(Optional.of(account));
        });
    }

    private static GuardianLink approvedLink(UUID parent, UUID student) {
        GuardianLink link = new GuardianLink(UUID.randomUUID(), WORKSPACE, parent, student, parent);
        link.approve(parent, Instant.now(), "offline-evidence-1");
        return link;
    }

    private GuardianPolicyProjector projector() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return new GuardianPolicyProjector(
                links,
                userAccounts,
                mock(GuardianAuthorizationPartitionRepository.class),
                memberships,
                mock(WorkspaceTransaction.class),
                directory,
                mock(AuthorizationOperationService.class),
                mock(CenterPolicyProjector.class),
                mock(ResultPolicyProjector.class),
                transactions);
    }
}
