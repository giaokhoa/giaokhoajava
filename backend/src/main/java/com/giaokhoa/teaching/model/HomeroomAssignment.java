package com.giaokhoa.teaching.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Một phân công chủ nhiệm gắn một giáo viên vào một lớp. {@code teacherUserId} giữ ID thuần vì tài
 * khoản thuộc mặt phẳng hệ thống.
 */
@Entity
@Table(name = "homeroom_assignment")
public class HomeroomAssignment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(
                name = "workspace_id",
                referencedColumnName = "workspace_id",
                insertable = false,
                updatable = false),
        @JoinColumn(
                name = "class_id",
                referencedColumnName = "id",
                insertable = false,
                updatable = false)
    })
    private SchoolClass schoolClass;

    @Column(name = "teacher_user_id", nullable = false)
    private UUID teacherUserId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

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

    protected HomeroomAssignment() {}

    public HomeroomAssignment(UUID id, UUID workspaceId, UUID classId, UUID teacherUserId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.classId = classId;
        this.teacherUserId = teacherUserId;
        this.state = "ACTIVE";
    }

    public void revoke() {
        this.state = "REVOKED";
        this.revokedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getClassId() {
        return classId;
    }

    public SchoolClass getSchoolClass() {
        return schoolClass;
    }

    public UUID getTeacherUserId() {
        return teacherUserId;
    }

    public String getState() {
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
