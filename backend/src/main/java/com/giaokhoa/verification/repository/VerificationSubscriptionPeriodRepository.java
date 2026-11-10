package com.giaokhoa.verification.repository;

import com.giaokhoa.subscriptions.model.SubscriptionPeriod;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc kỳ gói trên entity của module subscriptions cho các luồng xác thực. */
public interface VerificationSubscriptionPeriodRepository
        extends JpaRepository<SubscriptionPeriod, UUID> {

    /** Các kỳ đang hiệu lực tại thời điểm gọi, kỳ bắt đầu muộn nhất trước. */
    @Query(
            "select p.id from SubscriptionPeriod p"
                    + " where p.workspaceId = :workspaceId"
                    + " and p.validFrom <= current_timestamp and p.expiresAt > current_timestamp"
                    + " order by p.validFrom desc, p.id asc")
    List<UUID> findCurrentPeriodIds(@Param("workspaceId") UUID workspaceId);

    /** Số kỳ đang hiệu lực tại thời điểm gọi. */
    @Query(
            "select count(p) from SubscriptionPeriod p"
                    + " where p.workspaceId = :workspaceId"
                    + " and p.validFrom <= current_timestamp and p.expiresAt > current_timestamp")
    long countCurrent(@Param("workspaceId") UUID workspaceId);
}
