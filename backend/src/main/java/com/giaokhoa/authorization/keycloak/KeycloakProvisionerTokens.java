package com.giaokhoa.authorization.keycloak;

import com.giaokhoa.shared.config.GiaokhoaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/**
 * Thành phần dùng chung duy nhất giữ credential provisioner: mọi client Keycloak (directory,
 * policy graph, danh tính) lấy manager và realm tại đây thay vì mỗi client giữ một bản chép của
 * cùng đoạn code. Manager do {@link KeycloakProvisioningFactory} dựng một lần từ cấu hình đã bind;
 * Bearer gắn vào request qua interceptor của framework (xem {@link
 * KeycloakProvisioningFactory#provisionerInterceptor}), không gắn tay ở từng điểm gọi.
 */
@Component
public class KeycloakProvisionerTokens {
    private final String realm;
    private final OAuth2AuthorizedClientManager manager;

    @Autowired
    public KeycloakProvisionerTokens(
            GiaokhoaProperties properties,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                    String issuerUri) {
        this(
                KeycloakProvisioningFactory.realmOf(issuerUri),
                KeycloakProvisioningFactory.clientManager(
                        properties.keycloak().adminBaseUrl().replaceAll("/+$", ""),
                        KeycloakProvisioningFactory.realmOf(issuerUri),
                        properties.keycloak().provisioner().clientId(),
                        properties.keycloak().provisioner().clientSecret()));
    }

    private KeycloakProvisionerTokens(String realm, OAuth2AuthorizedClientManager manager) {
        this.realm = realm;
        this.manager = manager;
    }

    /** Realm suy ra từ issuer URI đã cấu hình. */
    public String realm() {
        return realm;
    }

    /** Manager provisioner đã dựng một lần; client gắn Bearer qua interceptor của nó. */
    public OAuth2AuthorizedClientManager manager() {
        return manager;
    }
}
