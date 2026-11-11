package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "sample_submission")
public class SampleSubmission {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    // Quan hệ chỉ đọc: cột FK do trường activityId ở trên ghi; chiều này chỉ để điều hướng.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", insertable = false, updatable = false)
    private @Nullable SampleActivity activity;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "content_stub", nullable = false)
    private String contentStub;

    @Column(name = "state", nullable = false, length = 32)
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

    protected SampleSubmission() {}

    public SampleSubmission(
            UUID id,
            UUID workspaceId,
            UUID activityId,
            UUID studentUserId,
            int attemptNo,
            Instant submittedAt,
            String contentStub,
            String state) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.activityId = activityId;
        this.studentUserId = studentUserId;
        this.attemptNo = attemptNo;
        this.submittedAt = submittedAt;
        this.contentStub = contentStub;
        this.state = state;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public @Nullable SampleActivity getActivity() {
        return activity;
    }

    public UUID getStudentUserId() {
        return studentUserId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getContentStub() {
        return contentStub;
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
