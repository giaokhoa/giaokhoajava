package com.giaokhoa.verification.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;

/** Cài đặt fragment claim: EntityManager native theo mẫu fragment của Spring Data JPA. */
public class SampleActionClaimImpl implements SampleActionClaim {

    // Container gán EntityManager qua @PersistenceContext trước mọi lời gọi nghiệp vụ;
    // NullAway không thấy đường inject này nên suppression chỉ đặt tại field này.
    @SuppressWarnings("NullAway")
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public int claim(
            UUID actorUserId,
            String idempotencyKey,
            String payloadHash,
            UUID workspaceId,
            String targetType,
            UUID targetId) {
        return entityManager
                .createNativeQuery(
                        "INSERT INTO sample_action_mutation(actor_user_id, idempotency_key,"
                                + " payload_hash, workspace_id, target_type, target_id)"
                                + " VALUES (?1, ?2, ?3, ?4, ?5, ?6)"
                                + " ON CONFLICT (actor_user_id, idempotency_key) DO NOTHING")
                .setParameter(1, actorUserId)
                .setParameter(2, idempotencyKey)
                .setParameter(3, payloadHash)
                .setParameter(4, workspaceId)
                .setParameter(5, targetType)
                .setParameter(6, targetId)
                .executeUpdate();
    }
}
