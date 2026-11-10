package com.giaokhoa.identity.security;

import com.giaokhoa.shared.config.GiaokhoaProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class OidcLogoutConfiguration {
    private static final int SIGNED_JWT_PARTS = 3;
    private static final JsonMapper LOGOUT_JSON = JsonMapper.builder().build();

    @Bean
    CookieSerializer sessionCookieSerializer(
            GiaokhoaProperties properties, Environment environment) {
        boolean secure = "https".equalsIgnoreCase(URI.create(properties.appOrigin()).getScheme());
        // Repo chưa chốt một tên profile production duy nhất, nên nhận cả hai tên thông dụng:
        // thiếu một tên là fail-fast này lặng lẽ không bao giờ kích hoạt.
        if (!secure && environment.acceptsProfiles(Profiles.of("prod | production"))) {
            throw new IllegalStateException(
                    "giaokhoa.app-origin must use https when the prod/production profile is"
                            + " active, but was: "
                            + properties.appOrigin());
        }
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        // Back-channel handler của Spring gửi session ID thô trong cookie SESSION.
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    @Bean
    FilterRegistrationBean<Filter> malformedBackChannelLogoutGuard() {
        // Spring Security 7.1.2 ném lỗi khi thiếu events/issuer thay vì trả 400.
        // Chỉ preflight hình dạng JWT chưa đáng tin; Spring xác minh chữ ký và claim.
        Filter guard =
                (request, response, chain) -> {
                    if (!"POST".equals(((HttpServletRequest) request).getMethod())) {
                        chain.doFilter(request, response);
                        return;
                    }
                    String token = ((HttpServletRequest) request).getParameter("logout_token");
                    if (!hasLogoutClaimShape(token)) {
                        ((HttpServletResponse) response)
                                .setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    }
                    chain.doFilter(request, response);
                };
        var registration = new FilterRegistrationBean<>(guard);
        registration.addUrlPatterns("/logout/connect/back-channel/keycloak");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static boolean hasLogoutClaimShape(String token) {
        if (token == null || token.length() > 32768) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != SIGNED_JWT_PARTS) {
            return false;
        }
        try {
            JsonNode payload = LOGOUT_JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return payload != null
                    && payload.isObject()
                    && payload.path("iss").isString()
                    && payload.path("events").isObject();
        } catch (IllegalArgumentException | JacksonException invalidJwt) {
            return false;
        }
    }

    @Bean
    OidcBackChannelLogoutHandler backChannelLogoutHandler(
            OidcSessionRegistry registry, @Value("${server.port:8080}") int serverPort) {
        var handler = new OidcBackChannelLogoutHandler(registry);
        handler.setSessionCookieName("SESSION");
        // Cổng gateway đến có thể khác cổng HTTP listener cục bộ của Spring.
        handler.setLogoutUri(
                "http://127.0.0.1:" + serverPort + "/logout/connect/back-channel/{registrationId}");
        return handler;
    }
}
