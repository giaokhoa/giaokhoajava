package com.giaokhoa.shared.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopedPageCursorTest {
    @Test
    void signedCursorIsBoundToActorWorkspaceQueryAndLimit() {
        ScopedPageCursor cursor = new ScopedPageCursor();
        UUID anchor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        String context = "memberships|" + actor + "|" + workspace + "|20";
        String encoded = cursor.encode(anchor, context);

        assertEquals(anchor, cursor.decode(encoded, context));
        for (String wrongContext :
                new String[] {
                    "memberships|" + UUID.randomUUID() + "|" + workspace + "|20",
                    "memberships|" + actor + "|" + UUID.randomUUID() + "|20",
                    "memberships|" + actor + "|" + workspace + "|100",
                    "classes|" + actor + "|" + workspace + "|20"
                }) {
            assertEquals(
                    "INVALID_CURSOR",
                    assertThrows(
                                    HttpProblemException.class,
                                    () -> cursor.decode(encoded, wrongContext))
                            .getCode());
        }
        assertEquals(
                "INVALID_CURSOR",
                assertThrows(
                                HttpProblemException.class,
                                () -> cursor.decode(anchor + ".bad-signature", context))
                        .getCode());
        assertEquals(
                "INVALID_CURSOR",
                assertThrows(
                                HttpProblemException.class,
                                () -> new ScopedPageCursor().decode(encoded, context))
                        .getCode());
    }
}
