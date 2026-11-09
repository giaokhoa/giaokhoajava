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
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "sample_result")
public class SampleResult {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    @Column(name = "course_subject_id", nullable = false)
    private UUID courseSubjectId;

    @Column(name = "score_stub")
    private @Nullable String scoreStub;

    @Column(name = "comment_stub")
    private @Nullable String commentStub;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "graded_by")
    private @Nullable UUID gradedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SampleResult() {}

    public SampleResult(
            UUID id,
            UUID workspaceId,
            UUID submissionId,
            UUID studentUserId,
            UUID courseSubjectId,
            @Nullable String scoreStub,
            @Nullable String commentStub,
            String state,
            @Nullable Instant publishedAt,
            @Nullable UUID gradedBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.submissionId = submissionId;
        this.studentUserId = studentUserId;
        this.courseSubjectId = courseSubjectId;
        this.scoreStub = scoreStub;
        this.commentStub = commentStub;
        this.state = state;
        this.publishedAt = publishedAt;
        this.gradedBy = gradedBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public UUID getStudentUserId() {
        return studentUserId;
    }

    public UUID getCourseSubjectId() {
        return courseSubjectId;
    }

    public @Nullable String getScoreStub() {
        return scoreStub;
    }

    public @Nullable String getCommentStub() {
        return commentStub;
    }

    public String getState() {
        return state;
    }

    public @Nullable Instant getPublishedAt() {
        return publishedAt;
    }

    public @Nullable UUID getGradedBy() {
        return gradedBy;
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
