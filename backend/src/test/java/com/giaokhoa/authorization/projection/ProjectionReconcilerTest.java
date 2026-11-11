package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.guardians.GuardianPolicyProjector;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.SharePolicyProjector;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.support.RandomIds;
import com.giaokhoa.teaching.PersonalTeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.verification.ActivityPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ProjectionReconcilerTest {
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final UUID USER = RandomIds.randomId();
    private static final String TARGET = "workspace:" + WORKSPACE;

    @Test
    void personalOwnerDescriptorMatchesTheContractFormat() {
        String descriptor =
                ProjectionReconciler.personalOwnerDescriptor(
                        WORKSPACE, USER, "keycloak-subject", TARGET, "enter");
        assertEquals(
                "PERSONAL_OWNER|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=keycloak-subject|target="
                        + TARGET
                        + "|scope=enter",
                descriptor);
    }

    @Test
    void desiredHashOfOneSourceIsTheHashOfItsDescriptor() throws Exception {
        String descriptor =
                ProjectionReconciler.personalOwnerDescriptor(
                        WORKSPACE, USER, "keycloak-subject", TARGET, "enter");
        assertEquals(
                sha256(descriptor),
                ProjectionReconciler.desiredHash(TARGET, "enter", List.of(descriptor)));
    }

    @Test
    void desiredHashIgnoresSourceOrderAndDuplicates() {
        String first =
                ProjectionReconciler.personalOwnerDescriptor(
                        WORKSPACE, USER, "sub-a", TARGET, "read");
        String second =
                ProjectionReconciler.personalOwnerDescriptor(
                        WORKSPACE, USER, "sub-b", TARGET, "read");
        assertEquals(
                ProjectionReconciler.desiredHash(TARGET, "read", List.of(first, second)),
                ProjectionReconciler.desiredHash(TARGET, "read", List.of(second, first, first)));
    }

    @Test
    void emptySourcesHashTheRetractDescriptor() throws Exception {
        assertEquals(
                sha256(ProjectionReconciler.retractDescriptor(TARGET, "enter")),
                ProjectionReconciler.desiredHash(TARGET, "enter", List.of()));
    }

    @Test
    void graphForBuildsAnActorSourceFromTheDescriptor() {
        String descriptor =
                ProjectionReconciler.personalOwnerDescriptor(
                        WORKSPACE, USER, "keycloak-subject", TARGET, "enter");
        PolicyGraph graph = ProjectionReconciler.graphFor(TARGET, "enter", List.of(descriptor));
        assertEquals(TARGET, graph.targetKey());
        assertEquals("enter", graph.scope());
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(
                                NativePolicyTemplates.ELIGIBILITY_GROUP)),
                graph.commonGates());
        assertEquals(
                List.of(new PolicyGraph.Gate.Actor("keycloak-subject")),
                graph.sources().getFirst().gates());
    }

    @Test
    void graphForRejectsUnknownSourceDescriptors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectionReconciler.graphFor(TARGET, "enter", List.of("UNKNOWN|x=1")));
    }

    @Test
    void graphForPlatformBusinessScopeKeepsTheEligibilityGate() {
        String descriptor = PlatformPolicyProjector.operatorDescriptor("center.create");
        PolicyGraph graph =
                ProjectionReconciler.graphFor("platform", "center.create", List.of(descriptor));
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(
                                NativePolicyTemplates.ELIGIBILITY_GROUP)),
                graph.commonGates());
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(
                                NativePolicyTemplates.PLATFORM_OPERATORS_GROUP)),
                graph.sources().getFirst().gates());
        assertEquals(
                List.of(
                        NativePolicyTemplates.groupPolicyName(
                                NativePolicyTemplates.ELIGIBILITY_GROUP),
                        NativePolicyTemplates.groupPolicyName(
                                NativePolicyTemplates.PLATFORM_OPERATORS_GROUP)),
                graph.policyNames());
        assertEquals(graph.policyNames(), graph.rootPolicyNames());
    }

    @Test
    void graphForPlatformRecoveryScopeOmitsTheEligibilityGate() {
        // account.recover được hợp đồng miễn trừ eligibility: dựng graph bằng đường mặc định có
        // gate sẽ thu hẹp quyền khôi phục, nên graph dựng lại phải không có common gate nào.
        String descriptor = PlatformPolicyProjector.operatorDescriptor("account.recover");
        PolicyGraph graph =
                ProjectionReconciler.graphFor("platform", "account.recover", List.of(descriptor));
        assertEquals(List.of(), graph.commonGates());
        assertEquals(
                List.of(
                        NativePolicyTemplates.groupPolicyName(
                                NativePolicyTemplates.PLATFORM_OPERATORS_GROUP)),
                graph.policyNames());
    }

    @Test
    void graphForProfileRecoveryRequestOmitsTheEligibilityGate() {
        String target = "account:" + USER;
        String descriptor =
                ProjectionReconciler.eligibilityDescriptor(
                        USER, "subject-1", target, "recovery.request");
        PolicyGraph graph =
                ProjectionReconciler.graphFor(target, "recovery.request", List.of(descriptor));
        assertEquals(List.of(), graph.commonGates());
    }

    @Test
    void noRecordedProjectionLogsDebugWithoutStackTraceAndOnlyAtMilestones() {
        UUID partitionId = RandomIds.randomId();
        AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
        AuthzProjectionRepository projections = mock(AuthzProjectionRepository.class);
        when(partitions.findById(partitionId))
                .thenReturn(
                        Optional.of(
                                partition(
                                        partitionId,
                                        1L,
                                        0L,
                                        AuthorizationPartition.State.PENDING)));
        when(projections.findByPartitionIdOrderByTargetKeyAsc(partitionId)).thenReturn(List.of());
        ProjectionReconciler reconciler = reconcilerWithInlineLock(partitions, projections);

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectionReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            assertFalse(reconciler.tryReconcilePartition(partitionId, 1));
            // Lần thất bại thứ ba không phải mốc lũy thừa hai: không ghi thêm.
            assertFalse(reconciler.tryReconcilePartition(partitionId, 3));
            assertEquals(1, appender.list.size());
            // Mốc lũy thừa tiếp theo (4) ghi lại đúng một dòng.
            assertFalse(reconciler.tryReconcilePartition(partitionId, 4));
            assertEquals(2, appender.list.size());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
        for (ILoggingEvent event : appender.list) {
            assertEquals(Level.DEBUG, event.getLevel());
            assertTrue(
                    event.getFormattedMessage()
                            .contains(ProjectionReconciler.ERROR_NO_RECORDED_PROJECTION));
            // Lỗi đã phân loại không được kéo theo stack trace.
            assertNull(event.getThrowableProxy());
        }
    }

    @Test
    void supersededRevisionLogsWarnWithCodeAndNoStackTrace() {
        UUID partitionId = RandomIds.randomId();
        AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
        AuthzProjectionRepository projections = mock(AuthzProjectionRepository.class);
        when(partitions.findById(partitionId))
                .thenReturn(
                        Optional.of(
                                partition(
                                        partitionId, 1L, 0L, AuthorizationPartition.State.READY)));
        ProjectionReconciler reconciler = reconcilerWithInlineLock(partitions, projections);

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectionReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(reconciler.tryReconcilePartition(partitionId, 1));
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(
                event.getFormattedMessage()
                        .contains(ProjectionReconciler.ERROR_REVISION_SUPERSEDED));
        assertNull(event.getThrowableProxy());
    }

    @Test
    void grantReconcileEnsuresReferencedGroupsBeforeApplying() {
        UUID grantId = RandomIds.randomId();
        UUID recipient = RandomIds.randomId();
        String target = "grant:" + grantId;
        String ownersPath = NativePolicyTemplates.ownersGroup(recipient);
        String managersPath = NativePolicyTemplates.managersGroup(recipient);
        // Bundle của grant OFFERED tới một center, đúng định dạng SharePolicyProjector phát ra:
        // descriptor owners + managers của workspace nhận cho scope accept.
        List<String> bundle =
                List.of(
                        "RESOURCE_GROUP|workspace="
                                + recipient
                                + "|group="
                                + ownersPath
                                + "|target="
                                + target
                                + "|scope=accept",
                        "RESOURCE_GROUP|workspace="
                                + recipient
                                + "|group="
                                + managersPath
                                + "|target="
                                + target
                                + "|scope=accept");
        UUID partitionId = RandomIds.randomId();
        UUID projectionId = RandomIds.randomId();
        AuthorizationPartition partition =
                new AuthorizationPartition(
                        partitionId,
                        AuthorizationPartition.Kind.GRANT,
                        "grant:" + grantId,
                        1L,
                        0L,
                        AuthorizationPartition.State.PENDING);
        AuthorizationProjection projection =
                new AuthorizationProjection(
                        projectionId,
                        partitionId,
                        target,
                        null,
                        List.of(),
                        List.of(),
                        ProjectionReconciler.desiredHash(target, "accept", bundle),
                        null,
                        AuthorizationProjection.State.PENDING);
        AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
        AuthzProjectionRepository projections = mock(AuthzProjectionRepository.class);
        when(partitions.findById(partitionId)).thenReturn(Optional.of(partition));
        when(partitions.lockById(partitionId)).thenReturn(Optional.of(partition));
        when(partitions.findDesiredRevisionById(partitionId)).thenReturn(Optional.of(1L));
        when(projections.findByPartitionIdOrderByTargetKeyAsc(partitionId))
                .thenReturn(List.of(projection));
        when(projections.findById(projectionId)).thenReturn(Optional.of(projection));
        when(projections.findDesiredHashById(projectionId))
                .thenReturn(Optional.of(projection.getDesiredHash()));
        PolicyGraphWriter writer = mock(PolicyGraphWriter.class);
        when(writer.apply(any()))
                .thenReturn(new PolicyGraphWriter.AppliedGraph("resource-1", List.of("policy-1")));
        AuthorizationProvisioningClient directory = mock(AuthorizationProvisioningClient.class);
        SharePolicyProjector shareProjector = mock(SharePolicyProjector.class);
        when(shareProjector.bundleDescriptors(target)).thenReturn(bundle);
        when(shareProjector.sourcesFor(target, "accept")).thenReturn(bundle);
        when(shareProjector.sourcesFor(target, "revoke")).thenReturn(List.of());
        ProjectionReconciler reconciler =
                reconciler(partitions, projections, writer, directory, shareProjector);

        reconciler.reconcilePartition(partitionId);

        // Group managers chưa từng có member vẫn phải được bảo đảm tồn tại trước khi writer áp
        // graph. Thiếu bước này, writer ném "Realm group is not provisioned" ở share tới trung
        // tâm chưa từng có quản lý.
        InOrder order = inOrder(directory, writer);
        order.verify(directory).ensureRealmGroup(managersPath);
        order.verify(writer).apply(any());
        verify(directory).ensureRealmGroup(ownersPath);
        verify(directory).ensureRealmGroup(NativePolicyTemplates.ELIGIBILITY_GROUP);
        assertEquals(AuthorizationPartition.State.READY, partition.getState());
        assertEquals(1L, partition.getAppliedRevision());
    }

    private static AuthorizationPartition partition(
            UUID id,
            long desiredRevision,
            long appliedRevision,
            AuthorizationPartition.State state) {
        return new AuthorizationPartition(
                id,
                AuthorizationPartition.Kind.WORKSPACE,
                "owner:" + id,
                desiredRevision,
                appliedRevision,
                state);
    }

    /**
     * Reconciler với coordinator và transaction chạy thẳng action trong luồng gọi, các cộng tác còn
     * lại là mock.
     */
    private static ProjectionReconciler reconcilerWithInlineLock(
            AuthzPartitionRepository partitions, AuthzProjectionRepository projections) {
        return reconciler(
                partitions,
                projections,
                mock(PolicyGraphWriter.class),
                mock(AuthorizationProvisioningClient.class),
                mock(SharePolicyProjector.class));
    }

    /**
     * Như {@link #reconcilerWithInlineLock} nhưng writer, directory và share projector do test cấp.
     */
    private static ProjectionReconciler reconciler(
            AuthzPartitionRepository partitions,
            AuthzProjectionRepository projections,
            PolicyGraphWriter writer,
            AuthorizationProvisioningClient directory,
            SharePolicyProjector shareProjector) {
        ResourceMutationCoordinator coordinator = mock(ResourceMutationCoordinator.class);
        when(coordinator.withResourceLock(anyString(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                });
        return new ProjectionReconciler(
                partitions,
                projections,
                writer,
                directory,
                mock(CenterPolicyProjector.class),
                mock(TeachingPolicyProjector.class),
                mock(GuardianPolicyProjector.class),
                mock(ResultPolicyProjector.class),
                mock(ActivityPolicyProjector.class),
                mock(ResourcePolicyProjector.class),
                shareProjector,
                mock(PersonalEntitlementProjector.class),
                mock(PersonalTeachingPolicyProjector.class),
                stubTransactions(),
                coordinator);
    }

    private static PlatformTransactionManager stubTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
