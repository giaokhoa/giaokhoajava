package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.shared.config.GiaokhoaProperties;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

class OidcLogoutConfigurationTest {

    @DisplayName(
            "Session cookie cho HTTPS là host-only, HttpOnly, Secure, SameSite=Lax và dùng raw session id")
    @Test
    void sessionCookieSecurityAttributes() {
        CookieSerializer serializer =
                new OidcLogoutConfiguration()
                        .sessionCookieSerializer(
                                properties("https://app.example.test"), environment());
        var request = new MockHttpServletRequest();
        request.setServerName("app.example.test");
        var response = new MockHttpServletResponse();

        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "id-123"));
        String header = Objects.requireNonNull(response.getHeader("Set-Cookie"), "Set-Cookie");
        assertTrue(header.startsWith("SESSION=id-123;"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));
        assertFalse(header.contains("Domain="));

        var inbound = new MockHttpServletRequest();
        inbound.setCookies(new Cookie("SESSION", "id-123"));
        assertEquals(List.of("id-123"), serializer.readCookieValues(inbound));
    }

    @DisplayName("Claims back-channel sai định dạng không thể tới được handler native của Spring")
    @Test
    void malformedClaimsBlockedEarly() throws Exception {
        var guard =
                java.util.Objects.requireNonNull(
                        new OidcLogoutConfiguration()
                                .malformedBackChannelLogoutGuard()
                                .getFilter());
        var request = new MockHttpServletRequest("POST", "/logout/connect/back-channel/keycloak");
        request.addParameter("logout_token", encodedJwt("{\"iss\":\"https://issuer\"}"));
        var response = new MockHttpServletResponse();
        var downstream = new MockFilterChain();
        guard.doFilter(request, response, downstream);
        assertEquals(400, response.getStatus());
        assertNull(downstream.getRequest());
    }

    @DisplayName("Claims back-channel hợp lệ tới được handler native của Spring")
    @Test
    void validClaimsReachNativeHandler() throws Exception {
        var guard =
                java.util.Objects.requireNonNull(
                        new OidcLogoutConfiguration()
                                .malformedBackChannelLogoutGuard()
                                .getFilter());
        var valid = new MockHttpServletRequest("POST", "/logout/connect/back-channel/keycloak");
        valid.addParameter(
                "logout_token",
                encodedJwt(
                        "{\"iss\":\"https://issuer\",\"events\":"
                                + "{\"http://schemas.openid.net/event/backchannel-logout\":{}}}"));
        var passed = new MockFilterChain();
        guard.doFilter(valid, new MockHttpServletResponse(), passed);
        assertSame(valid, passed.getRequest());
    }

    @Nested
    @DisplayName("Khi profile production đang hoạt động")
    class ProductionProfile {

        @DisplayName(
                "Origin http bị từ chối ngay khi tạo cookie serializer, với cả hai tên profile"
                        + " prod và production, để ứng dụng không khởi động được")
        @Test
        void httpOriginRejected() {
            var configuration = new OidcLogoutConfiguration();
            for (String profile : List.of("prod", "production")) {
                IllegalStateException failure =
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        configuration.sessionCookieSerializer(
                                                properties("http://app.example.test"),
                                                environment(profile)));
                String message = Objects.requireNonNull(failure.getMessage(), "exception message");
                assertTrue(message.contains("https"));
                assertTrue(message.contains("http://app.example.test"));
            }
        }

        @DisplayName("Origin https vẫn tạo được cookie serializer và cookie mang cờ Secure")
        @Test
        void httpsOriginAccepted() {
            CookieSerializer serializer =
                    new OidcLogoutConfiguration()
                            .sessionCookieSerializer(
                                    properties("https://app.example.test"), environment("prod"));
            var response = new MockHttpServletResponse();
            serializer.writeCookieValue(
                    new CookieSerializer.CookieValue(
                            new MockHttpServletRequest(), response, "prod-id"));
            String header = Objects.requireNonNull(response.getHeader("Set-Cookie"), "Set-Cookie");
            assertTrue(header.startsWith("SESSION=prod-id;"));
            assertTrue(header.contains("Secure"));
        }
    }

    private static MockEnvironment environment(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    /**
     * Chỉ appOrigin được method đọc; phần Keycloak không được tiêu dùng nên để giá trị trung tính.
     */
    private static GiaokhoaProperties properties(String appOrigin) {
        return new GiaokhoaProperties(
                appOrigin,
                new GiaokhoaProperties.Keycloak(
                        "http://localhost",
                        "test-audience",
                        new GiaokhoaProperties.Keycloak.Provisioner("test-client", "test-secret")));
    }

    private static String encodedJwt(String claims) {
        return "eyJhbGciOiJSUzI1NiJ9."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(claims.getBytes(StandardCharsets.UTF_8))
                + ".signature";
    }

    @DisplayName("Ngoài profile production, origin http trên máy local vẫn tạo cookie không Secure")
    @Test
    void localHttpSessionCookieDoesNotRequireTls() {
        CookieSerializer serializer =
                new OidcLogoutConfiguration()
                        .sessionCookieSerializer(
                                properties("http://localhost:3000"), environment());
        var response = new MockHttpServletResponse();
        serializer.writeCookieValue(
                new CookieSerializer.CookieValue(
                        new MockHttpServletRequest(), response, "local-id"));
        String header = Objects.requireNonNull(response.getHeader("Set-Cookie"), "Set-Cookie");
        assertTrue(header.startsWith("SESSION=local-id;"));
        assertTrue(header.contains("HttpOnly"));
        assertFalse(header.contains("Secure"));
        assertFalse(header.contains("Domain="));
    }
}
