package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.InvitationService.InvitationIssued;
import com.giaokhoa.tenancy.MembershipService.StudentIssueResult;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kiểm chứng tầng service của lời mời và membership: chấp nhận đúng đích tạo membership ACTIVE kèm
 * role đích, sai đích bị từ chối, token chỉ dùng được một lần, lời mời hết hạn bị từ chối, chấp
 * nhận vào membership đang chờ thì kích hoạt mà giữ nguyên lịch sử tham gia, cộng role mới vào
 * membership hiện hành làm tăng row version, và cấp học sinh phát lại theo cùng khoá không tái lộ
 * handoff. Test gọi thẳng service, không qua endpoint HTTP thuộc task controller riêng.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InvitationMembershipServiceIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA =
            "invitation_service_" + UUID.randomUUID().toString().replace("-", "");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = fixture("TEST_KEYCLOAK_REALM");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    // Worker thật chạy theo lịch sẽ mở giao dịch trên chính schema cô lập này trong khi
    // @AfterAll DROP SCHEMA cần khóa độc quyền mọi bảng — hai bên khóa chéo nhau và PostgreSQL
    // hủy DROP bằng deadlock (cùng cơ chế BootstrapProfileIT). Mọi khẳng định của class này
    // đều đồng bộ nên mock worker không đổi ý nghĩa kiểm thử.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired InvitationService invitations;
    @Autowired MembershipService memberships;
    @Autowired IdempotencyService idempotency;

    @Test
    void acceptByInvitedUserCreatesActiveMembershipWithIntendedRoles() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID center = seedCenter(owner);

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.TEACHER),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"TEACHER\"]}"));
        assertNotNull(issued.token(), "Lần tạo đầu tiên phải mang token thô");

        invitations.accept(
                invitee,
                Objects.requireNonNull(issued.token()),
                "accept-" + UUID.randomUUID(),
                hash("{}"));

        assertEquals("ACTIVE", membershipState(center, invitee));
        assertEquals(Set.of("TEACHER"), activeRoles(center, invitee));
        assertEquals(
                "ACCEPTED",
                jdbc.queryForObject(
                        "SELECT state FROM invitation WHERE id = ?",
                        String.class,
                        issued.invitationId()));
    }

    @Test
    void acceptByAnotherUserIsForbidden() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID stranger = seedAccount();
        UUID center = seedCenter(owner);

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.STUDENT),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"STUDENT\"]}"));

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                invitations.accept(
                                        stranger,
                                        Objects.requireNonNull(issued.token()),
                                        "accept-" + UUID.randomUUID(),
                                        hash("{}")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("INVITATION_TARGET_MISMATCH", ex.getCode());
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM invitation WHERE id = ?",
                        String.class,
                        issued.invitationId()),
                "Chấp nhận sai đích không được tiêu thụ lời mời");
        assertNull(membershipState(center, stranger), "Người lạ không được tạo membership");
    }

    @Test
    void acceptWithConsumedTokenIsConflict() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID center = seedCenter(owner);

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.STUDENT),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"STUDENT\"]}"));
        String token = Objects.requireNonNull(issued.token());
        invitations.accept(invitee, token, "accept-" + UUID.randomUUID(), hash("{}"));

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                invitations.accept(
                                        invitee, token, "accept-" + UUID.randomUUID(), hash("{}")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVITATION_NOT_PENDING", ex.getCode());
    }

    @Test
    void acceptExpiredInvitationIsConflict() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID center = seedCenter(owner);
        String rawToken = "expired-token-" + UUID.randomUUID();
        String expiredHash = tokenHash(rawToken);
        // Chèn thẳng lời mời đã hết hạn vì trigger của bảng cấm sửa expires_at sau khi tạo.
        tx(() -> {
            jdbc.update(
                    "INSERT INTO invitation (id, workspace_id, target_email, target_user_id,"
                            + " token_hash, intended_roles, expires_at, state)"
                            + " VALUES (?, ?, NULL, ?, ?, ARRAY['STUDENT'], ?, 'PENDING')",
                    UUID.randomUUID(),
                    center,
                    invitee,
                    expiredHash,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
            return null;
        });

        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                invitations.accept(
                                        invitee,
                                        rawToken,
                                        "accept-" + UUID.randomUUID(),
                                        hash("{}")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVITATION_EXPIRED", ex.getCode());
        assertNull(membershipState(center, invitee), "Lời mời hết hạn không tạo membership");
    }

    @Test
    void acceptActivatesPendingMembershipKeepingJoinedAt() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID center = seedCenter(owner);
        UUID membership = addMembership(center, invitee, "PENDING", "TEACHER");
        Object joinedAtBefore =
                jdbc.queryForObject(
                        "SELECT joined_at FROM center_membership WHERE id = ?",
                        Object.class,
                        membership);
        long versionBefore = membershipVersion(membership);

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.STUDENT),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"STUDENT\"]}"));
        invitations.accept(
                invitee,
                Objects.requireNonNull(issued.token()),
                "accept-" + UUID.randomUUID(),
                hash("{}"));

        assertEquals("ACTIVE", membershipState(center, invitee));
        assertEquals(Set.of("TEACHER", "STUDENT"), activeRoles(center, invitee));
        assertEquals(
                joinedAtBefore,
                jdbc.queryForObject(
                        "SELECT joined_at FROM center_membership WHERE id = ?",
                        Object.class,
                        membership),
                "Kích hoạt membership đang chờ phải giữ nguyên joined_at");
        assertTrue(
                membershipVersion(membership) > versionBefore,
                "Kích hoạt kèm cộng role phải làm tăng version của membership");
    }

    @Test
    void acceptAddingNewRoleToActiveMembershipBumpsVersionOnlyWhenRoleIsNew() throws Exception {
        UUID owner = seedAccount();
        UUID invitee = seedAccount();
        UUID center = seedCenter(owner);
        UUID membership = addMembership(center, invitee, "ACTIVE", "TEACHER");
        long versionBefore = membershipVersion(membership);

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.STUDENT),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"STUDENT\"]}"));
        invitations.accept(
                invitee,
                Objects.requireNonNull(issued.token()),
                "accept-" + UUID.randomUUID(),
                hash("{}"));

        assertEquals(Set.of("TEACHER", "STUDENT"), activeRoles(center, invitee));
        assertEquals(
                versionBefore + 1,
                membershipVersion(membership),
                "Cộng một role mới vào membership hiện hành phải tăng version đúng một lần");

        InvitationIssued duplicate =
                invitations.invite(
                        owner,
                        center,
                        null,
                        invitee,
                        Set.of(MembershipRole.Role.STUDENT),
                        "invite-" + UUID.randomUUID(),
                        hash("{\"roles\":[\"STUDENT\"]}"));
        invitations.accept(
                invitee,
                Objects.requireNonNull(duplicate.token()),
                "accept-" + UUID.randomUUID(),
                hash("{}"));

        assertEquals(
                versionBefore + 1,
                membershipVersion(membership),
                "Chấp nhận mà không cộng role mới thì không được tăng version thừa");
    }

    @Test
    void issueStudentReplayWithSameKeyDoesNotExposeHandoffAgain() throws Exception {
        UUID owner = seedAccount();
        UUID center = seedCenter(owner);
        String username = "service-student-" + UUID.randomUUID();
        String key = "issue-" + UUID.randomUUID();
        String payloadHash = hash("{\"username\":\"" + username + "\"}");
        String issuedSubject = null;
        try {
            StudentIssueResult first =
                    memberships.issueStudent(
                            owner, center, username, "Học Sinh Mới", null, key, payloadHash);
            assertNotNull(first.handoff(), "Lần cấp đầu tiên phải mang handoff credential");
            assertEquals(username, first.handoff().username());
            issuedSubject =
                    jdbc.queryForObject(
                            "SELECT keycloak_sub FROM user_account WHERE username = ?",
                            String.class,
                            username);

            StudentIssueResult replay =
                    memberships.issueStudent(
                            owner, center, username, "Học Sinh Mới", null, key, payloadHash);
            assertEquals(
                    first.operation().getId(),
                    replay.operation().getId(),
                    "Phát lại cùng khoá phải trả đúng operation đã ghi");
            assertNull(replay.handoff(), "Phát lại không được tái lộ handoff hay credential");
            assertEquals(
                    1,
                    Objects.requireNonNull(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM user_account WHERE username = ?",
                                    Integer.class,
                                    username)),
                    "Phát lại không tạo thêm danh tính");
        } finally {
            if (issuedSubject != null) {
                deleteKeycloakUser(issuedSubject);
            }
        }
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedAccount() {
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        String username = "service-user-" + user;
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    user,
                    "sub-" + user,
                    username,
                    username + "@example.test",
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    user);
            return null;
        });
        return user;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
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
                    "service-org-" + center,
                    "Center kiểm chứng service",
                    ownerUserId);
            UUID ownerMembership = UUID.randomUUID();
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

    private UUID addMembership(UUID workspaceId, UUID userId, String state, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,?::varchar,now())",
                    membership,
                    workspaceId,
                    userId,
                    state);
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

    private @Nullable String membershipState(UUID workspaceId, UUID userId) {
        List<String> states =
                jdbc.queryForList(
                        "SELECT state FROM center_membership WHERE workspace_id = ? AND user_id = ?"
                                + " AND state IN ('PENDING', 'ACTIVE')",
                        String.class,
                        workspaceId,
                        userId);
        return states.isEmpty() ? null : states.getFirst();
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

    private long membershipVersion(UUID membershipId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT version FROM center_membership WHERE id = ?",
                        Long.class,
                        membershipId));
    }

    private String hash(String body) throws Exception {
        return idempotency.payloadHash("POST", "/api/test", JSON.readTree(body));
    }

    private static String tokenHash(String rawToken) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    // ---------- Dọn user Keycloak do luồng cấp học sinh tạo ----------

    private static void deleteKeycloakUser(String subject) {
        try {
            HTTP.delete()
                    .uri(BASE + "/admin/realms/" + REALM + "/users/" + subject)
                    .headers(headers -> headers.setBearerAuth(masterToken()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw ex;
            }
        }
    }

    private static String masterToken() {
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
}
