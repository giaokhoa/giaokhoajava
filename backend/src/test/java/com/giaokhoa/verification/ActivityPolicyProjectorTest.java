package com.giaokhoa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.policy.PolicyGraph;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Khóa logic thuần của {@link ActivityPolicyProjector}: phép dựng lại gate native từ canonical
 * descriptor — đường mà {@code ProjectionReconciler} dùng để áp đúng graph đã ghi, nên định dạng
 * descriptor và gate của nó là hợp đồng phải giữ ổn định.
 */
class ActivityPolicyProjectorTest {

    private static final UUID WORKSPACE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PERIOD = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String TARGET =
            "workspace:" + WORKSPACE + ":activity:40000000-0000-0000-0000-000000000001";

    @Test
    @DisplayName("Descriptor nguồn nộp bài chỉ cho gate actor của học sinh, không qua gate gói")
    void actorDescriptorYieldsActorGateOnly() {
        String descriptor =
                "ACTIVITY_ACTOR|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=sub-1|target="
                        + TARGET
                        + "|scope=submit";
        assertEquals(
                List.of(new PolicyGraph.Gate.Actor("sub-1")),
                ActivityPolicyProjector.gatesFor(descriptor));
    }

    @Test
    @DisplayName(
            "Descriptor nguồn quản lý hoạt động cho gate actor AND cửa sổ kỳ gói nửa mở"
                    + " [from, until)")
    void actorPeriodDescriptorYieldsActorAndValidityGates() {
        String descriptor =
                "ACTIVITY_ACTOR_PERIOD|workspace="
                        + WORKSPACE
                        + "|user="
                        + USER
                        + "|subject=sub-1|period="
                        + PERIOD
                        + "|from=2026-01-01T00:00:00Z|until=2026-02-01T00:00:00Z|target="
                        + TARGET
                        + "|scope=schedule.extend";
        assertEquals(
                List.of(
                        new PolicyGraph.Gate.Actor("sub-1"),
                        new PolicyGraph.Gate.Validity(
                                Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-02-01T00:00:00Z"))),
                ActivityPolicyProjector.gatesFor(descriptor));
    }

    @Test
    @DisplayName(
            "Descriptor quản lý thiếu cửa sổ kỳ gói, thiếu subject hoặc lạ loại đều bị từ chối")
    void malformedDescriptorsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ActivityPolicyProjector.gatesFor(
                                "ACTIVITY_ACTOR_PERIOD|workspace="
                                        + WORKSPACE
                                        + "|user="
                                        + USER
                                        + "|subject=sub-1|target="
                                        + TARGET
                                        + "|scope=schedule.extend"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ActivityPolicyProjector.gatesFor(
                                "ACTIVITY_ACTOR|workspace=" + WORKSPACE + "|target=" + TARGET));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivityPolicyProjector.gatesFor("WHATEVER|subject=sub-1"));
    }
}
