package com.giaokhoa.subscriptions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Một kỳ gói đã được platform operator xác nhận thủ công cho một workspace. Kỳ gói là lịch sử bất
 * biến: chỉ chèn mới, gia hạn là một kỳ khác; {@code confirmation_ref} là neo audit duy nhất của
 * kỳ.
 */
@Entity
@Table(name = "subscription_period")
public class SubscriptionPeriod {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmation_ref", nullable = false, unique = true, length = 255)
    private String confirmationRef;

    @Column(name = "confirmed_by", nullable = false)
    private UUID confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SubscriptionPeriod() {}

    public SubscriptionPeriod(
            UUID id,
            UUID workspaceId,
            Instant validFrom,
            Instant expiresAt,
            String confirmationRef,
            UUID confirmedBy,
            Instant confirmedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.validFrom = validFrom;
        this.expiresAt = expiresAt;
        this.confirmationRef = confirmationRef;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = confirmedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getConfirmationRef() {
        return confirmationRef;
    }

    public UUID getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
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
