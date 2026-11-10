package com.giaokhoa.subscriptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SubscriptionPolicyTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void nullResultFromQueryMapsToFalse() {
        // Ngữ nghĩa thật của điều kiện gói (chưa có gói, gói hết hạn, gói còn hạn) đã khóa trên
        // PostgreSQL thật ở TeachingServiceIT.subscriptionPolicyMatchesRealPostgresLifecycle; ở
        // tầng unit chỉ còn khẳng định được ánh xạ kết quả null thành false, không buộc khẳng
        // định vào cú pháp SQL như bản cũ.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any()))
                .thenReturn((Boolean) null);

        assertFalse(SubscriptionPolicy.currentOrUnconfirmed(jdbc, WORKSPACE));
    }
}
