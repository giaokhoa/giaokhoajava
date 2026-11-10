package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "notification_receipt")
@IdClass(NotificationReceipt.Key.class)
public class NotificationReceipt {
    @Id
    @Column(name = "notice_id", nullable = false)
    private UUID noticeId;

    @Id
    @Column(name = "recipient_workspace_id", nullable = false)
    private UUID recipientWorkspaceId;

    @Column(name = "acknowledged_at")
    private @Nullable Instant acknowledgedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationReceipt() {}

    public NotificationReceipt(UUID noticeId, UUID recipientWorkspaceId) {
        this.noticeId = noticeId;
        this.recipientWorkspaceId = recipientWorkspaceId;
    }

    public UUID getNoticeId() {
        return noticeId;
    }

    public UUID getRecipientWorkspaceId() {
        return recipientWorkspaceId;
    }

    public @Nullable Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private UUID noticeId;
        private UUID recipientWorkspaceId;

        protected Key() {}

        public Key(UUID noticeId, UUID recipientWorkspaceId) {
            this.noticeId = noticeId;
            this.recipientWorkspaceId = recipientWorkspaceId;
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
