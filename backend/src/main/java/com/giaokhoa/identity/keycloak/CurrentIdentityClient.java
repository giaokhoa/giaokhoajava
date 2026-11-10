package com.giaokhoa.identity.keycloak;

import com.giaokhoa.authorization.keycloak.KeycloakProvisionerFactory;
import com.giaokhoa.shared.http.KeycloakRequestFactory;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Đọc profile danh tính hiện hành của một user từ Admin API của Keycloak bằng credential
 * provisioner nội bộ. Đây là input danh tính đáng tin cậy duy nhất của eligibility gate: không bao
 * giờ nhận email, trạng thái verified hay origin từ request của browser hoặc từ claim của token cũ.
 * Token quản trị chỉ đọc input danh tính, không evaluate quyền thay user. Lỗi đọc được truyền lên
 * trên để caller fail-closed.
 */
@Component
public class CurrentIdentityClient {
    private final RestClient http;
    private final String baseUrl;
    private final String realm;
    private final OAuth2AuthorizedClientManager manager;

    @Autowired
    public CurrentIdentityClient(
            @Value("${giaokhoa.keycloak.admin-base-url}") String baseUrl,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                    String issuerUri,
            @Value("${giaokhoa.keycloak.provisioner.client-id}") String clientId,
            @Value("${giaokhoa.keycloak.provisioner.client-secret}") String clientSecret) {
        this(
                RestClient.builder().requestFactory(KeycloakRequestFactory.create()),
                baseUrl,
                KeycloakProvisionerFactory.realmOf(issuerUri),
                KeycloakProvisionerFactory.clientManager(
                        baseUrl.replaceAll("/+$", ""),
                        KeycloakProvisionerFactory.realmOf(issuerUri),
                        clientId,
                        clientSecret));
    }

    CurrentIdentityClient(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            OAuth2AuthorizedClientManager manager) {
        this.http = builder.build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.manager = manager;
    }

    /** Profile Keycloak hiện hành của một user; Keycloak ID giữ nguyên dạng opaque. */
    public record CurrentIdentity(
            String id,
            String username,
            @Nullable String email,
            boolean emailVerified,
            boolean enabled) {}

    /** Đọc profile của đúng Keycloak user ID này; mọi lỗi đọc đều truyền lên trên. */
    public CurrentIdentity readCurrentIdentity(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new IllegalArgumentException("Keycloak user ID is required");
        }
        URI uri =
                UriComponentsBuilder.fromUriString(baseUrl)
                        .pathSegment("admin", "realms", realm, "users", keycloakUserId)
                        .build()
                        .encode()
                        .toUri();
        CurrentIdentity identity =
                http.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(accessToken()))
                        .retrieve()
                        .body(CurrentIdentity.class);
        if (identity == null) {
            throw new IllegalStateException("Empty Keycloak identity response");
        }
        return identity;
    }

    private String accessToken() {
        var client =
                manager.authorize(
                        OAuth2AuthorizeRequest.withClientRegistrationId("giaokhoa-provisioner")
                                .principal("giaokhoa-provisioner")
                                .build());
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("Keycloak provisioner cannot obtain an access token");
        }
        return client.getAccessToken().getTokenValue();
    }
}
