package com.giaokhoa.authorization.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "authz_projection")
public class AuthorizationProjection {
    public enum State {
        READY,
        PENDING,
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "partition_id", nullable = false)
    private UUID partitionId;

    @Column(name = "target_key", nullable = false, length = 1024)
    private String targetKey;

    @Column(name = "keycloak_resource_id", length = 512)
    private @Nullable String keycloakResourceId;

    // Cột mảng PostgreSQL ánh xạ basic collection theo Hibernate User Guide §3.2.43:
    // giữ nguyên DDL text[]/uuid[], không đổi sang bảng phần tử.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "policy_names", nullable = false, columnDefinition = "text[]")
    private List<String> policyNames;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "dependency_partition_ids", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> dependencyPartitionIds;

    @Column(name = "desired_hash", nullable = false, length = 64)
    private String desiredHash;

    @Column(name = "applied_hash", length = 64)
    private @Nullable String appliedHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AuthorizationProjection() {}

    public AuthorizationProjection(
            UUID id,
            UUID partitionId,
            String targetKey,
            @Nullable String keycloakResourceId,
            List<String> policyNames,
            List<UUID> dependencyPartitionIds,
            String desiredHash,
            @Nullable String appliedHash,
            State state) {
        this.id = id;
        this.partitionId = partitionId;
        this.targetKey = targetKey;
        this.keycloakResourceId = keycloakResourceId;
        this.policyNames = List.copyOf(Objects.requireNonNull(policyNames, "policyNames"));
        this.dependencyPartitionIds =
                List.copyOf(
                        Objects.requireNonNull(dependencyPartitionIds, "dependencyPartitionIds"));
        this.desiredHash = desiredHash;
        this.appliedHash = appliedHash;
        this.state = state;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPartitionId() {
        return partitionId;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public @Nullable String getKeycloakResourceId() {
        return keycloakResourceId;
    }

    public void setKeycloakResourceId(@Nullable String keycloakResourceId) {
        this.keycloakResourceId = keycloakResourceId;
    }

    public List<String> getPolicyNames() {
        return List.copyOf(policyNames);
    }

    public void setPolicyNames(List<String> policyNames) {
        this.policyNames = List.copyOf(Objects.requireNonNull(policyNames, "policyNames"));
    }

    public List<UUID> getDependencyPartitionIds() {
        return List.copyOf(dependencyPartitionIds);
    }

    public void setDependencyPartitionIds(List<UUID> dependencyPartitionIds) {
        this.dependencyPartitionIds =
                List.copyOf(
                        Objects.requireNonNull(dependencyPartitionIds, "dependencyPartitionIds"));
    }

    public String getDesiredHash() {
        return desiredHash;
    }

    public void setDesiredHash(String desiredHash) {
        this.desiredHash = desiredHash;
    }

    public @Nullable String getAppliedHash() {
        return appliedHash;
    }

    public void setAppliedHash(@Nullable String appliedHash) {
        this.appliedHash = appliedHash;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
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
