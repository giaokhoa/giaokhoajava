package com.giaokhoa.identity.repository;

import com.giaokhoa.identity.model.RecoveryMutation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bản cài native của {@link RecoveryMutationClaim}; là bean để inject JdbcTemplate chuẩn. */
@Component
public class RecoveryMutationClaimImpl implements RecoveryMutationClaim {
    private final JdbcTemplate jdbc;

    public RecoveryMutationClaimImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertIfAbsent(RecoveryMutation mutation) {
        return jdbc.update(
                "INSERT INTO recovery_mutation(actor_user_id,idempotency_key,payload_hash,request_id,kind)"
                        + " VALUES (?,?,?,?,?) ON CONFLICT (actor_user_id,idempotency_key) DO NOTHING",
                mutation.getId().getActorUserId(),
                mutation.getId().getIdempotencyKey(),
                mutation.getPayloadHash(),
                mutation.getRequestId(),
                mutation.getKind().name());
    }
}
