package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Lưu một user token Code+PKCE thật của Keycloak vào JDBC Spring Session thật của ứng dụng và nạp
 * lại nó qua OAuth2AuthorizedClientRepository đã cấu hình.
 */
final class NativeSpringSessionFixture implements AutoCloseable {
    private final SessionRepositoryFilter<?> filter;
    private final OAuth2AuthorizedClientRepository clients;
    private final SessionRepository<?> sessions;
    private final Cookie sessionCookie;
    private final OAuth2AuthenticationToken authentication;

    NativeSpringSessionFixture(
            NativeRevocationFixture nativeGate,
            SessionRepositoryFilter<?> filter,
            OAuth2AuthorizedClientRepository clients,
            ClientRegistrationRepository registrations,
            SessionRepository<?> sessions)
            throws Exception {
        this.filter = filter;
        this.clients = clients;
        this.sessions = sessions;
        var registration = Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
        var tokens = nativeGate.webTokensViaCodePkce();
        Instant issued = Instant.now();
        var idToken =
                new OidcIdToken(
                        tokens.idToken(),
                        issued,
                        issued.plusSeconds(300),
                        Map.of(
                                "iss",
                                IntegrationEnvironment.testIssuerUri(),
                                "sub",
                                nativeGate.userSubject(),
                                "aud",
                                List.of(registration.getClientId())));
        authentication =
                new OAuth2AuthenticationToken(
                        new DefaultOidcUser(List.of(), idToken, "sub"), List.of(), "keycloak");
        var authorized =
                new OAuth2AuthorizedClient(
                        registration,
                        authentication.getName(),
                        new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER,
                                tokens.accessToken(),
                                issued,
                                issued.plusSeconds(300)),
                        new OAuth2RefreshToken(tokens.refreshToken(), issued));

        var request = new MockHttpServletRequest("GET", "/");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (wrapped, reply) -> {
                    var servletRequest = (HttpServletRequest) wrapped;
                    servletRequest
                            .getSession(true)
                            .setAttribute(
                                    HttpSessionSecurityContextRepository
                                            .SPRING_SECURITY_CONTEXT_KEY,
                                    new SecurityContextImpl(authentication));
                    clients.saveAuthorizedClient(
                            authorized,
                            authentication,
                            servletRequest,
                            (HttpServletResponse) reply);
                });
        sessionCookie = Objects.requireNonNull(response.getCookie("SESSION"));
        Objects.requireNonNull(
                sessions.findById(sessionCookie.getValue()), "Session must persist in JDBC");
        assertTrue(
                java.util.Arrays.equals(
                        tokens.accessToken().toCharArray(), currentAccessToken().toCharArray()),
                "Stored web token must reload unchanged");
    }

    Cookie cookie() {
        return sessionCookie;
    }

    String currentAccessToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/");
        request.setCookies(sessionCookie);
        var loaded = new AtomicReference<OAuth2AuthorizedClient>();
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (wrapped, _) -> {
                    var httpRequest = (HttpServletRequest) wrapped;
                    var session = Objects.requireNonNull(httpRequest.getSession(false));
                    var context =
                            (SecurityContext)
                                    session.getAttribute(
                                            HttpSessionSecurityContextRepository
                                                    .SPRING_SECURITY_CONTEXT_KEY);
                    assertNotNull(context);
                    assertEquals(
                            authentication.getName(),
                            Objects.requireNonNull(context.getAuthentication()).getName());
                    loaded.set(
                            clients.loadAuthorizedClient("keycloak", authentication, httpRequest));
                });
        return Objects.requireNonNull(loaded.get()).getAccessToken().getTokenValue();
    }

    @Override
    public void close() {
        sessions.deleteById(sessionCookie.getValue());
    }
}
