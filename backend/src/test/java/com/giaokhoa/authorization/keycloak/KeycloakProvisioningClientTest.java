package com.giaokhoa.authorization.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;
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

class KeycloakProvisioningClientTest {
    private static final String HOST = "http://keycloak.test";
    private static final UUID WORKSPACE = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String NAME = "center-" + WORKSPACE;
    private static final String ORG_QUERY =
            HOST
                    + "/admin/realms/giaokhoa/organizations?search="
                    + NAME
                    + "&exact=true&first=0&max=100";

    @Test
    void createsOrganizationOnceAndReturnsStableOpaqueIdOnRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);

        server.expect(requestTo(ORG_QUERY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOST + "/admin/realms/giaokhoa/organizations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .json(
                                        "{\"name\":\""
                                                + NAME
                                                + "\",\"alias\":\""
                                                + NAME
                                                + "\",\"description\":\"Center A\"}"))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(ORG_QUERY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"id\":\"opaque-keycloak-id\",\"name\":\""
                                        + NAME
                                        + "\",\"alias\":\""
                                        + NAME
                                        + "\"}]",
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORG_QUERY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"id\":\"opaque-keycloak-id\",\"name\":\""
                                        + NAME
                                        + "\",\"alias\":\""
                                        + NAME
                                        + "\"}]",
                                MediaType.APPLICATION_JSON));

        assertEquals("opaque-keycloak-id", client.ensureOrganization(WORKSPACE, "Center A"));
        assertEquals("opaque-keycloak-id", client.ensureOrganization(WORKSPACE, "Center A"));
        server.verify();
    }

    @Test
    void readsAllPagesOfUserGroupsBeforeDecidingMembership() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);
        String prefix = "/gk/workspaces/" + WORKSPACE + "/students/";
        String firstPage =
                "["
                        + java.util.stream.IntStream.range(0, 100)
                                .mapToObj(i ->
                                        "{\"path\":\""
                                                + prefix
                                                + "00000000-0000-0000-0000-"
                                                + String.format("%012d", i)
                                                + "/guardians\"}")
                                .collect(java.util.stream.Collectors.joining(","))
                        + "]";
        String lastPath = prefix + "ffffffff-ffff-ffff-ffff-ffffffffffff/guardians";
        server.expect(
                        requestTo(
                                HOST
                                        + "/admin/realms/giaokhoa/users/opaque-user/groups?first=0&max=100"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                HOST
                                        + "/admin/realms/giaokhoa/users/opaque-user/groups?first=100&max=100"))
                .andRespond(
                        withSuccess(
                                "[{\"path\":\"" + lastPath + "\"}]", MediaType.APPLICATION_JSON));
        assertTrue(client.realmGroupPaths("opaque-user").contains(lastPath));
        server.verify();
    }

    @Test
    void resolvesGroupByExactPathWithoutNameSearch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);
        String[] paths = {"/gk", "/gk/identity", "/gk/identity/eligible"};
        String[] ids = {"root", "branch", "leaf"};
        for (int i = 0; i < paths.length; i++) {
            server.expect(requestTo(groupPath(paths[i])))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(
                            withSuccess(
                                    "{\"id\":\"" + ids[i] + "\",\"path\":\"" + paths[i] + "\"}",
                                    MediaType.APPLICATION_JSON));
        }
        assertEquals("leaf", client.ensureRealmGroup("/gk/identity/eligible"));
        server.verify();
    }

    @Test
    void revokeDoesNotSucceedWhenExactGroupIsMissing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);
        server.expect(requestTo(groupPath("/gk/identity/eligible")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(
                IllegalStateException.class,
                () ->
                        client.setRealmGroupMembership(
                                "opaque-user", "/gk/identity/eligible", false));
        server.verify();
    }

    @Test
    void revokeUsesExactIdAndFailsOnKeycloakError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);
        server.expect(requestTo(groupPath("/gk/identity/eligible")))
                .andRespond(
                        withSuccess(
                                "{\"id\":\"opaque-group\",\"path\":\"/gk/identity/eligible\"}",
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                HOST
                                        + "/admin/realms/giaokhoa/users/opaque-user/groups/opaque-group"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(
                org.springframework.web.client.RestClientResponseException.class,
                () ->
                        client.setRealmGroupMembership(
                                "opaque-user", "/gk/identity/eligible", false));
        server.verify();
    }

    @Test
    void retriesGroupCreationConflictByReadingActualId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakProvisioningClient client = client(builder);
        server.expect(requestTo(groupPath("/gk"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(HOST + "/admin/realms/giaokhoa/groups"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"name\":\"gk\"}"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(groupPath("/gk")))
                .andRespond(
                        withSuccess(
                                "{\"id\":\"root\",\"path\":\"/gk\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(groupPath("/gk/platform")))
                .andRespond(
                        withSuccess(
                                "{\"id\":\"platform\",\"path\":\"/gk/platform\"}",
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(groupPath("/gk/platform/operators")))
                .andRespond(
                        withSuccess(
                                "{\"id\":\"operators\",\"path\":\"/gk/platform/operators\"}",
                                MediaType.APPLICATION_JSON));
        assertEquals("operators", client.ensureRealmGroup("/gk/platform/operators"));
        server.verify();
    }

    @Test
    void rejectsArbitraryGroupSourcesBeforeCallingKeycloak() {
        KeycloakProvisioningClient client = client(RestClient.builder());

        assertThrows(
                IllegalArgumentException.class, () -> client.ensureRealmGroup("/gk/roles/admin"));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.ensureRealmGroup("/gk/workspaces/not-a-uuid/roles/teachers"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.setRealmGroupMembership(
                                "opaque-user", "/gk/platform/operators/other", false));
        assertThrows(IllegalArgumentException.class, () -> client.ensureOrganization(null, "A"));
    }

    private String groupPath(String path) {
        return HOST + "/admin/realms/giaokhoa/group-by-path" + path;
    }

    private KeycloakProvisioningClient client(RestClient.Builder builder) {
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("giaokhoa-provisioner")
                        .clientId("test-provisioner")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(HOST + "/realms/giaokhoa/protocol/openid-connect/token")
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
        return new KeycloakProvisioningClient(builder, HOST, manager);
    }
}
