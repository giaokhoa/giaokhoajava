package com.giaokhoa.identity.eligibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.RealmFoundation;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Gate eligibility trên stack TEST thật: synchronizer đọc profile hiện hành từ Keycloak 26.8.1, ghi
 * thay đổi lên ACCOUNT partition của PostgreSQL, và worker production chiếu membership {@code
 * /gk/identity/eligible} trước khi operation thành công. Các điều kiện tiên quyết của realm mà
 * bootstrap production sở hữu (đường group eligibility, client audience của authorization) được
 * seed tại đây như fixture revocation vẫn seed, và chỉ phần class này tự tạo mới bị dọn sau test.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdentityEligibilitySynchronizerIT extends IntegrationEnvironment {
    private static final String ELIGIBLE_GROUP = "/gk/identity/eligible";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = required("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = required("TEST_KEYCLOAK_REALM");

    @BeforeAll
    static void seedRealmPrerequisites() {
        seedEligibilityGroup();
        ensureApiClient();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityEligibilitySynchronizer synchronizer;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void selfRegisteredEligibilityFollowsTheCurrentProfileBothWays() {
        String subject = createUser("sync-self-" + UUID.randomUUID(), "self@example.test", true);
        try {
            UUID actor = account(subject, "SELF_REGISTERED", null, false, "self@example.test");

            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);
            assertTrue(granted.isPresent(), "A newly verified profile must record a change");
            awaitSucceeded(granted.get().getId());
            assertTrue(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Eligible actor must be projected into the eligibility group");
            assertEquals(
                    Boolean.TRUE,
                    jdbc.queryForObject(
                            "SELECT email_verified FROM user_account WHERE id = ?",
                            Boolean.class,
                            actor));
            assertEquals(
                    "READY",
                    jdbc.queryForObject(
                            "SELECT state FROM authz_partition WHERE owner_key = ?",
                            String.class,
                            "account:" + actor));

            setEmailVerified(subject, false);
            Optional<AuthorizationOperation> revoked = synchronizer.synchronizeEligibility(actor);
            assertTrue(revoked.isPresent(), "Losing verification must record a change");
            awaitSucceeded(revoked.get().getId());
            assertFalse(
                    groupPathsOf(subject).contains(ELIGIBLE_GROUP),
                    "Unverified actor must lose the eligibility membership");
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void centerIssuedAccountIsEligibleWithoutAnyVerifiedEmail() {
        String subject = createUser("sync-issued-" + UUID.randomUUID(), null, false);
        try {
            UUID actor = account(subject, "CENTER_ISSUED", subject, false, null);

            Optional<AuthorizationOperation> granted = synchronizer.synchronizeEligibility(actor);

            assertTrue(granted.isPresent());
            awaitSucceeded(granted.get().getId());
            assertTrue(groupPathsOf(subject).contains(ELIGIBLE_GROUP));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void anIdentityThatCannotBeReadFailsClosedAndRecordsNothing() {
        String missingSubject = UUID.randomUUID().toString();
        UUID actor = account(missingSubject, "SELF_REGISTERED", null, false, "ghost@example.test");

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> synchronizer.synchronizeEligibility(actor));

        assertEquals(503, failure.getStatus().value());
        assertEquals("IDENTITY_UNAVAILABLE", failure.getCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                        Integer.class,
                        actor));
    }

    private UUID account(
            String keycloakSub,
            String origin,
            @Nullable String usernameSeed,
            boolean verified,
            @Nullable String email) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        String username = "eligibility-" + (usernameSeed == null ? actor : usernameSeed);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    jdbc.update(
                            "INSERT INTO user_account"
                                    + " (id, keycloak_sub, username, email, email_verified,"
                                    + " origin, identity_state)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')",
                            actor,
                            keycloakSub,
                            username,
                            email,
                            verified,
                            origin);
                    jdbc.update(
                            "INSERT INTO workspace (id, kind, owner_user_id, state)"
                                    + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                            workspace,
                            actor);
                });
        return actor;
    }

    private void awaitSucceeded(UUID operationId) {
        awaitOperationSucceeded(jdbc, operationId);
    }

    private String createUser(String username, @Nullable String email, boolean emailVerified) {
        var body = new java.util.HashMap<String, Object>();
        body.put("username", username);
        body.put("enabled", true);
        body.put("emailVerified", emailVerified);
        body.put("requiredActions", List.of());
        if (email != null) {
            body.put("email", email);
        }
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

    private void setEmailVerified(String userId, boolean verified) {
        Map<String, Object> user =
                HTTP.get()
                        .uri(admin("/users/" + userId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(MAP);
        if (user == null) {
            throw new IllegalStateException("Keycloak user not readable: " + userId);
        }
        user.put("emailVerified", verified);
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
        return groups.stream().map(group -> String.valueOf(group.get("path"))).toList();
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
                HTTP, adminStatic(""), IdentityEligibilitySynchronizerIT::masterToken);
    }

    private static void ensureApiClient() {
        RealmFoundation.ensureApiClient(
                HTTP,
                adminStatic(""),
                IdentityEligibilitySynchronizerIT::masterToken,
                required("TEST_KEYCLOAK_AUTHZ_AUDIENCE"));
    }

    private static void delete(String uri) {
        HTTP.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private static String masterToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", required("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", required("TEST_KEYCLOAK_MASTER_PASSWORD"));
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

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }
}
