package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.support.TestIds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EligibilitySourceTest {
    private static final UUID USER = TestIds.randomId();
    private static final String TARGET = "account:" + USER;

    @Test
    void eligibilityDescriptorIsCanonicalAndScopeBound() {
        String descriptor =
                ProjectionReconciler.eligibilityDescriptor(
                        USER, "subject-1", TARGET, "workspace.list");

        assertEquals(
                "IDENTITY_ELIGIBILITY|user="
                        + USER
                        + "|subject=subject-1|target="
                        + TARGET
                        + "|scope=workspace.list|scopes=workspace.list,history.read",
                descriptor);
        assertNotEquals(
                ProjectionReconciler.desiredHash(TARGET, "workspace.list", List.of(descriptor)),
                ProjectionReconciler.desiredHash(
                        TARGET,
                        "history.read",
                        List.of(
                                ProjectionReconciler.eligibilityDescriptor(
                                        USER, "subject-1", TARGET, "history.read"))));
    }

    @Test
    void eligibilityGraphIsTheActorBehindTheMandatoryEligibilityGate() {
        String descriptor =
                ProjectionReconciler.eligibilityDescriptor(
                        USER, "subject-1", TARGET, "workspace.list");

        PolicyGraph graph =
                ProjectionReconciler.graphFor(TARGET, "workspace.list", List.of(descriptor));

        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(
                                NativePolicyTemplates.ELIGIBILITY_GROUP)),
                graph.commonGates());
        assertEquals(
                List.of(new PolicyGraph.Source(List.of(new PolicyGraph.Gate.Actor("subject-1")))),
                graph.sources());
    }
}
