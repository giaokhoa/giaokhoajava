package com.giaokhoa.subscriptions.repository;

import com.giaokhoa.subscriptions.model.SubscriptionPeriod;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubscriptionPeriodRepository extends JpaRepository<SubscriptionPeriod, UUID> {
    boolean existsByConfirmationRef(String confirmationRef);

    boolean existsByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndValidFromLessThanEqualAndExpiresAtGreaterThan(
            UUID workspaceId, Instant validFrom, Instant expiresAt);

    List<SubscriptionPeriod> findByWorkspaceIdOrderByValidFromAscIdAsc(UUID workspaceId);

    /** Hạn gói tối đa còn hiệu lực của từng workspace trong tập, cho danh sách workspace. */
    interface WorkspaceExpiry {
        UUID getWorkspaceId();

        Instant getExpiresAt();
    }

    @Query(
            "SELECT p.workspaceId AS workspaceId, MAX(p.expiresAt) AS expiresAt"
                    + " FROM SubscriptionPeriod p WHERE p.workspaceId IN :workspaceIds"
                    + " AND p.validFrom <= :now AND p.expiresAt > :now"
                    + " GROUP BY p.workspaceId")
    List<WorkspaceExpiry> findCurrentExpiries(Collection<UUID> workspaceIds, Instant now);
}
