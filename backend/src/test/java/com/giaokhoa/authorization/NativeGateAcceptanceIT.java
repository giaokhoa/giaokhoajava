package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.decision.KeycloakDecisionClient;
import com.giaokhoa.authorization.decision.RequiredPermissions;
import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraph.Gate;
import com.giaokhoa.authorization.policy.PolicyGraph.Source;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Nghiệm thu gate native trên realm dùng một lần của verify-native.sh: dữ kiện semantic được
 * provision qua chính PolicyGraphWriter production và quyết định qua chính KeycloakDecisionClient
 * production, với actor đăng nhập thật bằng Authorization Code + PKCE qua client native-gate-test.
 * Chỉ chạy khi PROVISIONING_TEST_KEYCLOAK_BASE_URL được đặt bởi script native.
 */
@EnabledIfEnvironmentVariable(named = "PROVISIONING_TEST_KEYCLOAK_BASE_URL", matches = ".+")
class NativeGateAcceptanceIT {
    private static final String PROVISIONER_CLIENT = "giaokhoa-provisioner";
    private static final String AUDIENCE = "giaokhoa-api";
    private static final String REALM = "giaokhoa";
    private static final String LOGIN_CLIENT = "native-gate-test";
    private static final String REDIRECT_URI = "http://127.0.0.1:18895/callback";
    private static final String ACTOR_PASSWORD = "acceptance-only-password-Aa1";
    private static final String TEMPORARY_PASSWORD = "temporary-only-password-Aa1";
    private static final Pattern LOGIN_FORM =
            Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern UPDATE_PASSWORD_FORM =
            Pattern.compile("<form[^>]*id=\"kc-passwd-update-form\"[^>]*action=\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private String base;
    private RestClient http;
    private String adminToken;
    private PolicyGraphWriter writer;
    private KeycloakProvisioningClient directory;
    private ClientRegistration webRegistration;

    @BeforeEach
    void setUp() {
        base = required("PROVISIONING_TEST_KEYCLOAK_BASE_URL");
        String secret = required("PROVISIONING_TEST_KEYCLOAK_PROVISIONER_SECRET");
        http = RestClient.create();
        adminToken = provisionerToken(secret);
        // Constructor production: writer tự dựng provisioner manager từ client credentials.
        // Coordinator khóa phân tán qua PostgreSQL của ứng dụng không tồn tại trong realm dùng
        // một lần này; gate chạy một tiến trình tuần tự nên dùng coordinator truyền thẳng,
        // không đổi semantics của writer hay của PDP.
        ResourceMutationCoordinator directCoordinator =
                new ResourceMutationCoordinator(
                        new JdbcTemplate(), new DataSourceTransactionManager()) {
                    @Override
                    public <T> T withResourceLock(
                            String targetKey, java.util.function.Supplier<T> action) {
                        return action.get();
                    }
                };
        writer =
                new PolicyGraphWriter(
                        base,
                        base + "/realms/" + REALM,
                        PROVISIONER_CLIENT,
                        secret,
                        AUDIENCE,
                        directCoordinator);
        directory =
                new KeycloakProvisioningClient(
                        RestClient.builder(), base, PROVISIONER_CLIENT, secret);
        webRegistration =
                ClientRegistration.withRegistrationId("keycloak")
                        .clientId(LOGIN_CLIENT)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationUri(
                                base + "/realms/" + REALM + "/protocol/openid-connect/auth")
                        .tokenUri(base + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .redirectUri(REDIRECT_URI)
                        .build();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Một actor đã tạo trong realm dùng một lần, kèm access token lấy bằng Code + PKCE thật. */
    private record Actor(String username, String userId, String accessToken) {}

    /** Một user đã tạo trong realm dùng một lần, chưa đăng nhập. */
    private record CreatedUser(String username, String userId) {}

    private CreatedUser createUser(
            String label,
            boolean withEmail,
            boolean emailVerified,
            boolean withNames,
            String password,
            boolean temporaryPassword,
            List<String> requiredActions) {
        String username =
                "acceptance-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        if (withEmail) {
            body.put("email", username + "@example.test");
            body.put("emailVerified", emailVerified);
        }
        if (withNames) {
            body.put("firstName", "Acceptance");
            body.put("lastName", "Gate");
        }
        body.put("enabled", true);
        body.put("requiredActions", requiredActions);
        body.put(
                "credentials",
                List.of(
                        Map.of(
                                "type", "password",
                                "value", password,
                                "temporary", temporaryPassword)));
        http.post()
                .uri(base + "/admin/realms/" + REALM + "/users")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        List<Map<String, Object>> found =
                http.get()
                        .uri(
                                base
                                        + "/admin/realms/"
                                        + REALM
                                        + "/users?username="
                                        + username
                                        + "&exact=true")
                        .headers(headers -> headers.setBearerAuth(adminToken))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertNotNull(found);
        assertEquals(1, found.size());
        return new CreatedUser(username, String.valueOf(found.getFirst().get("id")));
    }

    private Actor newActor(String label, boolean withEmail, boolean emailVerified) {
        CreatedUser created =
                createUser(label, withEmail, emailVerified, true, ACTOR_PASSWORD, false, List.of());
        return new Actor(
                created.username(),
                created.userId(),
                loginToken(created.username(), ACTOR_PASSWORD));
    }

    private void joinGroup(Actor actor, String groupPath) {
        joinGroup(actor.userId(), groupPath);
    }

    private void joinGroup(CreatedUser user, String groupPath) {
        joinGroup(user.userId(), groupPath);
    }

    private void joinGroup(String userId, String groupPath) {
        directory.ensureRealmGroup(groupPath);
        directory.setRealmGroupMembership(userId, groupPath, true);
    }

    private void joinEligible(Actor actor) {
        joinEligible(actor.userId());
    }

    private void joinEligible(CreatedUser user) {
        joinEligible(user.userId());
    }

    private void joinEligible(String userId) {
        directory.setRealmGroupMembership(userId, NativePolicyTemplates.ELIGIBILITY_GROUP, true);
    }

    private String applyResource(PolicyGraph graph) {
        return writer.apply(graph).resourceId();
    }

    private boolean allowed(Actor actor, String resourceId, String scope) {
        var subject =
                UsernamePasswordAuthenticationToken.authenticated(
                        actor.username(), null, List.of());
        var repository = new HttpSessionOAuth2AuthorizedClientRepository();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        OAuth2AccessToken token =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        actor.accessToken(),
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        repository.saveAuthorizedClient(
                new OAuth2AuthorizedClient(webRegistration, actor.username(), token),
                subject,
                request,
                new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
        try {
            KeycloakDecisionClient decision =
                    new KeycloakDecisionClient(
                            repository,
                            new InMemoryClientRegistrationRepository(webRegistration),
                            AUDIENCE);
            return decision.isAllowed(
                    subject,
                    request,
                    RequiredPermissions.of(RequiredPermissions.require(resourceId, scope)));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** Kết quả một vòng đăng nhập form: nơi Keycloak chuyển tới sau bước password. */
    private record LoginWalk(
            URI location, Map<String, String> cookieJar, String state, String verifier) {}

    /**
     * Đi hết form đăng nhập thật của Keycloak bằng Authorization Code + PKCE S256, không password
     * grant, và dừng ở đích chuyển hướng sau bước password: đích có thể là redirect URI đã đăng ký
     * hoặc một required action, tùy trạng thái account.
     */
    private LoginWalk loginWalk(String username, String password) {
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
                        + REALM
                        + "/protocol/openid-connect/auth?client_id="
                        + encode(LOGIN_CLIENT)
                        + "&redirect_uri="
                        + encode(REDIRECT_URI)
                        + "&response_type=code&scope=openid&state="
                        + encode(state)
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
            // Keycloak phát hành cookie Secure ngay cả trên HTTP loopback nên CookieManager
            // không gửi lại qua HTTP; gom cookie thủ công theo tên như một cookie jar của
            // trình duyệt, chỉ trả về đúng login action của realm dùng một lần này.
            Map<String, String> cookieJar = new LinkedHashMap<>();
            collectCookies(cookieJar, loginPage);
            if (!pageBody.contains("name=\"password\"")) {
                // Keycloak 26 tách form thành hai bước: gửi username trước, password ở bước sau.
                var stepOne =
                        browser.send(
                                HttpRequest.newBuilder(URI.create(formAction(pageBody)))
                                        .header("Cookie", cookieHeader(cookieJar))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "username=" + encode(username)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, stepOne.statusCode(), "Username step must render password form");
                pageBody = stepOne.body();
                collectCookies(cookieJar, stepOne);
            }
            var loginResponse =
                    browser.send(
                            HttpRequest.newBuilder(URI.create(formAction(pageBody)))
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
            return new LoginWalk(redirected, cookieJar, state, verifier);
        } catch (IOException ex) {
            throw new IllegalStateException("Native Code+PKCE login failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native Code+PKCE login interrupted", ex);
        }
    }

    /** Access token chỉ được cấp khi vòng đăng nhập kết thúc đúng tại redirect URI đã đăng ký. */
    private String loginToken(String username, String password) {
        return exchangeCode(loginWalk(username, password));
    }

    private String exchangeCode(LoginWalk walk) {
        URI redirected = walk.location();
        assertTrue(
                redirected.toString().startsWith(REDIRECT_URI + "?"),
                "Login must reach the registered redirect, not a required action: " + redirected);
        Map<String, String> params =
                Arrays.stream(redirected.getRawQuery().split("&"))
                        .map(value -> value.split("=", 2))
                        .collect(
                                Collectors.toMap(
                                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                                        pair ->
                                                URLDecoder.decode(
                                                        pair[1], StandardCharsets.UTF_8)));
        assertEquals(walk.state(), params.get("state"));
        String code = Objects.requireNonNull(params.get("code"));
        var exchange = new LinkedMultiValueMap<String, String>();
        exchange.add("grant_type", "authorization_code");
        exchange.add("client_id", LOGIN_CLIENT);
        exchange.add("redirect_uri", REDIRECT_URI);
        exchange.add("code", code);
        exchange.add("code_verifier", walk.verifier());
        Map<String, Object> tokens =
                http.post()
                        .uri(base + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(exchange)
                        .retrieve()
                        .body(MAP);
        assertNotNull(tokens);
        return Objects.requireNonNull((String) tokens.get("access_token"), "access_token");
    }

    /** Hoàn tất required action đổi mật khẩu (mật khẩu tạm lúc cấp account) ngay trong phiên. */
    private LoginWalk submitUpdatePassword(LoginWalk walk, String newPassword) {
        try (HttpClient browser =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager())
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            Map<String, String> jar = new LinkedHashMap<>(walk.cookieJar());
            var page =
                    browser.send(
                            HttpRequest.newBuilder(walk.location())
                                    .header("Cookie", cookieHeader(jar))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), "Update password form must render");
            collectCookies(jar, page);
            var form = UPDATE_PASSWORD_FORM.matcher(page.body());
            if (!form.find()) {
                throw new IllegalStateException("Update password form not found");
            }
            String action = form.group(1).replace("&amp;", "&");
            var response =
                    browser.send(
                            HttpRequest.newBuilder(URI.create(action))
                                    .header("Cookie", cookieHeader(jar))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "password-new="
                                                            + encode(newPassword)
                                                            + "&password-confirm="
                                                            + encode(newPassword)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(302, response.statusCode(), "Update password must finish the login flow");
            URI redirected = URI.create(response.headers().firstValue("location").orElseThrow());
            return new LoginWalk(redirected, jar, walk.state(), walk.verifier());
        } catch (IOException ex) {
            throw new IllegalStateException("Native update password step failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native update password step interrupted", ex);
        }
    }

    /**
     * Ba môn, nhiều giáo viên: giáo viên chỉ track môn mình dạy, học sinh chỉ đọc môn đã ghi danh,
     * homeroom giao bài và track mọi môn của lớp, phụ huynh chỉ đọc bản published của đúng con
     * mình.
     */
    @Test
    void classRosterSemanticsAcrossSubjectsHomeroomAndParent() {
        UUID workspace = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID subjectOne = UUID.randomUUID();
        UUID subjectTwo = UUID.randomUUID();
        UUID subjectThree = UUID.randomUUID();
        Actor teacherOne = newActor("teacher-one", true, true);
        Actor teacherTwo = newActor("teacher-two", true, true);
        Actor student = newActor("student", true, true);
        Actor otherStudent = newActor("other-student", true, true);
        Actor homeroom = newActor("homeroom", true, true);
        Actor parent = newActor("parent", true, true);
        for (Actor actor :
                List.of(teacherOne, teacherTwo, student, otherStudent, homeroom, parent)) {
            joinEligible(actor);
        }
        String teachersOne =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subjectOne);
        String teachersTwo =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subjectTwo);
        String teachersThree =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subjectThree);
        String studentsTwo =
                NativePolicyTemplates.courseSubjectStudentsGroup(workspace, subjectTwo);
        String homeroomGroup = NativePolicyTemplates.homeroomGroup(workspace, classId);
        String guardians =
                NativePolicyTemplates.guardiansGroup(workspace, UUID.fromString(student.userId()));
        String otherGuardians =
                NativePolicyTemplates.guardiansGroup(
                        workspace, UUID.fromString(otherStudent.userId()));
        joinGroup(teacherOne, teachersOne);
        joinGroup(teacherOne, teachersTwo);
        joinGroup(teacherTwo, teachersThree);
        joinGroup(student, studentsTwo);
        joinGroup(homeroom, homeroomGroup);
        joinGroup(parent, guardians);
        joinGroup(newActor("other-parent", true, true), otherGuardians);

        String keyOne = NativePolicyTemplates.courseSubjectKey(workspace, subjectOne);
        String keyTwo = NativePolicyTemplates.courseSubjectKey(workspace, subjectTwo);
        String keyThree = NativePolicyTemplates.courseSubjectKey(workspace, subjectThree);
        String completionOne =
                applyResource(
                        new PolicyGraph(
                                keyOne,
                                "completion.read",
                                List.of(
                                        Source.of(new Gate.GroupMembership(teachersOne)),
                                        Source.of(new Gate.GroupMembership(homeroomGroup)))));
        String completionTwo =
                applyResource(
                        new PolicyGraph(
                                keyTwo,
                                "completion.read",
                                List.of(
                                        Source.of(new Gate.GroupMembership(teachersTwo)),
                                        Source.of(new Gate.GroupMembership(homeroomGroup)))));
        String completionThree =
                applyResource(
                        new PolicyGraph(
                                keyThree,
                                "completion.read",
                                List.of(
                                        Source.of(new Gate.GroupMembership(teachersThree)),
                                        Source.of(new Gate.GroupMembership(homeroomGroup)))));
        String assignTwo =
                applyResource(
                        new PolicyGraph(
                                keyTwo,
                                "activity.assign",
                                List.of(Source.of(new Gate.GroupMembership(homeroomGroup)))));
        String readTwo =
                applyResource(
                        new PolicyGraph(
                                keyTwo,
                                "read",
                                List.of(
                                        Source.of(new Gate.GroupMembership(studentsTwo)),
                                        Source.of(new Gate.GroupMembership(teachersTwo)))));
        String readOne =
                applyResource(
                        new PolicyGraph(
                                keyOne,
                                "read",
                                List.of(Source.of(new Gate.GroupMembership(teachersOne)))));

        assertTrue(allowed(teacherOne, completionOne, "completion.read"));
        assertTrue(allowed(teacherOne, completionTwo, "completion.read"));
        assertFalse(allowed(teacherOne, completionThree, "completion.read"));
        assertFalse(allowed(teacherOne, assignTwo, "activity.assign"));
        assertTrue(allowed(teacherTwo, completionThree, "completion.read"));
        assertFalse(allowed(teacherTwo, completionOne, "completion.read"));
        assertTrue(allowed(homeroom, completionOne, "completion.read"));
        assertTrue(allowed(homeroom, completionTwo, "completion.read"));
        assertTrue(allowed(homeroom, completionThree, "completion.read"));
        assertTrue(allowed(homeroom, assignTwo, "activity.assign"));
        assertTrue(allowed(student, readTwo, "read"));
        assertFalse(allowed(student, readOne, "read"));

        UUID resultId = UUID.randomUUID();
        UUID otherResultId = UUID.randomUUID();
        String resultKey = NativePolicyTemplates.resultKey(workspace, resultId);
        String published =
                applyResource(
                        new PolicyGraph(
                                resultKey,
                                "read.published",
                                List.of(
                                        Source.of(new Gate.GroupMembership(guardians)),
                                        Source.of(new Gate.Actor(student.userId())))));
        String fullRead =
                applyResource(
                        new PolicyGraph(
                                resultKey,
                                "read",
                                List.of(Source.of(new Gate.GroupMembership(teachersTwo)))));
        String otherPublished =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.resultKey(workspace, otherResultId),
                                "read.published",
                                List.of(Source.of(new Gate.GroupMembership(otherGuardians)))));
        assertEquals(published, fullRead);
        assertTrue(allowed(parent, published, "read.published"));
        assertFalse(allowed(parent, fullRead, "read"));
        assertFalse(allowed(parent, otherPublished, "read.published"));
        assertTrue(allowed(teacherOne, fullRead, "read"));
    }

    /**
     * Enrollment/license đã hết hạn: attempt cũ mở trước hạn vẫn hoàn tất được trong deadline của
     * chính activity, nhưng mở attempt mới, extend và reassign đều bị từ chối; thu hồi enrollment
     * thì cả quyền hoàn tất cũng mất.
     */
    @Test
    void expiredEnrollmentCompletesOldAttemptOnly() {
        UUID workspace = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID oldActivity = UUID.randomUUID();
        UUID newActivity = UUID.randomUUID();
        Actor student = newActor("expiring-student", true, true);
        joinEligible(student);
        String students = NativePolicyTemplates.courseSubjectStudentsGroup(workspace, subject);
        joinGroup(student, students);
        Instant expired = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        Instant activityDeadline = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        String oldKey = NativePolicyTemplates.activityKey(workspace, oldActivity);
        String submit =
                applyResource(
                        new PolicyGraph(
                                oldKey,
                                "submit",
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(students),
                                                new Gate.Validity(null, activityDeadline)))));
        String openOld =
                applyResource(
                        new PolicyGraph(
                                oldKey,
                                "attempt.open",
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(students),
                                                new Gate.Validity(null, expired)))));
        String extend =
                applyResource(
                        new PolicyGraph(
                                oldKey,
                                "schedule.extend",
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(students),
                                                new Gate.Validity(null, expired)))));
        String reassign =
                applyResource(
                        new PolicyGraph(
                                oldKey,
                                "reassign",
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(students),
                                                new Gate.Validity(null, expired)))));
        String openNew =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.activityKey(workspace, newActivity),
                                "attempt.open",
                                List.of(
                                        Source.of(
                                                new Gate.GroupMembership(students),
                                                new Gate.Validity(null, expired)))));
        assertEquals(submit, openOld);
        assertTrue(allowed(student, submit, "submit"));
        assertFalse(allowed(student, openOld, "attempt.open"));
        assertFalse(allowed(student, extend, "schedule.extend"));
        assertFalse(allowed(student, reassign, "reassign"));
        assertFalse(allowed(student, openNew, "attempt.open"));
        directory.setRealmGroupMembership(student.userId(), students, false);
        assertFalse(allowed(student, submit, "submit"));
    }

    /**
     * Grant dài hạn còn sống sau khi grantor rời workspace; thu hồi grant có thể thu hồi thì chặn
     * cả bản nhận lẫn bản chỉnh sửa trên fork, trong khi nguồn độc lập dài hạn vẫn còn nguyên.
     */
    @Test
    void longTermGrantSurvivesGrantorExitAndRevocableRevokeBlocksFork() {
        UUID workspace = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID revocableVersion = UUID.randomUUID();
        UUID forkedResource = UUID.randomUUID();
        Actor grantor = newActor("grantor", true, true);
        Actor recipient = newActor("recipient", true, true);
        joinEligible(grantor);
        joinEligible(recipient);
        String owners = NativePolicyTemplates.ownersGroup(workspace);
        joinGroup(grantor, owners);
        Instant from = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        Instant farFuture = Instant.now().plusSeconds(31_536_000).truncatedTo(ChronoUnit.SECONDS);
        String versionKey = NativePolicyTemplates.resourceVersionKey(workspace, resource, version);
        String longTermRead =
                applyResource(
                        new PolicyGraph(
                                versionKey,
                                "content.read",
                                List.of(
                                        Source.of(new Gate.Actor(recipient.userId())),
                                        Source.of(
                                                new Gate.Actor(recipient.userId()),
                                                new Gate.Validity(from, farFuture)))));
        String ownerUse =
                applyResource(
                        new PolicyGraph(
                                versionKey,
                                "use",
                                List.of(Source.of(new Gate.GroupMembership(owners)))));
        assertEquals(longTermRead, ownerUse);
        assertTrue(allowed(recipient, longTermRead, "content.read"));
        assertTrue(allowed(grantor, ownerUse, "use"));

        String revocableKey =
                NativePolicyTemplates.resourceVersionKey(workspace, resource, revocableVersion);
        String revocableRead =
                applyResource(
                        new PolicyGraph(
                                revocableKey,
                                "content.read",
                                List.of(Source.of(new Gate.Actor(recipient.userId())))));
        String revocableFork =
                applyResource(
                        new PolicyGraph(
                                revocableKey,
                                "fork",
                                List.of(Source.of(new Gate.Actor(recipient.userId())))));
        String forkEdit =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.resourceKey(workspace, forkedResource),
                                "draft.edit",
                                List.of(Source.of(new Gate.Actor(recipient.userId())))));
        assertEquals(revocableRead, revocableFork);
        assertTrue(allowed(recipient, revocableRead, "content.read"));
        assertTrue(allowed(recipient, revocableFork, "fork"));
        assertTrue(allowed(recipient, forkEdit, "draft.edit"));

        directory.setRealmGroupMembership(grantor.userId(), owners, false);
        assertFalse(allowed(grantor, ownerUse, "use"));
        assertTrue(allowed(recipient, longTermRead, "content.read"));

        writer.retractRoot(revocableKey, "content.read");
        writer.retractRoot(revocableKey, "fork");
        writer.retractRoot(
                NativePolicyTemplates.resourceKey(workspace, forkedResource), "draft.edit");
        assertFalse(allowed(recipient, revocableRead, "content.read"));
        assertFalse(allowed(recipient, revocableFork, "fork"));
        assertFalse(allowed(recipient, forkEdit, "draft.edit"));
        assertTrue(allowed(recipient, longTermRead, "content.read"));
    }

    /**
     * Cùng access token: gỡ membership thì lần gọi ngay sau bị từ chối, actor còn lại không đổi.
     */
    @Test
    void sameTokenDeniedAfterMembershipRemovalPeerUnaffected() {
        UUID workspace = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        Actor teacherA = newActor("revoked-teacher", true, true);
        Actor teacherB = newActor("peer-teacher", true, true);
        joinEligible(teacherA);
        joinEligible(teacherB);
        String teachers = NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subject);
        joinGroup(teacherA, teachers);
        joinGroup(teacherB, teachers);
        String completion =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.courseSubjectKey(workspace, subject),
                                "completion.read",
                                List.of(Source.of(new Gate.GroupMembership(teachers)))));
        assertTrue(allowed(teacherA, completion, "completion.read"));
        assertTrue(allowed(teacherB, completion, "completion.read"));
        directory.setRealmGroupMembership(teacherA.userId(), teachers, false);
        assertFalse(allowed(teacherA, completion, "completion.read"));
        assertTrue(allowed(teacherB, completion, "completion.read"));
    }

    /**
     * User self-register có email chưa verified: vòng đăng nhập bị chặn ở required action
     * VERIFY_EMAIL nên không lấy được phiên nghiệp vụ nào. Sau khi email đã verified, đăng nhập
     * được nhưng quyết định vẫn bị từ chối cho tới khi eligibility được chiếu; cùng token sau khi
     * chiếu thì được phép.
     */
    @Test
    void unverifiedUserWithoutEligibilityHasNoBusinessAccess() {
        UUID workspace = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        CreatedUser selfRegistered =
                createUser("self-registered", true, false, true, ACTOR_PASSWORD, false, List.of());
        String teachers = NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subject);
        joinGroup(selfRegistered, teachers);
        String completion =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.courseSubjectKey(workspace, subject),
                                "completion.read",
                                List.of(Source.of(new Gate.GroupMembership(teachers)))));
        LoginWalk blocked = loginWalk(selfRegistered.username(), ACTOR_PASSWORD);
        assertTrue(
                blocked.location().toString().contains("VERIFY_EMAIL"),
                "Unverified login must stop at VERIFY_EMAIL, got: " + blocked.location());
        markEmailVerified(selfRegistered);
        Actor verified =
                new Actor(
                        selfRegistered.username(),
                        selfRegistered.userId(),
                        loginToken(selfRegistered.username(), ACTOR_PASSWORD));
        assertFalse(allowed(verified, completion, "completion.read"));
        joinEligible(verified);
        assertTrue(allowed(verified, completion, "completion.read"));
    }

    /** Đánh dấu email đã verified ở tầng directory, như khi link trong email được bấm thật. */
    private void markEmailVerified(CreatedUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.userId());
        body.put("username", user.username());
        body.put("email", user.username() + "@example.test");
        body.put("emailVerified", true);
        body.put("enabled", true);
        body.put("firstName", "Acceptance");
        body.put("lastName", "Gate");
        body.put("requiredActions", List.of());
        http.put()
                .uri(base + "/admin/realms/" + REALM + "/users/" + user.userId())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * User do center cấp đúng hình dạng hợp đồng (có tên, không email, mật khẩu tạm kèm
     * UPDATE_PASSWORD): đăng nhập Code + PKCE đi qua đúng một required action đổi mật khẩu của lần
     * cấp, không bị ép bổ sung email bởi required action hay profile nào, và có quyền nghiệp vụ đã
     * được provision.
     */
    @Test
    void userWithoutEmailLogsInWithoutForcedProfileActions() {
        UUID workspace = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        CreatedUser issued =
                createUser(
                        "center-issued",
                        false,
                        false,
                        true,
                        TEMPORARY_PASSWORD,
                        true,
                        List.of("UPDATE_PASSWORD"));
        // IdentityProvisioningClient chỉ có displayName ở firstName, không có họ tách riêng.
        http.put()
                .uri(base + "/admin/realms/" + REALM + "/users/" + issued.userId())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", ""))
                .retrieve()
                .toBodilessEntity();
        joinEligible(issued);
        String teachers = NativePolicyTemplates.courseSubjectTeachersGroup(workspace, subject);
        joinGroup(issued, teachers);
        Map<String, Object> representation =
                http.get()
                        .uri(base + "/admin/realms/" + REALM + "/users/" + issued.userId())
                        .headers(headers -> headers.setBearerAuth(adminToken))
                        .retrieve()
                        .body(MAP);
        assertNotNull(representation);
        assertNull(representation.get("email"));
        assertNull(representation.get("lastName"));
        assertEquals(List.of("UPDATE_PASSWORD"), representation.get("requiredActions"));
        String completion =
                applyResource(
                        new PolicyGraph(
                                NativePolicyTemplates.courseSubjectKey(workspace, subject),
                                "completion.read",
                                List.of(Source.of(new Gate.GroupMembership(teachers)))));
        LoginWalk walk = loginWalk(issued.username(), TEMPORARY_PASSWORD);
        assertTrue(
                walk.location().toString().contains("UPDATE_PASSWORD"),
                "Only the issuance password update may be required, got: " + walk.location());
        Actor actor =
                new Actor(
                        issued.username(),
                        issued.userId(),
                        exchangeCode(submitUpdatePassword(walk, ACTOR_PASSWORD)));
        assertTrue(allowed(actor, completion, "completion.read"));
    }

    private static String formAction(String pageBody) {
        var form = LOGIN_FORM.matcher(pageBody);
        if (!form.find()) {
            throw new IllegalStateException("Native login form not found");
        }
        return form.group(1).replace("&amp;", "&");
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

    private String provisionerToken(String secret) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", PROVISIONER_CLIENT);
        form.add("client_secret", secret);
        Map<String, Object> response =
                http.post()
                        .uri(base + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        return String.valueOf(Objects.requireNonNull(response).get("access_token"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        return Objects.requireNonNull(
                System.getenv(name), "Run with infra/keycloak/verify-native.sh: " + name);
    }
}
