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

/**
 * Một khóa thuộc đúng một lớp trong cùng workspace; FK tổng hợp (workspace_id, class_id) của DDL
 * V016 được mô hình hoá bằng association chỉ đọc tới {@link SchoolClass}, cột cơ bản là phía ghi.
 */
@Entity
@Table(name = "course")
public class Course {

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

    protected Course() {}

    public Course(UUID id, UUID workspaceId, UUID classId, String name) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.classId = classId;
        this.name = name;
        this.state = "ACTIVE";
    }

    public void rename(String name) {
        this.name = name;
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

    public UUID getClassId() {
        return classId;
    }

    public SchoolClass getSchoolClass() {
        return schoolClass;
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
