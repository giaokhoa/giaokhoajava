package com.giaokhoa.subscriptions;

import java.time.Instant;
import java.util.UUID;

/**
 * Một kỳ gói của workspace đọc từ bảng {@code subscription_period}: cửa sổ hiệu lực mà các
 * projector dùng để gate descriptor theo gói. Type thuộc domain subscriptions — nơi sở hữu bảng —
 * và được các projector của teaching/tenancy dùng chung thay vì khai báo trùng ở từng nơi.
 */
public record SubscriptionPeriodRow(UUID id, Instant validFrom, Instant expiresAt) {}
