package com.giaokhoa.identity.keycloak;

import com.giaokhoa.authorization.keycloak.KeycloakProvisionerFactory;
import com.giaokhoa.shared.http.KeycloakRequestFactory;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Tạo và xoá user trên Keycloak bằng credential provisioner nội bộ, phục vụ luồng cấp danh tính
 * mới. Tạo user kèm credential password tạm (temporary=true) khiến Keycloak tự gắn required action
 * UPDATE_PASSWORD cho lần đăng nhập đầu. Client chỉ thực thi lệnh Admin API và truyền lỗi lên trên;
 * quyết định nghiệp vụ (trùng username, reconcile sau lỗi) thuộc workflow gọi nó.
 */
@Component
public class IdentityProvisioningClient {
    private final RestClient http;
    private final String baseUrl;
    private final String realm;
    private final String webClientId;
    private final OAuth2AuthorizedClientManager manager;

    @Autowired
    public IdentityProvisioningClient(
            @Value("${giaokhoa.keycloak.admin-base-url}") String baseUrl,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                    String issuerUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
                    String webClientId,
            @Value("${giaokhoa.keycloak.provisioner.client-id}") String clientId,
            @Value("${giaokhoa.keycloak.provisioner.client-secret}") String clientSecret) {
        this(
                RestClient.builder().requestFactory(KeycloakRequestFactory.create()),
                baseUrl,
                KeycloakProvisionerFactory.realmOf(issuerUri),
                webClientId,
                KeycloakProvisionerFactory.clientManager(
                        baseUrl.replaceAll("/+$", ""),
                        KeycloakProvisionerFactory.realmOf(issuerUri),
                        clientId,
                        clientSecret));
    }

    IdentityProvisioningClient(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            String webClientId,
            OAuth2AuthorizedClientManager manager) {
        this.http = builder.build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.webClientId = webClientId;
        this.manager = manager;
    }

    /**
     * Tạo user mới duy nhất theo username và trả về Keycloak user ID lấy từ Location của response
     * 201. Username đã tồn tại khiến Keycloak trả 409 và lỗi được truyền nguyên lên caller; client
     * không tự tra cứu hay nhận nuôi user đã có.
     */
    public String createUser(
            String username, String displayName, @Nullable String email, String temporaryPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", displayName);
        body.put("emailVerified", false);
        if (email != null) {
            body.put("email", email);
        }
        body.put(
                "credentials",
                List.of(Map.of("type", "password", "value", temporaryPassword, "temporary", true)));
        ResponseEntity<Void> response =
                http.post()
                        .uri(endpoint("users"))
                        .headers(headers -> headers.setBearerAuth(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Xoá hẳn user theo Keycloak ID; dùng làm bước reconcile khi cấp danh tính thất bại giữa chừng.
     */
    public void deleteUser(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new IllegalArgumentException("Keycloak user ID is required");
        }
        http.delete()
                .uri(endpoint("users", keycloakUserId))
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Gửi email xác minh địa chỉ email cho user hiện hữu. Keycloak tự sinh liên kết hành động và
     * thời hạn của nó; backend không giữ, không lộ bất kỳ token nào của luồng xác minh.
     */
    public void sendVerifyEmail(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new IllegalArgumentException("Keycloak user ID is required");
        }
        URI uri =
                UriComponentsBuilder.fromUriString(baseUrl)
                        .pathSegment(
                                "admin",
                                "realms",
                                realm,
                                "users",
                                keycloakUserId,
                                "send-verify-email")
                        .queryParam("client_id", webClientId)
                        .build()
                        .encode()
                        .toUri();
        http.put()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private URI endpoint(String... segments) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(baseUrl).pathSegment("admin", "realms", realm);
        for (String segment : segments) {
            builder.pathSegment(segment);
        }
        return builder.build().encode().toUri();
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
