package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code subscription_period} cho các điểm đọc phía verification (kỳ
 * gói đang hiệu lực của workspace). Entity đầy đủ thuộc module tenancy; bản này chỉ mang các cột
 * phía verification cần và sẽ bị xoá, trỏ lại repository của tenancy khi đợt JPA land.
 */
@Entity(name = "VerificationSubscriptionPeriod")
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

    protected SubscriptionPeriod() {}

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
}
