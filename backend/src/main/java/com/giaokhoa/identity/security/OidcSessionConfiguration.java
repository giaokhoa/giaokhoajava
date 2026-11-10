package com.giaokhoa.identity.security;

import com.giaokhoa.identity.repository.OidcSessionCorrelationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OidcSessionConfiguration {

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        // Spring Session JDBC lưu bền HttpSession, gồm cả access/refresh token đã được ủy quyền.
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    OidcSessionRegistry oidcSessionRegistry(
            OidcSessionCorrelationRepository correlations,
            SessionRepository<? extends Session> sessions,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
                    String clientId) {
        return new JpaOidcSessionRegistry(correlations, sessions, clientId);
    }
}
