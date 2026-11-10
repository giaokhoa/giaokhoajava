package com.giaokhoa.authorization;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.support.IntegrationEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Hợp đồng thu hồi: thứ tự commit của PostgreSQL cộng các hợp đồng HTTP Foundation. */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("e2e-backend")
class ConcurrentRevocationIT extends IntegrationEnvironment {
    private static final JsonMapper JSON = new JsonMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyService idempotency;
    @Autowired IdentityEligibilityService eligibility;
    @Autowired ProjectionReconciler reconciler;
    @Autowired PersonalEntitlementProjector entitlementProjector;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext webContext;
    @Autowired SessionRepositoryFilter<?> sessionFilter;
    @Autowired OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired ClientRegistrationRepository clientRegistrations;
    @Autowired SessionRepository<?> sessions;

    @DisplayName(
            "Ghi đang bay (in-flight) hoàn tất trước khi revocation chiếm được exclusive fence")
    @Test
    void inFlightWriteFinishesBeforeFence() throws Exception {
        UUID partition = partition();
        CountDownLatch protectedLock = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch revokeStarted = new CountDownLatch(1);
        AtomicInteger writerBackendPid = new AtomicInteger();
        AtomicInteger revokeBackendPid = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer =
                    pool.submit(() ->
                            tx(() -> {
                                writerBackendPid.set(backendPid());
                                assertEquals("READY", lockedState(partition, "SHARE"));
                                protectedLock.countDown();
                                awaitLatch(releaseWrite);
                                // Protected mutation giữ read fence tới khi commit.
                                return 1;
                            }));
            assertTrue(protectedLock.await(10, TimeUnit.SECONDS));
            var revoker =
                    pool.submit(() ->
                            tx(() -> {
                                revokeBackendPid.set(backendPid());
                                revokeStarted.countDown();
                                lockedState(partition, "UPDATE");
                                fence(partition, 1);
                                return 1;
                            }));
            assertTrue(revokeStarted.await(10, TimeUnit.SECONDS));
            try {
                assertBlockedBy(revoker, revokeBackendPid.get(), writerBackendPid.get());
            } finally {
                releaseWrite.countDown();
            }

            assertEquals(1, writer.get(10, TimeUnit.SECONDS));
            assertEquals(1, revoker.get(10, TimeUnit.SECONDS));
            assertEquals("PENDING", state(partition));
        } finally {
            releaseWrite.countDown();
        }
    }

    @DisplayName("Ghi bắt đầu sau revocation không thể quan sát revision READY cũ")
    @Test
    void writeAfterRevokeSeesNoOldRevision() throws Exception {
        UUID partition = partition();
        CountDownLatch revokedButNotCommitted = new CountDownLatch(1);
        CountDownLatch commitRevoke = new CountDownLatch(1);
        CountDownLatch writerAtDatabase = new CountDownLatch(1);
        AtomicInteger revokerBackendPid = new AtomicInteger();
        AtomicInteger writerBackendPid = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoker =
                    pool.submit(() ->
                            tx(() -> {
                                revokerBackendPid.set(backendPid());
                                lockedState(partition, "UPDATE");
                                fence(partition, 1);
                                revokedButNotCommitted.countDown();
                                awaitLatch(commitRevoke);
                                return 1;
                            }));
            assertTrue(revokedButNotCommitted.await(10, TimeUnit.SECONDS));
            var writer =
                    pool.submit(() ->
                            tx(() -> {
                                writerBackendPid.set(backendPid());
                                writerAtDatabase.countDown();
                                return lockedState(partition, "SHARE");
                            }));
            try {
                assertTrue(writerAtDatabase.await(10, TimeUnit.SECONDS));
                assertBlockedBy(writer, writerBackendPid.get(), revokerBackendPid.get());
            } finally {
                commitRevoke.countDown();
            }

            assertEquals(1, revoker.get(10, TimeUnit.SECONDS));
            assertEquals("PENDING", writer.get(10, TimeUnit.SECONDS));
        } finally {
            commitRevoke.countDown();
        }
    }

    @DisplayName(
            "Token của actor qua luồng code PKCE thật vẫn sống qua JDBC session xuyên suốt native revocation")
    @Test
    void pkceTokenSurvivesNativeRevocation() throws Exception {
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            String first = session.currentAccessToken();
            assertFalse(nativeGate.containsGroupClaim(first));
            assertTrue(nativeGate.allowed(first, "a"));
            assertTrue(nativeGate.allowed(first, "b"));

            nativeGate.revoke("a");
            String after = session.currentAccessToken();
            assertTrue(
                    java.util.Arrays.equals(first.toCharArray(), after.toCharArray()),
                    "Spring JDBC session must retain the same user token");
            assertFalse(
                    nativeGate.allowed(after, "a"), "Revoked A must deny without another login");
            assertTrue(nativeGate.allowed(after, "b"), "Independent B must remain allowed");
        }
    }

    @DisplayName("Dọn dẹp fixture của native API giữ nguyên các grant độc lập của resource server")
    @Test
    void cleanupKeepsIndependentRsGrants() {
        // Graph đầu tiên sở hữu TEST API client tách biệt; graph thứ hai chỉ dùng chung resource
        // server của nó.
        try (var nativeGate = new NativeRevocationFixture();
                var first =
                        nativeGate.partiallyProvisionApplicationResource(
                                "workspace:" + UUID.randomUUID(), "enter")) {
            first.authorizeOwner();
            String token = nativeGate.userToken();
            assertTrue(first.allowed(token, first.resourceId()));
            long originalPolicies = first.policyCount();
            try (var second =
                    nativeGate.partiallyProvisionApplicationResource(
                            "workspace:" + UUID.randomUUID(), "enter")) {
                second.authorizeOwner();
                assertTrue(second.allowed(token, second.resourceId()));
                assertTrue(first.allowed(token, first.resourceId()));
                assertEquals(1, second.resourceCount());
            }
            assertTrue(first.allowed(token, first.resourceId()));
            assertEquals(
                    originalPolicies, first.policyCount(), "No extra policy after second cleanup");
            assertEquals(
                    1, first.resourceCount(), "The shared client owner still has its resource");
        }
    }

    private NativeSpringSessionFixture nativeSession(NativeRevocationFixture nativeGate)
            throws Exception {
        return new NativeSpringSessionFixture(
                nativeGate, sessionFilter, authorizedClients, clientRegistrations, sessions);
    }

    private enum PendingScope {
        ACCOUNT,
        WORKSPACE,
        GRANT_PENDING,
        GRANT_FAILED
    }

    @DisplayName(
            "Các capability được bảo vệ phải từ chối khi dependency đã commit nhưng còn PENDING")
    @ParameterizedTest
    @EnumSource(PendingScope.class)
    void capabilitiesRejectPendingDependency(PendingScope pending) throws Exception {
        // Hợp đồng request-fence đòi hỏi cùng một HTTP request đã xác thực
        // trước và sau một fence đã commit.
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            ActorScope fixture = actorWithReadyPersonalWorkspace(nativeGate.userSubject());
            boolean requiresGrant =
                    pending == PendingScope.GRANT_PENDING || pending == PendingScope.GRANT_FAILED;
            UUID resourceA = UUID.randomUUID();
            UUID versionA = UUID.randomUUID();
            UUID resourceB = UUID.randomUUID();
            UUID versionB = UUID.randomUUID();
            String keyA =
                    requiresGrant
                            ? versionTargetKey(fixture.workspace(), resourceA, versionA)
                            : "workspace:" + fixture.workspace();
            String keyB = versionTargetKey(fixture.workspace(), resourceB, versionB);
            try (var apiGraph =
                            nativeGate.partiallyProvisionApplicationResource(
                                    keyA, requiresGrant ? "content.read" : "enter");
                    var identityGraph =
                            nativeGate.partiallyProvisionApplicationResource(
                                    "account:" + fixture.actor(), "workspace.list")) {
                prepareEligibility(fixture, nativeGate, identityGraph);
                apiGraph.authorizeOwner(); // User policy native của Keycloak trên audience của TEST
                // API.
                String keycloakResourceB =
                        requiresGrant ? apiGraph.addAuthorizedResource(keyB) : null;
                GrantScope affectedGrant =
                        requiresGrant
                                ? grantDependency(
                                        fixture, resourceA, versionA, apiGraph.resourceId())
                                : null;
                GrantScope independentGrant =
                        requiresGrant
                                ? grantDependency(
                                        fixture,
                                        resourceB,
                                        versionB,
                                        java.util.Objects.requireNonNull(keycloakResourceB))
                                : null;
                String targetA =
                        affectedGrant == null
                                ? workspaceTarget(fixture.workspace())
                                : versionTarget(affectedGrant);
                String targetB = independentGrant == null ? null : versionTarget(independentGrant);
                String token = session.currentAccessToken();
                assertTrue(
                        apiGraph.allowed(token, apiGraph.resourceId()),
                        "Native READY A must allow via TEST API audience");
                if (requiresGrant) {
                    assertTrue(
                            apiGraph.allowed(
                                    token, java.util.Objects.requireNonNull(keycloakResourceB)),
                            "Native independent READY B must allow via TEST API audience");
                }
                MockMvc mvc =
                        MockMvcBuilders.webAppContextSetup(webContext)
                                .addFilters(sessionFilter)
                                .apply(springSecurity())
                                .build();
                MockHttpServletResponse readyA = capabilityResponse(mvc, fixture, session, targetA);
                if (affectedGrant != null) {
                    UUID grantPartition = affectedGrant.partition();
                    tx(() -> {
                        jdbc.update(
                                "UPDATE authz_projection SET state = 'PENDING'"
                                        + " WHERE partition_id = ?",
                                grantPartition);
                        jdbc.update(
                                "UPDATE authz_partition SET desired_revision = 1, state = ?"
                                        + " WHERE id = ?",
                                pending == PendingScope.GRANT_FAILED ? "FAILED" : "PENDING",
                                grantPartition);
                        return null;
                    });
                    assertEquals("READY", state(fixture.workspacePartition()));
                    assertEquals("READY", state(fixture.account()));
                    GrantScope verifiedIndependentGrant =
                            java.util.Objects.requireNonNull(
                                    independentGrant,
                                    "Independent grant is required when revoking a grant");
                    assertEquals("READY", state(verifiedIndependentGrant.partition()));
                } else {
                    tx(() -> {
                        fence(fixture.partition(pending), 1);
                        if (pending == PendingScope.ACCOUNT) {
                            fence(fixture.workspacePartition(), 1);
                            jdbc.update(
                                    "UPDATE authz_projection SET state = 'PENDING' WHERE partition_id = ?",
                                    fixture.workspacePartition());
                        }
                        return null;
                    });
                }
                MockHttpServletResponse fencedA =
                        capabilityResponse(mvc, fixture, session, targetA);
                MockHttpServletResponse independentB =
                        targetB == null ? null : capabilityResponse(mvc, fixture, session, targetB);
                assertTrue(
                        apiGraph.allowed(token, apiGraph.resourceId()),
                        "Native A remains allowed; the DB fence must deny.");
                if (independentB != null) {
                    assertTrue(
                            apiGraph.allowed(
                                    token, java.util.Objects.requireNonNull(keycloakResourceB)));
                }
                assertAll(
                        "REVOCATION_CONTRACT_READY_AND_FENCED_" + pending,
                        () -> assertReadyAllowed(readyA, "A_BEFORE_" + pending),
                        () ->
                                assertEquals(
                                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                                        fencedA.getStatus(),
                                        "REVOCATION_CONTRACT_FENCED_EXPECT_503_"
                                                + pending
                                                + " actual HTTP="
                                                + fencedA.getStatus()),
                        () -> {
                            if (independentB != null) {
                                assertReadyAllowed(independentB, "INDEPENDENT_B_AFTER_" + pending);
                            }
                        });
            }
        }
    }

    private String workspaceTarget(UUID workspace) {
        return ("{\"type\":\"WORKSPACE\",\"id\":\"%s\",\"scopes\":[\"enter\"]}")
                .formatted(workspace);
    }

    private String versionTarget(GrantScope grant) {
        return ("{\"type\":\"RESOURCE_VERSION\",\"id\":\"%s\","
                        + "\"versionId\":\"%s\",\"scopes\":[\"content.read\"]}")
                .formatted(grant.resource(), grant.version());
    }

    private MockHttpServletResponse capabilityResponse(
            MockMvc mvc, ActorScope actor, NativeSpringSessionFixture session, String target)
            throws Exception {
        return mvc.perform(
                        post("/api/workspaces/{wid}/capabilities", actor.workspace())
                                .cookie(session.cookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targets\":[" + target + "]}"))
                .andReturn()
                .getResponse();
    }

    private void assertReadyAllowed(MockHttpServletResponse response, String scenario)
            throws Exception {
        assertEquals(
                200, response.getStatus(), "REVOCATION_CONTRACT_READY_EXPECT_ALLOW_" + scenario);
        List<JsonNode> decisions =
                JSON.readTree(response.getContentAsString()).findValues("allowed");
        assertEquals(1, decisions.size(), "One exact target/scope must be evaluated");
        assertTrue(
                decisions.getFirst().isBoolean() && decisions.getFirst().booleanValue(),
                "READY must produce a native allow, not a constant HTTP 200/deny");
    }

    @DisplayName("Mutation đồng thời lên fixture phải chờ revocation fence ở tầng database")
    @Test
    void fixtureMutationWaitsForFence() throws Exception {
        // Sample action phải dùng cùng PEP production và giao thức khóa.
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            ActorScope fixture = actorWithReadyPersonalWorkspace(nativeGate.userSubject());
            try (var identityGraph =
                    nativeGate.partiallyProvisionApplicationResource(
                            "account:" + fixture.actor(), "workspace.list")) {
                prepareEligibility(fixture, nativeGate, identityGraph);
                MockMvc mvc =
                        MockMvcBuilders.webAppContextSetup(webContext)
                                .addFilters(sessionFilter)
                                .apply(springSecurity())
                                .build();
                assertTrue(nativeGate.allowed(session.currentAccessToken(), "a"));
                CountDownLatch revokeLocked = new CountDownLatch(1);
                CountDownLatch finishRevoke = new CountDownLatch(1);
                CountDownLatch writerStarted = new CountDownLatch(1);
                AtomicInteger revokerBackendPid = new AtomicInteger();

                try (var pool = Executors.newFixedThreadPool(2)) {
                    var revoker =
                            pool.submit(() ->
                                    tx(() -> {
                                        revokerBackendPid.set(backendPid());
                                        lockedState(fixture.workspacePartition(), "UPDATE");
                                        fence(fixture.workspacePartition(), 1);
                                        revokeLocked.countDown();
                                        awaitLatch(finishRevoke);
                                        return 1;
                                    }));
                    assertTrue(revokeLocked.await(10, TimeUnit.SECONDS));
                    var writer =
                            pool.submit(() -> {
                                writerStarted.countDown();
                                return mvc.perform(
                                                post(
                                                                "/api/workspaces/{wid}/samples/actions",
                                                                fixture.workspace())
                                                        .with(csrf())
                                                        .cookie(session.cookie())
                                                        .header(
                                                                "Idempotency-Key",
                                                                "revocation-concurrent")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                ("{\"action\":\"CREATE_RESOURCE\","
                                                                                + "\"subjectId\":\"%s\","
                                                                                + "\"kind\":\"QUESTION\","
                                                                                + "\"label\":\"Revocation contract protected\"}")
                                                                        .formatted(
                                                                                UUID.randomUUID())))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
                    try {
                        assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
                        AtomicBoolean blockedOnRevoke = new AtomicBoolean();
                        try {
                            await().atMost(Duration.ofSeconds(10))
                                    .pollInterval(Duration.ofMillis(25))
                                    .until(() -> {
                                        if (writer.isDone()) {
                                            return true;
                                        }
                                        // Khớp đúng backend PostgreSQL của revoker,
                                        // không phải chỉ độ chậm HTTP.
                                        boolean found =
                                                Boolean.TRUE.equals(
                                                        jdbc.queryForObject(
                                                                """
                                                                SELECT EXISTS (
                                                                  SELECT 1 FROM pg_stat_activity a
                                                                  WHERE ? = ANY(pg_blocking_pids(a.pid))
                                                                    AND a.wait_event_type = 'Lock'
                                                                    AND a.query ILIKE '%authz_partition%'
                                                                )
                                                                """,
                                                                Boolean.class,
                                                                revokerBackendPid.get()));
                                        if (found) {
                                            blockedOnRevoke.set(true);
                                        }
                                        return found;
                                    });
                        } catch (ConditionTimeoutException ignored) {
                            // Các assert ngay sau tự báo đúng trạng thái lệch như vòng poll cũ.
                        }
                        if (writer.isDone()) {
                            int earlyStatus = writer.get(10, TimeUnit.SECONDS);
                            fail(
                                    "REVOCATION_CONTRACT_EARLY_HTTP_STATUS="
                                            + earlyStatus
                                            + " before PostgreSQL fence: "
                                            + (earlyStatus == 404
                                                    ? "sample action route absent"
                                                    : "unexpected security/validation/business response"));
                        }
                        assertTrue(
                                blockedOnRevoke.get(),
                                "REVOCATION_CONTRACT_LOCK_NOT_OBSERVED: writer never waited on revoker PID");
                        assertFalse(
                                writer.isDone(), "No mutation response before the revoke commits");
                    } finally {
                        finishRevoke.countDown();
                    }

                    assertEquals(1, revoker.get(10, TimeUnit.SECONDS));
                    assertEquals(
                            HttpStatus.SERVICE_UNAVAILABLE.value(),
                            writer.get(10, TimeUnit.SECONDS));
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM authorization_operation WHERE workspace_id = ?",
                                    Integer.class,
                                    fixture.workspace()));
                } finally {
                    finishRevoke.countDown();
                }
            }
        }
    }

    @DisplayName("Request trong cùng session làm mới eligibility trước khi dùng native permission")
    @Test
    void sessionRefreshesEligibilityFirst() throws Exception {
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            ActorScope actor = actorWithReadyPersonalWorkspace(nativeGate.userSubject());
            String target = "workspace:" + actor.workspace();
            try (var graph = nativeGate.partiallyProvisionApplicationResource(target, "enter");
                    var identityGraph =
                            nativeGate.partiallyProvisionApplicationResource(
                                    "account:" + actor.actor(), "workspace.list")) {
                prepareEligibility(actor, nativeGate, identityGraph);
                tx(() -> {
                    jdbc.update(
                            "UPDATE authz_partition SET state='PENDING' WHERE id=?",
                            actor.workspacePartition());
                    jdbc.update(
                            "UPDATE authz_projection SET desired_hash = ?,"
                                    + " dependency_partition_ids = ARRAY[?]::uuid[], state = 'PENDING'"
                                    + " WHERE partition_id = ? AND target_key = ?",
                            ProjectionReconciler.desiredHash(
                                    target,
                                    "enter",
                                    entitlementProjector
                                            .bundleDescriptors(actor.workspace())),
                            actor.account(),
                            actor.workspacePartition(),
                            target);
                    return null;
                });
                reconciler.reconcilePartition(actor.workspacePartition());
                UUID projection =
                        jdbc.queryForList(
                                        "SELECT id FROM authz_projection WHERE target_key=?",
                                        UUID.class,
                                        target)
                                .getFirst();
                graph.cleanupProjectedPolicies(() -> projectionPolicies(projection));
                MockMvc mvc =
                        MockMvcBuilders.webAppContextSetup(webContext)
                                .addFilters(sessionFilter)
                                .apply(springSecurity())
                                .build();
                String request = workspaceTarget(actor.workspace());
                assertReadyAllowed(
                        capabilityResponse(mvc, actor, session, request), "verified actor");
                nativeGate.setEmailVerified(false);
                var response = capabilityResponse(mvc, actor, session, request);
                assertTrue(
                        response.getStatus() == 503
                                || (response.getStatus() == 200
                                        && !JSON.readTree(response.getContentAsString())
                                                .at("/targets/0/scopes/0/allowed")
                                                .booleanValue()),
                        "Same session must never receive a stale allow after verification is removed");
                reconciler.reconcilePartition(actor.account());
                reconciler.reconcilePartition(actor.workspacePartition());
                response = capabilityResponse(mvc, actor, session, request);
                assertEquals(200, response.getStatus());
                assertFalse(
                        JSON.readTree(response.getContentAsString())
                                .at("/targets/0/scopes/0/allowed")
                                .booleanValue());
                nativeGate.setEmailVerified(true);
                eligibility.synchronizeEligibility(actor.actor());
                reconciler.reconcilePartition(actor.account());
                reconciler.reconcilePartition(actor.workspacePartition());
                assertReadyAllowed(
                        capabilityResponse(mvc, actor, session, request), "verified again");
            }
        }
    }

    private void prepareEligibility(
            ActorScope actor,
            NativeRevocationFixture nativeGate,
            NativeRevocationFixture.PartialApplicationGraph identityGraph) {
        nativeGate.provisionEligibilityForWorkerFixture();
        eligibility.synchronizeEligibility(actor.actor());
        reconciler.reconcilePartition(actor.account());
        reconciler.reconcilePartition(actor.workspacePartition());
        await().atMost(Duration.ofSeconds(15))
                .until(() ->
                        jdbc.queryForList(
                                        "SELECT id FROM authorization_operation WHERE initiator_user_id = ? AND state <> 'SUCCEEDED'",
                                        UUID.class,
                                        actor.actor())
                                .isEmpty());
        UUID projection =
                jdbc.queryForList(
                                "SELECT id FROM authz_projection WHERE target_key=?",
                                UUID.class,
                                "account:" + actor.actor())
                        .getFirst();
        List<String> policyNames = projectionPolicies(projection);
        identityGraph.cleanupProjectedPolicies(() -> policyNames);
    }

    private record GrantScope(UUID partition, UUID resource, UUID version) {}

    private String versionTargetKey(UUID workspace, UUID resource, UUID version) {
        return "workspace:" + workspace + ":resource:" + resource + ":version:" + version;
    }

    private GrantScope grantDependency(
            ActorScope fixture, UUID resource, UUID version, String keycloakResourceId) {
        return tx(() -> {
            UUID grant = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO authz_partition(id,kind,owner_key)" + " VALUES (?,'GRANT',?)",
                    grant,
                    "grant:" + grant);
            jdbc.update(
                    "INSERT INTO authz_projection(id,partition_id,target_key,"
                            + "keycloak_resource_id,dependency_partition_ids,desired_hash,"
                            + "applied_hash,state)"
                            + " VALUES (?,?,?,?,ARRAY[?::uuid],?,?,'READY')",
                    UUID.randomUUID(),
                    grant,
                    versionTargetKey(fixture.workspace(), resource, version),
                    keycloakResourceId,
                    fixture.workspacePartition(),
                    "a".repeat(64),
                    "a".repeat(64));
            jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", grant);
            return new GrantScope(grant, resource, version);
        });
    }

    private ActorScope actorWithReadyPersonalWorkspace(String keycloakSub) {
        return tx(() -> {
            UUID actor = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,origin,identity_state)"
                            + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                    actor,
                    keycloakSub,
                    "revocation-actor-" + actor,
                    "revocation-" + actor + "@example.invalid");
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            UUID account =
                    jdbc.queryForObject(
                            "SELECT id FROM authz_partition WHERE owner_key = ?",
                            UUID.class,
                            "account:" + actor);
            UUID workspacePartition =
                    jdbc.queryForObject(
                            "SELECT id FROM authz_partition WHERE owner_key = ?",
                            UUID.class,
                            "workspace:" + workspace);
            jdbc.update(
                    "UPDATE authz_partition SET state = 'READY' WHERE id IN (?, ?)",
                    account,
                    workspacePartition);
            return new ActorScope(
                    actor,
                    workspace,
                    java.util.Objects.requireNonNull(account, "account partition"),
                    java.util.Objects.requireNonNull(workspacePartition, "workspace partition"));
        });
    }

    private record ActorScope(UUID actor, UUID workspace, UUID account, UUID workspacePartition) {
        UUID partition(PendingScope scope) {
            return switch (scope) {
                case ACCOUNT -> account;
                case WORKSPACE -> workspacePartition;
                case GRANT_PENDING, GRANT_FAILED ->
                        throw new IllegalArgumentException("GRANT is resolved from projection");
            };
        }
    }

    @DisplayName(
            "Worker bản production phải reconcile app graph mới provision một phần sau lần retry")
    @Test
    void workerReconcilesPartialGraph() throws Exception {
        // Reconciliation worker production phải tiếp tục operation này; nguồn là một
        // PERSONAL owner thật trong V001, không phải share_grant bịa ra
        // (vốn chưa có schema production trước các task user-story sau này).
        try (var nativeGate = new NativeRevocationFixture()) {
            nativeGate.provisionEligibilityForWorkerFixture();
            ActorScope actor = actorWithReadyPersonalWorkspace(nativeGate.userSubject());
            String targetKey = "workspace:" + actor.workspace();
            String scope = "enter";
            // Desired của personal workspace nay là bundle entitlement đầy đủ (enter, read,
            // class.list của owner), không còn là một descriptor enter đơn lẻ: reconciler
            // đối chiếu cả bundle nên seed theo đúng bundle mà bootstrap ghi.
            String desiredHash =
                    ProjectionReconciler.desiredHash(
                            targetKey,
                            scope,
                            entitlementProjector
                                    .bundleDescriptors(actor.workspace()));
            try (var graph = nativeGate.partiallyProvisionApplicationResource(targetKey, scope)) {
                UUID projection = UUID.randomUUID();
                var operation =
                        new AuthorizationOperation(
                                UUID.randomUUID(),
                                actor.actor(),
                                actor.workspace(),
                                "revocation-partial-provision-retry",
                                desiredHash,
                                List.of(actor.account(), actor.workspacePartition()),
                                List.of(0L, 1L),
                                0L);
                tx(() -> {
                    // Đây là một nguồn thật graph writer dựng lại được: user
                    // policy native cho workspace.owner_user_id, được đánh giá cho targetKey#enter.
                    assertEquals(
                            actor.actor(),
                            jdbc.queryForObject(
                                    "SELECT owner_user_id FROM workspace WHERE id=?",
                                    UUID.class,
                                    actor.workspace()));
                    assertEquals(
                            nativeGate.userSubject(),
                            jdbc.queryForObject(
                                    "SELECT keycloak_sub FROM user_account WHERE id=?",
                                    String.class,
                                    actor.actor()));
                    fence(actor.workspacePartition(), 1);
                    jdbc.update(
                            "INSERT INTO authz_projection"
                                    + "(id,partition_id,target_key,keycloak_resource_id,"
                                    + "dependency_partition_ids,desired_hash,state)"
                                    + " VALUES (?,?,?,?,ARRAY[?::uuid],?,'PENDING')",
                            projection,
                            actor.workspacePartition(),
                            targetKey,
                            graph.resourceId(),
                            actor.account(),
                            desiredHash);
                    idempotency.executeOnce(operation, () -> {});
                    // Giữ retry ở tương lai gần để worker không claim operation trước khi
                    // test khẳng định trạng thái chưa reconcile; test mở lại ngay trước
                    // khi chờ worker.
                    jdbc.update(
                            "UPDATE authorization_operation SET state='RETRYABLE_FAILURE',"
                                    + " attempt_count=1,next_attempt_at = now() + interval '5 minutes',"
                                    + " last_error_code='PARTIAL_KEYCLOAK_PROVISION'"
                                    + " WHERE id=?",
                            operation.getId());
                    return null;
                });
                graph.cleanupProjectedPolicies(() -> projectionPolicies(projection));
                assertEquals(1, graph.resourceCount(), "Native workspace resource exists already");
                assertEquals(0, graph.permissionCount(), "Permission absent at crash point");
                assertFalse(
                        graph.allowed(
                                nativeGate.webTokensViaCodePkce().accessToken(),
                                graph.resourceId()),
                        "Partial TEST API graph must be native PDP-denied until worker reconciles");
                assertEquals(
                        "PENDING",
                        state(actor.workspacePartition()),
                        "Workspace stays fenced until native reconciliation");
                assertEquals(
                        HttpStatus.ACCEPTED,
                        tx(() ->
                                idempotency
                                        .responseFor(
                                                idempotency
                                                        .findExisting(
                                                                actor.actor(),
                                                                "revocation-partial-provision-retry",
                                                                desiredHash)
                                                        .orElseThrow())
                                        .getStatusCode()));
                assertTrue(
                        webContext.containsBean("projectionWorker"),
                        "REVOCATION_CONTRACT_WORKER_NOT_INSTALLED: the reconciliation worker must resume owner source through"
                                + " production PolicyGraphWriter/ProjectionReconciler");
                // Trạng thái trước reconcile đã khẳng định xong: mở retry để worker claim.
                tx(() -> {
                    jdbc.update(
                            "UPDATE authorization_operation SET next_attempt_at = now()"
                                    + " WHERE id=?",
                            operation.getId());
                    return null;
                });
                awaitCondition(() ->
                        "READY".equals(state(actor.workspacePartition()))
                                && "SUCCEEDED"
                                        .equals(
                                                jdbc.queryForObject(
                                                        "SELECT state FROM authorization_operation"
                                                                + " WHERE id=?",
                                                        String.class,
                                                        operation.getId())));
                assertAll(
                        "REVOCATION_CONTRACT_REAL_WORKER_PARTIAL_GRAPH_RECOVERY",
                        () ->
                                assertEquals(
                                        "READY",
                                        state(actor.workspacePartition()),
                                        "Rebuilt owner workspace must be READY"),
                        () ->
                                assertEquals(
                                        "SUCCEEDED",
                                        jdbc.queryForObject(
                                                "SELECT state FROM authorization_operation WHERE id=?",
                                                String.class,
                                                operation.getId()),
                                        "Worker last_error_code="
                                                + operationErrorCode(operation.getId())),
                        () ->
                                assertEquals(
                                        desiredHash,
                                        jdbc.queryForObject(
                                                "SELECT applied_hash FROM authz_projection WHERE id=?",
                                                String.class,
                                                projection)),
                        () ->
                                assertFalse(
                                        projectionPolicies(projection).isEmpty(),
                                        "Writer must record exact native policy names for future cleanup"),
                        () ->
                                assertEquals(
                                        1, graph.resourceCount(), "Retry may not clone resource"),
                        () ->
                                assertTrue(
                                        graph.scopedPolicyCount() > 0,
                                        "Retry must install the missing native owner policy"),
                        () ->
                                assertEquals(
                                        3,
                                        graph.permissionCount(),
                                        "Bundle personal áp một scope permission cho mỗi scope"
                                                + " còn nguồn (enter, read, class.list) trên cùng"
                                                + " một resource"),
                        () ->
                                assertTrue(
                                        graph.allowed(
                                                nativeGate.webTokensViaCodePkce().accessToken(),
                                                graph.resourceId()),
                                        "Owner policy must be granted by TEST API audience PDP"));
                tx(() -> {
                    assertEquals(
                            operation.getId(),
                            idempotency
                                    .executeOnce(
                                            operation,
                                            () -> fail("Retry must not create another operation"))
                                    .getId());
                    return null;
                });
                assertEquals(1, graph.resourceCount());
                assertEquals(3, graph.permissionCount());
            }
        }
    }

    private List<String> projectionPolicies(UUID projection) {
        String[] names =
                jdbc.queryForObject(
                        "SELECT policy_names FROM authz_projection WHERE id=?",
                        (rs, _) -> (String[]) rs.getArray(1).getArray(),
                        projection);
        return java.util.Arrays.asList(java.util.Objects.requireNonNull(names));
    }

    @DisplayName(
            "Crash sau native revoke nhưng trước database ack có thể replay mà không nhân đôi graph")
    @Test
    void crashBeforeAckReplaysCleanly() throws Exception {
        // Partition này không ghi projection nào, nên worker không thể xác minh hay publish nó;
        // test điều khiển cửa sổ crash mạng/commit bằng tay,
        // với Keycloak native, các revision PostgreSQL đã commit và một lần revoke thứ hai giống
        // hệt.
        try (var nativeGate = new NativeRevocationFixture();
                var session = nativeSession(nativeGate)) {
            ActorScope fixture = actorWithReadyPersonalWorkspace(nativeGate.userSubject());
            String token = session.currentAccessToken();
            assertTrue(nativeGate.allowed(token, "a"));
            assertTrue(nativeGate.allowed(token, "b"));
            var graphBefore = nativeGate.graphInventory();
            var operation =
                    new AuthorizationOperation(
                            UUID.randomUUID(),
                            fixture.actor(),
                            fixture.workspace(),
                            "revocation-native-crash-retry",
                            "a".repeat(64),
                            List.of(fixture.account(), fixture.workspacePartition()),
                            List.of(0L, 1L),
                            0L);
            tx(() -> {
                idempotency.executeOnce(operation, () -> fence(fixture.workspacePartition(), 1));
                jdbc.update(
                        "UPDATE authorization_operation SET state='APPLYING', attempt_count=1"
                                + " WHERE id=?",
                        operation.getId());
                return null;
            });

            // Native operation commit, rồi worker biến mất trước khi ghi READY.
            nativeGate.revoke("a");
            assertFalse(nativeGate.allowed(token, "a"));
            assertTrue(nativeGate.allowed(token, "b"));
            assertEquals("PENDING", state(fixture.workspacePartition()));
            assertEquals(
                    HttpStatus.ACCEPTED,
                    tx(() ->
                            idempotency
                                    .responseFor(
                                            idempotency
                                                    .findExisting(
                                                            fixture.actor(),
                                                            "revocation-native-crash-retry",
                                                            "a".repeat(64))
                                                    .orElseThrow())
                                    .getStatusCode()));

            tx(() -> {
                jdbc.update(
                        "UPDATE authorization_operation SET state='RETRYABLE_FAILURE',"
                                + " last_error_code='KEYCLOAK_ACK_LOST', next_attempt_at=now()"
                                + " WHERE id=?",
                        operation.getId());
                return null;
            });
            nativeGate.revoke("a"); // Replay cùng native operation, không thêm đối tượng graph mới.
            assertEquals(graphBefore, nativeGate.graphInventory());
            assertFalse(nativeGate.allowed(token, "a"));
            assertTrue(nativeGate.allowed(token, "b"));

            tx(() -> {
                var replay =
                        idempotency.executeOnce(
                                operation, () -> fail("Retry must not reapply desired mutation"));
                assertEquals(operation.getId(), replay.getId());
                jdbc.update(
                        "UPDATE authorization_operation SET state='APPLYING',"
                                + " attempt_count=2, next_attempt_at=NULL, last_error_code=NULL"
                                + " WHERE id=?",
                        operation.getId());
                jdbc.update(
                        "UPDATE authz_partition SET applied_revision=1, state='READY' WHERE id=?",
                        fixture.workspacePartition());
                jdbc.update(
                        "UPDATE authorization_operation SET state='SUCCEEDED' WHERE id=?",
                        operation.getId());
                return null;
            });
            assertEquals(
                    HttpStatus.OK,
                    tx(() ->
                            idempotency
                                    .responseFor(
                                            idempotency
                                                    .findExisting(
                                                            fixture.actor(),
                                                            "revocation-native-crash-retry",
                                                            "a".repeat(64))
                                                    .orElseThrow())
                                    .getStatusCode()));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM authorization_operation WHERE id=?",
                            Integer.class,
                            operation.getId()));
        }
    }

    @DisplayName("Worker stale không thể publish revision cũ sau một lần revoke mới")
    @Test
    void staleWorkerCannotPublishOldRevision() {
        UUID partition = partition();
        tx(() -> {
            fence(partition, 2);
            return null;
        });

        // Một worker cho revision 1 không thể claim READY sau khi revision 2 đã thành desired.
        assertThrows(
                DataAccessException.class,
                () ->
                        tx(() -> {
                            jdbc.update(
                                    "UPDATE authz_partition SET applied_revision = 1,"
                                            + " state = 'READY' WHERE id = ?",
                                    partition);
                            return null;
                        }));
        assertEquals("PENDING", state(partition));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        partition));
    }

    @DisplayName(
            "Operation bị bỏ dở ở trạng thái APPLYING vẫn bị fence cho tới khi recovery publish revision")
    @Test
    void abandonedOperationStaysFenced() {
        Recovery created =
                tx(() -> {
                    UUID actor = UUID.randomUUID();
                    UUID workspace = UUID.randomUUID();
                    UUID grant = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                                    + " VALUES (?,?,?,false,'SELF_REGISTERED','PENDING')",
                            actor,
                            "revocation-sub-" + actor,
                            "revocation-user-" + actor);
                    jdbc.update(
                            "INSERT INTO workspace(id,kind,owner_user_id,state)"
                                    + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                            workspace,
                            actor);
                    jdbc.update(
                            "INSERT INTO authz_partition(id,kind,owner_key,state)"
                                    + " VALUES (?,'GRANT',?,'READY')",
                            grant,
                            "grant:" + grant);
                    UUID account =
                            jdbc.queryForObject(
                                    "SELECT id FROM authz_partition WHERE owner_key = ?",
                                    UUID.class,
                                    "account:" + actor);
                    UUID scope =
                            jdbc.queryForObject(
                                    "SELECT id FROM authz_partition WHERE owner_key = ?",
                                    UUID.class,
                                    "workspace:" + workspace);
                    jdbc.update(
                            "UPDATE authz_partition SET state = 'READY' WHERE id IN (?, ?)",
                            account,
                            scope);
                    var operation =
                            new AuthorizationOperation(
                                    UUID.randomUUID(),
                                    actor,
                                    workspace,
                                    "crash-retry",
                                    "a".repeat(64),
                                    List.of(account, scope, grant),
                                    List.of(0L, 0L, 1L),
                                    0L);
                    idempotency.executeOnce(operation, () -> fence(grant, 1));
                    jdbc.update(
                            "UPDATE authorization_operation SET state = 'APPLYING',"
                                    + " attempt_count = 1 WHERE id = ?",
                            operation.getId());
                    return new Recovery(actor, grant, operation.getId());
                });

        // Worker đã dừng sau commit: một transaction mới chỉ có thể trả 202.
        assertEquals("PENDING", state(created.grant()));
        assertEquals(
                HttpStatus.ACCEPTED,
                tx(() ->
                        idempotency
                                .responseFor(
                                        idempotency
                                                .findExisting(
                                                        created.actor(),
                                                        "crash-retry",
                                                        "a".repeat(64))
                                                .orElseThrow())
                                .getStatusCode()));

        tx(() -> {
            jdbc.update(
                    "UPDATE authorization_operation SET state = 'RETRYABLE_FAILURE',"
                            + " last_error_code = 'KEYCLOAK_FAILURE', next_attempt_at = now()"
                            + " WHERE id = ?",
                    created.operation());
            return null;
        });
        assertEquals("PENDING", state(created.grant()));

        tx(() -> {
            jdbc.update(
                    "UPDATE authorization_operation SET state = 'APPLYING', attempt_count = 2,"
                            + " next_attempt_at = NULL WHERE id = ?",
                    created.operation());
            jdbc.update(
                    "UPDATE authz_partition SET applied_revision = 1, state = 'READY'"
                            + " WHERE id = ?",
                    created.grant());
            jdbc.update(
                    "UPDATE authorization_operation SET state = 'SUCCEEDED',"
                            + " last_error_code = NULL WHERE id = ?",
                    created.operation());
            return null;
        });

        assertEquals("READY", state(created.grant()));
        assertEquals(
                HttpStatus.OK,
                tx(() ->
                        idempotency
                                .responseFor(
                                        idempotency
                                                .findExisting(
                                                        created.actor(),
                                                        "crash-retry",
                                                        "a".repeat(64))
                                                .orElseThrow())
                                .getStatusCode()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                        Integer.class,
                        created.actor()));
    }

    @DisplayName("Operation trẻ hơn không thể vượt qua operation cũ hơn trên cùng một partition")
    @Test
    void youngerOperationCannotOvertake() throws Exception {
        UUID partition = partition();
        UUID[] operations =
                tx(() -> {
                    UUID actor = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                                    + " VALUES (?,?,?,false,'SELF_REGISTERED','PENDING')",
                            actor,
                            "revocation-sub-" + actor,
                            "revocation-user-" + actor);
                    jdbc.update(
                            "INSERT INTO workspace(id,kind,owner_user_id,state)"
                                    + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                            UUID.randomUUID(),
                            actor);
                    fence(partition, 1);
                    var older =
                            new AuthorizationOperation(
                                    UUID.randomUUID(),
                                    actor,
                                    null,
                                    "ordering-older",
                                    "a".repeat(64),
                                    List.of(partition),
                                    List.of(1L),
                                    1L);
                    idempotency.executeOnce(older, () -> {});
                    jdbc.update(
                            "UPDATE authorization_operation SET state='RETRYABLE_FAILURE',"
                                    + " attempt_count=1,"
                                    + " next_attempt_at=now() + INTERVAL '1 hour'"
                                    + " WHERE id=?",
                            older.getId());
                    var younger =
                            new AuthorizationOperation(
                                    UUID.randomUUID(),
                                    actor,
                                    null,
                                    "ordering-younger",
                                    "b".repeat(64),
                                    List.of(partition),
                                    List.of(1L),
                                    1L);
                    idempotency.executeOnce(younger, () -> {});
                    return new UUID[] {older.getId(), younger.getId()};
                });
        UUID older = operations[0];
        UUID younger = operations[1];

        // Vài poll của worker trôi qua: operation trẻ hơn đã tới hạn, nhưng nó phải giữ nguyên
        // không bị đụng trong khi operation cũ hơn chạm cùng partition vẫn đang chờ.
        // Awaitility during() giữ điều kiện liên tục suốt khoảng chờ thay cho ngủ cố định.
        await().during(Duration.ofSeconds(8))
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                        "PENDING".equals(operationState(younger))
                                && operationAttempts(younger) == 0);
        assertEquals("PENDING", operationState(younger));
        assertEquals(0, operationAttempts(younger));

        // Khi operation cũ hơn tới hạn, nó được claim trước và fail closed (partition
        // không ghi projection nào để xác minh); operation trẻ hơn vẫn chờ phía sau nó.
        tx(() -> {
            jdbc.update(
                    "UPDATE authorization_operation SET next_attempt_at=now() WHERE id=?", older);
            return null;
        });
        awaitCondition(() -> operationAttempts(older) >= 2);
        assertEquals(2, operationAttempts(older));
        assertEquals("PENDING", operationState(younger));
        assertEquals(0, operationAttempts(younger));
    }

    @DisplayName(
            "Operation bị revision mới hơn vượt qua kết thúc ở trạng thái SUPERSEDED mà không bị fence")
    @Test
    void overtakenOperationEndsSuperseded() throws Exception {
        UUID partition = partition();
        UUID operation =
                tx(() -> {
                    UUID actor = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                                    + " VALUES (?,?,?,false,'SELF_REGISTERED','PENDING')",
                            actor,
                            "revocation-sub-" + actor,
                            "revocation-user-" + actor);
                    jdbc.update(
                            "INSERT INTO workspace(id,kind,owner_user_id,state)"
                                    + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                            UUID.randomUUID(),
                            actor);
                    fence(partition, 1);
                    var recorded =
                            new AuthorizationOperation(
                                    UUID.randomUUID(),
                                    actor,
                                    null,
                                    "superseded-operation",
                                    "a".repeat(64),
                                    List.of(partition),
                                    List.of(1L),
                                    1L);
                    idempotency.executeOnce(recorded, () -> {});
                    jdbc.update(
                            "UPDATE authorization_operation SET state='RETRYABLE_FAILURE',"
                                    + " attempt_count=1, next_attempt_at=now() WHERE id=?",
                            recorded.getId());
                    // Một mutation mới hơn fence partition tại revision 2 trước khi worker
                    // tiếp tục operation cũ hơn.
                    fence(partition, 2);
                    return recorded.getId();
                });

        awaitCondition(() -> "FAILED".equals(operationState(operation)));
        assertEquals("FAILED", operationState(operation));
        assertEquals(
                "SUPERSEDED_BY_NEWER_REVISION",
                jdbc.queryForObject(
                        "SELECT last_error_code FROM authorization_operation WHERE id=?",
                        String.class,
                        operation));
        // Revision mới hơn giữ fence của nó: operation bị superseded không được fail
        // partition cũng không được mở lại nó.
        assertEquals("PENDING", state(partition));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id=?",
                        Long.class,
                        partition));
    }

    private record Recovery(UUID actor, UUID grant, UUID operation) {}

    private int backendPid() {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

    /**
     * Chờ một điều kiện tối đa 15 giây bằng Awaitility (poll 100 ms) thay vòng poll viết tay.
     * Timeout bị nuốt có chủ đích: các assert ngay sau vòng chờ tự báo đúng trạng thái lệch kèm
     * thông điệp của chúng, như hành vi vòng poll cũ.
     */
    private static void awaitCondition(BooleanSupplier condition) {
        try {
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(100))
                    .until(condition::getAsBoolean);
        } catch (ConditionTimeoutException ignored) {
            // assert phía sau sẽ báo trạng thái thật
        }
    }

    private void assertBlockedBy(Future<?> waiting, int waiterPid, int blockerPid)
            throws Exception {
        AtomicBoolean blocked = new AtomicBoolean();
        try {
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(25))
                    .until(() -> {
                        if (waiting.isDone()) {
                            return true;
                        }
                        boolean found =
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT ? = ANY(pg_blocking_pids(?))",
                                                Boolean.class,
                                                blockerPid,
                                                waiterPid));
                        if (found) {
                            blocked.set(true);
                        }
                        return found;
                    });
        } catch (ConditionTimeoutException ignored) {
            // Hết 10 giây: fail ở dưới như vòng poll cũ.
        }
        if (!blocked.get()) {
            fail("PostgreSQL did not report backend " + waiterPid + " blocked by " + blockerPid);
        }
    }

    private UUID partition() {
        return tx(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO authz_partition(id,kind,owner_key,state)"
                            + " VALUES (?,'GRANT',?,'READY')",
                    id,
                    "grant:" + id);
            return id;
        });
    }

    private String lockedState(UUID partition, String lock) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ? FOR " + lock,
                        String.class,
                        partition));
    }

    private String state(UUID partition) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?", String.class, partition));
    }

    private String operationState(UUID operation) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT state FROM authorization_operation WHERE id = ?",
                        String.class,
                        operation));
    }

    private @org.jspecify.annotations.Nullable String operationErrorCode(UUID operation) {
        return jdbc.queryForObject(
                "SELECT last_error_code FROM authorization_operation WHERE id = ?",
                String.class,
                operation);
    }

    private int operationAttempts(UUID operation) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT attempt_count FROM authorization_operation WHERE id = ?",
                        Integer.class,
                        operation));
    }

    private void fence(UUID partition, long revision) {
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = ?, state = 'PENDING' WHERE id = ?",
                revision,
                partition);
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
