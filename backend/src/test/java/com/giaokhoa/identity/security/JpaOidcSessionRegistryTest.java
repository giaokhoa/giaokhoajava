package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.giaokhoa.identity.repository.OidcSessionCorrelationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class JpaOidcSessionRegistryTest {
    @DisplayName("Thiếu issuer bị từ chối trước khi ghi correlation")
    @Test
    void missingIssuerRejectedEarly() {
        var registry = new JpaOidcSessionRegistry(unwritableCorrelations(), sessions(), "client");
        var token =
                new OidcIdToken(
                        "id-token",
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(60),
                        Map.of("sub", "user", "aud", List.of("client")));
        var information =
                new OidcSessionInformation(
                        "session", Map.of(), new DefaultOidcUser(List.of(), token, "sub"));
        assertThrows(
                IllegalArgumentException.class, () -> registry.saveSessionInformation(information));
    }

    @DisplayName("Thiếu audience bị từ chối trước khi ghi correlation")
    @Test
    void missingAudienceRejectedEarly() {
        var registry = new JpaOidcSessionRegistry(unwritableCorrelations(), sessions(), "client");
        var token =
                new OidcIdToken(
                        "id-token",
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(60),
                        Map.of("sub", "user", "iss", "https://issuer.example"));
        var information =
                new OidcSessionInformation(
                        "session", Map.of(), new DefaultOidcUser(List.of(), token, "sub"));
        assertThrows(
                IllegalArgumentException.class, () -> registry.saveSessionInformation(information));
    }

    private static OidcSessionCorrelationRepository unwritableCorrelations() {
        return mock(
                OidcSessionCorrelationRepository.class,
                invocation -> {
                    throw new AssertionError("Invalid OIDC input must not reach the database");
                });
    }

    @SuppressWarnings("unchecked")
    private static SessionRepository<? extends Session> sessions() {
        return mock(SessionRepository.class);
    }
}
