package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "notification_receipt")
public class NotificationReceipt {

    @EmbeddedId private Key id;

    @Column(name = "acknowledged_at")
    private @Nullable Instant acknowledgedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationReceipt() {}

    public NotificationReceipt(UUID noticeId, UUID recipientWorkspaceId) {
        this.id = new Key(noticeId, recipientWorkspaceId);
    }

    public Key getId() {
        return id;
    }

    public UUID getNoticeId() {
        return id.getNoticeId();
    }

    public UUID getRecipientWorkspaceId() {
        return id.getRecipientWorkspaceId();
    }

    public @Nullable Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    /**
     * Ghi mốc bên nhận đã nhận cập nhật: guard của schema chỉ cho ghi một lần, nên lần ghi sau khi
     * đã có mốc là không-op thay vì ghi đè.
     */
    public void acknowledge(Instant acknowledgedAt) {
        if (this.acknowledgedAt == null) {
            this.acknowledgedAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Khóa ghép của receipt: đúng cặp (notice, nơi nhận) là định danh duy nhất của một receipt. */
    @Embeddable
    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "notice_id", nullable = false)
        private UUID noticeId;

        @Column(name = "recipient_workspace_id", nullable = false)
        private UUID recipientWorkspaceId;

        public Key() {}

        public Key(UUID noticeId, UUID recipientWorkspaceId) {
            this.noticeId = noticeId;
            this.recipientWorkspaceId = recipientWorkspaceId;
        }

        public UUID getNoticeId() {
            return noticeId;
        }

        public UUID getRecipientWorkspaceId() {
            return recipientWorkspaceId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(noticeId, key.noticeId)
                    && Objects.equals(recipientWorkspaceId, key.recipientWorkspaceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(noticeId, recipientWorkspaceId);
        }
    }
}
