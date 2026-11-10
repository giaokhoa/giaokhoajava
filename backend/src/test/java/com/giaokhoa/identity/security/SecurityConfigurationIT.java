package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.IntegrationEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Tag("e2e-backend")
class SecurityConfigurationIT extends IntegrationEnvironment {

    // Mọi khẳng định của class này đều qua MockMvc đồng bộ nên worker nền không thuộc
    // phạm vi kiểm thử: mock để nó không tự xử lý operation trong lúc class chạy.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired ClientRegistrationRepository registrations;

    private final MockMvc mvc;

    SecurityConfigurationIT(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authorizationCodeRequestRequiresPkce() throws Exception {
        var client =
                java.util.Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
        var response =
                mvc.perform(get("/oauth2/authorization/keycloak"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse();
        String location = response.getRedirectedUrl();
        assertTrue(location != null && location.contains("code_challenge="));
        assertTrue(location.contains("code_challenge_method=S256"));
        assertTrue(location.startsWith(client.getProviderDetails().getIssuerUri() + "/"));
    }

    @DisplayName("Các mutation và logout của user cần CSRF, còn back-channel thì không")
    @Test
    void csrfRequiredExceptBackChannel() throws Exception {
        assertEquals(403, mvc.perform(post("/api/logout")).andReturn().getResponse().getStatus());
        assertEquals(
                403,
                mvc.perform(post("/api/workspaces/test")).andReturn().getResponse().getStatus());
        assertEquals(
                400,
                mvc.perform(post("/logout/connect/back-channel/another-registration"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                400,
                mvc.perform(
                                post("/logout/connect/back-channel/keycloak")
                                        .param("logout_token", "bad"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                302,
                mvc.perform(post("/api/logout").with(csrf()))
                        .andReturn()
                        .getResponse()
                        .getStatus());
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

        var response =
                mvc.perform(post("/api/logout").with(csrf()).with(authentication(auth)))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse();

        String redirect = response.getRedirectedUrl();
        assertNotNull(redirect);
        assertTrue(
                redirect.startsWith(
                        client.getProviderDetails().getIssuerUri()
                                + "/protocol/openid-connect/logout?"));
        assertTrue(redirect.contains("id_token_hint="));
        assertTrue(redirect.contains("post_logout_redirect_uri="));
    }

    @DisplayName("API chưa xác thực không truy cập được bằng Bearer hay Basic")
    @Test
    void apisRejectBearerAndBasicWhenAnon() throws Exception {
        var bearer =
                mvc.perform(get("/api/me/workspaces").header("Authorization", "Bearer fake"))
                        .andReturn()
                        .getResponse();
        var basic =
                mvc.perform(get("/api/me/workspaces").header("Authorization", "Basic Zm9vOmJhcg=="))
                        .andReturn()
                        .getResponse();
        assertNotEquals(200, bearer.getStatus());
        assertNotEquals(200, basic.getStatus());
        assertNull(bearer.getHeader("WWW-Authenticate"));
    }
}
