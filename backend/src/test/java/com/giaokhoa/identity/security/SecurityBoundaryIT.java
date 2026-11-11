package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Biên bảo mật xuyên suốt (T095, issue #97): khóa các khẳng định của {@code
 * specs/001-multitenant-access/validation/security.md} bằng test chạy trên DB/Keycloak thật.
 *
 * <p>Chỉ khóa những gì chưa có test giữ: vai trò runtime/RLS/FK ở tầng DB (§1), fail-closed khi
 * không xác minh được actor/eligibility (§2, §3), CSRF sai token và rò rỉ token qua session view
 * (§4, §6), password grant bị từ chối (§7). Không viết lại những gì đã có: tương quan sid qua
 * restart do {@code OidcSessionPersistenceIT} giữ, CSRF thiếu token/back-channel do {@code
 * SecurityConfigurationIT} giữ, IDOR chéo workspace do các IT nghiệp vụ giữ, no-store trên các đáp
 * án bảo vệ do {@code OperationStatusIT} và {@code session-security.spec.ts} giữ.
 */
@SpringBootTest
class SecurityBoundaryIT extends IntegrationEnvironment {

    // Mọi khẳng định của class này đều đồng bộ nên worker nền không thuộc phạm vi kiểm
    // thử: mock để nó không tự xử lý operation song song với đường lái trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    private static final JsonMapper JSON = new JsonMapper();

    // Baseline hệ thống V001: 93 ràng buộc khóa ngoại, khớp đếm tĩnh trong security.md §1.
    private static final int SYSTEM_BASELINE_FOREIGN_KEYS = 93;

    @Autowired JdbcTemplate jdbc;

    @Autowired ProtectedActionExecutor executor;

    @Autowired IdentityEligibilityService eligibility;

    @Autowired ClientRegistrationRepository registrations;

    private final MockMvc mvc;

    SecurityBoundaryIT(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @DisplayName(
            "§1 RLS/runtime role: datasource chạy bằng giaokhoa_runtime, không superuser/bypassrls")
    @Test
    void runtimeRoleCannotEscalate() {
        assertEquals("giaokhoa_runtime", jdbc.queryForObject("SELECT current_user", String.class));
        Map<String, Object> role =
                jdbc.queryForMap(
                        "SELECT rolsuper, rolbypassrls, rolcanlogin FROM pg_roles"
                                + " WHERE rolname = current_user");
        assertEquals(Boolean.FALSE, role.get("rolsuper"));
        assertEquals(Boolean.FALSE, role.get("rolbypassrls"));
        assertEquals(Boolean.TRUE, role.get("rolcanlogin"));
    }

    @DisplayName("§1 RLS: không còn policy nào trên cơ sở dữ liệu")
    @Test
    void noRowLevelSecurityPolicies() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_policies", Integer.class));
    }

    @DisplayName("§1 FK: baseline hệ thống giữ đủ 93 ràng buộc khóa ngoại")
    @Test
    void systemBaselineForeignKeysAreIntact() {
        assertEquals(
                SYSTEM_BASELINE_FOREIGN_KEYS,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints"
                                + " WHERE constraint_type = 'FOREIGN KEY'"
                                + " AND table_schema = 'public'",
                        Integer.class));
    }

    @DisplayName(
            "§1 runtime role: không ghi được flyway_schema_history nhưng vẫn DML được bảng nghiệp vụ")
    @Test
    void runtimeRoleCannotWriteFlywayHistory() {
        assertEquals(
                Boolean.FALSE,
                jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,"
                                + " 'flyway_schema_history', 'INSERT')",
                        Boolean.class));
        assertEquals(
                Boolean.TRUE,
                jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,"
                                + " 'flyway_schema_history', 'SELECT')",
                        Boolean.class));
        assertEquals(
                Boolean.TRUE,
                jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user, 'subject', 'INSERT')",
                        Boolean.class));
    }

    @DisplayName("§2 actor: phiên đã xác thực nhưng không có directory account thì fail-closed 503")
    @Test
    void unknownSubjectFailsClosed() {
        Authentication unknown = new TestingAuthenticationToken(UUID.randomUUID().toString(), null);
        HttpProblemException thrown =
                assertThrows(HttpProblemException.class, () -> executor.actorUserId(unknown));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatus());
        assertEquals(ErrorCodes.AUTHORIZATION_NOT_READY, thrown.getCode());
    }

    @DisplayName("§2 actor: không có authentication thì 401, không fail-open")
    // Ca này cố ý truyền authentication null để khóa fail-closed: tham số của
    // actorUserId không @Nullable nên NullAway biến literal null thành lỗi biên dịch;
    // miễn trừ chỉ trong phương thức này, ca test giữ nguyên.
    @SuppressWarnings("NullAway")
    @Test
    void nullAuthenticationIsUnauthorized() {
        HttpProblemException thrown =
                assertThrows(HttpProblemException.class, () -> executor.actorUserId(null));
        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatus());
        assertEquals(ErrorCodes.UNAUTHORIZED, thrown.getCode());
    }

    @DisplayName("§2 pipeline: evaluate với subject lạ fail-closed 503 trước khi hỏi PDP")
    @Test
    void evaluateWithUnknownSubjectFailsClosed() {
        Authentication unknown = new TestingAuthenticationToken(UUID.randomUUID().toString(), null);
        UUID workspaceId = UUID.randomUUID();
        HttpProblemException thrown =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                executor.evaluate(
                                        unknown,
                                        new MockHttpServletRequest(),
                                        workspaceId,
                                        List.of(
                                                new ProtectedActionExecutor.Target(
                                                        NativePolicyTemplates.workspaceKey(
                                                                workspaceId),
                                                        List.of(
                                                                PermissionCatalog.WorkspaceScope
                                                                        .READ
                                                                        .scopeName())))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatus());
        assertEquals(ErrorCodes.AUTHORIZATION_NOT_READY, thrown.getCode());
    }

    @DisplayName(
            "§3 eligibility: đồng bộ eligibility của account không tồn tại thì fail-closed 503")
    @Test
    void eligibilitySyncWithoutAccountFailsClosed() {
        HttpProblemException thrown =
                assertThrows(
                        HttpProblemException.class,
                        () -> eligibility.synchronizeEligibility(UUID.randomUUID()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatus());
        assertEquals(ErrorCodes.AUTHORIZATION_NOT_READY, thrown.getCode());
    }

    @DisplayName("§4 CSRF: mutation mang token sai của đúng phiên vẫn bị 403")
    @Test
    void wrongCsrfTokenIsRejected() throws Exception {
        var sessionResponse =
                mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        JsonNode body = JSON.readTree(sessionResponse.getResponse().getContentAsString());
        String headerName = body.get("csrf").get("headerName").stringValue();
        assertEquals("X-CSRF-TOKEN", headerName);
        assertFalse(body.get("csrf").get("token").stringValue().isEmpty());
        MockHttpSession session =
                (MockHttpSession)
                        Objects.requireNonNull(sessionResponse.getRequest().getSession(false));

        mvc.perform(
                        post("/api/logout")
                                .session(session)
                                .header(headerName, "token-sai-khong-phai-cua-phien"))
                .andExpect(status().isForbidden());
    }

    @DisplayName(
            "§6 no leak: session view ẩn danh không trả bearer/secret và mang private, no-store")
    @Test
    void sessionViewLeaksNoToken() throws Exception {
        var response = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        String cacheControl = response.getResponse().getHeader("Cache-Control");
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("private"));
        assertTrue(cacheControl.contains("no-store"));
        String content = response.getResponse().getContentAsString();
        assertFalse(content.contains("access_token"));
        assertFalse(content.contains("id_token"));
        assertFalse(content.contains("refresh_token"));
        assertFalse(content.contains("Bearer"));
        assertFalse(content.contains("password"));
    }

    @DisplayName("§7 client OIDC chỉ dùng authorization code, không password grant")
    @Test
    void oauthClientUsesAuthorizationCodeOnly() {
        var client = Objects.requireNonNull(registrations.findByRegistrationId("keycloak"));
        assertEquals(AuthorizationGrantType.AUTHORIZATION_CODE, client.getAuthorizationGrantType());
    }

    @DisplayName("§7 issuer: password grant bị từ chối 400 unauthorized_client")
    @Test
    void passwordGrantIsRejectedByTestIssuer() throws Exception {
        String secret = System.getenv("TEST_OIDC_CLIENT_SECRET");
        assertNotNull(secret, "Thiếu TEST_OIDC_CLIENT_SECRET của stack TEST");
        String form =
                "grant_type=password&client_id=giaokhoa-it-web&client_secret="
                        + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                        + "&username=probe-user&password=probe-password";
        HttpResponse<String> response =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        testIssuerUri()
                                                                + "/protocol/openid-connect/token"))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(HttpRequest.BodyPublishers.ofString(form))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("unauthorized_client"));
    }
}
