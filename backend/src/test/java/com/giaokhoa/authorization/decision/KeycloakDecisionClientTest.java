package com.giaokhoa.authorization.decision;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class KeycloakDecisionClientTest {
    private static final String HOST = "http://keycloak.test";
    private static final String TOKEN_URL = HOST + "/realms/giaokhoa/protocol/openid-connect/token";
    private static final String AUDIENCE = "giaokhoa-api";
    private static final String USER = "session-user";
    private static final String USER_TOKEN = "user-access-token";
    private static final String REFRESHED_TOKEN = "refreshed-access-token";
    private static final String REFRESH_TOKEN = "stored-refresh-token";

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void allowsOnlyWhenEveryRequiredPairIsAllowed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        expectDecision(server, "resource-a%23enter", "resource-b").andRespond(allowed());
        expectDecision(server, "resource-b%23read", "resource-a").andRespond(allowed());

        assertTrue(
                client.isAllowed(
                        authentication(USER),
                        session.request(),
                        RequiredPermissions.of(
                                RequiredPermissions.require("resource-a", "enter"),
                                RequiredPermissions.require("resource-b", "read"))));
        server.verify();
    }

    @Test
    void deniesWhenOnePairIsDeniedByThePdp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        expectDecision(server, "resource-a%23enter", "resource-b").andRespond(allowed());
        expectDecision(server, "resource-b%23read", "resource-a")
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertFalse(
                client.isAllowed(
                        authentication(USER),
                        session.request(),
                        RequiredPermissions.of(
                                RequiredPermissions.require("resource-a", "enter"),
                                RequiredPermissions.require("resource-b", "read"))));
        server.verify();
    }

    @Test
    void deniesWhenThePdpDecisionResultIsFalse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        expectDecision(server, "resource-a%23enter", "resource-b")
                .andRespond(withSuccess("{\"result\":false}", MediaType.APPLICATION_JSON));

        assertFalse(
                client.isAllowed(
                        authentication(USER),
                        session.request(),
                        RequiredPermissions.of(
                                RequiredPermissions.require("resource-a", "enter"))));
        server.verify();
    }

    @DisplayName("Quyết định ALLOW không được cache giữa các request")
    @Test
    void allowDecisionsAreNotCached() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);
        RequiredPermissions required =
                RequiredPermissions.of(RequiredPermissions.require("resource-a", "enter"));
        Authentication authentication = authentication(USER);

        expectDecision(server, "resource-a%23enter", "resource-b").andRespond(allowed());
        expectDecision(server, "resource-a%23enter", "resource-b").andRespond(allowed());

        assertTrue(client.isAllowed(authentication, session.request(), required));
        assertTrue(client.isAllowed(authentication, session.request(), required));
        server.verify();
    }

    @DisplayName("Fail-closed khi response quyết định không có kết quả boolean")
    @Test
    void failsClosedWithoutBooleanResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        expectDecision(server, "resource-a%23enter", "resource-b")
                .andRespond(withSuccess("{\"result\":\"true\"}", MediaType.APPLICATION_JSON));

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("DECISION_UNVERIFIABLE", exception.getCode());
        server.verify();
    }

    @DisplayName("Fail-closed khi Keycloak trả lỗi server")
    @Test
    void failsClosedOnKeycloakServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        expectDecision(server, "resource-a%23enter", "resource-b")
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        server.verify();
    }

    @DisplayName("Fail-closed khi session không có authorized client")
    @Test
    void failsClosedWithoutAuthorizedClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = new Session(new HttpSessionOAuth2AuthorizedClientRepository());
        KeycloakDecisionClient client = client(builder, session);

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        server.verify();
    }

    @DisplayName("Fail-closed khi token của session thuộc về người dùng khác")
    @Test
    void failsClosedOnForeignSessionToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken("another-user", tokenExpiringIn(300));
        KeycloakDecisionClient client = client(builder, session);

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        server.verify();
    }

    @DisplayName("Token session hết hạn được refresh và quyết định dùng token mới")
    @Test
    void refreshesExpiredTokenAndDecides() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
        Session session = sessionWithRefreshToken(USER, tokenExpiringIn(-120));
        KeycloakDecisionClient client = client(builder, session, tokenBuilder);

        tokenServer
                .expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .string(
                                        allOf(
                                                containsString("grant_type=refresh_token"),
                                                containsString("refresh_token=" + REFRESH_TOKEN))))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\""
                                        + REFRESHED_TOKEN
                                        + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                                        + "\"refresh_token\":\"rotated-refresh-token\"}",
                                MediaType.APPLICATION_JSON));
        expectDecision(server, REFRESHED_TOKEN, "resource-a%23enter", "resource-b")
                .andRespond(allowed());

        assertTrue(
                client.isAllowed(
                        authentication(USER),
                        session.request(),
                        RequiredPermissions.of(
                                RequiredPermissions.require("resource-a", "enter"))));
        tokenServer.verify();
        server.verify();
        OAuth2AuthorizedClient stored =
                session.repository()
                        .loadAuthorizedClient("keycloak", authentication(USER), session.request());
        assertEquals(
                REFRESHED_TOKEN,
                java.util.Objects.requireNonNull(stored).getAccessToken().getTokenValue());
    }

    @DisplayName("Fail-closed khi Keycloak từ chối refresh token")
    @Test
    void failsClosedWhenRefreshRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
        Session session = sessionWithRefreshToken(USER, tokenExpiringIn(-120));
        KeycloakDecisionClient client = client(builder, session, tokenBuilder);

        tokenServer
                .expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("DECISION_UNVERIFIABLE", exception.getCode());
        tokenServer.verify();
        server.verify();
    }

    @DisplayName("Fail-closed khi token session đã hết hạn mà không refresh được")
    @Test
    void failsClosedWhenRefreshImpossible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Session session = sessionWithToken(USER, tokenExpiringIn(-120));
        KeycloakDecisionClient client = client(builder, session);

        // Không có refresh token trong phiên: Keycloak tự từ chối token đã hết hạn.
        expectDecision(server, "resource-a%23enter", "resource-b")
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        HttpProblemException exception =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                client.isAllowed(
                                        authentication(USER),
                                        session.request(),
                                        RequiredPermissions.of(
                                                RequiredPermissions.require(
                                                        "resource-a", "enter"))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("DECISION_UNVERIFIABLE", exception.getCode());
        server.verify();
    }

    private ResponseActions expectDecision(
            MockRestServiceServer server, String encodedPair, String otherResource) {
        return expectDecision(server, USER_TOKEN, encodedPair, otherResource);
    }

    private ResponseActions expectDecision(
            MockRestServiceServer server,
            String bearerToken,
            String encodedPair,
            String otherResource) {
        return server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + bearerToken))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(
                        content()
                                .string(
                                        allOf(
                                                containsString("grant_type="),
                                                containsString("uma-ticket"),
                                                containsString("audience=" + AUDIENCE),
                                                containsString("permission=" + encodedPair),
                                                containsString("response_mode=decision"),
                                                not(containsString(otherResource)),
                                                not(containsString("claim_token")))));
    }

    private ResponseCreator allowed() {
        return withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON);
    }

    private KeycloakDecisionClient client(Builder builder, Session session) {
        return client(builder, session, null);
    }

    private KeycloakDecisionClient client(
            Builder builder, Session session, @Nullable Builder tokenBuilder) {
        // Manager resolve servlet response từ request context, như trên một servlet
        // thread; chính request được truyền vào authorize() dưới dạng attribute.
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(session.request(), new MockHttpServletResponse()));
        var provider = OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode();
        if (tokenBuilder == null) {
            provider.refreshToken();
        } else {
            // Phản chiếu default client của chính token response client, vốn mang OAuth2
            // token response converter, trên builder đã gắn mock.
            RestClient tokenHttp =
                    tokenBuilder
                            .configureMessageConverters(converters ->
                                    converters.addCustomConverter(
                                            new OAuth2AccessTokenResponseHttpMessageConverter()))
                            .build();
            RestClientRefreshTokenTokenResponseClient tokenResponseClient =
                    new RestClientRefreshTokenTokenResponseClient();
            tokenResponseClient.setRestClient(tokenHttp);
            provider.refreshToken(
                    configurer -> configurer.accessTokenResponseClient(tokenResponseClient));
        }
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(
                        new InMemoryClientRegistrationRepository(registration()),
                        session.repository());
        manager.setAuthorizedClientProvider(provider.build());
        OAuth2AuthorizedClientManager authorizedClientManager = manager;
        return new KeycloakDecisionClient(builder, authorizedClientManager, TOKEN_URL, AUDIENCE);
    }

    private Authentication authentication(String name) {
        return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
    }

    private OAuth2AccessToken tokenExpiringIn(long seconds) {
        Instant now = Instant.now();
        return new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                USER_TOKEN,
                now.minusSeconds(3600),
                now.plusSeconds(seconds));
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("giaokhoa-web")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri(HOST + "/realms/giaokhoa/protocol/openid-connect/auth")
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .tokenUri(TOKEN_URL)
                .build();
    }

    private Session sessionWithToken(String principalName, OAuth2AccessToken token) {
        return sessionWithClient(new OAuth2AuthorizedClient(registration(), principalName, token));
    }

    private Session sessionWithRefreshToken(String principalName, OAuth2AccessToken token) {
        OAuth2RefreshToken refreshToken =
                new OAuth2RefreshToken(REFRESH_TOKEN, token.getIssuedAt());
        return sessionWithClient(
                new OAuth2AuthorizedClient(registration(), principalName, token, refreshToken));
    }

    private Session sessionWithClient(OAuth2AuthorizedClient authorized) {
        Session session = new Session(new HttpSessionOAuth2AuthorizedClientRepository());
        session.repository()
                .saveAuthorizedClient(
                        authorized,
                        authentication(authorized.getPrincipalName()),
                        session.request(),
                        new MockHttpServletResponse());
        return session;
    }

    private static final class Session {
        private final OAuth2AuthorizedClientRepository clientRepository;
        private final MockHttpServletRequest servletRequest = new MockHttpServletRequest();

        private Session(OAuth2AuthorizedClientRepository clientRepository) {
            this.clientRepository = clientRepository;
            this.servletRequest.setSession(new MockHttpSession());
        }

        private OAuth2AuthorizedClientRepository repository() {
            return clientRepository;
        }

        private HttpServletRequest request() {
            return servletRequest;
        }
    }
}
