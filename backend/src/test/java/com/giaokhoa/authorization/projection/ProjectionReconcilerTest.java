package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.support.TestIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionReconcilerTest {
    private static final UUID WORKSPACE = TestIds.randomId();
    private static final UUID USER = TestIds.randomId();
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

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
