package com.giaokhoa.authorization.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Chạy qua infra/keycloak/verify-native.sh trên Keycloak 26.8.1 dùng một lần của nó. */
class KeycloakProvisioningClientNativeIT {
    @Test
    void provisionsOrganizationGroupAndMembershipUsingRealClientCredentials() {
        String base = System.getenv("PROVISIONING_TEST_KEYCLOAK_BASE_URL");
        String secret = System.getenv("PROVISIONING_TEST_KEYCLOAK_PROVISIONER_SECRET");
        String userId = System.getenv("PROVISIONING_TEST_KEYCLOAK_USER_ID");
        assertNotNull(base, "Run with infra/keycloak/verify-native.sh");
        assertNotNull(secret);
        assertNotNull(userId);

        KeycloakProvisioningClient client =
                new KeycloakProvisioningClient(
                        RestClient.builder(), base, "giaokhoa-provisioner", secret);
        // ID giữ cố định: chạy lại verify-native.sh phải hội tụ trên cùng organization nhờ
        // ensureOrganization idempotent, không tạo organization rác mới ở mỗi lần chạy.
        UUID workspace = UUID.fromString("aaaabbbb-cccc-dddd-eeee-000000000010");
        String organizationId = client.ensureOrganization(workspace, "Native integration center");
        assertFalse(organizationId.isBlank());
        assertEquals(
                organizationId, client.ensureOrganization(workspace, "Native integration center"));

        String groupPath = "/gk/workspaces/" + workspace + "/members";
        String groupId = client.ensureRealmGroup(groupPath);
        assertFalse(groupId.isBlank());
        assertEquals(groupId, client.ensureRealmGroup(groupPath));

        RestClient verification = RestClient.create();
        String verificationToken = provisionerToken(verification, base, secret);
        client.setRealmGroupMembership(userId, groupPath, true);
        assertTrue(
                isMember(verification, base, verificationToken, userId, groupId),
                "Real Keycloak must reflect added membership");

        client.setRealmGroupMembership(userId, groupPath, false);
        assertFalse(
                isMember(verification, base, verificationToken, userId, groupId),
                "Real Keycloak must reflect revoked membership");
    }

    private String provisionerToken(RestClient http, String base, String secret) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "giaokhoa-provisioner");
        form.add("client_secret", secret);
        Map<String, Object> response =
                http.post()
                        .uri(base + "/realms/giaokhoa/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertNotNull(response);
        return java.util.Objects.requireNonNull(
                (String) response.get("access_token"), "access_token");
    }

    private boolean isMember(
            RestClient http, String base, String token, String userId, String groupId) {
        List<Map<String, Object>> groups =
                http.get()
                        .uri(base + "/admin/realms/giaokhoa/users/" + userId + "/groups")
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertNotNull(groups);
        return groups.stream().anyMatch(group -> groupId.equals(group.get("id")));
    }
}
