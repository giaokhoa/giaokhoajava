package com.giaokhoa.verification.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleActivityTest {

    @Test
    void submissionWindowIsHalfOpenAtDueAt() {
        Instant dueAt = Instant.parse("2026-11-12T12:00:00Z");
        SampleActivity activity =
                new SampleActivity(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        dueAt.minusSeconds(3600),
                        dueAt,
                        1,
                        UUID.randomUUID(),
                        "ASSIGNED",
                        SampleActivity.CompletionPolicy.INTERRUPTIBLE);

        assertTrue(activity.acceptsSubmissionAt(dueAt.minusNanos(1)));
        assertFalse(activity.acceptsSubmissionAt(dueAt));
        assertFalse(activity.acceptsSubmissionAt(dueAt.plusNanos(1)));
    }
}
