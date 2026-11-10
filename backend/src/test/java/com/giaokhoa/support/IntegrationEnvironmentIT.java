package com.giaokhoa.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/** Kiểm tra integration profile thật của Spring và các dịch vụ PostgreSQL/OIDC tách biệt của nó. */
@SpringBootTest
class IntegrationEnvironmentIT extends IntegrationEnvironment {
    @Autowired DataSource dataSource;

    @Autowired Environment environment;

    @Test
    void springIntegrationProfileUsesRealIsolatedServices() throws Exception {
        assertTrue(Arrays.asList(environment.getActiveProfiles()).contains("integration"));
        String database = environment.getRequiredProperty("spring.datasource.url");
        String issuer =
                environment.getRequiredProperty(
                        "spring.security.oauth2.client.provider.keycloak.issuer-uri");
        validateTargets(database, issuer);
        assertTrue(environment.getRequiredProperty("spring.flyway.clean-disabled", Boolean.class));

        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(5));
            assertEquals("giaokhoa_test", connection.getCatalog());
        }

        Map<?, ?> discovery =
                RestClient.create()
                        .get()
                        .uri(URI.create(issuer + "/.well-known/openid-configuration"))
                        .retrieve()
                        .body(Map.class);
        assertNotNull(discovery);
        assertEquals(issuer, discovery.get("issuer"));
        assertTrue(
                String.valueOf(discovery.get("authorization_endpoint"))
                        .contains("/protocol/openid-connect/auth"));
    }
}
