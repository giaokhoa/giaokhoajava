package com.giaokhoa.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Đăng nhập form OIDC Authorization Code + PKCE qua Keycloak TEST cho client web của ứng dụng — bản
 * dùng chung thay các bản sao trước đây ở IdentityFlowE2EIT và NativeRevocationFixture. Chỉ chấp
 * nhận đúng client BFF của stack TEST; không dùng Direct Access Grants cho phiên web.
 */
public final class OidcFormLogin {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern LOGIN_FORM =
            Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private OidcFormLogin() {}

    /** Bộ ba token của một phiên web đã đăng nhập thành công. */
    public record WebTokens(String accessToken, String idToken, String refreshToken) {}

    public static WebTokens webTokensViaCodePkce(
            RestClient http,
            String base,
            String realm,
            String redirectUri,
            String username,
            String password) {
        String client = IntegrationEnvironment.required("TEST_OIDC_CLIENT_ID");
        if (!"giaokhoa-it-web".equals(client)) {
            throw new IllegalArgumentException("Only TEST BFF client can be used");
        }
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String challenge;
        try {
            challenge =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("PKCE requires SHA-256", ex);
        }
        String state = UUID.randomUUID().toString();
        String url =
                base
                        + "/realms/"
                        + realm
                        + "/protocol/openid-connect/auth?client_id="
                        + encode(client)
                        + "&redirect_uri="
                        + encode(redirectUri)
                        + "&response_type=code&scope=openid&state="
                        + encode(state)
                        + "&nonce="
                        + UUID.randomUUID()
                        + "&code_challenge="
                        + encode(challenge)
                        + "&code_challenge_method=S256";
        try (HttpClient browser =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager())
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            var loginPage =
                    browser.send(
                            HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loginPage.statusCode(), "Expected native Keycloak login form");
            String pageBody = loginPage.body();
            // Cookie Secure của TEST Keycloak chỉ được trả về đúng login action loopback này
            // nên gom cookie thủ công theo tên như cookie jar của trình duyệt.
            Map<String, String> cookieJar = new LinkedHashMap<>();
            collectCookies(cookieJar, loginPage);
            if (!pageBody.contains("name=\"password\"")) {
                // Realm đã có organization thì Keycloak 26 chuyển sang đăng nhập
                // identity-first: gửi username trước, password ở bước sau.
                var usernameStep =
                        browser.send(
                                HttpRequest.newBuilder(
                                                URI.create(loginFormAction(base, realm, pageBody)))
                                        .header("Cookie", cookieHeader(cookieJar))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "username=" + encode(username)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(
                        200, usernameStep.statusCode(), "Username step must render password form");
                pageBody = usernameStep.body();
                collectCookies(cookieJar, usernameStep);
            }
            var loginResponse =
                    browser.send(
                            HttpRequest.newBuilder(
                                            URI.create(loginFormAction(base, realm, pageBody)))
                                    .header("Cookie", cookieHeader(cookieJar))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "username="
                                                            + encode(username)
                                                            + "&password="
                                                            + encode(password)
                                                            + "&credentialId="))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(302, loginResponse.statusCode(), "Native Code login must redirect");
            URI redirected =
                    URI.create(loginResponse.headers().firstValue("location").orElseThrow());
            if (!redirectUri.equals(
                    redirected.getScheme()
                            + "://"
                            + redirected.getHost()
                            + ":"
                            + redirected.getPort()
                            + redirected.getPath())) {
                throw new IllegalStateException("Unexpected TEST OIDC redirect target");
            }
            Map<String, String> params =
                    Arrays.stream(redirected.getRawQuery().split("&"))
                            .map(value -> value.split("=", 2))
                            .collect(
                                    Collectors.toMap(
                                            pair ->
                                                    URLDecoder.decode(
                                                            pair[0], StandardCharsets.UTF_8),
                                            pair ->
                                                    URLDecoder.decode(
                                                            pair[1], StandardCharsets.UTF_8)));
            assertEquals(state, params.get("state"));
            String code = Objects.requireNonNull(params.get("code"));
            var exchange = new LinkedMultiValueMap<String, String>();
            exchange.add("grant_type", "authorization_code");
            exchange.add("client_id", client);
            exchange.add(
                    "client_secret", IntegrationEnvironment.required("TEST_OIDC_CLIENT_SECRET"));
            exchange.add("redirect_uri", redirectUri);
            exchange.add("code", code);
            exchange.add("code_verifier", verifier);
            Map<String, Object> tokens =
                    http.post()
                            .uri(base + "/realms/" + realm + "/protocol/openid-connect/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(exchange)
                            .retrieve()
                            .body(MAP);
            assertNotNull(tokens);
            return new WebTokens(
                    Objects.requireNonNull((String) tokens.get("access_token"), "access_token"),
                    Objects.requireNonNull((String) tokens.get("id_token"), "id_token"),
                    Objects.requireNonNull((String) tokens.get("refresh_token"), "refresh_token"));
        } catch (IOException exception) {
            throw new IllegalStateException("Native TEST Code+PKCE login failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native TEST Code+PKCE login interrupted", exception);
        }
    }

    private static String loginFormAction(String base, String realm, String pageBody) {
        var form = LOGIN_FORM.matcher(pageBody);
        if (!form.find()) {
            throw new IllegalStateException("Native TEST Code login form not found");
        }
        String loginUrl = form.group(1).replace("&amp;", "&");
        if (!loginUrl.startsWith(base + "/realms/" + realm + "/login-actions/")) {
            throw new IllegalStateException("Unexpected TEST login form target");
        }
        return loginUrl;
    }

    private static void collectCookies(Map<String, String> jar, HttpResponse<?> response) {
        for (String header : response.headers().allValues("set-cookie")) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0) {
                jar.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        return jar.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
