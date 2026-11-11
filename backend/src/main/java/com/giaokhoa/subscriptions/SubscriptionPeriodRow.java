package com.giaokhoa.subscriptions;

import com.giaokhoa.subscriptions.model.SubscriptionPeriod;
import java.time.Instant;
import java.util.UUID;

/**
 * Ảnh chụp tối thiểu của một kỳ gói mà các projector dùng để dựng descriptor bị gate bởi kỳ gói:
 * một định nghĩa dùng chung cho mọi projector thay cho các bản sao theo module trước đây.
 */
public record SubscriptionPeriodRow(UUID id, Instant validFrom, Instant expiresAt) {
    public static SubscriptionPeriodRow from(SubscriptionPeriod period) {
        return new SubscriptionPeriodRow(
                period.getId(), period.getValidFrom(), period.getExpiresAt());
    }
}
