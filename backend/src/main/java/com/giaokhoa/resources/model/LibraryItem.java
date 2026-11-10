package com.giaokhoa.resources.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "library_item")
public class LibraryItem {
    public enum Custody {
        OWNED,
        LICENSED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "share_grant_id")
    private @Nullable UUID shareGrantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "custody", nullable = false, length = 16)
    private Custody custody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LibraryItem() {}

    public LibraryItem(
            UUID id,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            @Nullable UUID shareGrantId,
            Custody custody) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.resourceId = resourceId;
        this.versionId = versionId;
        this.shareGrantId = shareGrantId;
        this.custody = custody;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public @Nullable UUID getShareGrantId() {
        return shareGrantId;
    }

    public Custody getCustody() {
        return custody;
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
