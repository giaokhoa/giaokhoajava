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
 * Một ghi danh gắn một dòng roster vào một môn–khóa. Bất biến "hai phía cùng lớp" do FK tổng hợp ở
 * DB và kiểm tra tường minh ở service giữ; hai association ở đây không tự chứng minh được bất biến
 * đó.
 */
@Entity
@Table(name = "enrollment")
public class Enrollment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "class_student_id", nullable = false)
    private UUID classStudentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(
                name = "workspace_id",
                referencedColumnName = "workspace_id",
                insertable = false,
                updatable = false),
        @JoinColumn(
                name = "class_student_id",
                referencedColumnName = "id",
                insertable = false,
                updatable = false)
    })
    private ClassStudent classStudent;

    @Column(name = "course_subject_id", nullable = false)
    private UUID courseSubjectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(
                name = "workspace_id",
                referencedColumnName = "workspace_id",
                insertable = false,
                updatable = false),
        @JoinColumn(
                name = "course_subject_id",
                referencedColumnName = "id",
                insertable = false,
                updatable = false)
    })
    private CourseSubject courseSubject;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Enrollment() {}

    public Enrollment(UUID id, UUID workspaceId, UUID classStudentId, UUID courseSubjectId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.classStudentId = classStudentId;
        this.courseSubjectId = courseSubjectId;
        this.state = "ACTIVE";
    }

    /** Đóng quyền học tập của đúng môn–khóa nhưng giữ nguồn đọc published history. */
    public void complete(Instant at) {
        if (!"ACTIVE".equals(state)) {
            throw new IllegalStateException("Only ACTIVE enrollment can be completed");
        }
        this.state = "COMPLETED";
        this.completedAt = at;
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

    public UUID getClassStudentId() {
        return classStudentId;
    }

    public ClassStudent getClassStudent() {
        return classStudent;
    }

    public UUID getCourseSubjectId() {
        return courseSubjectId;
    }

    public CourseSubject getCourseSubject() {
        return courseSubject;
    }

    public String getState() {
        return state;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
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
