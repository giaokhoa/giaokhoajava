package com.giaokhoa.subscriptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.support.RandomIds;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubscriptionPolicyTest {
    private static final UUID WORKSPACE = RandomIds.randomId();

    private final SubscriptionPeriodRepository periods = mock(SubscriptionPeriodRepository.class);
    private final SubscriptionPolicy policy = new SubscriptionPolicy(periods);

    // Ngữ nghĩa thật của điều kiện gói (chưa có gói, gói hết hạn, gói còn hạn) khóa trên
    // PostgreSQL thật ở TeachingServiceIT.subscriptionPolicyMatchesRealPostgresLifecycle. Ở tầng
    // unit chỉ khẳng định ánh xạ từ kết quả của repository sang boolean của cả hai phương thức,
    // khớp theo đúng workspace được truyền vào.
    @DisplayName("current ánh xạ thẳng kết quả tra kỳ đang hiệu lực của đúng workspace")
    @Test
    void currentMapsRepositoryAnswer() {
        for (boolean result : new boolean[] {true, false}) {
            when(periods.existsByWorkspaceIdAndValidFromLessThanEqualAndExpiresAtGreaterThan(
                            eq(WORKSPACE), any(Instant.class), any(Instant.class)))
                    .thenReturn(result);
            assertEquals(result, policy.current(WORKSPACE));
        }
    }

    @DisplayName("Chưa từng có kỳ gói nào thì currentOrUnconfirmed đạt mà không cần kỳ hiệu lực")
    @Test
    void currentOrUnconfirmedWhenNoPeriodExists() {
        when(periods.existsByWorkspaceId(WORKSPACE)).thenReturn(false);
        assertTrue(policy.currentOrUnconfirmed(WORKSPACE));
    }

    @DisplayName("Đã có kỳ gói thì currentOrUnconfirmed theo đúng kết quả của current")
    @Test
    void currentOrUnconfirmedFallsBackToCurrent() {
        when(periods.existsByWorkspaceId(WORKSPACE)).thenReturn(true);
        when(periods.existsByWorkspaceIdAndValidFromLessThanEqualAndExpiresAtGreaterThan(
                        eq(WORKSPACE), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
        assertFalse(policy.currentOrUnconfirmed(WORKSPACE));
    }
}
