package com.giaokhoa.resources.model;

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

@Entity
@Table(name = "resource_dependency")
public class ResourceDependency {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "derived_resource_id", nullable = false)
    private UUID derivedResourceId;

    @Column(name = "derived_version_id", nullable = false)
    private UUID derivedVersionId;

    @Column(name = "source_resource_id", nullable = false)
    private UUID sourceResourceId;

    @Column(name = "source_version_id", nullable = false)
    private UUID sourceVersionId;

    @Column(name = "share_grant_id")
    private @Nullable UUID shareGrantId;

    @Column(name = "usage_constraints", nullable = false)
    private String usageConstraints;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ResourceDependency() {}

    public ResourceDependency(
            UUID id,
            UUID derivedResourceId,
            UUID derivedVersionId,
            UUID sourceResourceId,
            UUID sourceVersionId,
            @Nullable UUID shareGrantId,
            String usageConstraints) {
        this.id = id;
        this.derivedResourceId = derivedResourceId;
        this.derivedVersionId = derivedVersionId;
        this.sourceResourceId = sourceResourceId;
        this.sourceVersionId = sourceVersionId;
        this.shareGrantId = shareGrantId;
        this.usageConstraints = usageConstraints;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDerivedResourceId() {
        return derivedResourceId;
    }

    public UUID getDerivedVersionId() {
        return derivedVersionId;
    }

    public UUID getSourceResourceId() {
        return sourceResourceId;
    }

    public UUID getSourceVersionId() {
        return sourceVersionId;
    }

    public @Nullable UUID getShareGrantId() {
        return shareGrantId;
    }

    public String getUsageConstraints() {
        return usageConstraints;
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
