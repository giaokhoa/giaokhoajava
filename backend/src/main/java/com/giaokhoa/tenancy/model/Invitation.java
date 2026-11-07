package com.giaokhoa.tenancy.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "invitation")
public class Invitation {
    public enum State {
        PENDING,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "target_email", length = 320)
    private @Nullable String targetEmail;

    @Column(name = "target_user_id")
    private @Nullable UUID targetUserId;

    // Chỉ lưu SHA-256 hex của token thô (64 ký tự); token thô không bao giờ được lưu
    // hay ghi log — endpoint chấp nhận băm token trình ra rồi tra theo hash này.
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "intended_roles", nullable = false, columnDefinition = "text[]")
    private String[] intendedRoles;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Invitation() {}

    public Invitation(
            UUID id,
            UUID workspaceId,
            @Nullable String targetEmail,
            @Nullable UUID targetUserId,
            String tokenHash,
            String[] intendedRoles,
            Instant expiresAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.targetEmail = targetEmail;
        this.targetUserId = targetUserId;
        this.tokenHash = tokenHash;
        this.intendedRoles = Objects.requireNonNull(intendedRoles, "intendedRoles").clone();
        this.expiresAt = expiresAt;
        this.state = State.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public @Nullable String getTargetEmail() {
        return targetEmail;
    }

    public @Nullable UUID getTargetUserId() {
        return targetUserId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String[] getIntendedRoles() {
        return intendedRoles.clone();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public State getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
