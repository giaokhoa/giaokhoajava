package com.giaokhoa.identity.repository;

import com.giaokhoa.identity.model.RecoveryMutation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecoveryMutationRepository
        extends JpaRepository<RecoveryMutation, RecoveryMutation.Key> {
    @Query(
            "SELECT m FROM RecoveryMutation m"
                    + " WHERE m.id.actorUserId = :actorUserId"
                    + " AND m.id.idempotencyKey = :idempotencyKey")
    Optional<RecoveryMutation> findByActorUserIdAndIdempotencyKey(
            UUID actorUserId, String idempotencyKey);
}
