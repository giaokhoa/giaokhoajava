package com.giaokhoa.subscriptions;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Quy tắc gói theo loại workspace: gói là entitlement bắt buộc của personal, còn center chỉ khóa
 * khi đã có gói mà hết hiệu lực.
 */
public final class SubscriptionPolicy {
    private SubscriptionPolicy() {}

    /** Có một kỳ gói đang hiệu lực tại thời điểm gọi; chưa từng có kỳ nào thì không đạt. */
    public static boolean current(JdbcTemplate jdbc, UUID workspaceId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM subscription_period"
                                + " WHERE workspace_id = ? AND valid_from <= now()"
                                + " AND expires_at > now())",
                        Boolean.class,
                        workspaceId));
    }

    /** Một snapshot SQL cho cả điều kiện chưa có gói và có gói đang hiệu lực. */
    public static boolean currentOrUnconfirmed(JdbcTemplate jdbc, UUID workspaceId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT NOT EXISTS (SELECT 1 FROM subscription_period WHERE workspace_id = ?)"
                                + " OR EXISTS (SELECT 1 FROM subscription_period"
                                + " WHERE workspace_id = ? AND valid_from <= now() AND expires_at > now())",
                        Boolean.class,
                        workspaceId,
                        workspaceId));
    }
}
