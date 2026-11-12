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

/**
 * Liên kết phụ huynh (bảng {@code guardian_link}, V018): một parent với một học sinh trong đúng một
 * center. Cả bốn cột người dùng (parent, student, requested_by, approved_by) là định danh thuần,
 * không association — phía bên kia là dữ kiện directory, và vòng đời của link được khóa bằng CHECK
 * + trigger lịch sử ở DB: REQUESTED sang APPROVED/REJECTED, APPROVED sang REVOKED, trạng thái kết
 * thúc đóng băng toàn hàng.
 */
@Entity
@Table(name = "guardian_link")
public class GuardianLink {

    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String REVOKED = "REVOKED";

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

    // Tham chiếu tới xác minh NGOÀI hệ thống, không chứa ảnh/giấy tờ cá nhân và không ở DTO.
    @Column(name = "verification_ref", length = 160)
    private @Nullable String verificationRef;

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

    /** Link mới luôn ở REQUESTED, do chính parent gửi cho mình (requested_by = parent). */
    public GuardianLink(
            UUID id, UUID workspaceId, UUID parentUserId, UUID studentUserId, UUID requestedBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentUserId = parentUserId;
        this.studentUserId = studentUserId;
        this.state = REQUESTED;
        this.requestedBy = requestedBy;
    }

    /** Duyệt một request đang chờ: ghi người duyệt và thời điểm duyệt theo CHECK của bảng. */
    public void approve(UUID approverUserId, Instant approvedAt, String verificationRef) {
        this.state = APPROVED;
        this.approvedBy = approverUserId;
        this.approvedAt = approvedAt;
        this.verificationRef = verificationRef;
    }

    /** Từ chối một request đang chờ: trạng thái kết thúc, không ghi người quyết lên hàng. */
    public void reject() {
        this.state = REJECTED;
    }

    /** Gỡ một link đã duyệt: chuyển REVOKED kèm thời điểm gỡ, giữ nguyên lịch sử duyệt. */
    public void revoke(Instant revokedAt) {
        this.state = REVOKED;
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

    public @Nullable String getVerificationRef() {
        return verificationRef;
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
