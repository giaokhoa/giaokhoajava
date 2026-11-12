package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.service.IdentityService;
import com.giaokhoa.identity.service.IdentityService.BootstrapResult;
import com.giaokhoa.identity.service.IdentityService.CurrentUser;
import com.giaokhoa.identity.service.IssuedStudentRegistrationService;
import com.giaokhoa.identity.service.IssuedStudentRegistrationService.IssuedStudent;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.service.MembershipService;
import com.giaokhoa.tenancy.service.WorkspaceDirectoryService;
import com.giaokhoa.tenancy.service.WorkspaceDirectoryService.ListedWorkspace;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Bootstrap profile/user/personal workspace, thư mục workspace và cấp danh tính học sinh trên stack
 * TEST thật: bootstrap đọc profile hiện hành từ Keycloak 26.8.1, directory và registrar chỉ chạm
 * PostgreSQL.
 *
 * <p>Ở tầng service, lớp kiểm thử này khóa các hành vi sau:
 *
 * <ul>
 *   <li>cùng account chuyển A/B đúng quyền
 *   <li>không email không bị ép bổ sung
 *   <li>khôi phục không cấp lại mật khẩu cho quản lý
 *   <li>rời một center không mất danh tính
 * </ul>
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BootstrapProfileIT extends IntegrationEnvironment {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private static final RestClient HTTP = RestClient.create();
    private static final String BASE = required("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    private static final String REALM = required("TEST_KEYCLOAK_REALM");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired IdentityService identityService;
    @Autowired WorkspaceDirectoryService directory;
    @Autowired IssuedStudentRegistrationService registrar;
    @Autowired MembershipService memberships;

    // Worker không thuộc phạm vi kiểm thử của class này (mọi khẳng định đều qua service đồng
    // bộ): mock để worker nền không tự xử lý operation song song với các lời gọi service
    // trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    /**
     * Membership của một user trong một workspace, đọc qua đúng service mà endpoint danh sách gọi.
     */
    private List<MembershipService.MembershipView> membershipsOf(UUID workspaceId, UUID userId) {
        List<MembershipService.MembershipView> all = new ArrayList<>();
        String cursor = null;
        do {
            MembershipService.MembershipPage page =
                    memberships.pageMemberships(userId, workspaceId, cursor, 100);
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all.stream().filter(item -> item.userId().equals(userId)).toList();
    }

    @Test
    void bootstrapCreatesAccountAndPersonalWorkspaceForNewSelfRegisteredIdentity() {
        String username = "bootstrap-new-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, username + "@example.test", true));
        try {
            BootstrapResult result = identityService.bootstrap(subject);

            assertTrue(result.created());
            CurrentUser current = identityService.currentUser(result.userId());
            assertEquals(UserAccount.Origin.SELF_REGISTERED, current.origin());
            assertEquals(username + "@example.test", current.email());
            assertTrue(current.emailVerified());
            assertEquals(
                    List.of(
                            new ListedWorkspace(
                                    result.personalWorkspaceId(),
                                    com.giaokhoa.tenancy.model.Workspace.Kind.PERSONAL,
                                    "ACTIVE",
                                    null)),
                    directory.listWorkspaces(result.userId()));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapIsIdempotentAndRefreshesEmailFromCurrentIdentity() {
        String username = "bootstrap-refresh-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, "before@example.test", true));
        try {
            BootstrapResult first = identityService.bootstrap(subject);

            updateProfile(subject, "after@example.test", false);
            BootstrapResult second = identityService.bootstrap(subject);

            assertFalse(second.created());
            assertEquals(first.userId(), second.userId());
            assertEquals(first.personalWorkspaceId(), second.personalWorkspaceId());
            assertEquals("after@example.test", identityService.currentUser(first.userId()).email());
            assertFalse(identityService.currentUser(first.userId()).emailVerified());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM user_account WHERE keycloak_sub = ?",
                            Integer.class,
                            subject));
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapKeepsOriginOfExistingCenterIssuedAccount() {
        String username = "bootstrap-issued-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, username + "@example.test", true));
        try {
            UUID actor = seedAccount(subject, username, "CENTER_ISSUED", null, false);

            BootstrapResult result = identityService.bootstrap(subject);

            assertFalse(result.created());
            assertEquals(actor, result.userId());
            CurrentUser current = identityService.currentUser(actor);
            assertEquals(UserAccount.Origin.CENTER_ISSUED, current.origin());
            assertEquals(username + "@example.test", current.email());
            assertTrue(current.emailVerified());
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapWithoutEmailDoesNotForceEmail() {
        String username = "bootstrap-noemail-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, null, false));
        try {
            BootstrapResult result = identityService.bootstrap(subject);

            assertTrue(result.created());
            assertNull(identityService.currentUser(result.userId()).email());
            assertFalse(identityService.currentUser(result.userId()).emailVerified());
        } finally {
            deleteUser(subject);
        }
    }

    @Test
    void bootstrapWithUnreadableIdentityFailsClosedWithoutWriting() {
        String missingSubject = UUID.randomUUID().toString();

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> identityService.bootstrap(missingSubject));

        assertEquals(503, failure.getStatus().value());
        assertEquals("IDENTITY_UNAVAILABLE", failure.getCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM user_account WHERE keycloak_sub = ?",
                        Integer.class,
                        missingSubject));
    }

    @Test
    void directoryListsOnlyValidWorkspacesAndRolesStayPerWorkspace() {
        UUID actor = seedAccount("directory-actor-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID owner = seedAccount("directory-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID centerA = seedCenter(owner);
        UUID centerB = seedCenter(owner);
        addMembership(centerA, actor, "MANAGER");
        addMembership(centerB, actor, "STUDENT");

        List<ListedWorkspace> listed = directory.listWorkspaces(actor);

        assertEquals(3, listed.size());
        assertEquals(com.giaokhoa.tenancy.model.Workspace.Kind.PERSONAL, listed.getFirst().kind());
        assertEquals(Set.of(centerA, centerB), Set.of(listed.get(1).id(), listed.get(2).id()));
        assertEquals(Set.of(MembershipRole.Role.MANAGER), directory.rolesIn(actor, centerA));
        assertEquals(Set.of(MembershipRole.Role.STUDENT), directory.rolesIn(actor, centerB));
        assertEquals(
                List.of(),
                directory.listWorkspaces(UUID.randomUUID()),
                "Người ngoài không thấy workspace nào");
    }

    @Test
    void registrarIssuesStudentWithoutEmailAndReissueIsIdempotent() {
        UUID owner = seedAccount("registrar-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID center = seedCenter(owner);
        String studentSub = "issued-sub-" + UUID.randomUUID();
        String studentUsername = "issued-student-" + UUID.randomUUID();

        IssuedStudent first = registrar.issueStudent(owner, center, studentSub, studentUsername);
        IssuedStudent second = registrar.issueStudent(owner, center, studentSub, studentUsername);

        assertTrue(first.accountCreated());
        assertTrue(first.membershipCreated());
        assertFalse(second.accountCreated());
        assertFalse(second.membershipCreated());
        assertEquals(first.userId(), second.userId());
        assertEquals(first.membershipId(), second.membershipId());
        assertEquals(
                UserAccount.Origin.CENTER_ISSUED,
                identityService.currentUser(first.userId()).origin());
        assertNull(identityService.currentUser(first.userId()).email());
        List<MembershipService.MembershipView> issuedMemberships =
                membershipsOf(center, first.userId());
        assertEquals(1, issuedMemberships.size());
        assertEquals(first.membershipId(), issuedMemberships.getFirst().id());
        assertEquals("ACTIVE", issuedMemberships.getFirst().state());
        assertEquals(
                Set.of(MembershipRole.Role.STUDENT), directory.rolesIn(first.userId(), center));
    }

    @Test
    void registrarActivatesPendingMembershipOnReissue() {
        UUID owner = seedAccount("registrar-owner4-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID center = seedCenter(owner);
        String studentSub = "pending-sub-" + UUID.randomUUID();
        String studentUsername = "pending-student-" + UUID.randomUUID();
        UUID student = seedAccount(studentSub, studentUsername, "SELF_REGISTERED", null);
        UUID pending = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'PENDING',now())",
                    pending,
                    center,
                    student);
            return null;
        });
        OffsetDateTime joinedBefore =
                jdbc.queryForObject(
                        "SELECT joined_at FROM center_membership WHERE id = ?",
                        OffsetDateTime.class,
                        pending);

        IssuedStudent issued = registrar.issueStudent(owner, center, studentSub, studentUsername);

        assertFalse(issued.membershipCreated(), "Membership đã có từ lúc mời nên không tạo mới");
        assertEquals(pending, issued.membershipId());
        List<MembershipService.MembershipView> reissued = membershipsOf(center, student);
        assertEquals(1, reissued.size());
        assertEquals(pending, reissued.getFirst().id());
        assertEquals("ACTIVE", reissued.getFirst().state());
        assertEquals(
                joinedBefore,
                jdbc.queryForObject(
                        "SELECT joined_at FROM center_membership WHERE id = ?",
                        OffsetDateTime.class,
                        pending),
                "Kích hoạt không được sửa lịch sử tham gia");
        assertEquals(Set.of(MembershipRole.Role.STUDENT), directory.rolesIn(student, center));
        assertTrue(
                directory.listWorkspaces(student).stream().anyMatch(w -> w.id().equals(center)),
                "Học sinh vừa được cấp phải thấy center trong thư mục");
    }

    @Test
    void registrarKeepsOriginOfExistingIdentityAndRejectsUsernameMismatch() {
        UUID owner = seedAccount("registrar-owner2-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID center = seedCenter(owner);
        String existingSub = "existing-sub-" + UUID.randomUUID();
        String existingUsername = "existing-user-" + UUID.randomUUID();
        UUID actor = seedAccount(existingSub, existingUsername, "SELF_REGISTERED", null);

        IssuedStudent issued = registrar.issueStudent(owner, center, existingSub, existingUsername);

        assertFalse(issued.accountCreated());
        assertTrue(issued.membershipCreated());
        assertEquals(actor, issued.userId());
        assertEquals(
                UserAccount.Origin.SELF_REGISTERED, identityService.currentUser(actor).origin());

        HttpProblemException mismatch =
                assertThrows(
                        HttpProblemException.class,
                        () -> registrar.issueStudent(owner, center, existingSub, "other-name"));
        assertEquals(409, mismatch.getStatus().value());
        assertEquals("USERNAME_MISMATCH", mismatch.getCode());
    }

    @Test
    void registrarRejectsOperatorWithoutManagerRole() {
        UUID owner = seedAccount("registrar-owner3-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID center = seedCenter(owner);
        UUID student =
                seedAccount("registrar-student-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        addMembership(center, student, "STUDENT");
        String newSub = "rejected-sub-" + UUID.randomUUID();

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                registrar.issueStudent(
                                        student, center, newSub, "rejected-" + UUID.randomUUID()));

        assertEquals(403, failure.getStatus().value());
        assertEquals("OPERATOR_NOT_AUTHORIZED", failure.getCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM user_account WHERE keycloak_sub = ?",
                        Integer.class,
                        newSub),
                "Operator không đủ quyền thì không ghi gì");
    }

    @Test
    void leavingOneCenterKeepsIdentityPersonalWorkspaceAndOtherCenter() {
        UUID owner = seedAccount("leaver-owner-" + UUID.randomUUID(), "SELF_REGISTERED", null);
        UUID centerA = seedCenter(owner);
        UUID centerB = seedCenter(owner);
        String studentSub = "leaver-sub-" + UUID.randomUUID();
        String studentUsername = "leaver-student-" + UUID.randomUUID();
        IssuedStudent inA = registrar.issueStudent(owner, centerA, studentSub, studentUsername);
        registrar.issueStudent(owner, centerB, studentSub, studentUsername);

        tx(() -> {
            jdbc.update(
                    "UPDATE center_membership SET state = 'REVOKED', revoked_at = now()"
                            + " WHERE workspace_id = ? AND user_id = ? AND state = 'ACTIVE'",
                    centerA,
                    inA.userId());
            return null;
        });

        assertEquals(
                studentSub,
                jdbc.queryForObject(
                        "SELECT keycloak_sub FROM user_account WHERE id = ?",
                        String.class,
                        inA.userId()),
                "Rời một trung tâm không xóa hay đổi danh tính");
        assertEquals("ACTIVE", identityService.currentUser(inA.userId()).identityState());
        assertEquals(
                "ACTIVE",
                directory.listWorkspaces(inA.userId()).stream()
                        .filter(view -> view.id().equals(inA.personalWorkspaceId()))
                        .findFirst()
                        .orElseThrow()
                        .state());
        assertEquals(
                Set.of(inA.personalWorkspaceId(), centerB),
                Set.copyOf(
                        directory.listWorkspaces(inA.userId()).stream()
                                .map(view -> view.id())
                                .toList()));
        assertEquals(Set.of(), directory.rolesIn(inA.userId(), centerA));
        assertEquals(Set.of(MembershipRole.Role.STUDENT), directory.rolesIn(inA.userId(), centerB));
    }

    @Test
    void currentUserProfileReturnsPersonalWorkspaceAndUnknownActorIsNotFound() {
        String username = "profile-user-" + UUID.randomUUID();
        String subject = createUser(keycloakUser(username, username + "@example.test", true));
        try {
            BootstrapResult bootstrapped = identityService.bootstrap(subject);

            CurrentUser profile = identityService.currentUser(bootstrapped.userId());

            assertEquals(username, profile.username());
            assertEquals(username + "@example.test", profile.email());
            assertTrue(profile.emailVerified());
            assertEquals(
                    com.giaokhoa.identity.model.UserAccount.Origin.SELF_REGISTERED,
                    profile.origin());
            assertEquals("ACTIVE", profile.identityState());
            assertEquals(bootstrapped.personalWorkspaceId(), profile.personalWorkspaceId());

            HttpProblemException missing =
                    assertThrows(
                            HttpProblemException.class,
                            () -> identityService.currentUser(UUID.randomUUID()));
            assertEquals(404, missing.getStatus().value());
            assertEquals("ACCOUNT_NOT_FOUND", missing.getCode());
        } finally {
            deleteUser(subject);
        }
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedAccount(String keycloakSub, String origin, @Nullable String email) {
        return seedAccount(keycloakSub, "seed-user-" + UUID.randomUUID(), origin, email, false);
    }

    private UUID seedAccount(
            String keycloakSub, String username, String origin, @Nullable String email) {
        return seedAccount(keycloakSub, username, origin, email, false);
    }

    private UUID seedAccount(
            String keycloakSub,
            String username,
            String origin,
            @Nullable String email,
            boolean emailVerified) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,"
                            + "email_verified,origin,identity_state)"
                            + " VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    keycloakSub,
                    username,
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
                    "bootstrap-org-" + center,
                    "Bootstrap test center",
                    ownerUserId);
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

    private <T> @Nullable T tx(java.util.function.Supplier<@Nullable T> work) {
        return new TransactionTemplate(transactionManager).execute(_ -> work.get());
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    private Map<String, Object> keycloakUser(
            String username, @Nullable String email, boolean emailVerified) {
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
                List.of(
                        Map.of(
                                "type",
                                "password",
                                "value",
                                "bootstrap-" + UUID.randomUUID(),
                                "temporary",
                                false)));
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

    private void updateProfile(String userId, String email, boolean emailVerified) {
        Map<String, Object> user = new HashMap<>(getUser(userId));
        user.put("email", email);
        user.put("emailVerified", emailVerified);
        HTTP.put()
                .uri(admin("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(masterToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
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

    private void deleteUser(String userId) {
        deleteWithConflictRetry(() ->
                HTTP.delete()
                        .uri(admin("/users/" + userId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .toBodilessEntity());
    }

    private String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
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
