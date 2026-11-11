package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.HttpTestClient;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("e2e-backend")
class SecurityConfigurationE2EIT extends IntegrationEnvironment {

    // Mọi khẳng định của class này đều qua HTTP thật đồng bộ nên worker nền không thuộc
    // phạm vi kiểm thử: mock để nó không tự xử lý operation trong lúc class chạy.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired ClientRegistrationRepository registrations;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired SessionRepository<?> sessions;

    private final WebApplicationContext context;
    private @Nullable HttpTestClient httpClient;

    SecurityConfigurationE2EIT(WebApplicationContext context) {
        this.context = context;
    }

    private HttpTestClient realHttp() {
        if (httpClient == null) {
            httpClient = HttpTestClient.bind(context);
        }
        return httpClient;
    }

    @Test
    void authorizationCodeRequestRequiresPkce() {
        var client =
                java.util.Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
        var response =
                realHttp()
                        .exchange(
                                HttpMethod.GET,
                                "/oauth2/authorization/keycloak",
                                null,
                                false,
                                null,
                                Map.of());
        assertEquals(302, response.status());
        String location = response.header("Location");
        assertTrue(location != null && location.contains("code_challenge="));
        assertTrue(location.contains("code_challenge_method=S256"));
        assertTrue(location.startsWith(client.getProviderDetails().getIssuerUri() + "/"));
    }

    @DisplayName("Các mutation và logout của user cần CSRF, còn back-channel thì không")
    @Test
    void csrfRequiredExceptBackChannel() {
        assertEquals(
                403,
                realHttp()
                        .exchange(HttpMethod.POST, "/api/logout", null, false, null, Map.of())
                        .status());
        assertEquals(
                403,
                realHttp()
                        .exchange(
                                HttpMethod.POST,
                                "/api/workspaces/test",
                                null,
                                false,
                                null,
                                Map.of())
                        .status());
        assertEquals(
                400,
                realHttp()
                        .exchange(
                                HttpMethod.POST,
                                "/logout/connect/back-channel/another-registration",
                                null,
                                false,
                                null,
                                Map.of())
                        .status());
        assertEquals(
                400,
                realHttp()
                        .exchange(
                                HttpMethod.POST,
                                "/logout/connect/back-channel/keycloak",
                                null,
                                false,
                                "logout_token=bad",
                                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                        .status());
        assertEquals(
                302,
                realHttp()
                        .exchange(HttpMethod.POST, "/api/logout", null, true, null, Map.of())
                        .status());
    }

    @DisplayName("Post-logout với OIDC principal chuyển hướng tới end-session của provider")
    @Test
    void postLogoutRedirectsToEndSession() throws Exception {
        var client =
                java.util.Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
        var token =
                new OidcIdToken(
                        "test-id-token",
                        Instant.now(),
                        Instant.now().plusSeconds(600),
                        Map.of(
                                "iss", client.getProviderDetails().getIssuerUri(),
                                "sub", "test-user",
                                "aud", List.of(client.getClientId())));
        var principal = new DefaultOidcUser(List.of(), token, "sub");
        var auth = new OAuth2AuthenticationToken(principal, List.of(), "keycloak");

        FabricatedSession session = fabricateSession(auth);
        var response =
                realHttp()
                        .exchange(
                                HttpMethod.POST,
                                "/api/logout",
                                session.cookie(),
                                false,
                                null,
                                Map.of(session.csrf().getHeaderName(), session.csrf().getToken()));

        assertEquals(302, response.status());
        String redirect = response.header("Location");
        assertNotNull(redirect);
        assertTrue(
                redirect.startsWith(
                        client.getProviderDetails().getIssuerUri()
                                + "/protocol/openid-connect/logout?"));
        assertTrue(redirect.contains("id_token_hint="));
        assertTrue(redirect.contains("post_logout_redirect_uri="));
    }

    @DisplayName(
            "Chưa đăng nhập: ngữ cảnh trình duyệt nhận 302 tới đăng nhập OIDC, khách API JSON"
                    + " nhận 401 ProblemDetail")
    @Test
    void anonymousEntryPointDependsOnClientKind() {
        var browser =
                realHttp().get("/api/me/workspaces", null, Map.of("Accept", MediaType.ALL_VALUE));
        assertEquals(302, browser.status());
        String location = browser.header("Location");
        assertTrue(location != null && location.contains("/oauth2/authorization/keycloak"));

        var api =
                realHttp()
                        .get(
                                "/api/me/workspaces",
                                null,
                                Map.of("Accept", MediaType.APPLICATION_JSON_VALUE));
        assertEquals(401, api.status());
        assertTrue(
                Objects.requireNonNull(api.header("Content-Type"))
                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @DisplayName("API chưa xác thực không truy cập được bằng Bearer hay Basic")
    @Test
    void apisRejectBearerAndBasicWhenAnon() {
        var bearer =
                realHttp().get("/api/me/workspaces", null, Map.of("Authorization", "Bearer fake"));
        var basic =
                realHttp()
                        .get(
                                "/api/me/workspaces",
                                null,
                                Map.of("Authorization", "Basic Zm9vOmJhcg=="));
        assertNotEquals(200, bearer.status());
        assertNotEquals(200, basic.status());
        assertNull(bearer.header("WWW-Authenticate"));
    }

    /** Phiên JDBC thật mang sẵn SecurityContext và token CSRF của đúng phiên đó. */
    private record FabricatedSession(Cookie cookie, CsrfToken csrf) {}

    /**
     * Dựng phiên JDBC thật cho một principal tổng hợp ngay trong tiến trình — cùng cơ chế fixture
     * phiên thật của suite (chạy SessionRepositoryFilter với request giả để phiên được lưu vào
     * JDBC), chỉ khác là danh tính ở đây không đi qua Keycloak. Token CSRF được sinh và lưu vào
     * đúng phiên đó bằng repository của Spring Security, như một lượt tải trang thật sẽ làm, để
     * request logout qua HTTP mang token thật của phiên.
     */
    private FabricatedSession fabricateSession(OAuth2AuthenticationToken authentication)
            throws Exception {
        var csrfRepository = new HttpSessionCsrfTokenRepository();
        var captured = new Object[2];
        var request = new MockHttpServletRequest("GET", "/");
        var response = new MockHttpServletResponse();
        sessionFilter.doFilter(
                request,
                response,
                (wrapped, reply) -> {
                    var servletRequest = (HttpServletRequest) wrapped;
                    var servletResponse = (HttpServletResponse) reply;
                    servletRequest
                            .getSession(true)
                            .setAttribute(
                                    HttpSessionSecurityContextRepository
                                            .SPRING_SECURITY_CONTEXT_KEY,
                                    new SecurityContextImpl(authentication));
                    CsrfToken token = csrfRepository.generateToken(servletRequest);
                    csrfRepository.saveToken(token, servletRequest, servletResponse);
                    captured[1] = token;
                });
        captured[0] = Objects.requireNonNull(response.getCookie("SESSION"));
        Cookie cookie = (Cookie) captured[0];
        assertNotNull(sessions.findById(cookie.getValue()), "Session must persist in JDBC");
        return new FabricatedSession(cookie, (CsrfToken) captured[1]);
    }
}
