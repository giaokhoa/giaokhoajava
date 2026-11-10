package com.giaokhoa.tenancy.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Quan hệ thành viên của một account trong một CENTER workspace. Role nằm ở bảng {@code
 * membership_role} sẵn có và được ánh xạ là element collection của chính membership: vòng đời của
 * role gắn chặt với membership sở hữu nó, không có định danh độc lập. {@code joined_at} là lịch sử
 * tham gia do trigger {@code guard_directory_history} bảo vệ nên cột không được cập nhật qua
 * entity; hàng đã REVOKED cũng không bao giờ bị sửa tiếp.
 */
@Entity
@Table(name = "center_membership")
public class CenterMembership {
    public enum State {
        PENDING,
        ACTIVE,
        REVOKED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_role", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Set<MembershipRole.Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CenterMembership() {}

    public CenterMembership(UUID id, UUID workspaceId, UUID userId, State state, Instant joinedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.state = state;
        this.joinedAt = joinedAt;
    }

    /** Kích hoạt membership đang chờ; giữ nguyên joined_at theo bất biến lịch sử tham gia. */
    public void activate() {
        this.state = State.ACTIVE;
    }

    /** Thu hồi membership: ghi mốc thu hồi cùng trạng thái, không đụng joined_at. */
    public void revoke(Instant revokedAt) {
        this.state = State.REVOKED;
        this.revokedAt = revokedAt;
    }

    /** Thay toàn bộ tập role; version của membership được tầng service tăng riêng. */
    public void replaceRoles(Set<MembershipRole.Role> desiredRoles) {
        roles.clear();
        roles.addAll(desiredRoles);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public State getState() {
        return state;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    public Set<MembershipRole.Role> getRoles() {
        return roles;
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
