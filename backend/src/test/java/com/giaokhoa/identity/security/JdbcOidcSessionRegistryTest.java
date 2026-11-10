package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class JdbcOidcSessionRegistryTest {
    @DisplayName("Thiếu issuer bị từ chối trước khi ghi correlation")
    @Test
    void missingIssuerRejectedEarly() {
        var jdbc = unwritableJdbc();
        var registry = new JdbcOidcSessionRegistry(jdbc, "client");
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
        var jdbc = unwritableJdbc();
        var registry = new JdbcOidcSessionRegistry(jdbc, "client");
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

    private static JdbcTemplate unwritableJdbc() {
        return new JdbcTemplate() {
            @Override
            public int update(String sql, @Nullable Object @Nullable ... args) {
                throw new AssertionError("Invalid OIDC input must not reach the database");
            }
        };
    }
}
