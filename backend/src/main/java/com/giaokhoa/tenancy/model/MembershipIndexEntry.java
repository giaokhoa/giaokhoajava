package com.giaokhoa.tenancy.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Chỉ mục membership tối thiểu ở schema hệ thống: với mỗi (user, workspace) chỉ giữ trạng thái của
 * membership hiện hành (hoặc membership gần nhất khi đã thu hồi), để các đường đọc xuyên workspace
 * như đăng nhập và phân giải thư mục không phải quét mọi schema tenant. Đây là bản sao suy ra từ
 * {@code center_membership} của schema tenant — nguồn sự thật vẫn là membership ở tenant, chỉ mục
 * được các service ghi membership đồng bộ trong cùng transaction.
 */
@Entity
@Table(name = "membership_index")
public class MembershipIndexEntry {
    @Embeddable
    public static class Key implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "workspace_id", nullable = false)
        private UUID workspaceId;

        protected Key() {}

        public Key(UUID userId, UUID workspaceId) {
            this.userId = userId;
            this.workspaceId = workspaceId;
        }

        public UUID getUserId() {
            return userId;
        }

        public UUID getWorkspaceId() {
            return workspaceId;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(userId, key.userId)
                    && Objects.equals(workspaceId, key.workspaceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, workspaceId);
        }
    }

    @EmbeddedId private Key id;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private CenterMembership.State state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipIndexEntry() {}

    public MembershipIndexEntry(UUID userId, UUID workspaceId, CenterMembership.State state) {
        this.id = new Key(userId, workspaceId);
        this.state = state;
    }

    /** Đồng bộ trạng thái theo membership nguồn vừa đổi. */
    public void syncState(CenterMembership.State state) {
        this.state = state;
    }

    public Key getId() {
        return id;
    }

    public CenterMembership.State getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
