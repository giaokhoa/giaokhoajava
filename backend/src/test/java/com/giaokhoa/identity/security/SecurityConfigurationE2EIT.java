package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.KeycloakSessionFixture;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("e2e-backend")
class SecurityConfigurationE2EIT extends KeycloakSessionFixture {

    // Mọi khẳng định của class này đều qua HTTP thật đồng bộ nên worker nền không thuộc
    // phạm vi kiểm thử: mock để nó không tự xử lý operation trong lúc class chạy.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Test
    void authorizationCodeRequestRequiresPkce() {
        var client =
                java.util.Objects.requireNonNull(
                        clientRegistrations.findByRegistrationId("keycloak"));
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
                java.util.Objects.requireNonNull(
                        clientRegistrations.findByRegistrationId("keycloak"));
        KeycloakLogin login = createVerifiedUser("logout");
        try (var session = openSession(login.username(), login.password(), login.subject())) {
            var response =
                    realHttp()
                            .exchange(
                                    HttpMethod.POST,
                                    "/api/logout",
                                    session.cookie(),
                                    true,
                                    null,
                                    Map.of());

            assertEquals(302, response.status());
            String redirect = response.header("Location");
            assertNotNull(redirect);
            assertTrue(
                    redirect.startsWith(
                            client.getProviderDetails().getIssuerUri()
                                    + "/protocol/openid-connect/logout?"));
            assertTrue(redirect.contains("id_token_hint="));
            assertTrue(redirect.contains("post_logout_redirect_uri="));
            assertNull(
                    sessions.findById(session.cookie().getValue()),
                    "Logout phải xóa phiên JDBC trước khi chuyển hướng tới provider");
        } finally {
            deleteUser(login.subject());
        }
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
}
