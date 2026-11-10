package com.giaokhoa.authorization.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.decision.KeycloakDecisionClient;
import com.giaokhoa.authorization.decision.RequiredPermissions;
import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.policy.PolicyGraph.Gate;
import com.giaokhoa.authorization.policy.PolicyGraph.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Chỉ chạy qua verify-native.sh trên realm Keycloak 26.8.1 dùng một lần của nó. */
@EnabledIfEnvironmentVariable(named = "PROVISIONING_TEST_KEYCLOAK_BASE_URL", matches = ".+")
class PolicyGraphWriterNativeIT {
    private static final String CLIENT_ID = "giaokhoa-provisioner";
    private static final String AUDIENCE = "giaokhoa-api";
    private static final String USER = "native-gate-assigned";
    // ID giữ cố định, thuộc dải native gate (cùng họ infra/keycloak/fixtures/native-gate.json):
    // các lần chạy verify-native.sh phải hội tụ trên cùng một workspace thay vì phình dữ liệu.
    private static final UUID WORKSPACE = UUID.fromString("71000000-0000-4000-8000-0000000000fa");

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void nativeWriterRepairsGroupUserAndTimeDriftAndPdpHonorsRevocation() throws Exception {
        String base = required("PROVISIONING_TEST_KEYCLOAK_BASE_URL");
        String secret = required("PROVISIONING_TEST_KEYCLOAK_PROVISIONER_SECRET");
        String userId = required("NATIVE_GATE_ASSIGNED_USER_ID");
        String userToken =
                Files.readAllLines(Path.of(required("NATIVE_GATE_NATIVE_TOKENS_FILE"))).getFirst();
        RestClient http = RestClient.create();
        String adminToken = serviceToken(http, base, secret);
        ClientRegistration serviceRegistration =
                ClientRegistration.withRegistrationId(CLIENT_ID)
                        .clientId(CLIENT_ID)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .build();
        OAuth2AccessToken serviceAccessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        adminToken,
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        OAuth2AuthorizedClient serviceClient =
                new OAuth2AuthorizedClient(serviceRegistration, CLIENT_ID, serviceAccessToken);
        OAuth2AuthorizedClientManager serviceManager = _ -> serviceClient;
        PolicyGraphWriter writer =
                new PolicyGraphWriter(
                        RestClient.builder(), base, "giaokhoa", serviceManager, AUDIENCE, null);

        KeycloakProvisioningClient directory =
                new KeycloakProvisioningClient(RestClient.builder(), base, CLIENT_ID, secret);
        String owners = NativePolicyTemplates.ownersGroup(WORKSPACE);
        directory.ensureRealmGroup(owners);
        directory.setRealmGroupMembership(userId, owners, true);
        Instant from = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Instant until = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        PolicyGraph graph =
                new PolicyGraph(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        "enter",
                        List.of(
                                Source.of(
                                        new Gate.GroupMembership(owners),
                                        new Gate.Actor(userId),
                                        new Gate.Validity(from, until))));
        String root = authzRoot(http, base, adminToken);
        var subject = UsernamePasswordAuthenticationToken.authenticated(USER, null, List.of());
        var repository = new HttpSessionOAuth2AuthorizedClientRepository();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setSession(new MockHttpSession());
        ClientRegistration webRegistration =
                ClientRegistration.withRegistrationId("keycloak")
                        .clientId("native-gate-test")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationUri(base + "/realms/giaokhoa/protocol/openid-connect/auth")
                        .tokenUri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .redirectUri("http://127.0.0.1:18895/callback")
                        .build();
        OAuth2AccessToken webToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        userToken,
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        repository.saveAuthorizedClient(
                new OAuth2AuthorizedClient(webRegistration, USER, webToken),
                subject,
                servletRequest,
                new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(servletRequest, new MockHttpServletResponse()));
        KeycloakDecisionClient decision =
                new KeycloakDecisionClient(
                        repository,
                        new InMemoryClientRegistrationRepository(webRegistration),
                        AUDIENCE);

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph);
        RequiredPermissions required =
                RequiredPermissions.of(RequiredPermissions.require(applied.resourceId(), "enter"));
        assertTrue(decision.isAllowed(subject, servletRequest, required));

        String wrongGroupId =
                String.valueOf(
                        get(
                                        http,
                                        base
                                                + "/admin/realms/giaokhoa/group-by-path/gk/platform/operators",
                                        adminToken)
                                .get("id"));
        String groupId =
                policyId(http, root, adminToken, NativePolicyTemplates.groupPolicyName(owners));
        put(
                http,
                root + "/policy/group/" + groupId,
                adminToken,
                Map.of(
                        "id",
                        groupId,
                        "name",
                        NativePolicyTemplates.groupPolicyName(owners),
                        "type",
                        "group",
                        "logic",
                        "NEGATIVE",
                        "groups",
                        List.of(Map.of("id", wrongGroupId, "extendChildren", true))));
        String userPolicyId =
                policyId(http, root, adminToken, NativePolicyTemplates.userPolicyName(userId));
        put(
                http,
                root + "/policy/user/" + userPolicyId,
                adminToken,
                Map.of(
                        "id",
                        userPolicyId,
                        "name",
                        NativePolicyTemplates.userPolicyName(userId),
                        "type",
                        "user",
                        "logic",
                        "POSITIVE",
                        "users",
                        List.of(required("PROVISIONING_TEST_KEYCLOAK_USER_ID"))));
        String untilId =
                policyId(http, root, adminToken, NativePolicyTemplates.validUntilPolicyName(until));
        put(
                http,
                root + "/policy/time/" + untilId,
                adminToken,
                Map.of(
                        "id",
                        untilId,
                        "name",
                        NativePolicyTemplates.validUntilPolicyName(until),
                        "type",
                        "time",
                        "logic",
                        "POSITIVE",
                        "notBefore",
                        NativePolicyTemplates.KEYCLOAK_UTC.format(until)));

        assertEquals(applied.resourceId(), writer.apply(graph).resourceId());
        assertEquals(
                "POSITIVE", get(http, root + "/policy/group/" + groupId, adminToken).get("logic"));
        assertEquals(
                "NEGATIVE", get(http, root + "/policy/time/" + untilId, adminToken).get("logic"));
        assertTrue(decision.isAllowed(subject, servletRequest, required));
        directory.setRealmGroupMembership(userId, owners, false);
        assertFalse(decision.isAllowed(subject, servletRequest, required));
        writer.retractRoot(graph.targetKey(), graph.scope());
        assertFalse(decision.isAllowed(subject, servletRequest, required));
    }

    @Test
    void nativeWriterAttachesASecondScopeToTheSameResource() throws Exception {
        String base = required("PROVISIONING_TEST_KEYCLOAK_BASE_URL");
        String secret = required("PROVISIONING_TEST_KEYCLOAK_PROVISIONER_SECRET");
        String userId = required("NATIVE_GATE_ASSIGNED_USER_ID");
        String userToken =
                Files.readAllLines(Path.of(required("NATIVE_GATE_NATIVE_TOKENS_FILE"))).getFirst();
        RestClient http = RestClient.create();
        String adminToken = serviceToken(http, base, secret);
        ClientRegistration serviceRegistration =
                ClientRegistration.withRegistrationId(CLIENT_ID)
                        .clientId(CLIENT_ID)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .build();
        OAuth2AccessToken serviceAccessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        adminToken,
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        OAuth2AuthorizedClient serviceClient =
                new OAuth2AuthorizedClient(serviceRegistration, CLIENT_ID, serviceAccessToken);
        OAuth2AuthorizedClientManager serviceManager = _ -> serviceClient;
        PolicyGraphWriter writer =
                new PolicyGraphWriter(
                        RestClient.builder(), base, "giaokhoa", serviceManager, AUDIENCE, null);

        KeycloakProvisioningClient directory =
                new KeycloakProvisioningClient(RestClient.builder(), base, CLIENT_ID, secret);
        String owners = NativePolicyTemplates.ownersGroup(WORKSPACE);
        directory.ensureRealmGroup(owners);
        directory.setRealmGroupMembership(userId, owners, true);

        String target = NativePolicyTemplates.workspaceKey(WORKSPACE);
        List<Source> sources = List.of(Source.of(new Gate.GroupMembership(owners)));
        PolicyGraphWriter.AppliedGraph first =
                writer.apply(new PolicyGraph(target, "enter", sources));
        // Trước bản sửa writer, lần apply này gãy ở bước tạo root permission: scope thứ hai
        // không được Keycloak gắn vào resource dù lời gọi cập nhật trả thành công.
        PolicyGraphWriter.AppliedGraph second =
                writer.apply(new PolicyGraph(target, "read", sources));
        assertEquals(first.resourceId(), second.resourceId());

        String root = authzRoot(http, base, adminToken);
        Map<String, Object> resource =
                get(http, root + "/resource/" + first.resourceId(), adminToken);
        java.util.Set<String> scopeNames = new java.util.TreeSet<>();
        List<?> resourceScopes = java.util.Objects.requireNonNull((List<?>) resource.get("scopes"));
        for (Object item : resourceScopes) {
            scopeNames.add(String.valueOf(((Map<?, ?>) item).get("name")));
        }
        assertEquals(java.util.Set.of("enter", "read"), scopeNames);
        assertTrue(writer.isGraphIntact(target, "enter", first.resourceId(), first.policyNames()));
        assertTrue(writer.isGraphIntact(target, "read", second.resourceId(), second.policyNames()));

        var subject = UsernamePasswordAuthenticationToken.authenticated(USER, null, List.of());
        var repository = new HttpSessionOAuth2AuthorizedClientRepository();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setSession(new MockHttpSession());
        ClientRegistration webRegistration =
                ClientRegistration.withRegistrationId("keycloak")
                        .clientId("native-gate-test")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationUri(base + "/realms/giaokhoa/protocol/openid-connect/auth")
                        .tokenUri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .redirectUri("http://127.0.0.1:18895/callback")
                        .build();
        OAuth2AccessToken webToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        userToken,
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        repository.saveAuthorizedClient(
                new OAuth2AuthorizedClient(webRegistration, USER, webToken),
                subject,
                servletRequest,
                new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(servletRequest, new MockHttpServletResponse()));
        KeycloakDecisionClient decision =
                new KeycloakDecisionClient(
                        repository,
                        new InMemoryClientRegistrationRepository(webRegistration),
                        AUDIENCE);
        assertTrue(
                decision.isAllowed(
                        subject,
                        servletRequest,
                        RequiredPermissions.of(
                                RequiredPermissions.require(first.resourceId(), "enter"))));
        assertTrue(
                decision.isAllowed(
                        subject,
                        servletRequest,
                        RequiredPermissions.of(
                                RequiredPermissions.require(second.resourceId(), "read"))));
    }

    private static String serviceToken(RestClient http, String base, String secret) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", CLIENT_ID);
        form.add("client_secret", secret);
        Map<String, Object> response =
                http.post()
                        .uri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return String.valueOf(java.util.Objects.requireNonNull(response).get("access_token"));
    }

    private static String authzRoot(RestClient http, String base, String token) {
        List<Map<String, Object>> clients =
                http.get()
                        .uri(base + "/admin/realms/giaokhoa/clients?clientId=" + AUDIENCE)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertNotNull(clients);
        assertEquals(1, clients.size());
        return base
                + "/admin/realms/giaokhoa/clients/"
                + clients.getFirst().get("id")
                + "/authz/resource-server";
    }

    private static String policyId(RestClient http, String root, String token, String name) {
        List<Map<String, Object>> policies =
                http.get()
                        .uri(root + "/policy")
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertNotNull(policies);
        return policies.stream()
                .filter(policy -> name.equals(policy.get("name")))
                .map(policy -> String.valueOf(policy.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> get(RestClient http, String url, String token) {
        Map<String, Object> body =
                http.get()
                        .uri(url)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return java.util.Objects.requireNonNull(body);
    }

    private static void put(
            RestClient http, String url, String token, Map<String, Object> payload) {
        http.put()
                .uri(url)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private static String required(String name) {
        return java.util.Objects.requireNonNull(
                System.getenv(name), "Run with infra/keycloak/verify-native.sh: " + name);
    }
}
