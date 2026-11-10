package com.giaokhoa.identity.keycloak;

import com.giaokhoa.authorization.keycloak.KeycloakProvisionerTokens;
import com.giaokhoa.shared.config.GiaokhoaProperties;
import com.giaokhoa.shared.http.KeycloakRequestFactory;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public CurrentIdentityClient(GiaokhoaProperties properties, KeycloakProvisionerTokens tokens) {
        this(
                RestClient.builder().requestFactory(KeycloakRequestFactory.create()),
                properties.keycloak().adminBaseUrl(),
                tokens.realm(),
                tokens.manager());
    }

    CurrentIdentityClient(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            OAuth2AuthorizedClientManager manager) {
        this.http =
                builder.requestInterceptor(
                                KeycloakProvisionerFactory.provisionerInterceptor(manager))
                        .build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
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
        CurrentIdentity identity = http.get().uri(uri).retrieve().body(CurrentIdentity.class);
        if (identity == null) {
            throw new IllegalStateException("Empty Keycloak identity response");
        }
        return identity;
    }
}
