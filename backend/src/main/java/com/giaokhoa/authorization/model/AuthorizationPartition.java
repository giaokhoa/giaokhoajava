package com.giaokhoa.authorization.model;

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

@Entity
@Table(name = "authz_partition")
public class AuthorizationPartition {
    public enum Kind {
        WORKSPACE,
        GRANT,
        ACCOUNT,
        PLATFORM
    }

    public enum State {
        READY,
        PENDING,
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "owner_key", nullable = false, length = 255)
    private String ownerKey;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "applied_revision", nullable = false)
    private long appliedRevision;

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

    protected AuthorizationPartition() {}

    public AuthorizationPartition(
            UUID id,
            Kind kind,
            String ownerKey,
            long desiredRevision,
            long appliedRevision,
            State state) {
        this.id = id;
        this.kind = kind;
        this.ownerKey = ownerKey;
        this.desiredRevision = desiredRevision;
        this.appliedRevision = appliedRevision;
        this.state = state;
    }

    public UUID getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public long getDesiredRevision() {
        return desiredRevision;
    }

    public void setDesiredRevision(long desiredRevision) {
        this.desiredRevision = desiredRevision;
    }

    public long getAppliedRevision() {
        return appliedRevision;
    }

    public void setAppliedRevision(long appliedRevision) {
        this.appliedRevision = appliedRevision;
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
