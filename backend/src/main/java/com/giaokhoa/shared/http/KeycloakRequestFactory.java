package com.giaokhoa.shared.http;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Request factory dùng chung cho mọi client Keycloak phía server: timeout kết nối 2 giây, timeout
 * đọc 5 giây, để một Keycloak treo không giữ thread và transaction chờ vô hạn.
 */
public final class KeycloakRequestFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private KeycloakRequestFactory() {}

    public static ClientHttpRequestFactory create() {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
