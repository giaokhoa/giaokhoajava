package com.giaokhoa.verification.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Biên nhận idempotency của chính sample action (bảng {@code sample_action_mutation}, V022): khóa
 * chính là cặp (actor, idempotency key) — cùng key và cùng payload thì trả lại kết quả đầu tiên,
 * khác payload thì 409. Hàng chỉ được chèn qua fragment claim của repository (insert-or-ignore
 * native), entity này phục vụ đường đọc lại biên nhận khi replay.
 */
@Entity
@Table(name = "sample_action_mutation")
public class SampleActionMutation {

    /** Khóa chính ghép của biên nhận: actor sở hữu key, không có id surrogate. */
    @Embeddable
    public record Id(UUID actorUserId, String idempotencyKey) implements Serializable {}

    // Tên cột ghi tường minh: record Id không mang @Column trên từng thành phần như các key
    // dạng class (RecoveryMutation.Key), và Hibernate resolve tên cột của @EmbeddedId dạng
    // record theo đúng tên thành phần (actorUserId) thay vì dạng snake_case của V022.
    @EmbeddedId
    @AttributeOverrides({
        @AttributeOverride(name = "actorUserId", column = @Column(name = "actor_user_id")),
        @AttributeOverride(
                name = "idempotencyKey",
                column = @Column(name = "idempotency_key", length = 255))
    })
    private Id id;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SampleActionMutation() {}

    public SampleActionMutation(
            Id id, String payloadHash, UUID workspaceId, String targetType, UUID targetId) {
        this.id = id;
        this.payloadHash = payloadHash;
        this.workspaceId = workspaceId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public Id getId() {
        return id;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
