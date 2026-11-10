package com.giaokhoa.guardians;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GuardianPolicyProjectorTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KeycloakProvisioningClient directory = mock(KeycloakProvisioningClient.class);

    @DisplayName(
            "Liên kết lịch sử đọc một live snapshot cho mỗi phụ huynh thay vì cho từng cặp phụ huynh–học sinh")
    @Test
    void historyReadsOneSnapshotPerParent() {
        List<UUID> students = IntStream.range(0, 100).mapToObj(_ -> UUID.randomUUID()).toList();
        List<Map<String, Object>> parents =
                IntStream.range(0, 100)
                        .mapToObj(i ->
                                Map.<String, Object>of(
                                        "parent_user_id",
                                        UUID.randomUUID(),
                                        "keycloak_sub",
                                        "parent-" + i))
                        .toList();
        sources(students, parents);
        when(jdbc.queryForList(
                        startsWith("SELECT gl.student_user_id FROM guardian_link gl"),
                        eq(UUID.class),
                        eq(WORKSPACE),
                        any(UUID.class)))
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
        sources(
                List.of(first, second),
                List.of(Map.of("parent_user_id", parent, "keycloak_sub", "parent")));
        when(jdbc.queryForList(
                        startsWith("SELECT gl.student_user_id FROM guardian_link gl"),
                        eq(UUID.class),
                        eq(WORKSPACE),
                        eq(parent)))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second));
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

    private void sources(List<UUID> students, List<Map<String, Object>> parents) {
        when(jdbc.queryForList(
                        "SELECT DISTINCT student_user_id FROM guardian_link WHERE workspace_id = ?",
                        UUID.class,
                        WORKSPACE))
                .thenReturn(students);
        when(jdbc.queryForList(
                        "SELECT DISTINCT gl.parent_user_id, u.keycloak_sub FROM guardian_link gl"
                                + " JOIN user_account u ON u.id = gl.parent_user_id WHERE gl.workspace_id = ?",
                        WORKSPACE))
                .thenReturn(parents);
    }

    private GuardianPolicyProjector projector() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return new GuardianPolicyProjector(
                jdbc,
                directory,
                mock(AuthorizationOperationService.class),
                mock(CenterPolicyProjector.class),
                mock(ResultPolicyProjector.class),
                transactions);
    }
}
