package com.giaokhoa.resources.model;

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
@Table(name = "share_grant")
public class ShareGrant {
    public enum Mode {
        REVOCABLE,
        LONG_TERM
    }

    public enum State {
        OFFERED,
        ACCEPTED,
        REVOKED,
        EXPIRED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source_resource_id", nullable = false)
    private UUID sourceResourceId;

    @Column(name = "source_version_id", nullable = false)
    private UUID sourceVersionId;

    @Column(name = "grantor_user_id", nullable = false)
    private UUID grantorUserId;

    @Column(name = "recipient_workspace_id", nullable = false)
    private UUID recipientWorkspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    // Tên scope cố định theo PermissionCatalog; DB khóa đúng danh mục bằng CHECK,
    // entity giữ nguyên dạng mảng như cột text[] (cùng mẫu resource_assignment).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "actions", nullable = false, columnDefinition = "text[]")
    private String[] actions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_workspace_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] allowedWorkspaceIds;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "expires_at")
    private @Nullable Instant expiresAt;

    @Column(name = "accepted_at")
    private @Nullable Instant acceptedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ShareGrant() {}

    public ShareGrant(
            UUID id,
            UUID sourceResourceId,
            UUID sourceVersionId,
            UUID grantorUserId,
            UUID recipientWorkspaceId,
            Mode mode,
            String[] actions,
            UUID[] allowedWorkspaceIds,
            Instant validFrom,
            @Nullable Instant expiresAt) {
        this.id = id;
        this.sourceResourceId = sourceResourceId;
        this.sourceVersionId = sourceVersionId;
        this.grantorUserId = grantorUserId;
        this.recipientWorkspaceId = recipientWorkspaceId;
        this.mode = mode;
        this.actions = Objects.requireNonNull(actions, "actions").clone();
        this.allowedWorkspaceIds =
                Objects.requireNonNull(allowedWorkspaceIds, "allowedWorkspaceIds").clone();
        this.validFrom = validFrom;
        this.expiresAt = expiresAt;
        this.state = State.OFFERED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceResourceId() {
        return sourceResourceId;
    }

    public UUID getSourceVersionId() {
        return sourceVersionId;
    }

    public UUID getGrantorUserId() {
        return grantorUserId;
    }

    public UUID getRecipientWorkspaceId() {
        return recipientWorkspaceId;
    }

    public Mode getMode() {
        return mode;
    }

    public State getState() {
        return state;
    }

    public String[] getActions() {
        return actions.clone();
    }

    public UUID[] getAllowedWorkspaceIds() {
        return allowedWorkspaceIds.clone();
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable Instant getAcceptedAt() {
        return acceptedAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
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
