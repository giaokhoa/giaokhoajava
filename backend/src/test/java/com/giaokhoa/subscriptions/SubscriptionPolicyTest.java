package com.giaokhoa.subscriptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.support.RandomIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SubscriptionPolicyTest {
    private static final UUID WORKSPACE = RandomIds.randomId();

    // Ngữ nghĩa thật của điều kiện gói (chưa có gói, gói hết hạn, gói còn hạn) khóa trên
    // PostgreSQL thật ở TeachingServiceIT.subscriptionPolicyMatchesRealPostgresLifecycle. Ở tầng
    // unit chỉ khẳng định ánh xạ từ kết quả truy vấn sang boolean của cả hai phương thức, khớp
    // theo đúng workspace được truyền vào chứ không theo văn bản SQL.
    @Test
    void queryResultMapsToBooleanForBothPolicies() {
        for (Boolean result : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
            JdbcTemplate jdbc = jdbcReturning(result);
            boolean expected = Boolean.TRUE.equals(result);
            assertEquals(expected, SubscriptionPolicy.current(jdbc, WORKSPACE));
            assertEquals(expected, SubscriptionPolicy.currentOrUnconfirmed(jdbc, WORKSPACE));
        }
    }

    private static JdbcTemplate jdbcReturning(Boolean result) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(WORKSPACE))).thenReturn(result);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(WORKSPACE), eq(WORKSPACE)))
                .thenReturn(result);
        return jdbc;
    }
}
