package com.giaokhoa.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "recovery_request")
public class RecoveryRequest {
    public enum State {
        REQUESTED,
        VERIFIED,
        RESOLVED,
        REJECTED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(name = "workspace_id")
    private @Nullable UUID workspaceId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "reason", length = 1000)
    private @Nullable String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "verified_by")
    private @Nullable UUID verifiedBy;

    @Column(name = "resolved_at")
    private @Nullable Instant resolvedAt;

    @Column(name = "verification_note", length = 1000)
    private @Nullable String verificationNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RecoveryRequest() {}

    public RecoveryRequest(
            UUID id,
            UUID requesterUserId,
            @Nullable UUID workspaceId,
            UUID targetUserId,
            @Nullable String reason) {
        this.id = id;
        this.requesterUserId = requesterUserId;
        this.workspaceId = workspaceId;
        this.targetUserId = targetUserId;
        this.reason = reason;
        this.state = State.REQUESTED;
    }

    /** Ghi nhận xác minh của operator: chỉ hợp lệ từ REQUESTED, điều kiện do tầng service ép. */
    public void verify(UUID verifiedBy, @Nullable String verificationNote) {
        this.state = State.VERIFIED;
        this.verifiedBy = verifiedBy;
        this.verificationNote = verificationNote;
    }

    /**
     * Kết thúc vòng đời yêu cầu ở RESOLVED hoặc REJECTED. Ghi chú truyền vào null thì giữ ghi chú
     * xác minh đã ghi, đúng ngữ nghĩa COALESCE của đường ghi trước đây.
     */
    public void resolve(State outcome, Instant resolvedAt, @Nullable String verificationNote) {
        this.state = outcome;
        this.resolvedAt = resolvedAt;
        if (verificationNote != null) {
            this.verificationNote = verificationNote;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequesterUserId() {
        return requesterUserId;
    }

    public @Nullable UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public State getState() {
        return state;
    }

    public @Nullable UUID getVerifiedBy() {
        return verifiedBy;
    }

    public @Nullable Instant getResolvedAt() {
        return resolvedAt;
    }

    public @Nullable String getVerificationNote() {
        return verificationNote;
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
