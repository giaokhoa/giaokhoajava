package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.CenterAdministrationIT.RealSession;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
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
 * Contract US2 ở tầng lời mời và cấp học sinh: mời account đã có không đổi origin, token chấp nhận
 * một lần và phải đúng người, cấp học sinh mới là create mới (username trùng không chiếm user đã
 * có), handoff không email chỉ xuất hiện trong response CREATE đầu tiên.
 *
 * <p>Đây là task test-first: các endpoint US2 chưa được triển khai nên test khẳng định đúng hợp
 * đồng HTTP và hiện đỏ với 404 vì route chưa tồn tại — đỏ đó là có ý nghĩa. Test mời account đã có
 * ở đây bao phủ đúng hợp đồng của test invitations đang tạm tắt trong IdentityFlowIT, nên không bật
 * lại test đó để tránh trùng lặp. Đường chấp nhận lời mời cần token thật do endpoint tạo lời mời
 * trả về, vì vậy happy-path accept đầy đủ chờ task triển khai rồi mới kiểm chứng được; ở đây chỉ
 * khóa trường hợp token lạ phải bị từ chối.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MembershipInvitationIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "member_invite_" + UUID.randomUUID().toString().replace("-", "");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE =
            CenterAdministrationIT.fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = CenterAdministrationIT.fixture("TEST_KEYCLOAK_REALM");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + SCHEMA + ",public");
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
    void inviteExistingIdentityReturnsPendingOperation() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-create-" + UUID.randomUUID())
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
                assertFalse(body.get("statusUrl").stringValue().isBlank());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, invitee),
                    "Mời account đã có không đổi origin của danh tính đó");
            assertEquals(
                    inviteeLogin.subject(),
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            invitee));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership"
                                    + " WHERE workspace_id = ? AND user_id = ?",
                            Integer.class,
                            center,
                            invitee),
                    "Tạo lời mời chưa tạo membership; membership chỉ có khi người được mời chấp nhận");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void inviteCannotGrantOwnerRole() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner-two");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-owner-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"roles\":[\"OWNER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(400, response.getStatus(), "Lời mời không được cấp role OWNER");
            }

            assertEquals(
                    Set.of(),
                    activeRoles(center, invitee),
                    "Lời mời bị từ chối không được gắn quyền gì");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void managerCanInviteStudentButNotManager() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner-three");
        KeycloakLogin managerLogin = createVerifiedUser("invite-manager");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee-three");
        KeycloakLogin inviteeTwoLogin = createVerifiedUser("invite-invitee-four");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID inviteeTwo =
                    seedAccount(
                            inviteeTwoLogin.subject(),
                            "SELF_REGISTERED",
                            inviteeTwoLogin.email(),
                            true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");

            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse studentInvite =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-student-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, studentInvite.getStatus(), "Manager được mời học sinh");
                JsonNode body = JSON.readTree(studentInvite.getContentAsString());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);

                MockHttpServletResponse managerInvite =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-manager-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + inviteeTwo
                                                                + "\",\"roles\":[\"MANAGER\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, managerInvite.getStatus(), "Chỉ owner mới được mời manager");
            }

            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM center_membership"
                                    + " WHERE workspace_id = ? AND user_id = ?",
                            Integer.class,
                            center,
                            invitee),
                    "Tạo lời mời chưa tạo membership; membership chỉ có khi người được mời chấp nhận");
            assertEquals(
                    Set.of(),
                    activeRoles(center, inviteeTwo),
                    "Lời mời manager bị từ chối không được gắn quyền gì");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(inviteeLogin.subject());
            deleteUser(inviteeTwoLogin.subject());
        }
    }

    @Test
    void acceptWithUnknownTokenIsNotFound() throws Exception {
        KeycloakLogin user = createVerifiedUser("invite-accepter");
        try {
            seedAccount(user.subject(), "SELF_REGISTERED", user.email(), true);

            try (var session = openSession(user)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/invitations/" + UUID.randomUUID() + "/accept")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-accept-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        404,
                        response.getStatus(),
                        "Token lạ không được chấp nhận và không lộ sự tồn tại của lời mời nào");
            }
        } finally {
            deleteUser(user.subject());
        }
    }

    @Test
    void studentIssueReturnsHandoffOnlyOnCreate() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner");
        KeycloakLogin managerLogin = createVerifiedUser("issue-manager");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            String username = "student-" + UUID.randomUUID();

            String temporaryPassword;
            UUID operationId;
            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "issue-create-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\""
                                                                + username
                                                                + "\",\"displayName\":\"Học sinh mới\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertFalse(body.get("completed").booleanValue());
                JsonNode handoff = body.get("handoff");
                assertNotNull(handoff, "Response CREATE đầu tiên phải mang handoff");
                assertEquals(username, handoff.get("username").stringValue());
                temporaryPassword = handoff.get("temporaryPassword").stringValue();
                assertFalse(temporaryPassword.isBlank(), "Handoff phải có mật khẩu tạm");
                operationId = UUID.fromString(body.get("operation").get("id").stringValue());
                awaitOperationSucceeded(session, operationId);

                MockHttpServletResponse poll =
                        mockMvc()
                                .perform(
                                        get("/api/me/operations/" + operationId)
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, poll.getStatus());
                assertFalse(
                        poll.getContentAsString().contains("temporaryPassword"),
                        "Poll trạng thái không được echo mật khẩu tạm");
                assertFalse(
                        poll.getContentAsString().contains(temporaryPassword),
                        "Poll trạng thái không được lộ giá trị mật khẩu tạm");

                MockHttpServletResponse list =
                        mockMvc()
                                .perform(
                                        get("/api/workspaces/" + center + "/memberships")
                                                .cookie(session.cookie()))
                                .andReturn()
                                .getResponse();

                assertEquals(200, list.getStatus());
                assertFalse(
                        list.getContentAsString().contains(temporaryPassword),
                        "Danh sách thành viên không được lộ mật khẩu tạm");
            }

            assertEquals(
                    "CENTER_ISSUED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE username = ?",
                            String.class,
                            username),
                    "Học sinh được cấp mang origin do server ghi, không do client chọn");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
        }
    }

    @Test
    void duplicateUsernameDoesNotTakeOverExistingUser() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner-two");
        KeycloakLogin managerLogin = createVerifiedUser("issue-manager-two");
        KeycloakLogin existingLogin = createVerifiedUser("issue-existing");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID existing =
                    seedAccount(
                            existingLogin.subject(),
                            "SELF_REGISTERED",
                            existingLogin.email(),
                            true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");

            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "issue-dup-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\""
                                                                + existingLogin.username()
                                                                + "\",\"displayName\":\"Kẻ mạo danh\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        409, response.getStatus(), "Username trùng không được chiếm user đã có");
            }

            Map<String, Object> kept = getUser(existingLogin.subject());
            assertEquals(
                    existingLogin.email(),
                    kept.get("email"),
                    "User đã có không bị đổi email hay reset");
            assertEquals(
                    existingLogin.subject(),
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE id = ?",
                            String.class,
                            existing),
                    "Danh tính đã có không bị trỏ sang subject khác");
            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?",
                            String.class,
                            existing));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM user_account WHERE username = ?",
                            Integer.class,
                            existingLogin.username()),
                    "Không tạo thêm account trùng username");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
            deleteUser(existingLogin.subject());
        }
    }

    @Test
    void studentIssueWithoutEmailHasOnlyPasswordChangeAction() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner-three");
        KeycloakLogin managerLogin = createVerifiedUser("issue-manager-three");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            String username = "student-" + UUID.randomUUID();

            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "issue-noemail-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\""
                                                                + username
                                                                + "\",\"displayName\":\"Học sinh không email\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                UUID operationId = UUID.fromString(body.get("operation").get("id").stringValue());
                awaitOperationSucceeded(session, operationId);
            }

            Map<String, Object> created = findUserByUsername(username).orElseThrow();
            assertNull(created.get("email"), "Học sinh cấp không email thì không có email");
            assertEquals(
                    List.of("UPDATE_PASSWORD"),
                    created.get("requiredActions"),
                    "Học sinh được cấp chỉ bị yêu cầu đổi mật khẩu, không bị ép xác minh email");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
        }
    }

    @Test
    void expiredSubscriptionBlocksStudentIssue() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner-four");
        KeycloakLogin managerLogin = createVerifiedUser("issue-manager-four");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            seedSubscription(center, -60, -1, owner);

            try (var session = openSession(managerLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "issue-exp-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\"student-"
                                                                + UUID.randomUUID()
                                                                + "\",\"displayName\":\"Học sinh mới\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Gói hết hạn chặn cấp tài khoản học sinh");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(managerLogin.subject());
        }
    }

    @Test
    void teacherCannotIssueStudent() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner-five");
        KeycloakLogin teacherLogin = createVerifiedUser("issue-teacher");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacher =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, teacher, "TEACHER");

            try (var session = openSession(teacherLogin)) {
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "issue-teacher-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\"student-"
                                                                + UUID.randomUUID()
                                                                + "\",\"displayName\":\"Học sinh mới\"}"))
                                .andReturn()
                                .getResponse();

                assertEquals(403, response.getStatus(), "Giáo viên không được cấp học sinh");
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    // ---------- Dữ kiện thư mục ----------

    private record KeycloakLogin(String username, String password, String subject, String email) {}

    private KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "invite-password-" + UUID.randomUUID();
        String email = username + "@example.test";
        String subject = createUser(keycloakUser(username, email, true, password));
        return new KeycloakLogin(username, password, subject, email);
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
                    "invite-user-" + actor,
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
                    "invite-org-" + center,
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
                    "invite-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
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

    private Optional<Map<String, Object>> findUserByUsername(String username) {
        List<Map<String, Object>> users =
                HTTP.get()
                        .uri(admin("/users?username=" + username + "&exact=true"))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .body(LIST);
        if (users == null || users.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(users.getFirst());
    }

    private void deleteUser(String userId) {
        HTTP.delete()
                .uri(admin("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    private String masterToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", CenterAdministrationIT.fixture("TEST_KEYCLOAK_MASTER_USERNAME"));
        form.add("password", CenterAdministrationIT.fixture("TEST_KEYCLOAK_MASTER_PASSWORD"));
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
}
