package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformPolicyProjectorTest {

    @DisplayName("Descriptor của operator khớp định dạng hợp đồng")
    @Test
    void operatorDescriptorMatchesContract() {
        assertEquals(
                "PLATFORM_OPERATOR|group=/gk/platform/operators|target=platform"
                        + "|scope=center.create",
                PlatformPolicyProjector.operatorDescriptor("center.create"));
    }

    @DisplayName("gatesFor round-trip về đúng một group gate duy nhất của operator")
    @Test
    void gatesForRoundTripsToGroupGate() {
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

    @DisplayName("Bundle có đúng một descriptor cho mỗi managed scope")
    @Test
    void oneDescriptorPerManagedScope() {
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
