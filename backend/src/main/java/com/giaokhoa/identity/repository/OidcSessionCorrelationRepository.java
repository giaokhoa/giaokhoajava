package com.giaokhoa.identity.repository;

import com.giaokhoa.identity.model.OidcSessionCorrelation;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OidcSessionCorrelationRepository
        extends JpaRepository<OidcSessionCorrelation, String> {
    List<OidcSessionCorrelation> findByIssuerAndClientIdAndSidAndSub(
            String issuer, String clientId, String sid, String sub);

    List<OidcSessionCorrelation> findByIssuerAndClientIdAndSid(
            String issuer, String clientId, String sid);

    List<OidcSessionCorrelation> findByIssuerAndClientIdAndSub(
            String issuer, String clientId, String sub);

    List<OidcSessionCorrelation> findByRecordedAtBefore(Instant recordedAt);
}
