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

/**
 * Một lượt nộp bổ sung đã mở cho một học sinh của một hoạt động mẫu: snapshot {@code attempt_limit}
 * của hoạt động là bất biến nên lượt mở thêm là dữ kiện lịch sử riêng, được tính vào trần lượt nộp
 * ở cả tầng service lẫn trigger của bảng bài nộp. Lịch sử đã mở không sửa được và không xoá được —
 * bất biến đó do trigger của bảng khóa ở tầng dữ liệu.
 */
@Entity
@Table(name = "sample_attempt_opening")
public class SampleAttemptOpening {

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

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SampleAttemptOpening() {}

    public SampleAttemptOpening(
            UUID id,
            UUID workspaceId,
            UUID activityId,
            UUID studentUserId,
            UUID openedBy,
            Instant openedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.activityId = activityId;
        this.studentUserId = studentUserId;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
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

    public UUID getStudentUserId() {
        return studentUserId;
    }

    public UUID getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
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
