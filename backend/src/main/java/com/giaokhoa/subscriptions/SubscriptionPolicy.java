package com.giaokhoa.subscriptions;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Quy tắc gói trung tâm: chưa từng xác nhận gói thì chưa bị khóa; đã có gói phải còn hiệu lực. */
public final class SubscriptionPolicy {
    private SubscriptionPolicy() {}

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
