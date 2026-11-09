package com.giaokhoa.subscriptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SubscriptionPolicyTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void queriesBothSubscriptionConditionsInOneSqlStatement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                        contains("SELECT NOT EXISTS"),
                        eq(Boolean.class),
                        eq(WORKSPACE),
                        eq(WORKSPACE)))
                .thenReturn(true, false, null);

        assertTrue(SubscriptionPolicy.currentOrUnconfirmed(jdbc, WORKSPACE));
        assertFalse(SubscriptionPolicy.currentOrUnconfirmed(jdbc, WORKSPACE));
        assertFalse(SubscriptionPolicy.currentOrUnconfirmed(jdbc, WORKSPACE));
    }
}
