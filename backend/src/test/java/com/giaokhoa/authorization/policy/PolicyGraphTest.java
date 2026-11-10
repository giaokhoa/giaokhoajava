package com.giaokhoa.authorization.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.NativePolicyTemplates.PrerequisiteRole;
import com.giaokhoa.authorization.policy.NativePolicyTemplates.TargetType;
import com.giaokhoa.authorization.policy.PolicyGraph.Gate;
import com.giaokhoa.authorization.policy.PolicyGraph.Source;
import com.giaokhoa.support.TestIds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyGraphTest {
    private static final UUID WORKSPACE = TestIds.randomId();
    private static final UUID OBJECT = TestIds.randomId();
    private static final Instant FROM = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2030-02-01T00:00:00Z");

    @Test
    void descriptorAndHashIgnoreGateAndSourceOrder() {
        Gate owners = new Gate.GroupMembership(NativePolicyTemplates.ownersGroup(WORKSPACE));
        Gate managers = new Gate.GroupMembership(NativePolicyTemplates.managersGroup(WORKSPACE));
        Gate validity = new Gate.Validity(FROM, UNTIL);
        PolicyGraph first =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "class.create",
                        List.of(Source.of(owners), Source.of(managers, validity)));
        PolicyGraph reordered =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "class.create",
                        List.of(Source.of(validity, managers), Source.of(owners)));

        assertEquals(first.descriptor(), reordered.descriptor());
        assertEquals(first.desiredHash(), reordered.desiredHash());
        assertEquals(64, first.desiredHash().length());
        assertTrue(first.descriptor().contains("VALIDITY|from=" + FROM + "|until=" + UNTIL));
    }

    @Test
    void rootPolicyNamesMatchWhatTheWriterLinksToTheRootPermission() {
        Gate owners = new Gate.GroupMembership(NativePolicyTemplates.ownersGroup(WORKSPACE));
        Gate managers = new Gate.GroupMembership(NativePolicyTemplates.managersGroup(WORKSPACE));
        Gate validity = new Gate.Validity(FROM, UNTIL);
        String eligible =
                NativePolicyTemplates.groupPolicyName(NativePolicyTemplates.ELIGIBILITY_GROUP);
        String target = NativePolicyTemplates.workspaceKey(WORKSPACE);

        PolicyGraph singleLeaf =
                new PolicyGraph(
                        target,
                        "enter",
                        List.of(
                                Source.of(
                                        new Gate.GroupMembership(
                                                NativePolicyTemplates.membersGroup(WORKSPACE)))));
        assertEquals(singleLeaf.policyNames(), singleLeaf.rootPolicyNames());

        Source gatedOwners = Source.of(owners, validity);
        PolicyGraph gated = new PolicyGraph(target, "class.create", List.of(gatedOwners));
        assertEquals(
                List.of(
                        eligible,
                        NativePolicyTemplates.sourcePolicyName(gatedOwners.descriptorHash())),
                gated.rootPolicyNames());

        PolicyGraph multiSource =
                new PolicyGraph(
                        target,
                        "class.create",
                        List.of(Source.of(owners), Source.of(managers, validity)));
        assertEquals(
                List.of(
                        NativePolicyTemplates.anySourcePolicyName(target, "class.create"),
                        eligible),
                multiSource.rootPolicyNames());
    }

    @Test
    void differentScopeOrSourceChangesTheHash() {
        Gate owners = new Gate.GroupMembership(NativePolicyTemplates.ownersGroup(WORKSPACE));
        PolicyGraph create =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "class.create",
                        List.of(Source.of(owners)));
        PolicyGraph read =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "read",
                        List.of(Source.of(owners)));
        PolicyGraph otherSource =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "class.create",
                        List.of(
                                Source.of(
                                        new Gate.GroupMembership(
                                                NativePolicyTemplates.managersGroup(WORKSPACE)))));

        assertNotEquals(create.desiredHash(), read.desiredHash());
        assertNotEquals(create.desiredHash(), otherSource.desiredHash());
    }

    @Test
    void eligibilityIsACommonGateOutsideTheSources() {
        PolicyGraph graph =
                PolicyGraph.forEligibleActor(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "enter",
                        List.of(
                                Source.of(
                                        new Gate.GroupMembership(
                                                NativePolicyTemplates.membersGroup(WORKSPACE)))));

        assertEquals(
                List.of(new Gate.GroupMembership(NativePolicyTemplates.ELIGIBILITY_GROUP)),
                graph.commonGates());
        assertTrue(graph.descriptor().contains("|common=GROUP|path=/gk/identity/eligible"));
    }

    @Test
    void rejectsTargetsAndScopesOutsideTheFixedTemplates() {
        Gate owners = new Gate.GroupMembership(NativePolicyTemplates.ownersGroup(WORKSPACE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyGraph("workspace:not-a-uuid", "enter", List.of(Source.of(owners))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyGraph(
                                NativePolicyTemplates.workspaceKey(WORKSPACE),
                                "content.read",
                                List.of(Source.of(owners))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyGraph(
                                NativePolicyTemplates.workspaceKey(WORKSPACE), "enter", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyGraph(
                                NativePolicyTemplates.workspaceKey(WORKSPACE),
                                "enter",
                                List.of(new Source(List.of()))));
    }

    @Test
    void rejectsArbitraryGroupsAndBrokenValidityWindows() {
        assertThrows(
                IllegalArgumentException.class, () -> new Gate.GroupMembership("/gk/roles/admin"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Gate.GroupMembership("/gk/workspaces/not-a-uuid/members"));
        assertThrows(IllegalArgumentException.class, () -> new Gate.Validity(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Gate.Validity(UNTIL, FROM));
        assertThrows(IllegalArgumentException.class, () -> new Gate.Actor(" "));
    }

    @Test
    void businessGraphRequiresEligibilityOutsideTheSources() {
        Source owner =
                Source.of(new Gate.GroupMembership(NativePolicyTemplates.ownersGroup(WORKSPACE)));
        String target = NativePolicyTemplates.workspaceKey(WORKSPACE);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyGraph(target, "enter", List.of(), List.of(owner)));
        // Đặt eligibility vào một OR source là không đủ: nó phải chung cho mọi source.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyGraph(
                                target,
                                "enter",
                                List.of(),
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(
                                                        NativePolicyTemplates.ELIGIBILITY_GROUP)),
                                        owner)));
        assertEquals(
                List.of(new Gate.GroupMembership(NativePolicyTemplates.ELIGIBILITY_GROUP)),
                new PolicyGraph(target, "enter", List.of(owner)).commonGates());
    }

    @Test
    void onboardingAndRecoveryAreExplicitEligibilityExceptions() {
        Source actor = Source.of(new Gate.Actor("identity-actor"));
        assertTrue(
                new PolicyGraph(
                                NativePolicyTemplates.profileKey(OBJECT),
                                "profile.read",
                                List.of(),
                                List.of(actor))
                        .commonGates()
                        .isEmpty());
        assertTrue(
                new PolicyGraph(
                                NativePolicyTemplates.profileKey(OBJECT),
                                "recovery.request",
                                List.of(),
                                List.of(actor))
                        .commonGates()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyGraph(
                                NativePolicyTemplates.profileKey(OBJECT),
                                "workspace.list",
                                List.of(),
                                List.of(actor)));
    }

    @Test
    void rejectsSubsecondTimeBoundariesInsteadOfGrantingEarlyOrExpiringEarly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Gate.Validity(FROM.plusMillis(900), UNTIL));
        assertThrows(
                IllegalArgumentException.class, () -> new Gate.Validity(FROM, UNTIL.plusNanos(1)));
        assertEquals(
                FROM, java.util.Objects.requireNonNull(new Gate.Validity(FROM, UNTIL).validFrom()));
    }

    @Test
    void groupTemplatesMatchTheFixedSources() {
        List<String> paths =
                List.of(
                        NativePolicyTemplates.ELIGIBILITY_GROUP,
                        NativePolicyTemplates.PLATFORM_OPERATORS_GROUP,
                        NativePolicyTemplates.membersGroup(WORKSPACE),
                        NativePolicyTemplates.ownersGroup(WORKSPACE),
                        NativePolicyTemplates.managersGroup(WORKSPACE),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                WORKSPACE, PrerequisiteRole.TEACHERS),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                WORKSPACE, PrerequisiteRole.STUDENTS),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                WORKSPACE, PrerequisiteRole.PARENTS),
                        NativePolicyTemplates.homeroomGroup(WORKSPACE, OBJECT),
                        NativePolicyTemplates.courseSubjectTeachersGroup(WORKSPACE, OBJECT),
                        NativePolicyTemplates.courseSubjectStudentsGroup(WORKSPACE, OBJECT),
                        NativePolicyTemplates.guardiansGroup(WORKSPACE, OBJECT));
        for (String path : paths) {
            assertTrue(NativePolicyTemplates.isGroupSource(path), path);
        }
        // Prerequisite role là group được chiếu, không bao giờ là global realm role.
        assertEquals(
                "/gk/workspaces/" + WORKSPACE + "/roles/teachers",
                NativePolicyTemplates.prerequisiteRoleGroup(WORKSPACE, PrerequisiteRole.TEACHERS));
    }

    @Test
    void targetKeysCarryWorkspaceObjectAndVersionAndResolveTheirType() {
        assertEquals(TargetType.PLATFORM, typeOf(NativePolicyTemplates.platformKey()));
        assertEquals(TargetType.PROFILE, typeOf(NativePolicyTemplates.profileKey(OBJECT)));
        assertEquals(TargetType.WORKSPACE, typeOf(NativePolicyTemplates.workspaceKey(WORKSPACE)));
        assertEquals(TargetType.CLASS, typeOf(NativePolicyTemplates.classKey(WORKSPACE, OBJECT)));
        assertEquals(TargetType.COURSE, typeOf(NativePolicyTemplates.courseKey(WORKSPACE, OBJECT)));
        assertEquals(
                TargetType.COURSE_SUBJECT,
                typeOf(NativePolicyTemplates.courseSubjectKey(WORKSPACE, OBJECT)));
        assertEquals(
                TargetType.RESOURCE, typeOf(NativePolicyTemplates.resourceKey(WORKSPACE, OBJECT)));
        assertEquals(
                TargetType.RESOURCE_VERSION,
                typeOf(NativePolicyTemplates.resourceVersionKey(WORKSPACE, OBJECT, OBJECT)));
        assertEquals(
                TargetType.ACTIVITY, typeOf(NativePolicyTemplates.activityKey(WORKSPACE, OBJECT)));
        assertEquals(TargetType.RESULT, typeOf(NativePolicyTemplates.resultKey(WORKSPACE, OBJECT)));
        assertEquals(TargetType.GRANT, typeOf(NativePolicyTemplates.grantKey(OBJECT)));
        assertTrue(NativePolicyTemplates.targetTypeOf("tenant:whatever").isEmpty());
    }

    @Test
    void fixedScopeCatalogMatchesTheContract() {
        assertTrue(TargetType.WORKSPACE.scopes().contains("enter"));
        assertTrue(TargetType.WORKSPACE.scopes().contains("membership.role.revoke"));
        assertTrue(TargetType.RESOURCE_VERSION.scopes().contains("content.read"));
        assertTrue(TargetType.RESOURCE_VERSION.scopes().contains("answer.read"));
        assertEquals(List.of("accept", "revoke"), TargetType.GRANT.scopes());
        assertEquals(List.of("read", "update", "subject.add"), TargetType.COURSE.scopes());
    }

    private static TargetType typeOf(String targetKey) {
        return NativePolicyTemplates.targetTypeOf(targetKey).orElseThrow();
    }
}
