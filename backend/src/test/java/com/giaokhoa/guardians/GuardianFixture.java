package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.OidcFormLogin;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing dùng chung cho các test phụ huynh ở tầng HTTP: seed center/thành viên (gồm vai trò
 * PARENT), gói, lớp/khóa/ghi danh, phiên Spring thật qua Code+PKCE và các lời gọi guardian-link
 * theo hợp đồng. Dựng theo cùng mẫu với fixture giảng dạy; không chứa quyết định quyền nào, không
 * chứa test.
 */
abstract class GuardianFixture extends IntegrationEnvironment {
    protected static final JsonMapper JSON = new JsonMapper();
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    protected static final RestClient HTTP = RestClient.create();
    protected static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    protected static final String REALM = fixture("TEST_KEYCLOAK_REALM");
    protected static final String REDIRECT_URI = "http://127.0.0.1:18888/callback";
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected WebApplicationContext webContext;
    @Autowired protected SessionRepositoryFilter<?> sessionFilter;
    @Autowired protected OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired protected ClientRegistrationRepository clientRegistrations;
    @Autowired protected SessionRepository<?> sessions;

    // ---------- Dữ kiện thư mục ----------

    protected record KeycloakLogin(
            String username, String password, String subject, String email) {}

    protected record GuardianCenter(
            UUID workspaceId,
            KeycloakLogin owner,
            UUID ownerId,
            KeycloakLogin manager,
            UUID managerId,
            KeycloakLogin homeroom,
            UUID homeroomId,
            KeycloakLogin teacher,
            UUID teacherId,
            KeycloakLogin parent,
            UUID parentId,
            KeycloakLogin otherParent,
            UUID otherParentId,
            List<KeycloakLogin> students,
            List<UUID> studentIds) {
        protected GuardianCenter {
            // Danh sách nội bộ không lộ ra ngoài qua accessor của record.
            students = List.copyOf(students);
            studentIds = List.copyOf(studentIds);
        }

        protected UUID studentX() {
            return studentIds.get(0);
        }

        protected UUID studentY() {
            return studentIds.get(1);
        }
    }

    protected GuardianCenter seedGuardianCenter() {
        KeycloakLogin ownerLogin = createVerifiedUser("guardian-owner");
        KeycloakLogin managerLogin = createVerifiedUser("guardian-manager");
        KeycloakLogin homeroomLogin = createVerifiedUser("guardian-homeroom");
        KeycloakLogin teacherLogin = createVerifiedUser("guardian-teacher");
        KeycloakLogin parentLogin = createVerifiedUser("guardian-parent");
        KeycloakLogin otherParentLogin = createVerifiedUser("guardian-parent-b");
        List<KeycloakLogin> studentLogins =
                List.of(
                        createVerifiedUser("guardian-student-x"),
                        createVerifiedUser("guardian-student-y"));
        UUID ownerId = seedAccount(ownerLogin);
        UUID managerId = seedAccount(managerLogin);
        UUID homeroomId = seedAccount(homeroomLogin);
        UUID teacherId = seedAccount(teacherLogin);
        UUID parentId = seedAccount(parentLogin);
        UUID otherParentId = seedAccount(otherParentLogin);
        List<UUID> studentIds = new ArrayList<>();
        for (KeycloakLogin student : studentLogins) {
            studentIds.add(seedAccount(student));
        }
        UUID workspaceId = seedCenter(ownerId);
        addMembership(workspaceId, managerId, "MANAGER");
        addMembership(workspaceId, homeroomId, "TEACHER");
        addMembership(workspaceId, teacherId, "TEACHER");
        addMembership(workspaceId, parentId, "PARENT");
        addMembership(workspaceId, otherParentId, "PARENT");
        for (UUID studentId : studentIds) {
            addMembership(workspaceId, studentId, "STUDENT");
        }
        seedSubscription(workspaceId, ownerId);
        return new GuardianCenter(
                workspaceId,
                ownerLogin,
                ownerId,
                managerLogin,
                managerId,
                homeroomLogin,
                homeroomId,
                teacherLogin,
                teacherId,
                parentLogin,
                parentId,
                otherParentLogin,
                otherParentId,
                studentLogins,
                studentIds);
    }

    /** Center thứ hai có cùng chủ sở hữu; phụ huynh và học sinh X cũng là thành viên ở đó. */
    protected UUID seedSecondCenter(GuardianCenter center) {
        UUID workspaceId = seedCenter(center.ownerId());
        addMembership(workspaceId, center.parentId(), "PARENT");
        addMembership(workspaceId, center.studentX(), "STUDENT");
        addMembership(workspaceId, center.studentY(), "STUDENT");
        seedSubscription(workspaceId, center.ownerId());
        return workspaceId;
    }

    protected void deleteGuardianCenter(GuardianCenter center) {
        deleteUser(center.owner().subject());
        deleteUser(center.manager().subject());
        deleteUser(center.homeroom().subject());
        deleteUser(center.teacher().subject());
        deleteUser(center.parent().subject());
        deleteUser(center.otherParent().subject());
        for (KeycloakLogin student : center.students()) {
            deleteUser(student.subject());
        }
    }

    protected KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "guardian-password-" + UUID.randomUUID();
        String email = username + "@example.test";
        String subject = createUser(keycloakUser(username, email, true, password));
        return new KeycloakLogin(username, password, subject, email);
    }

    protected UUID seedAccount(KeycloakLogin login) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    actor,
                    login.subject(),
                    "guardian-user-" + actor,
                    login.email(),
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    protected UUID seedCenter(UUID ownerUserId) {
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

    protected UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
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

    protected void seedSubscription(UUID workspaceId, UUID confirmedBy) {
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(365),
                    "guardian-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    // ---------- Dữ kiện giảng dạy tối thiểu ----------

    protected UUID createClass(RealSession session, UUID workspaceId, String name)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes",
                        "{\"name\":\"" + name + "\"}");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM school_class WHERE workspace_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        name),
                "Lớp phải tồn tại sau khi operation SUCCEEDED");
    }

    protected UUID createCourse(
            RealSession session, UUID workspaceId, UUID classId, String name, List<UUID> subjectIds)
            throws Exception {
        String subjects =
                subjectIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/courses",
                        "{\"name\":\"" + name + "\",\"subjectIds\":[" + subjects + "]}");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course WHERE workspace_id = ? AND class_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        classId,
                        name),
                "Khóa phải tồn tại sau khi operation SUCCEEDED");
    }

    protected UUID addRoster(
            RealSession session, UUID workspaceId, UUID classId, UUID studentUserId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students",
                        "{\"studentUserIds\":[\"" + studentUserId + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
        return classStudentId(classId, studentUserId);
    }

    protected void enroll(
            RealSession session, UUID workspaceId, UUID classStudentId, UUID courseSubjectId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/enrollments",
                        "{\"classStudentIds\":[\""
                                + classStudentId
                                + "\"],\"courseSubjectIds\":[\""
                                + courseSubjectId
                                + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
    }

    protected void assignHomeroom(
            RealSession session, UUID workspaceId, UUID classId, UUID teacherUserId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/classes/"
                                + classId
                                + "/homeroom-assignments",
                        "{\"teacherUserId\":\"" + teacherUserId + "\"}");
        awaitOperationSucceeded(jdbc, operationId);
    }

    protected void assignTeacher(
            RealSession session, UUID workspaceId, UUID courseSubjectId, UUID teacherUserId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/teachers",
                        "{\"teacherUserIds\":[\"" + teacherUserId + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
    }

    protected record SubjectRef(UUID id, String name) {}

    protected SubjectRef firstSubject(RealSession session) throws Exception {
        JsonNode body = getJson(session, "/api/subjects", 200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh mục môn trả dạng {items}");
        assertTrue(items.isArray() && !items.isEmpty(), "Danh mục môn phải có ít nhất một môn");
        JsonNode first = items.get(0);
        return new SubjectRef(
                UUID.fromString(first.get("id").stringValue()), first.get("name").stringValue());
    }

    protected UUID courseSubjectId(UUID courseId, UUID subjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course_subject WHERE course_id = ? AND subject_id = ?",
                        UUID.class,
                        courseId,
                        subjectId),
                "Môn–khóa phải tồn tại");
    }

    protected UUID classStudentId(UUID classId, UUID studentUserId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM class_student WHERE class_id = ? AND student_user_id = ?",
                        UUID.class,
                        classId,
                        studentUserId),
                "Học sinh phải có trong roster của lớp");
    }

    // ---------- Guardian link theo hợp đồng ----------

    protected static String linksPath(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/guardian-links";
    }

    protected static String resultsPath(UUID workspaceId, UUID studentUserId) {
        return "/api/workspaces/" + workspaceId + "/students/" + studentUserId + "/results";
    }

    protected MockHttpServletResponse requestLink(
            RealSession session, UUID workspaceId, String json) throws Exception {
        return mutate(session, post(linksPath(workspaceId)), json);
    }

    /** Gửi request liên kết cho chính actor và chờ operation hoàn tất; trả về item trong list. */
    protected JsonNode requestLinkSucceeded(
            RealSession parentSession, UUID workspaceId, UUID studentUserId) throws Exception {
        MockHttpServletResponse response =
                requestLink(
                        parentSession,
                        workspaceId,
                        "{\"studentUserId\":\"" + studentUserId + "\"}");
        awaitOperationSucceeded(jdbc, operationIdOf(response, "Gửi yêu cầu liên kết"));
        JsonNode items = listLinks(parentSession, workspaceId);
        return findLink(items, studentUserId);
    }

    protected JsonNode listLinks(RealSession session, UUID workspaceId) throws Exception {
        JsonNode body = getJson(session, linksPath(workspaceId), 200);
        assertNull(
                body.get("totalCount"),
                "Danh sách liên kết không được lộ tổng số hàng chưa được authorize");
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh sách liên kết trả dạng {items}");
        assertTrue(items.isArray(), "Danh sách liên kết là một mảng");
        return items;
    }

    protected JsonNode findLink(JsonNode items, UUID studentUserId) {
        // Danh sách trả cũ nhất trước và gồm cả lịch sử đã đóng, nên link hiện hành của một học
        // sinh là phần tử khớp CUỐI CÙNG, không phải đầu tiên.
        JsonNode found = null;
        for (JsonNode item : items) {
            if (studentUserId.toString().equals(item.get("studentUserId").stringValue())) {
                found = item;
            }
        }
        if (found == null) {
            return fail("Không thấy liên kết cho học sinh " + studentUserId + " trong danh sách");
        }
        return found;
    }

    protected MockHttpServletResponse decide(
            RealSession session, UUID workspaceId, UUID linkId, String decision, long version)
            throws Exception {
        return mutate(
                session,
                post(linksPath(workspaceId) + "/" + linkId + "/decision"),
                "{\"decision\":\"" + decision + "\",\"expectedVersion\":" + version + "}");
    }

    protected void decideSucceeded(
            RealSession session, UUID workspaceId, JsonNode link, String decision)
            throws Exception {
        UUID linkId = UUID.fromString(link.get("id").stringValue());
        JsonNode version = link.get("version");
        assertNotNull(version, "Item liên kết phải lộ version để decision mang expectedVersion");
        MockHttpServletResponse response =
                decide(session, workspaceId, linkId, decision, version.longValue());
        awaitOperationSucceeded(jdbc, operationIdOf(response, "Quyết định liên kết"));
    }

    protected void revokeSucceeded(RealSession session, UUID workspaceId, UUID linkId)
            throws Exception {
        MockHttpServletResponse response =
                mutate(session, delete(linksPath(workspaceId) + "/" + linkId), null);
        awaitOperationSucceeded(jdbc, operationIdOf(response, "Gỡ liên kết"));
    }

    protected UUID linkIdOf(JsonNode link) {
        return UUID.fromString(link.get("id").stringValue());
    }

    protected String linkStateOf(RealSession session, UUID workspaceId, UUID studentUserId)
            throws Exception {
        return findLink(listLinks(session, workspaceId), studentUserId).get("state").stringValue();
    }

    // ---------- HTTP plumbing ----------

    protected UUID operationIdOf(MockHttpServletResponse response, String action) throws Exception {
        assertEquals(202, response.getStatus(), action + " phải trả 202 OperationView");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("PENDING", body.get("state").stringValue());
        assertFalse(body.get("completed").booleanValue());
        assertFalse(body.get("statusUrl").stringValue().isBlank());
        return UUID.fromString(body.get("operationId").stringValue());
    }

    /** Mutation kỳ vọng thành công theo hợp đồng OperationView 202; dùng cho mọi setup đã có. */
    protected UUID postOperation(RealSession session, String uri, String json) throws Exception {
        return operationIdOf(mutate(session, post(uri), json), "POST " + uri);
    }

    protected MockHttpServletResponse mutate(
            RealSession session, MockHttpServletRequestBuilder builder, @Nullable String json)
            throws Exception {
        var request =
                builder.cookie(session.cookie())
                        .with(csrf())
                        .header("Idempotency-Key", "guardian-" + UUID.randomUUID());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc().perform(request).andReturn().getResponse();
    }

    protected MockHttpServletResponse getRaw(RealSession session, String uri) throws Exception {
        return mockMvc().perform(get(uri).cookie(session.cookie())).andReturn().getResponse();
    }

    protected JsonNode getJson(RealSession session, String uri, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response = getRaw(session, uri);
        assertEquals(expectedStatus, response.getStatus(), "GET " + uri);
        return JSON.readTree(response.getContentAsString());
    }

    protected MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
    }

    protected <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    protected RealSession openSession(KeycloakLogin login) throws Exception {
        RealSession session =
                RealSession.open(
                        login.username(),
                        login.password(),
                        login.subject(),
                        sessionFilter,
                        authorizedClients,
                        clientRegistrations,
                        sessions);
        // Phiên thật luôn nạp workspace trước: lời gọi có fence đó đồng bộ eligibility
        // của actor, điều kiện để PDP cho qua các scope nghiệp vụ.
        getWorkspacesWhenReady(session);
        return session;
    }

    /**
     * Đọc danh sách workspace của actor seed trực tiếp bằng JDBC: account chưa từng bootstrap nên
     * các lời gọi đầu có thể nhận 503 AUTHORIZATION_NOT_READY trong lúc eligibility và projection
     * của tài khoản đang đồng bộ — hành vi fail-closed đúng của sản phẩm. Client thật thử lại có
     * giới hạn với đúng mã lỗi đó rồi mới khẳng định; ngữ nghĩa khẳng định của test không đổi.
     */
    protected MockHttpServletResponse getWorkspacesWhenReady(RealSession session) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (true) {
            MockHttpServletResponse response =
                    mockMvc()
                            .perform(get("/api/me/workspaces").cookie(session.cookie()))
                            .andReturn()
                            .getResponse();
            boolean syncing =
                    response.getStatus() == 503
                            && response.getContentAsString().contains("AUTHORIZATION_NOT_READY");
            if (!syncing || Instant.now().isAfter(deadline)) {
                return response;
            }
            sleep();
        }
    }

    protected static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for the operation");
        }
    }

    // ---------- Keycloak TEST realm qua Admin API ----------

    protected Map<String, Object> keycloakUser(
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

    protected String createUser(Map<String, Object> body) {
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

    protected void deleteUser(String userId) {
        deleteWithConflictRetry(() ->
                HTTP.delete()
                        .uri(admin("/users/" + userId))
                        .headers(headers -> headers.setBearerAuth(masterToken()))
                        .retrieve()
                        .toBodilessEntity());
    }

    protected String masterToken() {
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

    protected String admin(String path) {
        return BASE + "/admin/realms/" + REALM + path;
    }

    static String fixture(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing TEST fixture variable " + key);
        }
        return value;
    }

    /**
     * Phiên Spring thật chứa token Code+PKCE thật của một user TEST. Đăng nhập đi qua form login
     * của Keycloak TEST bằng client BFF của ứng dụng; không dùng Direct Access Grants cho phiên
     * web. Login dùng helper dùng chung {@code OidcFormLogin} (đã biết hai bước identity-first).
     */
    static final class RealSession implements AutoCloseable {
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
