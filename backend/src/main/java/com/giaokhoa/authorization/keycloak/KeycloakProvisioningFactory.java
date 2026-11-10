package com.giaokhoa.authorization.keycloak;

import org.jspecify.annotations.Nullable;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** Điểm dùng chung để suy realm và dựng manager cho credential provisioner của Keycloak. */
public final class KeycloakProvisioningFactory {
    private KeycloakProvisioningFactory() {}

    /**
     * Realm là segment cuối của issuer URI OIDC đã cấu hình — nguồn duy nhất app tin cho cả tên
     * realm production lẫn realm TEST.
     */
    public static String realmOf(@Nullable String issuerUri) {
        if (issuerUri == null) {
            throw new IllegalStateException("Keycloak issuer URI has no realm: null");
        }
        String marker = "/realms/";
        int start = issuerUri.lastIndexOf(marker);
        String realm =
                start < 0 ? "" : issuerUri.substring(start + marker.length()).replaceAll("/+$", "");
        if (realm.isBlank() || realm.contains("/")) {
            throw new IllegalStateException("Keycloak issuer URI has no realm: " + issuerUri);
        }
        return realm;
    }

    /**
     * Interceptor gắn Bearer token provisioner vào mọi request Admin API qua manager của framework,
     * thay cho việc mỗi điểm gọi tự gắn header tay. Registration và principal đều cố định là
     * provisioner (không có user trong ngữ cảnh gọi này) nên không đọc SecurityContext; manager tự
     * xin mới token khi hết hạn.
     */
    public static ClientHttpRequestInterceptor provisionerInterceptor(
            OAuth2AuthorizedClientManager manager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> "giaokhoa-provisioner");
        interceptor.setPrincipalResolver(
                request -> new UsernamePasswordAuthenticationToken("giaokhoa-provisioner", null));
        return interceptor;
    }

    public static OAuth2AuthorizedClientManager clientManager(
            String baseUrl, String realm, String clientId, String secret) {
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("giaokhoa-provisioner")
                        .clientId(clientId)
                        .clientSecret(secret)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(baseUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                        .build();
        InMemoryClientRegistrationRepository repository =
                new InMemoryClientRegistrationRepository(registration);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        repository, new InMemoryOAuth2AuthorizedClientService(repository));
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }
}
