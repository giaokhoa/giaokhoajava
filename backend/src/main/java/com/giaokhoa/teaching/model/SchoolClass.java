package com.giaokhoa.teaching.model;

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
 * Một lớp thuộc đúng một workspace. Lớp không có cột {@code revoked_at}: thu hồi chỉ đổi {@code
 * state} sang REVOKED, khớp DDL V014 và hành vi của tầng JDBC cũ.
 */
@Entity
@Table(name = "school_class")
public class SchoolClass {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

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

    protected SchoolClass() {}

    public SchoolClass(UUID id, UUID workspaceId, String name) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.state = "ACTIVE";
    }

    public void rename(String name) {
        this.name = name;
        // Chạm updated_at để Hibernate phát UPDATE và tăng @Version kể cả khi tên mới trùng tên
        // cũ: tầng JDBC cũ tăng version vô điều kiện ở mọi lần đổi tên thành công.
        this.updatedAt = Instant.now();
    }

    public void revoke() {
        this.state = "REVOKED";
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getName() {
        return name;
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
