package com.giaokhoa.teaching;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing dùng chung cho các test giảng dạy ở tầng HTTP: seed center/thành viên/gói, phiên Spring
 * thật qua Code+PKCE, gọi HTTP qua MockMvc và truy vấn Keycloak Admin. Tách từ hai class IT giảng
 * dạy để tránh trùng lặp; không chứa quyết định quyền nào, không chứa test.
 */
abstract class TeachingFixture extends IntegrationEnvironment {
    protected static final JsonMapper JSON = new JsonMapper();
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    protected static final RestClient HTTP = RestClient.create();
    protected static final String BASE = fixture("TEST_KEYCLOAK_BASE_URL").replaceAll("/+$", "");
    protected static final String REALM = fixture("TEST_KEYCLOAK_REALM");
    protected static final String REDIRECT_URI = TEST_REDIRECT_URI;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected WebApplicationContext webContext;
    @Autowired protected SessionRepositoryFilter<?> sessionFilter;
    @Autowired protected OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired protected ClientRegistrationRepository clientRegistrations;
    @Autowired protected SessionRepository<?> sessions;

    protected UUID createClass(RealSession session, UUID workspaceId, String name)
            throws Exception {
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes",
                        "{\"name\":\"" + name + "\"}",
                        "POST");
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
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/courses",
                        "{\"name\":\"" + name + "\",\"subjectIds\":[" + subjects + "]}",
                        "POST");
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
        return addRoster(session, workspaceId, classId, List.of(studentUserId)).getFirst();
    }

    protected List<UUID> addRoster(
            RealSession session, UUID workspaceId, UUID classId, List<UUID> studentUserIds)
            throws Exception {
        String students =
                studentUserIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students",
                        "{\"studentUserIds\":[" + students + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
        return studentUserIds.stream().map(id -> classStudentId(classId, id)).toList();
    }

    protected void enroll(
            RealSession session,
            UUID workspaceId,
            List<UUID> classStudentIds,
            List<UUID> courseSubjectIds)
            throws Exception {
        String students =
                classStudentIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        String subjects =
                courseSubjectIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/" + workspaceId + "/enrollments",
                        "{\"classStudentIds\":["
                                + students
                                + "],\"courseSubjectIds\":["
                                + subjects
                                + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
    }

    protected void assignTeachers(
            RealSession session, UUID workspaceId, UUID courseSubjectId, List<UUID> teacherUserIds)
            throws Exception {
        String teachers =
                teacherUserIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(","));
        UUID operationId =
                postMutation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/teachers",
                        "{\"teacherUserIds\":[" + teachers + "]}",
                        "POST");
        awaitOperationSucceeded(jdbc, operationId);
    }

    protected List<UUID> subjectIds(RealSession session, int needed) throws Exception {
        JsonNode body = getJson(session, "/api/subjects", 200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh mục môn trả dạng {items}");
        assertTrue(items.isArray(), "Danh mục môn là một mảng");
        assertTrue(
                items.size() >= needed,
                "Danh mục môn phải có ít nhất " + needed + " môn, thực tế " + items.size());
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(UUID.fromString(item.get("id").stringValue()));
            if (ids.size() == needed) {
                break;
            }
        }
        return ids;
    }

    protected int enrollmentCount(RealSession session, UUID workspaceId, UUID courseSubjectId)
            throws Exception {
        JsonNode body =
                getJson(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/enrollments",
                        200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh sách ghi danh trả dạng {items}");
        assertTrue(items.isArray(), "Danh sách ghi danh là một mảng");
        return items.size();
    }

    protected int teacherAssignmentCount(
            RealSession session, UUID workspaceId, UUID courseSubjectId) throws Exception {
        JsonNode body =
                getJson(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/teachers",
                        200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh sách phân công trả dạng {items}");
        assertTrue(items.isArray(), "Danh sách phân công là một mảng");
        return items.size();
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

    protected UUID postMutation(RealSession session, String uri, String json, String method)
            throws Exception {
        MockHttpServletResponse response =
                switch (method) {
                    case "POST" ->
                            mockMvc()
                                    .perform(
                                            post(uri)
                                                    .cookie(session.cookie())
                                                    .with(csrf())
                                                    .header(
                                                            "Idempotency-Key",
                                                            "teaching-" + UUID.randomUUID())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(json))
                                    .andReturn()
                                    .getResponse();
                    case "DELETE" ->
                            mockMvc()
                                    .perform(
                                            delete(uri)
                                                    .cookie(session.cookie())
                                                    .with(csrf())
                                                    .header(
                                                            "Idempotency-Key",
                                                            "teaching-" + UUID.randomUUID())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(json))
                                    .andReturn()
                                    .getResponse();
                    default -> throw new IllegalArgumentException("Method không hỗ trợ: " + method);
                };
        assertEquals(202, response.getStatus(), "Mutation phải trả 202 OperationView, URI: " + uri);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("PENDING", body.get("state").stringValue());
        assertFalse(body.get("completed").booleanValue());
        assertFalse(body.get("statusUrl").stringValue().isBlank());
        return UUID.fromString(body.get("operationId").stringValue());
    }

    protected JsonNode getJson(RealSession session, String uri, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc().perform(get(uri).cookie(session.cookie())).andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus(), "GET " + uri);
        return JSON.readTree(response.getContentAsString());
    }

    // ---------- Dữ kiện thư mục ----------

    protected record KeycloakLogin(
            String username, String password, String subject, String email) {}

    protected record Fixture(
            UUID workspaceId,
            KeycloakLogin owner,
            UUID ownerId,
            KeycloakLogin manager,
            UUID managerId,
            KeycloakLogin teacherA,
            UUID teacherAId,
            KeycloakLogin teacherB,
            UUID teacherBId,
            List<KeycloakLogin> students,
            List<UUID> studentIds) {
        protected Fixture {
            // Danh sách nội bộ không lộ ra ngoài qua accessor của record.
            students = List.copyOf(students);
            studentIds = List.copyOf(studentIds);
        }
    }

    /**
     * Danh tính dùng chung cho cả class test: tạo user Keycloak và tài khoản là phần seed đắt (mỗi
     * user tạo ra là một lần Keycloak băm mật khẩu), còn center, membership và gói là JDBC rẻ.
     * Center mặc định riêng từng test; riêng TeachingAuthorizationIT và TeachingStructureIT dùng
     * chung một center cho cả class theo mô hình phân vùng thực thể con (mỗi test tự tạo
     * class/course với tên duy nhất và chỉ khẳng định trên vùng của mình, không test nào sửa
     * membership hay gói của center dùng chung).
     */
    protected record SharedIdentities(
            KeycloakLogin owner,
            UUID ownerId,
            KeycloakLogin manager,
            UUID managerId,
            KeycloakLogin teacherA,
            UUID teacherAId,
            KeycloakLogin teacherB,
            UUID teacherBId,
            List<KeycloakLogin> students,
            List<UUID> studentIds) {
        protected SharedIdentities {
            students = List.copyOf(students);
            studentIds = List.copyOf(studentIds);
        }
    }

    protected SharedIdentities seedSharedIdentities() {
        KeycloakLogin ownerLogin = createVerifiedUser("teaching-owner");
        KeycloakLogin managerLogin = createVerifiedUser("teaching-manager");
        KeycloakLogin teacherALogin = createVerifiedUser("teaching-teacher-a");
        KeycloakLogin teacherBLogin = createVerifiedUser("teaching-teacher-b");
        List<KeycloakLogin> studentLogins =
                List.of(
                        createVerifiedUser("teaching-student-a"),
                        createVerifiedUser("teaching-student-b"),
                        createVerifiedUser("teaching-student-c"));
        UUID ownerId =
                seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
        UUID managerId =
                seedAccount(managerLogin.subject(), "SELF_REGISTERED", managerLogin.email(), true);
        UUID teacherAId =
                seedAccount(
                        teacherALogin.subject(), "SELF_REGISTERED", teacherALogin.email(), true);
        UUID teacherBId =
                seedAccount(
                        teacherBLogin.subject(), "SELF_REGISTERED", teacherBLogin.email(), true);
        List<UUID> studentIds = new ArrayList<>();
        for (KeycloakLogin student : studentLogins) {
            studentIds.add(
                    seedAccount(student.subject(), "SELF_REGISTERED", student.email(), true));
        }
        return new SharedIdentities(
                ownerLogin,
                ownerId,
                managerLogin,
                managerId,
                teacherALogin,
                teacherAId,
                teacherBLogin,
                teacherBId,
                studentLogins,
                studentIds);
    }

    protected void deleteSharedIdentities(SharedIdentities identities) {
        deleteUser(identities.owner().subject());
        deleteUser(identities.manager().subject());
        deleteUser(identities.teacherA().subject());
        deleteUser(identities.teacherB().subject());
        for (KeycloakLogin student : identities.students()) {
            deleteUser(student.subject());
        }
    }

    protected Fixture seedCenterFixture(SharedIdentities identities) {
        return seedCenterFixture(identities, -1, 365);
    }

    /**
     * Dựng một center riêng cho một test trên danh tính dùng chung của class. Fixture trả về không
     * được đưa vào {@code deleteFixture} vì sẽ xoá user dùng chung của cả class; danh tính chỉ dọn
     * một lần ở cuối class qua {@code deleteSharedIdentities}, còn dữ kiện của center nằm trong
     * schema dùng chung, được xoá sạch khi class kế tiếp bắt đầu.
     */
    protected Fixture seedCenterFixture(
            SharedIdentities identities, int validFromOffsetDays, int expiresOffsetDays) {
        UUID workspaceId = seedCenter(identities.ownerId());
        addMembership(workspaceId, identities.managerId(), "MANAGER");
        addMembership(workspaceId, identities.teacherAId(), "TEACHER");
        addMembership(workspaceId, identities.teacherBId(), "TEACHER");
        for (UUID studentId : identities.studentIds()) {
            addMembership(workspaceId, studentId, "STUDENT");
        }
        seedSubscription(workspaceId, validFromOffsetDays, expiresOffsetDays, identities.ownerId());
        return new Fixture(
                workspaceId,
                identities.owner(),
                identities.ownerId(),
                identities.manager(),
                identities.managerId(),
                identities.teacherA(),
                identities.teacherAId(),
                identities.teacherB(),
                identities.teacherBId(),
                identities.students(),
                identities.studentIds());
    }

    protected Fixture seedTeachingCenter() {
        return seedTeachingCenterWithSubscription(-1, 365);
    }

    protected Fixture seedTeachingCenterWithSubscription(
            int validFromOffsetDays, int expiresOffsetDays) {
        return seedCenterFixture(seedSharedIdentities(), validFromOffsetDays, expiresOffsetDays);
    }

    protected void deleteFixture(Fixture fixture) {
        deleteUser(fixture.owner().subject());
        deleteUser(fixture.manager().subject());
        deleteUser(fixture.teacherA().subject());
        deleteUser(fixture.teacherB().subject());
        for (KeycloakLogin student : fixture.students()) {
            deleteUser(student.subject());
        }
    }

    protected KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "teaching-password-" + UUID.randomUUID();
        String email = username + "@example.test";
        String subject = createUser(keycloakUser(username, email, true, password));
        return new KeycloakLogin(username, password, subject, email);
    }

    protected UUID seedAccount(
            String keycloakSub, String origin, @Nullable String email, boolean emailVerified) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    keycloakSub,
                    "teaching-user-" + actor,
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

    protected void seedSubscription(
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
                    "teaching-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    /**
     * Đọc danh sách workspace của actor seed trực tiếp bằng JDBC: account chưa từng bootstrap nên
     * các lời gọi đầu có thể nhận 503 AUTHORIZATION_NOT_READY trong lúc eligibility và projection
     * của tài khoản đang đồng bộ — hành vi fail-closed đúng của sản phẩm. Client thật thử lại có
     * giới hạn với đúng mã lỗi đó rồi mới khẳng định; ngữ nghĩa khẳng định của test không đổi.
     */
    protected MockHttpServletResponse getWorkspacesWhenReady(RealSession session) throws Exception {
        AtomicReference<MockHttpServletResponse> last = new AtomicReference<>();
        try {
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        MockHttpServletResponse response =
                                mockMvc()
                                        .perform(get("/api/me/workspaces").cookie(session.cookie()))
                                        .andReturn()
                                        .getResponse();
                        last.set(response);
                        return response.getStatus() != 503
                                || !response.getContentAsString()
                                        .contains("AUTHORIZATION_NOT_READY");
                    });
        } catch (ConditionTimeoutException ignored) {
            // Hết 60 giây mà account chưa sẵn sàng: trả phản hồi cuối như vòng poll cũ để
            // khẳng định phía trên tự báo đúng trạng thái.
        }
        return Objects.requireNonNull(last.get());
    }

    protected <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    protected MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
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
                withMasterToken(token ->
                        HTTP.post()
                                .uri(admin("/users"))
                                .headers(headers -> headers.setBearerAuth(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .toEntity(String.class));
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    protected void deleteUser(String userId) {
        deleteWithConflictRetry(() -> deleteResource(admin("/users/" + userId)));
    }

    protected void deleteResource(String uri) {
        withMasterToken(token -> {
            HTTP.delete()
                    .uri(uri)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    // Token master được giữ trong bộ nhớ tới gần hết hạn thay vì xin mới cho mỗi lần gọi
    // Admin API: mỗi lần xin token là một lượt Keycloak xác thực mật khẩu của tài khoản master.
    // Hiệu lực chỉ căn theo masterTokenValidUntil: hạ hạn về EPOCH là token cũ hết được dùng.
    private static String cachedMasterToken = "";
    private static Instant masterTokenValidUntil = Instant.EPOCH;

    protected String masterToken() {
        if (Instant.now().isBefore(masterTokenValidUntil)) {
            return cachedMasterToken;
        }
        return fetchMasterToken();
    }

    private static synchronized String fetchMasterToken() {
        if (Instant.now().isBefore(masterTokenValidUntil)) {
            return cachedMasterToken;
        }
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
        String token =
                Objects.requireNonNull((String) response.get("access_token"), "access_token");
        long expiresIn =
                response.get("expires_in") instanceof Number number ? number.longValue() : 60;
        cachedMasterToken = token;
        // Chừa biên 10 giây để không cầm token sắp hết hạn vào một lời gọi Admin API.
        masterTokenValidUntil = Instant.now().plusSeconds(Math.max(5, expiresIn - 10));
        return token;
    }

    private static synchronized void invalidateMasterToken() {
        masterTokenValidUntil = Instant.EPOCH;
    }

    /**
     * Chạy một lời gọi Admin API bằng token đã cache; gặp 401 (token bị thu hồi phía Keycloak dù
     * hạn theo đồng hồ local chưa tới) thì bỏ cache, xin token mới và thử lại đúng một lần.
     */
    private <T> T withMasterToken(java.util.function.Function<String, T> call) {
        try {
            return call.apply(masterToken());
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw ex;
            }
            invalidateMasterToken();
            return call.apply(masterToken());
        }
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
     * web. Login dùng helper dùng chung {@code OidcFormLogin} (đã biết hai bước identity-first),
     * phần còn lại dựng phiên Spring từ token như bản gốc trong test hợp đồng US2.
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
