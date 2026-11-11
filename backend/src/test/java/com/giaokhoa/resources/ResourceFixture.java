package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * Plumbing dùng chung cho các test tài nguyên ở tầng HTTP: seed account/center/thành viên/gói và
 * tài nguyên canonical bằng JDBC, phiên Spring thật qua Code+PKCE, gọi HTTP qua MockMvc và truy vấn
 * Keycloak Admin. Cùng mẫu với fixture của các package giảng dạy/phụ huynh; không chứa quyết định
 * quyền nào và không chứa test.
 */
abstract class ResourceFixture extends IntegrationEnvironment {
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

    protected record KeycloakLogin(
            String username, String password, String subject, String email) {}

    /** Cặp định danh canonical của một version đã seed: resource và version không đổi khi lưu. */
    protected record VersionRef(UUID resourceId, UUID versionId) {}

    protected <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    protected MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
    }

    // ---------- HTTP ----------

    protected JsonNode getJson(RealSession session, String uri, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response = getRaw(session, uri);
        assertEquals(
                expectedStatus,
                response.getStatus(),
                "GET " + uri + ", body: " + response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    protected MockHttpServletResponse getRaw(RealSession session, String uri) throws Exception {
        return requestWhenReady(mockMvc(), get(uri).cookie(session.cookie()));
    }

    protected MockHttpServletResponse postRaw(RealSession session, String uri, String json)
            throws Exception {
        return mockMvc()
                .perform(
                        post(uri)
                                .cookie(session.cookie())
                                .with(csrf())
                                .header("Idempotency-Key", "resource-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn()
                .getResponse();
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
                                                            "resource-" + UUID.randomUUID())
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
                                                            "resource-" + UUID.randomUUID())
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
        return UUID.fromString(body.get("operationId").stringValue());
    }

    protected MockHttpServletResponse sampleAction(
            RealSession session, UUID workspaceId, String json, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response =
                postRaw(session, "/api/workspaces/" + workspaceId + "/samples/actions", json);
        assertEquals(
                expectedStatus,
                response.getStatus(),
                "Sample action tại workspace "
                        + workspaceId
                        + ", body: "
                        + response.getContentAsString());
        return response;
    }

    protected JsonNode sampleActionExpectCreated(RealSession session, UUID workspaceId, String json)
            throws Exception {
        MockHttpServletResponse response = sampleAction(session, workspaceId, json, 201);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("SUCCEEDED", body.get("state").stringValue());
        return body;
    }

    /** Đáp án action thành công mang định danh đối tượng đích ở targetId, bản cũ hơn ở id. */
    protected static UUID targetIdOf(JsonNode actionResult) {
        return UUID.fromString(
                actionResult.hasNonNull("targetId")
                        ? actionResult.get("targetId").stringValue()
                        : actionResult.get("id").stringValue());
    }

    protected static String bodyUri(UUID workspaceId, UUID resourceId, UUID versionId) {
        return "/api/workspaces/"
                + workspaceId
                + "/samples/resources/"
                + resourceId
                + "/versions/"
                + versionId;
    }

    protected static String answerUri(UUID workspaceId, UUID resourceId, UUID versionId) {
        return bodyUri(workspaceId, resourceId, versionId) + "/answer";
    }

    // ---------- Dữ kiện thư mục ----------

    protected KeycloakLogin createVerifiedUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        String password = "resource-password-" + UUID.randomUUID();
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
                    "resource-user-" + actor,
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

    protected UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId),
                "personal workspace");
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
                    "resource-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    protected UUID firstSubjectId() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM subject ORDER BY code LIMIT 1", UUID.class),
                "Danh mục môn phải có sẵn");
    }

    // ---------- Seed tài nguyên canonical ----------

    /**
     * Tài nguyên hệ thống đã phát hành: workspace SYSTEM sở hữu, version đầu ở trạng thái LOCKED.
     */
    protected VersionRef seedSystemVersion(
            UUID authorUserId, UUID subjectId, String bodyStub, String answerStub) {
        UUID systemWorkspace = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,state) VALUES (?,'SYSTEM','ACTIVE')",
                    systemWorkspace);
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'QUESTION',?,'ACTIVE')",
                    resourceId,
                    systemWorkspace,
                    authorUserId,
                    subjectId);
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state,locked_at)"
                            + " VALUES (?,?,1,'Bản phát hành',?,?,'LOCKED',now())",
                    versionId,
                    resourceId,
                    bodyStub,
                    answerStub);
            return null;
        });
        return new VersionRef(resourceId, versionId);
    }

    /** Tài nguyên do một workspace sở hữu với version đầu đã duyệt (APPROVED). */
    protected VersionRef seedOwnedVersion(
            UUID ownerWorkspaceId,
            UUID creatorUserId,
            UUID subjectId,
            String kind,
            String bodyStub,
            String answerStub) {
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    resourceId,
                    ownerWorkspaceId,
                    creatorUserId,
                    kind,
                    subjectId);
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản đầu',?,?,'APPROVED')",
                    versionId,
                    resourceId,
                    bodyStub,
                    answerStub);
            return null;
        });
        return new VersionRef(resourceId, versionId);
    }

    /** Version tiếp theo của cùng resource, đã phát hành (LOCKED) như bản nguồn mới. */
    protected UUID seedNextLockedVersion(
            UUID resourceId, int versionNo, String bodyStub, String answerStub) {
        UUID versionId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state,locked_at)"
                            + " VALUES (?,?,?,'Bản ' || ?,?,?,'LOCKED',now())",
                    versionId,
                    resourceId,
                    versionNo,
                    versionNo,
                    bodyStub,
                    answerStub);
            return null;
        });
        return versionId;
    }

    protected UUID systemWorkspaceOf(UUID resourceId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT owner_workspace_id FROM resource WHERE id = ?",
                        UUID.class,
                        resourceId),
                "resource");
    }

    // ---------- Seed chuỗi giảng dạy và lịch sử mẫu ----------

    /**
     * Seed thẳng chuỗi lớp/khóa/môn/roster/ghi danh trong một workspace bằng JDBC, cùng mẫu seed
     * của các test kết quả mẫu: các test tài nguyên phải độc lập với route giảng dạy. Mọi quan hệ ở
     * trạng thái ACTIVE như sau một operation SUCCEEDED. Trả về course_subject_id của chuỗi.
     */
    protected UUID seedTeachingChain(UUID workspaceId, UUID studentUserId) {
        UUID subjectId = firstSubjectId();
        UUID classId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID courseSubjectId = UUID.randomUUID();
        UUID classStudentId = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO school_class(id,workspace_id,name,state)"
                            + " VALUES (?,?,?,'ACTIVE')",
                    classId,
                    workspaceId,
                    "Lớp seed");
            jdbc.update(
                    "INSERT INTO course(id,workspace_id,class_id,name,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    courseId,
                    workspaceId,
                    classId,
                    "Khóa seed");
            jdbc.update(
                    "INSERT INTO course_subject(id,workspace_id,class_id,course_id,subject_id,"
                            + "state) VALUES (?,?,?,?,?,'ACTIVE')",
                    courseSubjectId,
                    workspaceId,
                    classId,
                    courseId,
                    subjectId);
            jdbc.update(
                    "INSERT INTO class_student(id,workspace_id,class_id,student_user_id,state)"
                            + " VALUES (?,?,?,?,'ACTIVE')",
                    classStudentId,
                    workspaceId,
                    classId,
                    studentUserId);
            jdbc.update(
                    "INSERT INTO enrollment(id,workspace_id,class_student_id,course_subject_id,"
                            + "state) VALUES (?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(),
                    workspaceId,
                    classStudentId,
                    courseSubjectId);
            return null;
        });
        return courseSubjectId;
    }

    /** Seed một hoạt động đã giao trên đúng version và một bài nộp của học sinh đã ghi danh. */
    protected UUID seedActivityWithSubmission(
            UUID workspaceId,
            UUID courseSubjectId,
            UUID resourceId,
            UUID versionId,
            UUID assignedBy,
            UUID studentUserId) {
        UUID periodId =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subscription_period WHERE workspace_id = ?",
                                UUID.class,
                                workspaceId),
                        "Workspace phải có kỳ gói đã seed");
        UUID activityId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,resource_id,"
                            + "version_id,assigned_by,assigned_at,due_at,attempt_limit,"
                            + "subscription_period_id,state)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ASSIGNED')",
                    activityId,
                    workspaceId,
                    courseSubjectId,
                    resourceId,
                    versionId,
                    assignedBy,
                    now,
                    now.plusDays(7),
                    1,
                    periodId);
            jdbc.update(
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,student_user_id,"
                            + "attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,?,'SUBMITTED')",
                    UUID.randomUUID(),
                    workspaceId,
                    activityId,
                    studentUserId,
                    now,
                    "Bài làm mẫu");
            return null;
        });
        return activityId;
    }

    // ---------- JSON của các action tài nguyên ----------

    protected static String saveJson(UUID resourceId, UUID versionId) {
        return "{\"action\":\"SAVE_VERSION\",\"resourceId\":\""
                + resourceId
                + "\",\"versionId\":\""
                + versionId
                + "\"}";
    }

    protected static String forkJson(UUID resourceId, UUID versionId, String label) {
        return "{\"action\":\"FORK_VERSION\",\"resourceId\":\""
                + resourceId
                + "\",\"versionId\":\""
                + versionId
                + "\",\"label\":\""
                + label
                + "\"}";
    }

    protected static String shareJson(
            UUID resourceId,
            UUID versionId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds) {
        String granted =
                actions.stream()
                        .map(a -> "\"" + a + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
        String allowed =
                allowedWorkspaceIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
        return "{\"action\":\"SHARE_VERSION\",\"resourceId\":\""
                + resourceId
                + "\",\"versionId\":\""
                + versionId
                + "\",\"recipientWorkspaceId\":\""
                + recipientWorkspaceId
                + "\",\"mode\":\""
                + mode
                + "\",\"actions\":["
                + granted
                + "],\"allowedWorkspaceIds\":["
                + allowed
                + "]}";
    }

    protected static String acceptJson(UUID grantId) {
        return "{\"action\":\"ACCEPT_SHARE\",\"grantId\":\"" + grantId + "\"}";
    }

    protected static String revokeJson(UUID grantId) {
        return "{\"action\":\"REVOKE_SHARE\",\"grantId\":\"" + grantId + "\"}";
    }

    // ---------- Phiên ----------

    /**
     * Đọc danh sách workspace của actor: các lời gọi đầu có thể nhận 503 AUTHORIZATION_NOT_READY
     * trong lúc eligibility và projection của tài khoản đang đồng bộ — hành vi fail-closed đúng của
     * sản phẩm. Thử lại có giới hạn với đúng mã lỗi đó rồi mới trả phản hồi cuối.
     */
    protected MockHttpServletResponse getWorkspacesWhenReady(RealSession session) throws Exception {
        return requestWhenReady(mockMvc(), get("/api/me/workspaces").cookie(session.cookie()));
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
     * Chạy một lời gọi Admin API bằng token đã cache; gặp 401 thì bỏ cache, xin token mới và thử
     * lại đúng một lần.
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
