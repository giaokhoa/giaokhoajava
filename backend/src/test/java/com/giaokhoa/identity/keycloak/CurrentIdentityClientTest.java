package com.giaokhoa.identity.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.support.TestIds;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class CurrentIdentityClientTest {
    private static final String HOST = "http://keycloak.test";
    private static final String REALM = "giaokhoa-it";
    private static final String USER_ID = TestIds.randomId().toString();
    private static final String USER_URL = HOST + "/admin/realms/" + REALM + "/users/" + USER_ID;

    @Test
    void readsTheCurrentProfileOfExactlyThatUserFromTheAdminApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CurrentIdentityClient client = client(builder);

        server.expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer server-token"))
                .andRespond(
                        withSuccess(
                                "{\"id\":\""
                                        + USER_ID
                                        + "\",\"username\":\"learner-1\",\"email\":\"learner@example.test\","
                                        + "\"emailVerified\":true,\"enabled\":true,"
                                        + "\"firstName\":\"Extra\",\"attributes\":{\"origin\":[\"forged\"]}}",
                                MediaType.APPLICATION_JSON));

        CurrentIdentity identity = client.readCurrentIdentity(USER_ID);

        assertEquals(
                new CurrentIdentity(USER_ID, "learner-1", "learner@example.test", true, true),
                identity);
        server.verify();
    }

    @Test
    void readFailuresPropagateInsteadOfInventingAProfile() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CurrentIdentityClient client = client(builder);

        server.expect(requestTo(USER_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        RestClientResponseException failure =
                assertThrows(
                        RestClientResponseException.class,
                        () -> client.readCurrentIdentity(USER_ID));
        assertTrue(failure.getStatusCode().is5xxServerError());
        server.verify();
    }

    @Test
    void anUnknownUserIsAReadFailureNotAnEmptyProfile() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CurrentIdentityClient client = client(builder);

        server.expect(requestTo(USER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(RestClientResponseException.class, () -> client.readCurrentIdentity(USER_ID));
        server.verify();
    }

    private CurrentIdentityClient client(RestClient.Builder builder) {
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("giaokhoa-provisioner")
                        .clientId("test-provisioner")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(HOST + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .build();
        OAuth2AccessToken token =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "server-token",
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        OAuth2AuthorizedClient authorized =
                new OAuth2AuthorizedClient(registration, "giaokhoa-provisioner", token);
        OAuth2AuthorizedClientManager manager = _ -> authorized;
        return new CurrentIdentityClient(builder, HOST, REALM, manager);
    }
}
