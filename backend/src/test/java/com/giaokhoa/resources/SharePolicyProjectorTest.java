package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.PolicyGraph;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Khóa logic thuần của {@link SharePolicyProjector}: bundle của một grant chỉ phụ thuộc trạng thái
 * và mode của chính nó — accept là nguồn của phía nhận khi còn OFFERED, revoke là nguồn của chính
 * người cấp chỉ khi đã ACCEPTED và mode REVOCABLE, LONG_TERM không bao giờ có nguồn revoke.
 * Descriptor phải giữ đúng từ vựng canonical của họ resource để phép dựng gate của reconciliation
 * áp được như bundle resource version.
 */
class SharePolicyProjectorTest {

    private static final UUID GRANT_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_WORKSPACE =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT_WORKSPACE =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID GRANTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT_OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String TARGET = "grant:" + GRANT_ID;

    @Test
    void managedScopesAndBundleScopeFollowTheGrantCatalog() {
        assertEquals("accept", SharePolicyProjector.bundleScope());
        assertEquals(List.of("accept", "revoke"), SharePolicyProjector.managedScopes());
    }

    @Test
    void offeredGrantToCenterIsAcceptableByRecipientOwnersAndManagersOnly() {
        List<String> descriptors =
                SharePolicyProjector.descriptorsFor(
                        facts("OFFERED", "REVOCABLE", "CENTER"), TARGET);
        assertEquals(
                List.of(
                        "RESOURCE_GROUP|workspace="
                                + RECIPIENT_WORKSPACE
                                + "|group=/gk/workspaces/"
                                + RECIPIENT_WORKSPACE
                                + "/managers|target="
                                + TARGET
                                + "|scope=accept",
                        "RESOURCE_GROUP|workspace="
                                + RECIPIENT_WORKSPACE
                                + "|group=/gk/workspaces/"
                                + RECIPIENT_WORKSPACE
                                + "/owners|target="
                                + TARGET
                                + "|scope=accept"),
                descriptors);
    }

    @Test
    void offeredGrantToPersonalIsAcceptableByRecipientOwnerOnly() {
        List<String> descriptors =
                SharePolicyProjector.descriptorsFor(
                        facts("OFFERED", "LONG_TERM", "PERSONAL"), TARGET);
        assertEquals(
                List.of(
                        "RESOURCE_ACTOR|workspace="
                                + RECIPIENT_WORKSPACE
                                + "|user="
                                + RECIPIENT_OWNER
                                + "|subject=sub-owner|target="
                                + TARGET
                                + "|scope=accept"),
                descriptors);
    }

    @Test
    void acceptedRevocableGrantIsRevocableByGrantorOnly() {
        List<String> descriptors =
                SharePolicyProjector.descriptorsFor(
                        facts("ACCEPTED", "REVOCABLE", "CENTER"), TARGET);
        assertEquals(
                List.of(
                        "RESOURCE_ACTOR|workspace="
                                + SOURCE_WORKSPACE
                                + "|user="
                                + GRANTOR
                                + "|subject=sub-grantor|target="
                                + TARGET
                                + "|scope=revoke"),
                descriptors);
    }

    @Test
    void longTermGrantHasNoRevokeSourceEvenAfterAcceptance() {
        assertTrue(
                SharePolicyProjector.descriptorsFor(
                                facts("ACCEPTED", "LONG_TERM", "CENTER"), TARGET)
                        .isEmpty());
        assertTrue(
                SharePolicyProjector.descriptorsFor(facts("OFFERED", "REVOCABLE", "CENTER"), TARGET)
                        .stream()
                        .noneMatch(descriptor -> descriptor.endsWith("|scope=revoke")));
    }

    @Test
    void closedGrantsHaveNoSources() {
        assertTrue(
                SharePolicyProjector.descriptorsFor(facts("REVOKED", "REVOCABLE", "CENTER"), TARGET)
                        .isEmpty());
        assertTrue(
                SharePolicyProjector.descriptorsFor(facts("EXPIRED", "REVOCABLE", "CENTER"), TARGET)
                        .isEmpty());
    }

    @Test
    void grantDescriptorsYieldNativeGatesThroughTheSharedVocabulary() {
        String acceptDescriptor =
                SharePolicyProjector.descriptorsFor(facts("OFFERED", "REVOCABLE", "CENTER"), TARGET)
                        .getFirst();
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(
                                "/gk/workspaces/" + RECIPIENT_WORKSPACE + "/managers")),
                ResourcePolicyProjector.gatesFor(acceptDescriptor));
        String revokeDescriptor =
                SharePolicyProjector.descriptorsFor(
                                facts("ACCEPTED", "REVOCABLE", "CENTER"), TARGET)
                        .getFirst();
        assertEquals(
                List.of(new PolicyGraph.Gate.Actor("sub-grantor")),
                ResourcePolicyProjector.gatesFor(revokeDescriptor));
    }

    private static SharePolicyProjector.GrantFacts facts(
            String state, String mode, String recipientKind) {
        return new SharePolicyProjector.GrantFacts(
                GRANT_ID,
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                SOURCE_WORKSPACE,
                GRANTOR,
                "sub-grantor",
                RECIPIENT_WORKSPACE,
                recipientKind,
                "PERSONAL".equals(recipientKind) ? RECIPIENT_OWNER : null,
                "PERSONAL".equals(recipientKind) ? "sub-owner" : null,
                mode,
                state);
    }
}
