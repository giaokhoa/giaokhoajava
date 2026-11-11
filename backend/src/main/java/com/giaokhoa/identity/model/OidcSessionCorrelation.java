package com.giaokhoa.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Tương quan bền giữa phiên ứng dụng và phiên OIDC phục vụ back-channel logout: dữ kiện của ứng
 * dụng nên được entity hoá, khác với bảng phiên {@code SPRING_SESSION*} do Spring Session JDBC quản
 * lý. Cặp (csrf_header_name, csrf_token) đồng null hoặc đồng có theo CHECK của bảng.
 */
@Entity
@Table(name = "oidc_session_correlation")
public class OidcSessionCorrelation {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "issuer", nullable = false, length = 2048)
    private String issuer;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "sub", nullable = false, length = 512)
    private String sub;

    @Column(name = "sid", length = 512)
    private @Nullable String sid;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "csrf_header_name", length = 128)
    private @Nullable String csrfHeaderName;

    @Column(name = "csrf_token", length = 1024)
    private @Nullable String csrfToken;

    protected OidcSessionCorrelation() {}

    public OidcSessionCorrelation(
            String sessionId,
            String issuer,
            String clientId,
            String sub,
            @Nullable String sid,
            Instant recordedAt,
            @Nullable String csrfHeaderName,
            @Nullable String csrfToken) {
        this.sessionId = sessionId;
        this.issuer = issuer;
        this.clientId = clientId;
        this.sub = sub;
        this.sid = sid;
        this.recordedAt = recordedAt;
        this.csrfHeaderName = csrfHeaderName;
        this.csrfToken = csrfToken;
    }

    /** Ghi lại tương quan mới nhất cho cùng một phiên, đúng ngữ nghĩa upsert theo session_id. */
    public void refresh(
            String issuer,
            String clientId,
            String sub,
            @Nullable String sid,
            Instant recordedAt,
            @Nullable String csrfHeaderName,
            @Nullable String csrfToken) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.sub = sub;
        this.sid = sid;
        this.recordedAt = recordedAt;
        this.csrfHeaderName = csrfHeaderName;
        this.csrfToken = csrfToken;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSub() {
        return sub;
    }

    public @Nullable String getSid() {
        return sid;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public @Nullable String getCsrfHeaderName() {
        return csrfHeaderName;
    }

    public @Nullable String getCsrfToken() {
        return csrfToken;
    }
}
