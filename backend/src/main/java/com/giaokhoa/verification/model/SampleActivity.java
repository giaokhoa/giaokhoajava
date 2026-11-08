package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "sample_activity")
public class SampleActivity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "course_subject_id", nullable = false)
    private UUID courseSubjectId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "attempt_limit", nullable = false)
    private int attemptLimit;

    @Column(name = "subscription_period_id", nullable = false)
    private UUID subscriptionPeriodId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SampleActivity() {}

    public SampleActivity(
            UUID id,
            UUID workspaceId,
            UUID courseSubjectId,
            UUID resourceId,
            UUID versionId,
            UUID assignedBy,
            Instant assignedAt,
            Instant dueAt,
            int attemptLimit,
            UUID subscriptionPeriodId,
            String state) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.courseSubjectId = courseSubjectId;
        this.resourceId = resourceId;
        this.versionId = versionId;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
        this.dueAt = dueAt;
        this.attemptLimit = attemptLimit;
        this.subscriptionPeriodId = subscriptionPeriodId;
        this.state = state;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getCourseSubjectId() {
        return courseSubjectId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public int getAttemptLimit() {
        return attemptLimit;
    }

    public UUID getSubscriptionPeriodId() {
        return subscriptionPeriodId;
    }

    public String getState() {
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
