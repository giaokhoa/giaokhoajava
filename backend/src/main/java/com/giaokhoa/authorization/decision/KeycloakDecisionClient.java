package com.giaokhoa.authorization.decision;

import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.shared.http.KeycloakRequestFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client quyết định UMA cho user của phiên. Mỗi lần gọi chỉ hỏi Keycloak về đúng một cặp
 * resource/scope bằng token của chính user; service account không bao giờ đánh giá thay user, và
 * một allow không bao giờ được cache hay dùng lại cho request sau. Authorized client manager làm
 * mới access token đã hết hạn từ refresh token của phiên thay vì từ chối nó.
 */
@Component
public class KeycloakDecisionClient {
    private static final String REGISTRATION_ID = "keycloak";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:uma-ticket";
    private static final String FAILURE_DETAIL = "Không xác minh được quyết định quyền.";

    private final RestClient http;
    private final OAuth2AuthorizedClientManager manager;
    private final String tokenUrl;
    private final String audience;

    @Autowired
    public KeycloakDecisionClient(
            OAuth2AuthorizedClientRepository authorizedClients,
            ClientRegistrationRepository registrations,
            @Value("${giaokhoa.keycloak.authorization-audience}") String audience) {
        this(
                RestClient.builder().requestFactory(KeycloakRequestFactory.create()),
                sessionClientManager(registrations, authorizedClients),
                tokenEndpoint(registrations),
                audience);
    }

    KeycloakDecisionClient(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager manager,
            String tokenUrl,
            String audience) {
        this.http = builder.build();
        this.manager = manager;
        this.tokenUrl = tokenUrl;
        this.audience = audience;
    }

    private static OAuth2AuthorizedClientManager sessionClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .build());
        return manager;
    }

    private static String tokenEndpoint(ClientRegistrationRepository registrations) {
        ClientRegistration registration = registrations.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            throw new IllegalStateException(
                    "Missing OAuth2 client registration: " + REGISTRATION_ID);
        }
        return registration.getProviderDetails().getTokenUri();
    }

    /** Chỉ true khi Keycloak cho phép mọi cặp bắt buộc đối với user của phiên. */
    public boolean isAllowed(
            Authentication authentication,
            HttpServletRequest request,
            RequiredPermissions required) {
        String accessToken = userAccessToken(authentication, request);
        for (RequiredPermissions.Permission permission : required.permissions()) {
            if (!decide(accessToken, permission)) {
                return false;
            }
        }
        return true;
    }

    private String userAccessToken(
            @Nullable Authentication authentication, HttpServletRequest request) {
        if (authentication == null) {
            throw unverifiable(null);
        }
        OAuth2AuthorizedClient client;
        try {
            client =
                    manager.authorize(
                            OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                                    .principal(authentication)
                                    .attribute(HttpServletRequest.class.getName(), request)
                                    .build());
        } catch (OAuth2AuthorizationException ex) {
            throw unverifiable(ex);
        }
        if (client == null
                || !client.getPrincipalName().equals(authentication.getName())
                || client.getAccessToken() == null) {
            throw unverifiable(null);
        }
        return client.getAccessToken().getTokenValue();
    }

    private boolean decide(String accessToken, RequiredPermissions.Permission permission) {
        Map<String, Object> answer;
        try {
            answer =
                    http.post()
                            .uri(tokenUrl)
                            .headers(headers -> headers.setBearerAuth(accessToken))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form(permission))
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                return false;
            }
            throw unverifiable(ex);
        } catch (RestClientException ex) {
            throw unverifiable(ex);
        }
        if (answer == null || !(answer.get("result") instanceof Boolean decision)) {
            throw unverifiable(null);
        }
        return decision;
    }

    private MultiValueMap<String, String> form(RequiredPermissions.Permission permission) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", GRANT_TYPE);
        form.add("audience", audience);
        form.add("permission", permission.resourceId() + "#" + permission.scope());
        form.add("response_mode", "decision");
        return form;
    }

    private static HttpProblemException unverifiable(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.SERVICE_UNAVAILABLE, "DECISION_UNVERIFIABLE", FAILURE_DETAIL, cause);
    }
}
