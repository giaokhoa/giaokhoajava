package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract US2 ở tầng quản trị trung tâm: operator tạo tenant và xác nhận gói thủ công, owner bổ
 * nhiệm manager, manager mời thành viên và đổi role theo scope riêng, gói hết hạn chặn thêm quyền
 * nhưng không chặn thu hồi, giáo viên không tạo được lớp center, và người quản trị không tự đọc
 * được nội dung riêng của người khác.
 *
 * <p>Đây là task test-first: các endpoint US2 chưa được triển khai nên test khẳng định đúng hợp
 * đồng HTTP (202 operation chưa hoàn tất, 403 khi thiếu quyền, 400 khi kỳ gói sai) và hiện đỏ với
 * 404 vì route chưa tồn tại — đỏ đó là có ý nghĩa, không phải lỗi hạ tầng. Các test dùng endpoint
 * đã có (workspaces, operations, capabilities) để khóa hành vi nền thì xanh ngay.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CenterAdministrationIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "center_admin_" + UUID.randomUUID().toString().replace("-", "");
    private static final String OPERATOR_GROUP = "/gk/platform/operators";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");
    private static final List<String> OWNED_GROUP_IDS = new ArrayList<>();
    private static @Nullable String operatorGroupId;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + SCHEMA + ",public");
    }

    @BeforeAll
    static void seedOperatorGroup() {
        operatorGroupId = ensureGroup(OPERATOR_GROUP);
    }

    @AfterAll
    static void removeOperatorGroup() {
        for (int i = OWNED_GROUP_IDS.size() - 1; i >= 0; i--) {
            deleteResource(adminStatic("/groups/" + OWNED_GROUP_IDS.get(i)));
        }
        OWNED_GROUP_IDS.clear();
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext webContext;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired ClientRegistrationRepository clientRegistrations;
    @Autowired SessionRepository<?> sessions;

    @Test
    void operatorCreateCenterReturnsPendingOperation() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);

            try (var session = openSession(operator)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/centers")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-create-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"name\":\"Trung tâm mới\",\"ownerUserId\":\""
                                                                + owner
                                                                + "\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
                assertFalse(
                        body.get("statusUrl").stringValue().isBlank(),
                        "Operation chưa xong phải có đường poll trạng thái");
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);

                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM center WHERE owner_user_id = ?",
                                Integer.class,
                                owner),
                        "Center chỉ tồn tại sau khi operation SUCCEEDED");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ordinaryUserCannotCreateTenant() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-plain");
        try {
            seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/centers")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-plain-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"name\":\"Trung tâm tự tạo\",\"ownerUserId\":\""
                                                                + UUID.randomUUID()
                                                                + "\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Người thường không được tự tạo tenant");
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM center WHERE name = 'Trung tâm tự tạo'",
                                Integer.class),
                        "Lời gọi bị từ chối không được để lại tenant");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void ordinaryUserCannotListPlatformCenters() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-list-plain");
        try {
            seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(get("/api/platform/centers").cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Danh sách tenant chỉ dành cho operator");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void operatorListsPlatformCenters() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            seedAccount(operator.subject(), "SELF_REGISTERED", null, false);

            try (var session = openSession(operator)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(get("/api/platform/centers").cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertTrue(body.isArray(), "Danh sách tenant là một mảng");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ordinaryUserCannotConfirmSubscription() throws Exception {
        KeycloakLogin user = createVerifiedUser("center-sub-plain");
        try {
            UUID actor = seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);
            UUID center = seedCenter(actor);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 0, 365)))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Người thường không tự kích hoạt gói");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void operatorConfirmsSubscriptionWithValidPeriod() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
            UUID center = seedCenter(owner);

            try (var session = openSession(operator)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-op-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 0, 365)))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);

                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                                Integer.class,
                                center),
                        "Kỳ gói chỉ được ghi sau khi operation SUCCEEDED");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void subscriptionWithEndBeforeStartIsRejected() throws Exception {
        KeycloakLogin operator = createOperator();
        try {
            UUID owner = seedAccount(operator.subject(), "SELF_REGISTERED", null, false);
            UUID center = seedCenter(owner);

            try (var session = openSession(operator)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/platform/subscriptions")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-sub-bad-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(subscriptionJson(center, 30, 0)))
                                .andReturn()
                                .getResponse();

                assertEquals(400, response.getStatus(), "Kỳ gói phải có mốc kết thúc sau bắt đầu");
            }
        } finally {
            deleteUser(operator.subject());
        }
    }

    @Test
    void ownerAppointsManagerInOwnCenterOnly() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner");
        KeycloakLogin memberLogin = createVerifiedUser("center-member");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            UUID membershipA = addMembership(centerA, member, "STUDENT");
            addMembership(centerB, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                long version = membershipVersion(session, centerA, membershipA);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + centerA
                                                        + "/memberships/"
                                                        + membershipA
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of("MANAGER"),
                    activeRoles(centerA, member),
                    "Bổ nhiệm manager chỉ đổi quyền trong đúng center");
            assertEquals(
                    Set.of("STUDENT"),
                    activeRoles(centerB, member),
                    "Quyền ở center còn lại không bị kéo theo");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void managerCannotAppointAnotherManager() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-two");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(managerLogin)) {
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-mgr-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Manager không được bổ nhiệm manager khác");
                assertEquals(
                        Set.of("STUDENT"),
                        activeRoles(center, member),
                        "Lời gọi bị từ chối không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void managerCannotPromoteSelfToOwner() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-three");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, manager, "MANAGER");

            try (var session = openSession(managerLogin)) {
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-role-self-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"OWNER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Manager không tự trở thành owner");
                assertEquals(
                        Set.of("MANAGER"),
                        activeRoles(center, manager),
                        "Lời gọi bị từ chối không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
        }
    }

    @Test
    void teacherCannotCreateCenterClassEvenWithPersonalPackage() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-four");
        KeycloakLogin teacherLogin = createVerifiedUser("center-teacher");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacher =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, teacher, "TEACHER");
            seedSubscription(personalWorkspaceOf(teacher), -1, 365, owner);

            try (var session = openSession(teacherLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/classes")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-class-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"name\":\"Lớp của giáo viên\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        403,
                        response.getStatus(),
                        "Giáo viên không tạo được lớp center dù có gói cá nhân");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    @Test
    void roleChangeBatchReturnsPendingOperation() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-five");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-three");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-batch-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(
                        body.get("completed").booleanValue(),
                        "Đổi role theo batch chưa hoàn tất thì không được báo xong sớm");
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of("TEACHER"),
                    activeRoles(center, member),
                    "Batch vừa gỡ role cũ vừa thêm role mới phải áp đủ cả hai phần");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void cannotRemoveLastOwner() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-last");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID ownerMembership =
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT id FROM center_membership"
                                            + " WHERE workspace_id = ? AND user_id = ?",
                                    UUID.class,
                                    center,
                                    owner),
                            "membership của owner");
            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        delete(
                                                        "/api/workspaces/"
                                                                + center
                                                                + "/memberships/"
                                                                + ownerMembership)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-last-owner-" + UUID.randomUUID()))
                                .andReturn()
                                .getResponse();

                // Từ chối vì vi phạm quy tắc nghiệp vụ (giống invite OWNER → 400),
                // không phải xung đột version hay danh tính trùng (những trường hợp đó mới 409).
                assertEquals(400, response.getStatus(), "Không được gỡ owner cuối cùng của center");
                assertEquals(
                        Set.of("OWNER"),
                        activeRoles(center, owner),
                        "Owner cuối cùng vẫn còn quyền sau lời gọi bị từ chối");
            }
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void roleChangeWithoutIfMatchIsPreconditionRequired() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-no-ifmatch");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-no-ifmatch");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-no-ifmatch-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        428, response.getStatus(), "Đổi role thiếu If-Match phải bị từ chối 428");
                assertEquals(
                        Set.of("STUDENT"),
                        activeRoles(center, member),
                        "Lời gọi thiếu precondition không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void roleChangeWithStaleVersionIsConflict() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-stale");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-stale");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            UUID membership = addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                long version = membershipVersion(session, center, membership);
                MockHttpServletResponse first =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-stale-first-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"TEACHER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, first.getStatus());
                UUID operationId =
                        UUID.fromString(
                                JSON.readTree(first.getContentAsString())
                                        .get("operationId")
                                        .stringValue());
                awaitOperationSucceeded(session, operationId);

                // Đổi role lần đầu đã cập nhật row membership nên version tăng; version cũ không
                // còn khớp.
                MockHttpServletResponse stale =
                        mockMvc()
                                .perform(
                                        put("/api/workspaces/"
                                                        + center
                                                        + "/memberships/"
                                                        + membership
                                                        + "/roles")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-stale-second-" + UUID.randomUUID())
                                                .header("If-Match", "\"" + version + "\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(409, stale.getStatus(), "Đổi role với version cũ phải bị từ chối 409");
                assertEquals(
                        Set.of("TEACHER"),
                        activeRoles(center, member),
                        "Lời gọi lỗi version không được đổi role");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void expiredSubscriptionBlocksInviteButNotRevoke() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-six");
        KeycloakLogin managerLogin = createVerifiedUser("center-manager-three");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-four");
        KeycloakLogin inviteeLogin = createVerifiedUser("center-invitee-four");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            UUID membership = addMembership(center, member, "STUDENT");
            seedSubscription(center, -60, -1, owner);

            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse invite =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-exp-invite-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, invite.getStatus(), "Gói hết hạn chặn mời thành viên mới");

                MockHttpServletResponse revoke =
                        mockMvc()
                                .perform(
                                        delete(
                                                        "/api/workspaces/"
                                                                + center
                                                                + "/memberships/"
                                                                + membership)
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-exp-revoke-" + UUID.randomUUID()))
                                .andReturn()
                                .getResponse();

                assertEquals(202, revoke.getStatus(), "Thu hồi vẫn được phép khi gói hết hạn");
                JsonNode body = JSON.readTree(revoke.getContentAsString());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    Set.of(),
                    activeRoles(center, member),
                    "Thành viên đã bị thu hồi dù gói hết hạn");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(memberLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void operationStatusHiddenFromOtherUser() throws Exception {
        KeycloakLogin requesterLogin = createVerifiedUser("center-requester");
        KeycloakLogin otherLogin = createVerifiedUser("center-other");
        try {
            UUID requester =
                    seedAccount(
                            requesterLogin.subject(),
                            "SELF_REGISTERED",
                            requesterLogin.email(),
                            true);
            seedAccount(otherLogin.subject(), "SELF_REGISTERED", otherLogin.email(), true);

            UUID requestId;
            try (var session = openSession(requesterLogin)) {
                MockHttpServletResponse created =
                        mockMvc()
                                .perform(
                                        post("/api/me/recovery-requests")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-op-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"targetUserId\":\""
                                                                + requester
                                                                + "\",\"reason\":\"Quên mật khẩu\"}"))
                                .andReturn()
                                .getResponse();
                assertEquals(202, created.getStatus());
                requestId =
                        UUID.fromString(
                                JSON.readTree(created.getContentAsString())
                                        .get("operationId")
                                        .stringValue());
            }

            try (var session = openSession(otherLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        get("/api/me/operations/" + requestId)
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        404,
                        response.getStatus(),
                        "Trạng thái operation của người khác bị giấu thành không tồn tại");
            }
        } finally {
            deleteUser(requesterLogin.subject());
            deleteUser(otherLogin.subject());
        }
    }

    @Test
    void workspacesEndpointDoesNotLeakOtherCenters() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-seven");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-five");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID centerA = seedCenter(owner);
            UUID centerB = seedCenter(owner);
            addMembership(centerA, member, "STUDENT");

            try (var session = openSession(memberLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(get("/api/me/workspaces").cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertTrue(body.isArray());
                List<String> ids = new ArrayList<>();
                body.forEach(item -> ids.add(item.get("id").stringValue()));
                assertTrue(
                        ids.contains(centerA.toString()), "Thành viên thấy center mình tham gia");
                assertFalse(
                        ids.contains(centerB.toString()), "Không lộ center mình không tham gia");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void capabilitiesForAnotherProfileIsRejected() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-eight");
        KeycloakLogin memberLogin = createVerifiedUser("center-member-six");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID member =
                    seedAccount(
                            memberLogin.subject(), "SELF_REGISTERED", memberLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, member, "STUDENT");

            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/capabilities")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"targets\":[{\"type\":\"PROFILE\",\"id\":\""
                                                                + member
                                                                + "\",\"scopes\":[\"profile.read\"]}]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        400,
                        response.getStatus(),
                        "Capabilities chỉ xét target của chính actor, không xét profile người khác");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(memberLogin.subject());
        }
    }

    @Test
    void mutationWithoutCsrfIsRejected() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("center-owner-ten");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .header(
                                                        "Idempotency-Key",
                                                        "center-nocsrf-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + owner
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Mutation trình duyệt thiếu CSRF bị chặn");
            }
        } finally {
            deleteUser(ownerLogin.subject());
        }
    }

    @Test
    void anonymousMembershipListIsRedirectedToLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(get("/api/workspaces/" + UUID.randomUUID() + "/memberships"))
                        .andReturn()
                        .getResponse();

        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"),
                "Chưa đăng nhập thì được hướng dẫn đăng nhập, không nhận dữ liệu nghiệp vụ");
    }

    // ---------- Dữ kiện thư mục ----------

    private record KeycloakLogin(String username, String password, String subject, String email) {}

    private KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "center-password-" + UUID.randomUUID();
        String email = username + "@example.test";
        String subject = createUser(keycloakUser(username, email, true, password));
        return new KeycloakLogin(username, password, subject, email);
    }

    private KeycloakLogin createOperator() {
        KeycloakLogin login = createVerifiedUser("center-operator");
        joinGroup(login.subject(), Objects.requireNonNull(operatorGroupId, "operatorGroupId"));
        return login;
    }

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
                    "center-user-" + actor,
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

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId),
                "personal workspace");
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
                    "center-org-" + center,
                    "Center test",
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

    private UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
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
        return membership;
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

    // Row version của membership để gửi If-Match khi đổi role; hợp đồng bắt buộc
    // precondition này, thiếu thì server trả 428.
    private long membershipVersion(RealSession session, UUID workspaceId, UUID membershipId)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc()
                        .perform(
                                get("/api/workspaces/" + workspaceId + "/memberships")
                                        .cookie(session.cookie()))
                        .andReturn()
                        .getResponse();
        assertEquals(200, response.getStatus(), "Đọc danh sách thành viên để lấy row version");
        JsonNode found = null;
        for (JsonNode item : JSON.readTree(response.getContentAsString()).get("items")) {
            if (membershipId.toString().equals(item.get("id").stringValue())) {
                found = item;
                break;
            }
        }
        return Objects.requireNonNull(
                        found, "Không thấy membership trong danh sách: " + membershipId)
                .get("version")
                .longValue();
    }

    private void seedSubscription(
            UUID workspaceId, int validFromOffsetDays, int expiresOffsetDays, UUID confirmedBy) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(validFromOffsetDays);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresOffsetDays);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    validFrom,
                    expiresAt,
                    "center-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    private String subscriptionJson(UUID workspaceId, int validFromDaysAgo, int expiresInDays) {
        OffsetDateTime validFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(validFromDaysAgo);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays);
        return "{\"workspaceId\":\""
                + workspaceId
                + "\",\"validFrom\":\""
                + validFrom
                + "\",\"expiresAt\":\""
                + expiresAt
                + "\",\"confirmationRef\":\"center-confirm-"
                + UUID.randomUUID()
                + "\"}";
    }

    private void awaitOperationSucceeded(RealSession session, UUID operationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(
                                    get("/api/me/operations/" + operationId)
                                            .cookie(session.cookie()))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus(), "Đường poll operation phải đọc được");
            JsonNode body = JSON.readTree(response.getContentAsString());
            if (body.get("completed").booleanValue()) {
                assertEquals(
                        "SUCCEEDED",
                        body.get("state").stringValue(),
                        "Operation hoàn tất nhưng không thành công");
                return;
            }
            sleep();
        }
        fail("Operation did not succeed in time: " + operationId);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for the operation");
        }
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

    private RealSession openSession(KeycloakLogin login) throws Exception {
        return RealSession.open(
                login.username(),
                login.password(),
                login.subject(),
                sessionFilter,
                authorizedClients,
                clientRegistrations,
                sessions);
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

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

    private void deleteUser(String userId) {
        deleteResource(admin("/users/" + userId));
    }

    private void joinGroup(String userId, String groupId) {
        HTTP.put()
                .uri(admin("/users/" + userId + "/groups/" + groupId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private String admin(String path) {
        return adminStatic(path);
    }

    private static String adminStatic(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private static String ensureGroup(String groupPath) {
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : groupPath.substring(1).split("/")) {
            path.append('/').append(segment);
            String currentPath = path.toString();
            String found = null;
            try {
                Map<String, Object> group =
                        HTTP.get()
                                .uri(adminStatic("/group-by-path" + currentPath))
                                .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                                .retrieve()
                                .body(MAP);
                if (group != null && currentPath.equals(group.get("path"))) {
                    found = String.valueOf(group.get("id"));
                }
            } catch (RestClientResponseException ex) {
                if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    throw ex;
                }
            }
            if (found == null) {
                String endpoint =
                        parentId == null ? "/groups" : "/groups/" + parentId + "/children";
                found = createResource(adminStatic(endpoint), Map.of("name", segment));
                OWNED_GROUP_IDS.add(found);
            }
            parentId = found;
        }
        return Objects.requireNonNull(parentId, "operator group");
    }

    private static String createResource(String uri, Map<String, ?> body) {
        var response =
                HTTP.post()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a location for " + uri);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void deleteResource(String uri) {
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
     * của US2 trong package này. Đăng nhập đi qua form login của Keycloak TEST bằng client BFF của
     * ứng dụng; không dùng Direct Access Grants cho phiên web.
     */
    static final class RealSession implements AutoCloseable {
        private static final String REDIRECT_URI = "http://127.0.0.1:18888/callback";
        private static final Pattern LOGIN_FORM =
                Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
        private static final SecureRandom RANDOM = new SecureRandom();

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
            Tokens tokens = webTokensViaCodePkce(username, password);
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

        private record Tokens(String accessToken, String idToken, String refreshToken) {}

        private static Tokens webTokensViaCodePkce(String username, String password) {
            String client = fixture("TEST_OIDC_CLIENT_ID");
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
                                                .digest(
                                                        verifier.getBytes(
                                                                StandardCharsets.US_ASCII)));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("PKCE requires SHA-256", ex);
            }
            String state = UUID.randomUUID().toString();
            String url =
                    BASE
                            + "/realms/"
                            + REALM
                            + "/protocol/openid-connect/auth?client_id="
                            + encode(client)
                            + "&redirect_uri="
                            + encode(REDIRECT_URI)
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
                var form = LOGIN_FORM.matcher(loginPage.body());
                if (!form.find()) {
                    throw new IllegalStateException("Native TEST Code login form not found");
                }
                String loginUrl = form.group(1).replace("&amp;", "&");
                if (!loginUrl.startsWith(BASE + "/realms/" + REALM + "/login-actions/")) {
                    throw new IllegalStateException("Unexpected TEST login form target");
                }
                // Cookie Secure của TEST Keycloak chỉ được trả về đúng login action loopback này.
                String loginCookies =
                        loginPage.headers().allValues("set-cookie").stream()
                                .map(cookie -> cookie.split(";", 2)[0])
                                .collect(Collectors.joining("; "));
                var loginResponse =
                        browser.send(
                                HttpRequest.newBuilder(URI.create(loginUrl))
                                        .header("Cookie", loginCookies)
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
                if (!REDIRECT_URI.equals(
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
                exchange.add("client_secret", fixture("TEST_OIDC_CLIENT_SECRET"));
                exchange.add("redirect_uri", REDIRECT_URI);
                exchange.add("code", code);
                exchange.add("code_verifier", verifier);
                Map<String, Object> tokens =
                        HTTP.post()
                                .uri(BASE + "/realms/" + REALM + "/protocol/openid-connect/token")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .body(exchange)
                                .retrieve()
                                .body(MAP);
                assertNotNull(tokens);
                return new Tokens(
                        Objects.requireNonNull((String) tokens.get("access_token"), "access_token"),
                        Objects.requireNonNull((String) tokens.get("id_token"), "id_token"),
                        Objects.requireNonNull(
                                (String) tokens.get("refresh_token"), "refresh_token"));
            } catch (IOException exception) {
                throw new IllegalStateException("Native TEST Code+PKCE login failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Native TEST Code+PKCE login interrupted", exception);
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
