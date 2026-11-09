package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceRevocationCoordinatorTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORKSPACE_PARTITION =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SUBJECT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID RESULT = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    void teachingRevocationRecordsTeachingBundlesAndAllResultBundlesInOneOperation() {
        Fixture fixture = new Fixture();
        String subjectKey = NativePolicyTemplates.courseSubjectKey(WORKSPACE, SUBJECT);
        String resultKey = NativePolicyTemplates.resultKey(WORKSPACE, RESULT);
        when(fixture.teaching.bundleDescriptors(subjectKey))
                .thenReturn(List.of("teaching|scope=read"));
        AuthorizationOperationService.DesiredProjection resultProjection =
                new AuthorizationOperationService.DesiredProjection(
                        resultKey, "read", List.of("result|scope=read.published"), List.of());
        when(fixture.results.allBundleProjections(WORKSPACE)).thenReturn(List.of(resultProjection));

        fixture.coordinator.recordTeachingRevocation(
                ACTOR, WORKSPACE, "key", "hash", List.of(subjectKey));

        List<AuthorizationOperationService.PartitionChange> changes = fixture.capturedChanges();
        assertEquals(1, changes.size());
        assertEquals(WORKSPACE_PARTITION, changes.getFirst().partitionId());
        assertEquals(
                List.of(
                        new AuthorizationOperationService.DesiredProjection(
                                subjectKey,
                                TeachingPolicyProjector.bundleScope(),
                                List.of("teaching|scope=read"),
                                List.of()),
                        resultProjection),
                changes.getFirst().projections());
    }

    @Test
    void membershipRevocationRecordsCenterTeachingAndResultBundlesInOneOperation() {
        Fixture fixture = new Fixture();
        String workspaceKey = NativePolicyTemplates.workspaceKey(WORKSPACE);
        String subjectKey = NativePolicyTemplates.courseSubjectKey(WORKSPACE, SUBJECT);
        String resultKey = NativePolicyTemplates.resultKey(WORKSPACE, RESULT);
        when(fixture.center.bundleDescriptors(WORKSPACE)).thenReturn(List.of("center|scope=enter"));
        AuthorizationOperationService.DesiredProjection teachingProjection =
                new AuthorizationOperationService.DesiredProjection(
                        subjectKey, "read", List.of("teaching|scope=read"), List.of());
        AuthorizationOperationService.DesiredProjection resultProjection =
                new AuthorizationOperationService.DesiredProjection(
                        resultKey, "read", List.of("result|scope=read.published"), List.of());
        when(fixture.teaching.allBundleProjections(WORKSPACE))
                .thenReturn(List.of(teachingProjection));
        when(fixture.results.allBundleProjections(WORKSPACE)).thenReturn(List.of(resultProjection));

        fixture.coordinator.recordMembershipRevocation(ACTOR, WORKSPACE, "key", "hash");

        List<AuthorizationOperationService.PartitionChange> changes = fixture.capturedChanges();
        assertEquals(1, changes.size());
        assertEquals(WORKSPACE_PARTITION, changes.getFirst().partitionId());
        assertEquals(
                List.of(
                        new AuthorizationOperationService.DesiredProjection(
                                workspaceKey, "enter", List.of("center|scope=enter"), List.of()),
                        teachingProjection,
                        resultProjection),
                changes.getFirst().projections());
    }

    @Test
    void missingWorkspacePartitionFailsBeforeRecordingAnything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("authz_partition"), eq(UUID.class), anyString()))
                .thenReturn(List.of());
        AuthorizationOperationService operations = mock(AuthorizationOperationService.class);
        var coordinator =
                new SourceRevocationCoordinator(
                        jdbc,
                        operations,
                        mock(CenterPolicyProjector.class),
                        mock(TeachingPolicyProjector.class),
                        mock(ResultPolicyProjector.class));

        assertThrows(
                IllegalStateException.class,
                () ->
                        coordinator.recordTeachingRevocation(
                                ACTOR,
                                WORKSPACE,
                                "key",
                                "hash",
                                List.of("workspace:" + WORKSPACE)));

        verifyNoInteractions(operations);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final AuthorizationOperationService operations = mock(AuthorizationOperationService.class);
        final CenterPolicyProjector center = mock(CenterPolicyProjector.class);
        final TeachingPolicyProjector teaching = mock(TeachingPolicyProjector.class);
        final ResultPolicyProjector results = mock(ResultPolicyProjector.class);
        final SourceRevocationCoordinator coordinator =
                new SourceRevocationCoordinator(jdbc, operations, center, teaching, results);

        Fixture() {
            when(jdbc.queryForList(contains("authz_partition"), eq(UUID.class), anyString()))
                    .thenReturn(List.of(WORKSPACE_PARTITION));
            AuthorizationOperation operation =
                    new AuthorizationOperation(
                            UUID.randomUUID(),
                            ACTOR,
                            WORKSPACE,
                            "key",
                            "hash",
                            new UUID[] {WORKSPACE_PARTITION},
                            new Long[] {2L},
                            2L);
            when(operations.applyDesiredState(
                            eq(ACTOR), eq(WORKSPACE), eq("key"), eq("hash"), anyList()))
                    .thenReturn(operation);
        }

        List<AuthorizationOperationService.PartitionChange> capturedChanges() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AuthorizationOperationService.PartitionChange>> changes =
                    ArgumentCaptor.forClass(List.class);
            verify(operations)
                    .applyDesiredState(
                            eq(ACTOR), eq(WORKSPACE), eq("key"), eq("hash"), changes.capture());
            assertEquals(1, changes.getAllValues().size(), "Exactly one apply call");
            return changes.getValue();
        }
    }
}
