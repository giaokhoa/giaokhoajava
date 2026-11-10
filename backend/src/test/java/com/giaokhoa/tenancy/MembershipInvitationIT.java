package com.giaokhoa.tenancy;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.KeycloakSessionFixture;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Contract US2 ở tầng lời mời và cấp học sinh: mời account đã có không đổi origin, token chấp nhận
 * một lần và phải đúng người, cấp học sinh mới là create mới (username trùng không chiếm user đã
 * có), handoff không email chỉ xuất hiện trong response CREATE đầu tiên.
 *
 * <p>Test mời account đã có ở đây là nơi bao phủ hợp đồng invitations đó; bản trùng ở
 * IdentityFlowIT đã gỡ để tránh trùng lặp.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class MembershipInvitationIT extends KeycloakSessionFixture {
    private static final String DEFAULT_PERMISSION = "Default Permission";
    private static final String DEFAULT_RESOURCE = "Default Resource";
    private static final String DEFAULT_POLICY = "Default Policy";

    @BeforeAll
    static void seedApiClient() {
        ensureApiClient();
    }

    @Test
    void issuedIdentityWithoutCurrentEmailCanStillReadItsMemberships() throws Exception {
        KeycloakLogin login = createVerifiedUser("issued-no-email");
        try {
            UUID actor = seedAccount(login.subject(), "CENTER_ISSUED", null, false);
            UUID workspace = seedCenter(actor);
            try (var session = openSession(login)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                Map<String, Object> profile = new HashMap<>(getUser(login.subject()));
                profile.put("email", "");
                profile.put("emailVerified", false);
                HTTP.put()
                        .uri(admin("/users/" + login.subject()))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(profile)
                        .retrieve()
                        .toBodilessEntity();
                assertNull(getUser(login.subject()).get("email"));
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/workspaces/" + workspace + "/memberships")
                                                .cookie(session.cookie()))
                                .getStatus());
            }
        } finally {
            deleteUser(login.subject());
        }
    }

    @Test
    void unverifiedIdentityCannotUseMembershipBusinessRoutes() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("review-unverified-owner");
        KeycloakLogin targetLogin = createVerifiedUser("review-unverified-target");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID target =
                    seedAccount(
                            targetLogin.subject(), "SELF_REGISTERED", targetLogin.email(), true);
            UUID workspace = seedCenter(owner);
            try (var session = openSession(ownerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                HTTP.put()
                        .uri(admin("/users/" + ownerLogin.subject()))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("emailVerified", false))
                        .retrieve()
                        .toBodilessEntity();
                int list =
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/workspaces/" + workspace + "/memberships")
                                                .cookie(session.cookie()))
                                .getStatus();
                int invite =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + workspace + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "review-unverified-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + target
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                org.junit.jupiter.api.Assertions.assertAll(
                        () ->
                                assertEquals(
                                        403,
                                        list,
                                        "SELF_REGISTERED chưa verified không được đọc danh sách nghiệp vụ"),
                        () ->
                                assertEquals(
                                        403,
                                        invite,
                                        "SELF_REGISTERED chưa verified không được mời thành viên"));
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(targetLogin.subject());
        }
    }

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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertFalse(
                        membershipUserIds(session, center).contains(invitee),
                        "Tạo lời mời chưa tạo membership; membership chỉ có khi người được mời"
                                + " chấp nhận");
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
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void inviteByEmailReturnsPendingOperation() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner-email");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee-email");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                MockHttpServletResponse response =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-email-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"email\":\""
                                                                + inviteeLogin.email()
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(202, response.getStatus());
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertEquals("PENDING", body.get("state").stringValue());
                assertFalse(body.get("completed").booleanValue());
                UUID operationId = UUID.fromString(body.get("operationId").stringValue());
                awaitOperationSucceeded(session, operationId);
                assertFalse(
                        membershipUserIds(session, center).contains(invitee),
                        "Tạo lời mời chưa tạo membership; membership chỉ có khi người được mời"
                                + " chấp nhận");
            }

            assertEquals(
                    "SELF_REGISTERED",
                    jdbc.queryForObject(
                            "SELECT origin FROM user_account WHERE id = ?", String.class, invitee),
                    "Mời bằng email không đổi origin của danh tính đó");
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(inviteeLogin.subject());
        }
    }

    @Test
    void inviteWithBothOrNeitherIdentifierIsRejected() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner-both");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee-both");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID invitee =
                    seedAccount(
                            inviteeLogin.subject(), "SELF_REGISTERED", inviteeLogin.email(), true);
            UUID center = seedCenter(owner);

            try (var session = openSession(ownerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                MockHttpServletResponse both =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-both-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + invitee
                                                                + "\",\"email\":\""
                                                                + inviteeLogin.email()
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        400, both.getStatus(), "Lời mời phải có đúng một trong email hoặc userId");

                MockHttpServletResponse neither =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header(
                                                        "Idempotency-Key",
                                                        "invite-neither-" + UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        400,
                        neither.getStatus(),
                        "Lời mời thiếu cả email lẫn userId phải bị từ chối");
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertFalse(
                        membershipUserIds(session, center).contains(invitee),
                        "Tạo lời mời chưa tạo membership; membership chỉ có khi người được mời"
                                + " chấp nhận");
            }

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
    void sameIdempotencyKeyWithDifferentPayloadIsConflict() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("invite-owner-key");
        KeycloakLogin inviteeLogin = createVerifiedUser("invite-invitee-key");
        KeycloakLogin inviteeTwoLogin = createVerifiedUser("invite-invitee-key-two");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
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
            String key = "invite-replay-" + UUID.randomUUID();
            String firstBody = "{\"userId\":\"" + invitee + "\",\"roles\":[\"STUDENT\"]}";

            try (var session = openSession(ownerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                MockHttpServletResponse first =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(firstBody))
                                .andReturn()
                                .getResponse();

                assertEquals(202, first.getStatus());
                UUID operationId =
                        UUID.fromString(
                                JSON.readTree(first.getContentAsString())
                                        .get("operationId")
                                        .stringValue());
                awaitOperationSucceeded(session, operationId);

                // Cùng key, cùng payload → trả lại kết quả đã ghi, không tạo operation mới.
                MockHttpServletResponse replay =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(firstBody))
                                .andReturn()
                                .getResponse();

                assertTrue(
                        replay.getStatus() == 200 || replay.getStatus() == 202,
                        "Phát lại cùng key phải trả lại kết quả đã ghi"
                                + " (200 khi xong, 202 khi chưa xong)");
                assertEquals(
                        operationId.toString(),
                        JSON.readTree(replay.getContentAsString()).get("operationId").stringValue(),
                        "Phát lại cùng key không được tạo operation mới");

                // Cùng key, payload khác → 409.
                MockHttpServletResponse conflict =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/invitations")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"userId\":\""
                                                                + inviteeTwo
                                                                + "\",\"roles\":[\"STUDENT\"]}"))
                                .andReturn()
                                .getResponse();

                assertEquals(
                        409,
                        conflict.getStatus(),
                        "Cùng Idempotency-Key với yêu cầu khác phải bị từ chối 409");
                assertFalse(
                        membershipUserIds(session, center).contains(inviteeTwo),
                        "Lời mời xung đột key không được tạo membership");
            }
        } finally {
            deleteUser(ownerLogin.subject());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                JsonNode operation = body.get("operation");
                assertNotNull(operation, "StudentIssueView bọc biên nhận trong operation");
                assertFalse(operation.get("completed").booleanValue());
                assertEquals("PENDING", operation.get("state").stringValue());
                JsonNode handoff = body.get("handoff");
                assertNotNull(handoff, "Response CREATE đầu tiên phải mang handoff");
                assertEquals(username, handoff.get("username").stringValue());
                temporaryPassword = handoff.get("temporaryPassword").stringValue();
                assertFalse(temporaryPassword.isBlank(), "Handoff phải có mật khẩu tạm");
                operationId = UUID.fromString(operation.get("id").stringValue());
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
    void studentIssueReplayWithSameKeyDoesNotExposeHandoffAgain() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("issue-owner-replay");
        KeycloakLogin managerLogin = createVerifiedUser("issue-manager-replay");
        try {
            UUID owner =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID manager =
                    seedAccount(
                            managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");
            String username = "student-replay-" + UUID.randomUUID();
            String key = "issue-replay-" + UUID.randomUUID();
            String requestBody =
                    "{\"username\":\"" + username + "\",\"displayName\":\"Học sinh phát lại\"}";

            String temporaryPassword;
            UUID operationId;
            try (var session = openSession(managerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
                MockHttpServletResponse first =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(requestBody))
                                .andReturn()
                                .getResponse();

                assertEquals(202, first.getStatus());
                JsonNode firstBody = JSON.readTree(first.getContentAsString());
                JsonNode handoff = firstBody.get("handoff");
                assertNotNull(handoff, "Lần cấp đầu tiên phải mang handoff");
                temporaryPassword = handoff.get("temporaryPassword").stringValue();
                assertFalse(temporaryPassword.isBlank(), "Handoff phải có mật khẩu tạm");
                operationId = UUID.fromString(firstBody.get("operation").get("id").stringValue());
                awaitOperationSucceeded(session, operationId);

                // Phát lại đúng key và payload: trả lại operation đã ghi, tuyệt đối
                // không lộ handoff/credential lần hai ở tầng HTTP.
                MockHttpServletResponse replay =
                        mockMvc()
                                .perform(
                                        post("/api/workspaces/" + center + "/students")
                                                .cookie(session.cookie())
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(requestBody))
                                .andReturn()
                                .getResponse();

                assertTrue(
                        replay.getStatus() == 200 || replay.getStatus() == 202,
                        "Phát lại cùng key phải trả lại kết quả đã ghi"
                                + " (200 khi xong, 202 khi chưa xong)");
                String replayContent = replay.getContentAsString();
                assertFalse(
                        replayContent.contains("temporaryPassword"),
                        "Phát lại cùng key không được chứa lại trường mật khẩu tạm");
                assertFalse(
                        replayContent.contains(temporaryPassword),
                        "Phát lại cùng key không được lộ lại giá trị mật khẩu tạm");
                JsonNode replayBody = JSON.readTree(replayContent);
                JsonNode replayHandoff = replayBody.get("handoff");
                assertTrue(
                        replayHandoff == null || replayHandoff.isNull(),
                        "Phát lại cùng key không mang handoff");
                assertEquals(
                        operationId.toString(),
                        replayBody.get("operation").get("id").stringValue(),
                        "Phát lại cùng key trả đúng operation đã ghi, không tạo operation mới");
            }

            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM user_account WHERE username = ?",
                            Integer.class,
                            username),
                    "Phát lại cùng key không tạo thêm danh tính");
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
            // seedAccount dùng username tổng hợp; test này cần account đã có mang đúng username
            // Keycloak đang bị trùng để khẳng định việc cấp học sinh bị chặn và không tạo thêm.
            tx(() -> {
                jdbc.update(
                        "UPDATE user_account SET username = ? WHERE id = ?",
                        existingLogin.username(),
                        existing);
                return null;
            });
            UUID center = seedCenter(owner);
            addMembership(center, manager, "MANAGER");

            try (var session = openSession(managerLogin)) {
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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
                assertEquals(
                        200,
                        requestWhenReady(
                                        mockMvc(),
                                        get("/api/me/workspaces").cookie(session.cookie()))
                                .getStatus());
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

    /** Đọc danh sách thành viên qua API như người dùng thật: tập userId đang PENDING/ACTIVE. */
    private Set<UUID> membershipUserIds(RealSession session, UUID workspaceId) throws Exception {
        Set<UUID> userIds = new HashSet<>();
        String cursor = null;
        do {
            String path =
                    "/api/workspaces/"
                            + workspaceId
                            + "/memberships?limit=100"
                            + (cursor == null ? "" : "&cursor=" + cursor);
            MockHttpServletResponse response =
                    mockMvc().perform(get(path).cookie(session.cookie())).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            JsonNode body = JSON.readTree(response.getContentAsString());
            for (JsonNode item : body.get("items")) {
                userIds.add(UUID.fromString(item.get("user").get("id").stringValue()));
            }
            cursor = nextCursorOf(body);
        } while (cursor != null);
        return userIds;
    }

    private static @Nullable String nextCursorOf(JsonNode body) {
        return body.hasNonNull("nextCursor") ? body.get("nextCursor").stringValue() : null;
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
        AtomicReference<MockHttpServletResponse> last = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        try {
            await().atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        MockHttpServletResponse response =
                                mockMvc()
                                        .perform(
                                                get("/api/me/operations/" + operationId)
                                                        .cookie(session.cookie()))
                                        .andReturn()
                                        .getResponse();
                        last.set(response);
                        boolean readable = response.getStatus() == 200;
                        if (!readable) {
                            return true;
                        }
                        JsonNode body = JSON.readTree(response.getContentAsString());
                        boolean done = body.get("completed").booleanValue();
                        if (done) {
                            completed.set(true);
                        }
                        return done;
                    });
        } catch (ConditionTimeoutException ignored) {
            // Hết 90 giây: các khẳng định phía dưới báo đúng trạng thái như vòng poll cũ.
        }
        MockHttpServletResponse response = Objects.requireNonNull(last.get());
        assertEquals(200, response.getStatus(), "Đường poll operation phải đọc được");
        if (!completed.get()) {
            fail("Operation did not succeed in time: " + operationId);
        }
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals(
                "SUCCEEDED",
                body.get("state").stringValue(),
                "Operation hoàn tất nhưng không thành công");
    }

    @Override
    protected RealSession openSession(KeycloakLogin login) throws Exception {
        // Không nạp workspace trước như mẫu chung: các ca ở đây tự poll qua fence bằng
        // requestWhenReady tại đúng điểm cần khóa, như hành vi trước khi dùng fixture chung.
        return openSession(login.username(), login.password(), login.subject());
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

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

    private static List<Map<String, Object>> getList(String uri) {
        List<Map<String, Object>> result =
                HTTP.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST);
        if (result == null) {
            throw new IllegalStateException("Keycloak list not readable: " + uri);
        }
        return result;
    }

    /**
     * Resource server client mà policy graph writer resolve theo audience: test-realm.json không
     * khai client này nên class tự tạo khi thiếu (theo mẫu IdentityFlowIT) để chạy độc lập. Client
     * là object realm theo tên cố định dùng chung toàn suite nên không bao giờ xóa ở teardown, kể
     * cả khi chính class này tạo ra.
     */
    private static void ensureApiClient() {
        String audience = fixture("TEST_KEYCLOAK_AUTHZ_AUDIENCE");
        List<Map<String, Object>> matches =
                HTTP.get()
                        .uri(admin("/clients?clientId=" + audience))
                        .headers(headers -> headers.setBearerAuth(masterTokenStatic()))
                        .retrieve()
                        .body(LIST);
        if (matches != null && !matches.isEmpty()) {
            return;
        }
        String clientId =
                createResource(
                        admin("/clients"),
                        Map.of(
                                "clientId",
                                audience,
                                "protocol",
                                "openid-connect",
                                "enabled",
                                true,
                                "publicClient",
                                false,
                                "standardFlowEnabled",
                                false,
                                "directAccessGrantsEnabled",
                                false,
                                "authorizationServicesEnabled",
                                true,
                                "serviceAccountsEnabled",
                                true,
                                "fullScopeAllowed",
                                false,
                                "authorizationSettings",
                                Map.of(
                                        "policyEnforcementMode",
                                        "ENFORCING",
                                        "allowRemoteResourceManagement",
                                        true,
                                        "decisionStrategy",
                                        "UNANIMOUS")));
        removeClientDefaults(clientId);
    }

    private static void removeClientDefaults(String clientId) {
        String root = "/clients/" + clientId + "/authz/resource-server";
        for (Map<String, Object> permission : getList(admin(root + "/permission/resource"))) {
            if (DEFAULT_PERMISSION.equals(permission.get("name"))) {
                deleteResource(admin(root + "/permission/resource/" + permission.get("id")));
            }
        }
        for (Map<String, Object> resource : getList(admin(root + "/resource"))) {
            if (DEFAULT_RESOURCE.equals(resource.get("name"))) {
                deleteResource(admin(root + "/resource/" + resource.get("_id")));
            }
        }
        for (Map<String, Object> policy : getList(admin(root + "/policy"))) {
            if (DEFAULT_POLICY.equals(policy.get("name"))) {
                deleteResource(admin(root + "/policy/" + policy.get("id")));
            }
        }
    }
}
