package com.giaokhoa.identity.model;

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
@Table(name = "user_account")
public class UserAccount {
    public enum Origin {
        SELF_REGISTERED,
        CENTER_ISSUED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "keycloak_sub", nullable = false, length = 512)
    private String keycloakSub;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "email", length = 320)
    private @Nullable String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 32)
    private Origin origin;

    @Column(name = "identity_state", nullable = false, length = 32)
    private String identityState;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserAccount() {}

    public UserAccount(
            UUID id,
            String keycloakSub,
            String username,
            @Nullable String email,
            boolean emailVerified,
            Origin origin,
            String identityState) {
        this.id = id;
        this.keycloakSub = keycloakSub;
        this.username = username;
        this.email = email;
        this.emailVerified = emailVerified;
        this.origin = origin;
        this.identityState = identityState;
    }

    /**
     * Đồng bộ email và trạng thái verified từ profile Keycloak hiện hành. Origin, username và
     * identity_state không bao giờ bị đồng bộ đổi.
     */
    public void syncEmail(@Nullable String email, boolean emailVerified) {
        this.email = email;
        this.emailVerified = emailVerified;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakSub() {
        return keycloakSub;
    }

    public String getUsername() {
        return username;
    }

    public @Nullable String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getIdentityState() {
        return identityState;
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
