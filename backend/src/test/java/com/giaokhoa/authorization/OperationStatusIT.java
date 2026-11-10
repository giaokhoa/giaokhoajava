package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.NativeGateFixture;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gate của GET operation status: initiator đọc trạng thái operation của chính mình qua phiên JDBC
 * thật chứa token Code+PKCE thật; operation của người khác bị giấu thành 404; response chỉ chứa
 * metadata điều khiển, không chứa payload nội bộ hay secret.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class OperationStatusIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();

    // Worker không thuộc phạm vi kiểm thử của class này (mọi khẳng định đều qua dữ kiện seed
    // trực tiếp và đường đọc đồng bộ): mock để worker nền không tự xử lý các operation mà
    // class seed trực tiếp — trạng thái khẳng định phải do class điều khiển, không do lịch
    // poll của worker quyết định.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext webContext;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired ClientRegistrationRepository clientRegistrations;
    @Autowired SessionRepository<?> sessions;

    @DisplayName("Initiator đọc được operation PENDING của chính mình mà không lộ payload nội bộ")
    @Test
    void initiatorReadsOwnOperation() throws Exception {
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            var gate = new NativeGateFixture(jdbc);
            var actor = tx(() -> gate.seedActor(nativeGate.userSubject()));
            UUID operation =
                    tx(() -> gate.seedOperation(actor, AuthorizationOperation.State.PENDING, null));

            MockHttpServletResponse response = getOperation(session, operation);

            assertEquals(200, response.getStatus());
            assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
            String raw = response.getContentAsString();
            JsonNode body = JSON.readTree(raw);
            assertEquals(operation.toString(), body.get("id").stringValue());
            assertEquals("PENDING", body.get("state").stringValue());
            assertFalse(body.get("completed").booleanValue());
            assertEquals("PROFILE", body.get("targetType").stringValue());
            assertEquals(actor.userId().toString(), body.get("targetId").stringValue());
            assertFalse(body.has("errorCode"), "PENDING không có mã lỗi để trả");
            assertFalse(raw.contains("payloadHash"));
            assertFalse(raw.contains("idempotencyKey"));
            assertFalse(raw.contains("affectedPartitions"));
            String payloadHash =
                    jdbc.queryForObject(
                            "SELECT payload_hash FROM authorization_operation WHERE id = ?",
                            String.class,
                            operation);
            assertFalse(
                    raw.contains(Objects.requireNonNull(payloadHash, "payload hash")),
                    "Payload hash trong DB không được lộ qua response");
        }
    }

    @DisplayName("Operation SUCCEEDED báo COMPLETED, còn failure có thể retry chỉ lộ mã lỗi")
    @Test
    void statusReportsCompletedAndErrorCode()
            throws Exception {
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            var gate = new NativeGateFixture(jdbc);
            var actor = tx(() -> gate.seedActor(nativeGate.userSubject()));
            UUID succeeded =
                    tx(() ->
                            gate.seedOperation(
                                    actor, AuthorizationOperation.State.SUCCEEDED, null));
            UUID retryable =
                    tx(() ->
                            gate.seedOperation(
                                    actor,
                                    AuthorizationOperation.State.RETRYABLE_FAILURE,
                                    "KEYCLOAK_UNAVAILABLE"));

            JsonNode done = JSON.readTree(getOperation(session, succeeded).getContentAsString());
            assertEquals("SUCCEEDED", done.get("state").stringValue());
            assertTrue(done.get("completed").booleanValue());

            JsonNode failed = JSON.readTree(getOperation(session, retryable).getContentAsString());
            assertEquals("RETRYABLE_FAILURE", failed.get("state").stringValue());
            assertFalse(
                    failed.get("completed").booleanValue(),
                    "Operation chưa SUCCEEDED không bao giờ được báo hoàn tất");
            assertEquals("KEYCLOAK_UNAVAILABLE", failed.get("errorCode").stringValue());
        }
    }

    @DisplayName("Operation của initiator khác bị ẩn dưới dạng NOT_FOUND")
    @Test
    void foreignOperationHiddenAsNotFound() throws Exception {
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            var gate = new NativeGateFixture(jdbc);
            tx(() -> gate.seedActor(nativeGate.userSubject()));
            var stranger =
                    tx(() -> gate.seedActor("operation-status-stranger-" + UUID.randomUUID()));
            UUID foreign =
                    tx(() ->
                            gate.seedOperation(
                                    stranger, AuthorizationOperation.State.PENDING, null));

            MockHttpServletResponse response = getOperation(session, foreign);

            assertEquals(404, response.getStatus());
            assertTrue(
                    Objects.requireNonNull(response.getContentType(), "content type")
                            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
            JsonNode body = JSON.readTree(response.getContentAsString());
            assertEquals("NOT_FOUND", body.get("code").stringValue());
        }
    }

    @DisplayName("Request ẩn danh không bao giờ nhận được trạng thái operation")
    @Test
    void anonymousGetsNoOperationStatus() throws Exception {
        MockMvc mvc = mockMvc();
        MockHttpServletResponse response =
                mvc.perform(get("/api/me/operations/" + UUID.randomUUID()))
                        .andReturn()
                        .getResponse();
        assertEquals(302, response.getStatus());
        assertTrue(
                Objects.requireNonNull(response.getHeader(HttpHeaders.LOCATION), "Location")
                        .contains("/oauth2/authorization/keycloak"));
    }

    @Test
    void gateDiagnosticsCarryStateButNoSecrets() {
        var gate = new NativeGateFixture(jdbc);
        var actor = tx(() -> gate.seedActor("operation-status-diagnostics-" + UUID.randomUUID()));
        UUID operation =
                tx(() -> gate.seedOperation(actor, AuthorizationOperation.State.PENDING, null));

        String diagnostics = gate.diagnostics(operation);

        assertTrue(diagnostics.contains("state=PENDING"));
        assertTrue(diagnostics.contains("completed=false"));
        assertTrue(diagnostics.contains("ACCOUNT:PENDING:0/0"));
        assertTrue(diagnostics.contains("WORKSPACE:PENDING:0/0"));
        String payloadHash =
                jdbc.queryForObject(
                        "SELECT payload_hash FROM authorization_operation WHERE id = ?",
                        String.class,
                        operation);
        String idempotencyKey =
                jdbc.queryForObject(
                        "SELECT idempotency_key FROM authorization_operation WHERE id = ?",
                        String.class,
                        operation);
        assertFalse(diagnostics.contains(Objects.requireNonNull(payloadHash, "payload hash")));
        assertFalse(
                diagnostics.contains(Objects.requireNonNull(idempotencyKey, "idempotency key")));
    }

    private MockHttpServletResponse getOperation(NativeSpringSessionFixture session, UUID operation)
            throws Exception {
        return mockMvc()
                .perform(get("/api/me/operations/" + operation).cookie(session.cookie()))
                .andReturn()
                .getResponse();
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(sessionFilter)
                .apply(springSecurity())
                .build();
    }

    private NativeSpringSessionFixture nativeSession(NativeRevocationFixture nativeGate)
            throws Exception {
        return new NativeSpringSessionFixture(
                nativeGate, sessionFilter, authorizedClients, clientRegistrations, sessions);
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }
}
