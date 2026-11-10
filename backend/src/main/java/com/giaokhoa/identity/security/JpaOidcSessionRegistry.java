package com.giaokhoa.identity.security;

import com.giaokhoa.identity.model.OidcSessionCorrelation;
import com.giaokhoa.identity.repository.OidcSessionCorrelationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.util.Assert;

/**
 * Tương quan bền vững phục vụ OIDC back-channel logout native của Spring Security. Correlation là
 * dữ kiện của ứng dụng nên nằm ở entity/repository JPA; riêng việc phiên ứng dụng còn sống hay
 * không được hỏi qua {@link SessionRepository} của Spring Session chứ không đọc thẳng bảng phiên
 * của framework.
 */
public final class JpaOidcSessionRegistry implements OidcSessionRegistry {

    private final OidcSessionCorrelationRepository correlations;
    private final SessionRepository<? extends Session> sessions;
    private final String clientId;

    public JpaOidcSessionRegistry(
            OidcSessionCorrelationRepository correlations,
            SessionRepository<? extends Session> sessions,
            String clientId) {
        Assert.notNull(correlations, "correlations is required");
        Assert.notNull(sessions, "sessions is required");
        Assert.hasText(clientId, "clientId is required");
        this.correlations = correlations;
        this.sessions = sessions;
        this.clientId = clientId;
    }

    @Override
    public void saveSessionInformation(OidcSessionInformation information) {
        Assert.notNull(information, "information is required");
        var principal = information.getPrincipal();
        var issuer = principal.getIssuer();
        if (issuer == null) {
            throw new IllegalArgumentException("OIDC issuer is required");
        }
        Assert.hasText(principal.getSubject(), "OIDC subject is required");
        var audience = principal.getAudience();
        Assert.isTrue(
                audience != null && audience.contains(clientId),
                "OIDC audience must contain configured clientId");

        Map<String, String> authorities = information.getAuthorities();
        Assert.isTrue(authorities.size() <= 1, "Only the Spring CSRF header is supported");
        var csrf = authorities.entrySet().stream().findFirst().orElse(null);

        String sessionId = information.getSessionId();
        String csrfHeader = csrf == null ? null : csrf.getKey();
        String csrfToken = csrf == null ? null : csrf.getValue();
        correlations
                .findById(sessionId)
                .ifPresentOrElse(
                        correlation -> {
                            correlation.refresh(
                                    issuer.toString(),
                                    clientId,
                                    principal.getSubject(),
                                    principal.getClaimAsString("sid"),
                                    Instant.now(),
                                    csrfHeader,
                                    csrfToken);
                            correlations.save(correlation);
                        },
                        () ->
                                correlations.save(
                                        new OidcSessionCorrelation(
                                                sessionId,
                                                issuer.toString(),
                                                clientId,
                                                principal.getSubject(),
                                                principal.getClaimAsString("sid"),
                                                Instant.now(),
                                                csrfHeader,
                                                csrfToken)));
    }

    @Override
    public @Nullable OidcSessionInformation removeSessionInformation(String sessionId) {
        var found = correlations.findById(sessionId);
        if (found.isEmpty()) {
            return null;
        }
        OidcSessionCorrelation correlation = found.get();
        correlations.delete(correlation);
        return sessionInformation(correlation);
    }

    // Claim sid và sub có thể thiếu dù ECJ suy luận chúng luôn non-null.
    @Override
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken logoutToken) {
        Assert.notNull(logoutToken, "logoutToken is required");
        // Đòi hỏi CẢ issuer lẫn audience đã cấu hình; không bao giờ khớp một OIDC client khác.
        if (logoutToken.getIssuer() == null
                || logoutToken.getAudience() == null
                || !logoutToken.getAudience().contains(clientId)) {
            return List.of();
        }

        @Nullable String sid = logoutToken.getSessionId();
        @Nullable String subject = logoutToken.getSubject();
        if (sid == null && subject == null) {
            return List.of();
        }

        String issuer = logoutToken.getIssuer().toString();
        // Khi cả hai claim đều có, không bao giờ tin sid một mình: đòi hỏi cùng subject nữa.
        // Một logout chỉ theo subject cố ý thu hồi mọi session của subject đó.
        List<OidcSessionCorrelation> candidates =
                sid != null && subject != null
                        ? correlations.findByIssuerAndClientIdAndSidAndSub(
                                issuer, clientId, sid, subject)
                        : sid != null
                                ? correlations.findByIssuerAndClientIdAndSid(issuer, clientId, sid)
                                : correlations.findByIssuerAndClientIdAndSub(
                                        issuer, clientId, subject);
        // Spring Session đã flush xong vào lúc một provider logout một trình duyệt.
        // Tương quan cũ không được chọn một session đã hết hạn/bị xóa sau khi khởi động lại:
        // SessionRepository trả null cho session không còn hoặc đã hết hạn.
        List<OidcSessionInformation> removed = new ArrayList<>();
        for (OidcSessionCorrelation correlation : candidates) {
            if (sessions.findById(correlation.getSessionId()) == null) {
                continue;
            }
            correlations.delete(correlation);
            removed.add(sessionInformation(correlation));
        }
        return removed;
    }

    // Một lần login có thể đăng ký tương quan trước khi Spring Session ghi dòng của nó.
    // Cho một giờ để request flush; không bao giờ xóa mapping của một session hiện có.
    @Scheduled(fixedDelayString = "PT1H")
    void cleanUpOrphanedCorrelations() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        for (OidcSessionCorrelation correlation : correlations.findByRecordedAtBefore(cutoff)) {
            if (sessions.findById(correlation.getSessionId()) == null) {
                correlations.delete(correlation);
            }
        }
    }

    private static OidcSessionInformation sessionInformation(OidcSessionCorrelation correlation) {
        // Logout handler của Spring chỉ cần sessionId và CSRF header, không cần ID token thật.
        // Dựng lại một principal tối thiểu để SessionIdChangedEvent gọi được withSessionId().
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", correlation.getIssuer());
        claims.put("aud", List.of(correlation.getClientId()));
        claims.put("sub", correlation.getSub());
        if (correlation.getSid() != null) {
            claims.put("sid", correlation.getSid());
        }
        var idToken =
                new OidcIdToken(
                        "correlation-only", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), claims);
        var principal = new DefaultOidcUser(List.of(), idToken, "sub");
        String header = correlation.getCsrfHeaderName();
        Map<String, String> authorities =
                header == null ? Map.of() : Map.of(header, correlation.getCsrfToken());
        return new OidcSessionInformation(
                correlation.getSessionId().trim(), authorities, principal);
    }
}
