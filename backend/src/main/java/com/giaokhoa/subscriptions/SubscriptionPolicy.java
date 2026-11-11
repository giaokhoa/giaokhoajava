package com.giaokhoa.subscriptions;

import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Quy tắc gói theo loại workspace: gói là entitlement bắt buộc của personal, còn center chỉ khóa
 * khi đã có gói mà hết hiệu lực. Điều kiện hiệu lực đọc qua repository kỳ gói với mốc thời gian lấy
 * tại lúc gọi, đúng cửa sổ [valid_from, expires_at) của dữ kiện đã ghi.
 */
@Component
public class SubscriptionPolicy {
    private final SubscriptionPeriodRepository periods;

    public SubscriptionPolicy(SubscriptionPeriodRepository periods) {
        this.periods = periods;
    }

    /** Có một kỳ gói đang hiệu lực tại thời điểm gọi; chưa từng có kỳ nào thì không đạt. */
    public boolean current(UUID workspaceId) {
        Instant now = Instant.now();
        return periods.existsByWorkspaceIdAndValidFromLessThanEqualAndExpiresAtGreaterThan(
                workspaceId, now, now);
    }

    /** Chưa từng có kỳ gói nào, hoặc có một kỳ đang hiệu lực tại thời điểm gọi. */
    public boolean currentOrUnconfirmed(UUID workspaceId) {
        return !periods.existsByWorkspaceId(workspaceId) || current(workspaceId);
    }
}
