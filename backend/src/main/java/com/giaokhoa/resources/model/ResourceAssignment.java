package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "resource_assignment")
public class ResourceAssignment {
    public enum State {
        PENDING,
        ACTIVE,
        REVOKED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "resource_id")
    private @Nullable UUID resourceId;

    @Column(name = "subject_id")
    private @Nullable UUID subjectId;

    // Tên scope cố định theo PermissionCatalog; DB khóa đúng danh mục bằng CHECK,
    // entity giữ nguyên dạng mảng như cột text[] (cùng mẫu intended_roles).
    // Cột mảng ánh xạ thành basic collection List ngay trên cột, cùng mẫu share_grant.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "actions", nullable = false, columnDefinition = "text[]")
    private List<String> actions;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

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

    protected ResourceAssignment() {}

    public ResourceAssignment(
            UUID id,
            UUID workspaceId,
            UUID userId,
            @Nullable UUID resourceId,
            @Nullable UUID subjectId,
            List<String> actions) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.resourceId = resourceId;
        this.subjectId = subjectId;
        this.actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        this.state = State.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public @Nullable UUID getResourceId() {
        return resourceId;
    }

    public @Nullable UUID getSubjectId() {
        return subjectId;
    }

    public List<String> getActions() {
        return List.copyOf(actions);
    }

    public State getState() {
        return state;
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
