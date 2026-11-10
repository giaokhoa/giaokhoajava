package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "correction_notice")
public class CorrectionNotice {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "affected_version_id", nullable = false)
    private UUID affectedVersionId;

    @Column(name = "replacement_version_id", nullable = false)
    private UUID replacementVersionId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CorrectionNotice() {}

    public CorrectionNotice(
            UUID id,
            UUID resourceId,
            UUID affectedVersionId,
            UUID replacementVersionId,
            String reason) {
        this.id = id;
        this.resourceId = resourceId;
        this.affectedVersionId = affectedVersionId;
        this.replacementVersionId = replacementVersionId;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getAffectedVersionId() {
        return affectedVersionId;
    }

    public UUID getReplacementVersionId() {
        return replacementVersionId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
