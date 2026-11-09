package com.giaokhoa.guardians.model;

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
@Table(name = "guardian_link")
public class GuardianLink {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "parent_user_id", nullable = false)
    private UUID parentUserId;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approved_by")
    private @Nullable UUID approvedBy;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;

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

    protected GuardianLink() {}

    public GuardianLink(
            UUID id,
            UUID workspaceId,
            UUID parentUserId,
            UUID studentUserId,
            String state,
            UUID requestedBy,
            @Nullable UUID approvedBy,
            @Nullable Instant approvedAt,
            @Nullable Instant revokedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentUserId = parentUserId;
        this.studentUserId = studentUserId;
        this.state = state;
        this.requestedBy = requestedBy;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.revokedAt = revokedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getParentUserId() {
        return parentUserId;
    }

    public UUID getStudentUserId() {
        return studentUserId;
    }

    public String getState() {
        return state;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public @Nullable UUID getApprovedBy() {
        return approvedBy;
    }

    public @Nullable Instant getApprovedAt() {
        return approvedAt;
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
