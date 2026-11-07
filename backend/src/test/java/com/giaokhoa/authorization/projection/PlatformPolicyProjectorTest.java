package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformPolicyProjectorTest {

    @Test
    void operatorDescriptorMatchesTheContractFormat() {
        assertEquals(
                "PLATFORM_OPERATOR|group=/gk/platform/operators|target=platform"
                        + "|scope=center.create",
                PlatformPolicyProjector.operatorDescriptor("center.create"));
    }

    @Test
    void gatesForRoundTripsToTheSingleOperatorGroupGate() {
        for (String scope : PlatformPolicyProjector.managedScopes()) {
            assertEquals(
                    List.of(
                            new PolicyGraph.Gate.GroupMembership(
                                    NativePolicyTemplates.PLATFORM_OPERATORS_GROUP)),
                    PlatformPolicyProjector.gatesFor(
                            PlatformPolicyProjector.operatorDescriptor(scope)),
                    scope);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PlatformPolicyProjector.gatesFor("PERSONAL_OWNER|subject=x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlatformPolicyProjector.gatesFor("PLATFORM_OPERATOR|target=platform"));
    }

    @Test
    void bundleHasExactlyOneDescriptorPerManagedScope() {
        List<String> bundle = PlatformPolicyProjector.bundleDescriptors();
        assertEquals(PlatformPolicyProjector.managedScopes().size(), bundle.size());
        assertEquals(bundle.size(), bundle.stream().distinct().count());
        for (String scope : PlatformPolicyProjector.managedScopes()) {
            assertEquals(
                    List.of(PlatformPolicyProjector.operatorDescriptor(scope)),
                    PlatformPolicyProjector.sourcesFor(scope));
        }
    }

    @Test
    void bundleHashIsStableAndScopeSensitive() {
        String target = NativePolicyTemplates.platformKey();
        String scope = PlatformPolicyProjector.bundleScope();
        List<String> bundle = PlatformPolicyProjector.bundleDescriptors();
        List<String> shuffled = new ArrayList<>(bundle);
        Collections.reverse(shuffled);
        assertEquals(
                ProjectionReconciler.desiredHash(target, scope, bundle),
                ProjectionReconciler.desiredHash(target, scope, shuffled));
        assertNotEquals(
                ProjectionReconciler.desiredHash(target, scope, bundle),
                ProjectionReconciler.desiredHash(target, scope, bundle.subList(1, bundle.size())));
    }
}
