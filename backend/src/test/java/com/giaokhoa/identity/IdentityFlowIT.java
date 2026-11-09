package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.identity.eligibility.IdentityEligibilitySynchronizer;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.OidcFormLogin;
import com.giaokhoa.support.RealmFoundation;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract US1 ở tầng danh tính: profile Keycloak hiện hành lái eligibility qua synchronizer và
 * worker production; quan hệ thư mục (membership, role, origin) giữ đúng ranh giới từng workspace;
 * các endpoint identity của hợp đồng HTTP được lái bằng phiên Code+PKCE thật. Phần hành vi native
 * của role trong PDP (quản lý ở A, học sinh ở B) thuộc gate native của hợp đồng quyết định quyền;
 * class này khóa phần danh tính và thư mục mà các task triển khai US1 sẽ dựng lên trên.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class IdentityFlowIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "identity_flow_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ELIGIBLE_GROUP = "/gk/identity/eligible";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @BeforeAll
    static void seedRealmPrerequisites() {
        seedEligibilityGroup();
        ensureApiClient();
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        // Class này chờ operation do worker thật xử lý (awaitSucceeded) nên không mock được như
        // BootstrapProfileIT; teardown dừng scheduler trước để không còn actor đồng thời nào chạm
        // schema khi DROP.
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityEligibilitySynchronizer synchronizer;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext webContext;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired ClientRegistrationRepository clientRegistrations;
    @Autowired SessionRepository<?> sessions;

    @Test
    void unverifiedSelfRegisteredUserHasNoEligibilityUntilEmailVerified() {
        String subject =
                createUser(keycloakUser("flow-unverified", "unverified@example.test", false));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "unverified@example.test", false);

            synchronizer.synchronizeEligibility(actor);

            assertFalse(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Chưa xác minh email thì không được vào nhóm eligibility");
            assertEquals(
                    Boolean.FALSE,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor));

            setEmailVerified(subject, true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent(), "Vừa xác minh email phải ghi một thay đổi eligibility");
            awaitSucceeded(granted.get().getId());
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Đã xác minh email thì được chiếu vào nhóm eligibility");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void changingEmailToAnUnverifiedOneRevokesEligibility() {
        String subject = createUser(keycloakUser("flow-email-change", "before@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "before@example.test", true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            assertTrue(groupPathsOf(subject).contains(ELIGIBLE_GROUP));

            updateProfile(subject, "after@example.test", false);
            Optional<AuthorizationOperation> revoked = synchronizer.synchronizeEligibility(actor);

            assertTrue(revoked.isPresent(), "Đổi sang email chưa xác minh phải ghi thay đổi");
            awaitSucceeded(revoked.get().getId());
            assertFalse(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Email hiện hành chưa xác minh thì mất eligibility");
            assertEquals(
                    "after@example.test",
                    jdbc.queryForObject(
                            "SELECT email FROM user_account WHERE id = ?", String.class, actor));
            assertEquals(
                    Boolean.FALSE,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void verifiedUserBaselineIsNeutral() {
        String subject = createUser(keycloakUser("flow-neutral", "neutral@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "neutral@example.test", true);

            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());

            assertEquals(
                    List.of(ELIGIBLE_GROUP),
                    groupPathsOf(subject),
                    "Tài khoản trung tính chỉ thuộc nhóm eligibility, không vai trò toàn hệ thống nào");
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership WHERE user_id = ?",
                            Integer.class,
                            actor));
            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, actor));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void neutralUserWorkspacesEndpointListsOnlyPersonalWorkspace() throws Exception {
        String username = "flow-workspaces-" + UUID.randomUUID();
        String password = "flow-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            UUID personalWorkspace =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id FROM workspace WHERE owner_user_id = ?"
                                            + " AND kind = 'PERSONAL'",
                                    UUID.class,
                                    actor),
                            "personal workspace");

            webContext.getBean(IdentityService.class).bootstrap(subject);
            awaitIdentity(actor);
            try (var session = openSession(username, password, subject)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(get("/api/me/workspaces").cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertTrue(body.isArray(), "Danh sách workspace là một mảng");
                assertEquals(1, body.size(), "Tài khoản trung tính chỉ có workspace cá nhân");
                assertEquals(personalWorkspace.toString(), body.get(0).get("id").stringValue());
                assertEquals("PERSONAL", body.get(0).get("kind").stringValue());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void centerIssuedUserWithoutEmailIsEligibleWithoutVerification() {
        String username = "flow-issued-" + UUID.randomUUID();
        Map<String, Object> issued = new HashMap<>();
        issued.put("username", username);
        issued.put("enabled", true);
        issued.put("firstName", "Synthetic");
        issued.put("lastName", "User");
        issued.put("emailVerified", false);
        issued.put("requiredActions", List.of("UPDATE_PASSWORD"));
        issued.put(
                "credentials",
                List.of(
                        Map.of(
                                "type",
                                "password",
                                "value",
                                "flow-temp-" + UUID.randomUUID(),
                                "temporary",
                                true)));
        String subject = createUser(issued);
        try {
            Map<String, Object> representation = getUser(subject);
            assertNull(representation.get("email"));
            assertEquals(
                    List.of("UPDATE_PASSWORD"),
                    representation.get("requiredActions"),
                    "Học sinh được cấp không email chỉ có một required action đổi mật khẩu,"
                            + " không bị ép xác minh email hay bổ sung profile");

            UUID actor = seedAccount(subject, "CENTER_ISSUED", null, false);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);

            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Account do backend cấp được eligibility mà không cần email đã xác minh");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void existingIdentityJoinsSecondCenterWithoutChangingOrigin() {
        String subject = createUser(keycloakUser("flow-invitee", "invitee@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "invitee@example.test", true);
            UUID owner =
                    seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, actor, "STUDENT");

            addMembership(centerB, actor, "STUDENT");

            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, actor),
                    "Vào thêm trung tâm không đổi origin của danh tính đã có");
            assertEquals(
                    subject,
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            actor));
            assertEquals(Set.of("STUDENT"), activeRoles(centerA, actor));
            assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));
        } finally {
            deleteUser(subject);
        }
    }

    // Endpoint invitations thuộc các task US2, chưa được triển khai; bật lại test khi endpoint đó
    // land.
    @Test
    @Disabled("Endpoint invitations thuộc US2, chưa triển khai")
    void invitationEndpointRegistersExistingIdentityWithoutOriginChange() throws Exception {
        String username = "flow-inviter-" + UUID.randomUUID();
        String password = "flow-password-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            UUID invitee =
                    seedAccount(
                            "flow-invite-target-" + UUID.randomUUID(),
                            "SELF_REGISTERED",
                            null,
                            false);
            UUID center = seedCenter(actor);

            try (var session = openSession(username, password, subject)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "flow-invite-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void oppositeRolesInTwoCentersStaySeparated() {
        UUID actor =
                seedAccount("flow-opposite-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID owner = seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
        UUID centerA = seedCenter(owner);
        UUID centerB = seedCenter(owner);
        addMembership(centerA, actor, "MANAGER");
        addMembership(centerB, actor, "STUDENT");

        assertEquals(Set.of("MANAGER"), activeRoles(centerA, actor));
        assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));

        revokeMembership(centerA, actor);

        assertEquals(Set.of(), activeRoles(centerA, actor), "Quyền quản lý ở A đã bị thu hồi");
        assertEquals(
                Set.of("STUDENT"),
                activeRoles(centerB, actor),
                "Vai trò học sinh ở B không bị quyền ở A kéo theo");
    }

    @Test
    void leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter() {
        String subject = createUser(keycloakUser("flow-leaver", "leaver@example.test", true));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", "leaver@example.test", true);
            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            UUID owner =
                    seedAccount("flow-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null, false);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, actor, "MANAGER");
            addMembership(centerB, actor, "STUDENT");

            revokeMembership(centerA, actor);

            assertEquals(
                    subject,
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            actor),
                    "Rời một trung tâm không xóa hay đổi danh tính");
            assertEquals(
                    "ACTIVE",
                    jdbc.queryForObject(
                            "SELECT identity_state FROM user_account WHERE id = ?",
                            String.class,
                            actor));
            assertEquals(
                    "ACTIVE",
                    jdbc.queryForObject(
                            "SELECT state FROM workspace WHERE owner_user_id = ?"
                                    + " AND kind = 'PERSONAL'",
                            String.class,
                            actor));
            assertEquals(Set.of("STUDENT"), activeRoles(centerB, actor));
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Eligibility là thuộc tính của danh tính, không mất khi rời một trung tâm");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void anonymousCallerIsRedirectedToLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc().perform(get("/api/me/workspaces")).andReturn().getResponse();

        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"),
                "Chưa đăng nhập thì được hướng dẫn đăng nhập, không nhận dữ liệu nghiệp vụ");
    }

    @Test
    void forgedSessionCookieDoesNotAuthenticate() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                get("/api/me/workspaces")
                                        .cookie(
                                                new Cookie(
                                                        "SESSION", "forged-" + UUID.randomUUID())))
                        .andReturn()
                        .getResponse();

        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"));
    }

    @Test
    void sessionEndpointDescribesAnonymousState() throws Exception {
        MockHttpServletResponse response =
                mockMvc().perform(get("/api/session")).andReturn().getResponse();

        assertEquals(200, response.getStatus());
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertFalse(body.get("authenticated").booleanValue());
        assertFalse(
                body.get("csrf").get("token").stringValue().isBlank(),
                "Phiên ẩn danh vẫn nhận CSRF token để bắt đầu đăng nhập");
        assertFalse(body.has("user"), "Chưa đăng nhập thì không có thông tin người dùng");
    }

    @Test
    void lostVerificationBlocksWorkspaceMetadataAndHistoryOnTheSameSession() throws Exception {
        String username = "regression-eligibility-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = webContext.getBean(IdentityService.class).bootstrap(subject).userId();
            awaitIdentity(actor);
            UUID center = seedCenter(actor);
            try (var session = openSession(username, password, subject)) {
                assertEquals(
                        200,
                        mockMvc()
                                .perform(get("/api/me/workspaces").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus());
                assertEquals(
                        200,
                        mockMvc()
                                .perform(get("/api/me/practice-history").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus());
                updateProfile(subject, "changed-" + UUID.randomUUID() + "@example.test", false);
                for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                    MockHttpServletResponse denied =
                            mockMvc()
                                    .perform(get(path).cookie(session.cookie()))
                                    .andReturn()
                                    .getResponse();
                    assertTrue(
                            denied.getStatus() == 403 || denied.getStatus() == 503,
                            denied.getContentAsString());
                    assertFalse(denied.getContentAsString().contains(center.toString()));
                    assertFalse(denied.getContentAsString().contains("Flow test center"));
                }
                awaitIdentity(actor);
                assertFalse(groupPathsOf(subject).contains(ELIGIBLE_GROUP));
                setEmailVerified(subject, true);
                webContext.getBean(IdentityService.class).bootstrap(subject);
                awaitIdentity(actor);
                assertEquals(
                        200,
                        mockMvc()
                                .perform(get("/api/me/practice-history").cookie(session.cookie()))
                                .andReturn()
                                .getResponse()
                                .getStatus());
                webContext
                        .getBean(
                                com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient
                                        .class)
                        .setRealmGroupMembership(subject, ELIGIBLE_GROUP, false);
                for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                    assertEquals(
                            403,
                            mockMvc()
                                    .perform(get(path).cookie(session.cookie()))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus());
                }
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void pendingOrFailedPersonalPartitionBlocksBothProfileReadEndpoints() throws Exception {
        String username = "regression-fence-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            var actor = webContext.getBean(IdentityService.class).bootstrap(subject);
            awaitIdentity(actor.userId());
            try (var session = openSession(username, password, subject)) {
                for (String state : List.of("PENDING", "FAILED")) {
                    jdbc.update(
                            "UPDATE authz_partition SET state = ? WHERE owner_key = ?",
                            state,
                            "workspace:" + actor.personalWorkspaceId());
                    for (String path : List.of("/api/me/workspaces", "/api/me/practice-history")) {
                        assertEquals(
                                503,
                                mockMvc()
                                        .perform(get(path).cookie(session.cookie()))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());
                    }
                }
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapConvergesAndConcurrentFirstRequestsShareOneIdentity() throws Exception {
        String username = "regression-bootstrap-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, username + "@example.test", true));
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var identities = webContext.getBean(IdentityService.class);
            try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first =
                        threads.submit(() -> {
                            start.await();
                            return identities.bootstrap(subject);
                        });
                var second =
                        threads.submit(() -> {
                            start.await();
                            return identities.bootstrap(subject);
                        });
                start.countDown();
                var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(a.userId(), b.userId());
                assertEquals(a.personalWorkspaceId(), b.personalWorkspaceId());
                assertNotEquals(a.created(), b.created());
                awaitIdentity(a.userId());
                assertTrue(groupPathsOf(subject).contains(ELIGIBLE_GROUP));
                assertEquals(
                        2,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authz_partition WHERE owner_key IN (?,?) AND state = 'READY'",
                                Integer.class,
                                "account:" + a.userId(),
                                "workspace:" + a.personalWorkspaceId()));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                                Integer.class,
                                a.userId()));
                assertFalse(identities.bootstrap(subject).created());
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                                Integer.class,
                                a.userId()));
                var resource =
                        webContext
                                .getBean(com.giaokhoa.authorization.policy.PolicyGraphWriter.class)
                                .findResourceId("account:" + a.userId())
                                .orElseThrow();
                assertNotNull(resource);
                assertTrue(
                        webContext
                                .getBean(
                                        com.giaokhoa.authorization.projection.ProjectionReconciler
                                                .class)
                                .verifyStoredGraph(
                                        Objects.requireNonNull(
                                                jdbc.queryForObject(
                                                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                                                        UUID.class,
                                                        "account:" + a.userId()))));
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void recoveryHttpRetriesReplayAndPreconditionsAreRequired() throws Exception {
        String username = "regression-replay-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        try {
            UUID actor = seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            try (var session = openSession(username, password, subject)) {
                String body =
                        JSON.writeValueAsString(
                                Map.of("targetUserId", actor, "reason", "Quên mật khẩu"));
                String key = "retry-" + UUID.randomUUID();
                MockHttpServletResponse missing =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse();
                assertEquals(428, missing.getStatus());
                MockHttpServletResponse first =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse();
                MockHttpServletResponse second =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse();
                assertEquals(202, first.getStatus(), first.getContentAsString());
                assertEquals(first.getContentAsString(), second.getContentAsString());
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recovery_request WHERE requester_user_id = ?",
                                Integer.class,
                                actor));
                MockHttpServletResponse conflict =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of(
                                                                        "targetUserId",
                                                                        UUID.randomUUID(),
                                                                        "reason",
                                                                        "Quên mật khẩu"))))
                                .andReturn()
                                .getResponse();
                assertEquals(409, conflict.getStatus());
                var changedReason =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of(
                                                                        "targetUserId",
                                                                        actor,
                                                                        "reason",
                                                                        "Lý do khác"))))
                                .andReturn()
                                .getResponse();
                assertEquals(409, changedReason.getStatus());

                var service = webContext.getBean(RecoveryService.class);
                var start = new java.util.concurrent.CountDownLatch(1);
                String concurrentKey = "concurrent-" + UUID.randomUUID();
                try (var threads =
                        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var one =
                            threads.submit(() -> {
                                start.await();
                                return service.submitOnce(
                                        actor,
                                        actor,
                                        null,
                                        "Khôi phục đăng nhập",
                                        concurrentKey,
                                        "a".repeat(64));
                            });
                    var two =
                            threads.submit(() -> {
                                start.await();
                                return service.submitOnce(
                                        actor,
                                        actor,
                                        null,
                                        "Khôi phục đăng nhập",
                                        concurrentKey,
                                        "a".repeat(64));
                            });
                    start.countDown();
                    assertEquals(
                            one.get(20, java.util.concurrent.TimeUnit.SECONDS).id(),
                            two.get(20, java.util.concurrent.TimeUnit.SECONDS).id());
                }
                assertEquals(
                        2,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recovery_request WHERE requester_user_id = ?",
                                Integer.class,
                                actor));
            }
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void operatorApprovalKeepsRecoveryPendingUntilConfirmedDelivery() throws Exception {
        String username = "regression-operator-" + UUID.randomUUID();
        String password = "Password1-" + UUID.randomUUID();
        String subject =
                createUser(keycloakUser(username, username + "@example.test", true, password));
        String targetSubject =
                createUser(
                        keycloakUser(
                                "regression-target-" + UUID.randomUUID(),
                                null,
                                false,
                                "Password1-" + UUID.randomUUID()));
        var directory =
                webContext.getBean(
                        com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient.class);
        try {
            seedAccount(subject, "SELF_REGISTERED", username + "@example.test", true);
            UUID target = seedAccount(targetSubject, "CENTER_ISSUED", null, false);
            var submitted = webContext.getBean(RecoveryService.class).submit(target, target, null);
            directory.setRealmGroupMembership(
                    subject, NativePolicyTemplates.PLATFORM_OPERATORS_GROUP, true);
            try (var session = openSession(username, password, subject)) {
                String path = "/api/platform/recovery-requests/" + submitted.id() + "/resolve";
                String key = "approve-" + UUID.randomUUID();
                var early =
                        requestWhenReady(
                                mockMvc(),
                                post(path)
                                        .cookie(session.cookie())
                                        .with(csrf())
                                        .header("Idempotency-Key", "early-" + UUID.randomUUID())
                                        .header("If-Match", "\"0\"")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"decision\":\"COMPLETE\",\"verificationNote\":\"Đã bàn giao\"}"));
                assertEquals(409, early.getStatus());

                String approval =
                        "{\"decision\":\"APPROVE\",\"verificationNote\":\"Đã xác minh đúng chủ tài khoản\"}";
                var missing =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(approval))
                                .andReturn()
                                .getResponse();
                assertEquals(428, missing.getStatus());
                var approved =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .header("If-Match", "\"0\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(approval))
                                .andReturn()
                                .getResponse();
                assertEquals(202, approved.getStatus(), approved.getContentAsString());
                assertFalse(
                        JSON.readTree(approved.getContentAsString())
                                .get("completed")
                                .booleanValue());
                assertEquals(
                        "VERIFIED",
                        jdbc.queryForObject(
                                "SELECT state FROM recovery_request WHERE id = ?",
                                String.class,
                                submitted.id()));
                var replay =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .header("If-Match", "\"0\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(approval))
                                .andReturn()
                                .getResponse();
                assertEquals(approved.getContentAsString(), replay.getContentAsString());
                var poll =
                        mockMvc()
                                .perform(
                                        get("/api/me/operations/" + submitted.id())
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();
                assertEquals(200, poll.getStatus());
                assertEquals(
                        "PENDING",
                        JSON.readTree(poll.getContentAsString()).get("state").stringValue());
                String completion =
                        "{\"decision\":\"COMPLETE\",\"verificationNote\":\"Đã khôi phục và bàn giao qua kênh riêng\"}";
                var stale =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "stale-" + UUID.randomUUID())
                                                .header("If-Match", "\"0\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(completion))
                                .andReturn()
                                .getResponse();
                assertEquals(409, stale.getStatus());
                // Mô phỏng công việc operator thực hiện ở console, không gửi credential qua app.
                HTTP.put()
                        .uri(admin("/users/" + targetSubject + "/reset-password"))
                        .headers(h -> h.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "type",
                                        "password",
                                        "value",
                                        "Recovered1-" + UUID.randomUUID(),
                                        "temporary",
                                        true))
                        .retrieve()
                        .toBodilessEntity();
                var completed =
                        mockMvc()
                                .perform(
                                        post(path)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "complete-" + UUID.randomUUID())
                                                .header("If-Match", "\"1\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(completion))
                                .andReturn()
                                .getResponse();
                assertEquals(200, completed.getStatus(), completed.getContentAsString());
                assertTrue(
                        JSON.readTree(completed.getContentAsString())
                                .get("completed")
                                .booleanValue());
                assertEquals(
                        "RESOLVED",
                        jdbc.queryForObject(
                                "SELECT state FROM recovery_request WHERE id = ?",
                                String.class,
                                submitted.id()));
            }
        } finally {
            deleteUser(subject);
            deleteUser(targetSubject);
        }
    }

    private void awaitIdentity(UUID actor) {
        for (UUID operation :
                jdbc.queryForList(
                        "SELECT id FROM authorization_operation WHERE initiator_user_id = ?",
                        UUID.class,
                        actor)) {
            awaitSucceeded(operation);
        }
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedAccount(
            String keycloakSub, String origin, @Nullable String email, boolean emailVerified) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    keycloakSub,
                    "flow-user-" + actor,
                    email,
                    emailVerified,
                    origin);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state)"
                            + " VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "flow-org-" + center,
                    "Flow test center",
                    ownerUserId);
            // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER của chủ sở hữu.
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    ownerMembership,
                    workspace,
                    ownerUserId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')",
                    ownerMembership);
            return null;
        });
        return workspace;
    }

    private void addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            for (String role : roles) {
                jdbc.update(
                        "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                        membership,
                        role);
            }
            return null;
        });
    }

    private void revokeMembership(UUID workspaceId, UUID userId) {
        tx(() -> {
            jdbc.update(
                    "UPDATE center_membership SET state = 'REVOKED', revoked_at = now()"
                            + " WHERE workspace_id = ? AND user_id = ? AND state = 'ACTIVE'",
                    workspaceId,
                    userId);
            return null;
        });
    }

    private Set<String> activeRoles(UUID workspaceId, UUID userId) {
        return Set.copyOf(
                jdbc.queryForList(
                        "SELECT r.role FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ? AND m.state = 'ACTIVE'",
                        String.class,
                        workspaceId,
                        userId));
    }

    private void awaitSucceeded(UUID operationId) {
        awaitOperationSucceeded(jdbc, operationId);
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
    }

    private RealSession openSession(String username, String password, String subject)
            throws Exception {
        return RealSession.open(
                username,
                password,
                subject,
                sessionFilter,
                authorizedClients,
                clientRegistrations,
                sessions);
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> keycloakUser(
            String username, @Nullable String email, boolean emailVerified) {
        return keycloakUser(username, email, emailVerified, "flow-password-" + UUID.randomUUID());
    }

    private Map<String, Object> keycloakUser(
            String username, @Nullable String email, boolean emailVerified, String password) {
        var body = new HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("firstName", "Synthetic");
        body.put("lastName", "User");
        body.put("emailVerified", emailVerified);
        body.put("requiredActions", List.of());
        if (email != null) {
            body.put("email", email);
        }
        body.put(
                "credentials",
                List.of(Map.of("type", "password", "value", password, "temporary", false)));
        return body;
    }

    private String createUser(Map<String, Object> body) {
        var response =
                HTTP.post()
                        .uri(admin("/users"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private Map<String, Object> getUser(String userId) {
        Map<String, Object> user =
                HTTP.get()
                        .uri(admin("/users/" + userId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(MAP);
        if (user == null) {
            throw new IllegalStateException("Keycloak user not readable: " + userId);
        }
        return user;
    }

    private void setEmailVerified(String userId, boolean verified) {
        Map<String, Object> user = new HashMap<>(getUser(userId));
        user.put("emailVerified", verified);
        putUser(userId, user);
    }

    private void updateProfile(String userId, String email, boolean emailVerified) {
        Map<String, Object> user = new HashMap<>(getUser(userId));
        user.put("email", email);
        user.put("emailVerified", emailVerified);
        putUser(userId, user);
    }

    private void putUser(String userId, Map<String, Object> user) {
        HTTP.put()
                .uri(admin("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    private List<String> groupPathsOf(String userId) {
        List<Map<String, Object>> groups =
                HTTP.get()
                        .uri(admin("/users/" + userId + "/groups"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(LIST);
        if (groups == null) {
            throw new IllegalStateException("Keycloak groups not readable: " + userId);
        }
        return groups.stream().map(group -> String.valueOf(group.get("path"))).sorted().toList();
    }

    private void deleteUser(String userId) {
        delete(admin("/users/" + userId));
    }

    private String admin(String path) {
        return adminStatic(path);
    }

    private static String adminStatic(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private static void seedEligibilityGroup() {
        RealmFoundation.ensureEligibilityTree(
                HTTP, adminStatic(""), IdentityFlowIT::masterTokenStatic);
    }

    private static void ensureApiClient() {
        RealmFoundation.ensureApiClient(
                HTTP,
                adminStatic(""),
                IdentityFlowIT::masterTokenStatic,
                fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
    }

    private static void delete(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                .retrieve()
                .toBodilessEntity();
    }

    private String masterToken() {
        return masterTokenStatic();
    }

    private static String masterTokenStatic() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
        Map<String, Object> response =
                HTTP.post()
                        .uri(BASE + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(MAP);
        if (response == null) {
            throw new IllegalStateException("Master token not readable");
        }
        return Objects.requireNonNull((String) response.get("access_token"), "access_token");
    }

    static String fixture(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }

    /**
     * Phiên Spring thật chứa token Code+PKCE thật của một user TEST, dùng chung cho hai class test
     * của US1 trong package này. Đăng nhập đi qua form login của Keycloak TEST bằng client BFF của
     * ứng dụng; không dùng Direct Access Grants cho phiên web.
     */
    static final class RealSession implements AutoCloseable {
        private static final String REDIRECT_URI = "http://127.0.0.1:18888/callback";

        private final SessionRepository<?> sessions;
        private final Cookie sessionCookie;

        private RealSession(SessionRepository<?> sessions, Cookie sessionCookie) {
            this.sessions = sessions;
            this.sessionCookie = sessionCookie;
        }

        Cookie cookie() {
            return sessionCookie;
        }

        @Override
        public void close() {
            sessions.deleteById(sessionCookie.getValue());
        }

        static RealSession open(
                String username,
                String password,
                String expectedSubject,
                SessionRepositoryFilter<?> filter,
                OAuth2AuthorizedClientRepository clients,
                ClientRegistrationRepository registrations,
                SessionRepository<?> sessions)
                throws Exception {
            var registration =
                    Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
            OidcFormLogin.WebTokens tokens =
                    OidcFormLogin.webTokensViaCodePkce(
                            HTTP, BASE, REALM, REDIRECT_URI, username, password);
            String claims =
                    new String(
                            Base64.getUrlDecoder().decode(tokens.idToken().split("[.]")[1]),
                            StandardCharsets.UTF_8);
            assertEquals(expectedSubject, JSON.readTree(claims).get("sub").stringValue());
            Instant issued = Instant.now();
            var idToken =
                    new OidcIdToken(
                            tokens.idToken(),
                            issued,
                            issued.plusSeconds(300),
                            Map.of(
                                    "iss",
                                    BASE + "/realms/" + REALM,
                                    "sub",
                                    expectedSubject,
                                    "aud",
                                    List.of(registration.getClientId())));
            var authentication =
                    new OAuth2AuthenticationToken(
                            new DefaultOidcUser(List.of(), idToken, "sub"), List.of(), "keycloak");
            var authorized =
                    new OAuth2AuthorizedClient(
                            registration,
                            authentication.getName(),
                            new OAuth2AccessToken(
                                    OAuth2AccessToken.TokenType.BEARER,
                                    tokens.accessToken(),
                                    issued,
                                    issued.plusSeconds(300)),
                            new OAuth2RefreshToken(tokens.refreshToken(), issued));
            var request = new MockHttpServletRequest("GET", "/");
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request,
                    response,
                    (wrapped, reply) -> {
                        var servletRequest = (HttpServletRequest) wrapped;
                        servletRequest
                                .getSession(true)
                                .setAttribute(
                                        HttpSessionSecurityContextRepository
                                                .SPRING_SECURITY_CONTEXT_KEY,
                                        new SecurityContextImpl(authentication));
                        clients.saveAuthorizedClient(
                                authorized,
                                authentication,
                                servletRequest,
                                (HttpServletResponse) reply);
                    });
            Cookie sessionCookie = Objects.requireNonNull(response.getCookie("SESSION"));
            assertNotNull(
                    sessions.findById(sessionCookie.getValue()), "Session must persist in JDBC");
            return new RealSession(sessions, sessionCookie);
        }
    }
}
