package com.giaokhoa.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Biên nhận idempotency của một mutation khôi phục, khóa theo (actor, idempotency key). Hàng
 * mutation được ghi trước khi yêu cầu khôi phục tồn tại nên {@code request_id} chỉ là tham chiếu ID
 * thuần; ràng buộc khóa ngoại ở DB là deferred tới cuối transaction.
 */
@Entity
@Table(name = "recovery_mutation")
public class RecoveryMutation {
    public enum Kind {
        SUBMIT,
        RESOLVE
    }

    @Embeddable
    public static class Key implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        @Column(name = "actor_user_id", nullable = false)
        private UUID actorUserId;

        @Column(name = "idempotency_key", nullable = false, length = 255)
        private String idempotencyKey;

        protected Key() {}

        public Key(UUID actorUserId, String idempotencyKey) {
            this.actorUserId = actorUserId;
            this.idempotencyKey = idempotencyKey;
        }

        public UUID getActorUserId() {
            return actorUserId;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(actorUserId, key.actorUserId)
                    && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(actorUserId, idempotencyKey);
        }
    }

    @EmbeddedId private Key id;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RecoveryMutation() {}

    public RecoveryMutation(
            UUID actorUserId,
            String idempotencyKey,
            String payloadHash,
            UUID requestId,
            Kind kind) {
        this.id = new Key(actorUserId, idempotencyKey);
        this.payloadHash = payloadHash;
        this.requestId = requestId;
        this.kind = kind;
    }

    public Key getId() {
        return id;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Kind getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
