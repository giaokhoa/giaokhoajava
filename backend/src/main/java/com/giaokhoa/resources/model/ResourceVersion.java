package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "resource_version")
public class ResourceVersion {
    public enum ReviewState {
        DRAFT,
        APPROVED,
        LOCKED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    // Association duy nhất của module resource: version thuộc về resource của nó. Cột thuần ở
    // trên vẫn là trường ghi; association chỉ để đọc/nạp lười (LAZY) và không cascade.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", insertable = false, updatable = false)
    private Resource resource;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "body_stub", nullable = false)
    private String bodyStub;

    @Column(name = "answer_stub")
    private String answerStub;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_state", nullable = false, length = 16)
    private ReviewState reviewState;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ResourceVersion() {}

    public ResourceVersion(
            UUID id,
            UUID resourceId,
            int versionNo,
            String label,
            String bodyStub,
            String answerStub,
            ReviewState reviewState,
            Instant lockedAt) {
        this.id = id;
        this.resourceId = resourceId;
        this.versionNo = versionNo;
        this.label = label;
        this.bodyStub = bodyStub;
        this.answerStub = answerStub;
        this.reviewState = reviewState;
        this.lockedAt = lockedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Resource getResource() {
        return resource;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getLabel() {
        return label;
    }

    public String getBodyStub() {
        return bodyStub;
    }

    public String getAnswerStub() {
        return answerStub;
    }

    public ReviewState getReviewState() {
        return reviewState;
    }

    public Instant getLockedAt() {
        return lockedAt;
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
