package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.policy.PolicyGraph;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Khóa logic thuần của {@link ResourcePolicyProjector}: map token action lưu trữ sang scope mức
 * version và phép dựng lại gate native từ canonical descriptor — đường mà {@code
 * ProjectionReconciler} dùng để áp đúng graph đã ghi, nên định dạng descriptor và gate của nó là
 * hợp đồng phải giữ ổn định.
 */
class ResourcePolicyProjectorTest {

    private static final UUID WORKSPACE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PERIOD = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID GRANT = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final String TARGET =
            "workspace:"
                    + WORKSPACE
                    + ":resource:30000000-0000-0000-0000-000000000001"
                    + ":version:50000000-0000-0000-0000-000000000001";

    @Test
    void storedActionsMapToVersionScopes() {
        assertEquals(Optional.of("content.read"), ResourcePolicyProjector.mapAction("content.read"));
        assertEquals(Optional.of("answer.read"), ResourcePolicyProjector.mapAction("answer.read"));
        assertEquals(Optional.of("use"), ResourcePolicyProjector.mapAction("use"));
        assertEquals(Optional.of("review"), ResourcePolicyProjector.mapAction("review"));
        assertEquals(Optional.of("share"), ResourcePolicyProjector.mapAction("share"));
        assertEquals(Optional.of("library.save"), ResourcePolicyProjector.mapAction("library.save"));
        // Biên soạn trên target version là tạo bản chỉnh sửa.
        assertEquals(Optional.of("fork"), ResourcePolicyProjector.mapAction("draft.edit"));
        assertEquals(Optional.of("fork"), ResourcePolicyProjector.mapAction("fork"));
        assertTrue(ResourcePolicyProjector.mapAction("version.create").isEmpty());
        assertTrue(ResourcePolicyProjector.mapAction("bogus").isEmpty());
        assertEquals(
                List.of("use", "fork"),
                ResourcePolicyProjector.mapActions(List.of("use", "draft.edit", "bogus")));
    }

    @Test
    void actorDescriptorYieldsActorGate() {
        String descriptor =
                "RESOURCE_ACTOR|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=sub-1|target="
                        + TARGET
                        + "|scope=content.read";
        assertEquals(
                List.of(new PolicyGraph.Gate.Actor("sub-1")),
                ResourcePolicyProjector.gatesFor(descriptor));
    }

    @Test
    void actorPeriodDescriptorYieldsActorAndValidityGates() {
        String descriptor =
                "RESOURCE_ACTOR_PERIOD|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=sub-1|period="
                        + PERIOD
                        + "|from=2026-01-01T00:00:00Z|until=2026-02-01T00:00:00Z|target="
                        + TARGET
                        + "|scope=library.save";
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.Actor("sub-1"),
                        new PolicyGraph.Gate.Validity(
                                Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-02-01T00:00:00Z"))),
                ResourcePolicyProjector.gatesFor(descriptor));
    }

    @Test
    void groupDescriptorYieldsGroupGate() {
        String group = "/gk/workspaces/" + WORKSPACE + "/members";
        String descriptor =
                "RESOURCE_GROUP|workspace="
                        + WORKSPACE
                        + "|group="
                        + group
                        + "|target="
                        + TARGET
                        + "|scope=content.read";
        assertEquals(
                List.of(new PolicyGraph.Gate.GroupMembership(group)),
                ResourcePolicyProjector.gatesFor(descriptor));
    }

    @Test
    void licenseDescriptorYieldsAudienceAndOpenEndedValidity() {
        String descriptor =
                "RESOURCE_LICENSE|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=sub-1|grant="
                        + GRANT
                        + "|from=2026-01-01T00:00:00Z|until=-|target="
                        + TARGET
                        + "|scope=use";
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.Actor("sub-1"),
                        new PolicyGraph.Gate.Validity(Instant.parse("2026-01-01T00:00:00Z"), null)),
                ResourcePolicyProjector.gatesFor(descriptor));
    }

    @Test
    void licenseGroupDescriptorYieldsGroupAndValidity() {
        String group = "/gk/workspaces/" + WORKSPACE + "/members";
        String descriptor =
                "RESOURCE_LICENSE|workspace="
                        + WORKSPACE
                        + "|group="
                        + group
                        + "|grant="
                        + GRANT
                        + "|from=2026-01-01T00:00:00Z|until=2026-03-01T00:00:00Z|target="
                        + TARGET
                        + "|scope=content.read";
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.GroupMembership(group),
                        new PolicyGraph.Gate.Validity(
                                Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-03-01T00:00:00Z"))),
                ResourcePolicyProjector.gatesFor(descriptor));
    }

    @Test
    void unknownDescriptorIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyProjector.gatesFor("WHATEVER|subject=sub-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyProjector.gatesFor("RESOURCE_ACTOR|workspace=" + WORKSPACE));
    }
}
