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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Tag("e2e-backend")
class SecurityConfigurationIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "security_configuration_" + UUID.randomUUID().toString().replace("-", "");

    // Schema cô lập riêng thay vì public dùng chung: context của class này không còn dùng
    // chung trạng thái DB với class khác và không cần ai RESET public sau lượt chạy.
    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    // Worker thật chạy theo lịch sẽ mở giao dịch trên chính schema cô lập này trong khi
    // @AfterAll DROP SCHEMA cần khóa độc quyền mọi bảng — hai bên khóa chéo nhau và PostgreSQL
    // hủy DROP bằng deadlock (cùng cơ chế BootstrapProfileIT). Mọi khẳng định của class này
    // đều qua MockMvc đồng bộ nên mock worker không đổi ý nghĩa kiểm thử.
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

    @Test
    void mutationsAndUserLogoutNeedCsrfButBackChannelDoesNot() throws Exception {
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

    @Test
    void postLogoutWithOidcPrincipalRedirectsToProviderEndSession() throws Exception {
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

    @Test
    void unauthenticatedApisAreNotAccessibleViaBearerOrBasic() throws Exception {
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
