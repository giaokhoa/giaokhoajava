package com.giaokhoa.authorization.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.fence.DependencyManifestResolver;
import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.DesiredProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.PartitionChange;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.identity.eligibility.IdentityEligibilitySynchronizer;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.support.ProjectionFaultInjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.WebApplicationContext;

/** PostgreSQL thật; latch ở biên Keycloak kiểm soát chính xác thứ tự side effect của hai worker. */
@SpringBootTest
class ProjectionRecoveryIT extends IntegrationEnvironment {
    // Schema cô lập là bắt buộc: test mock ProjectionWorker để tự poll thủ công, khóa thứ
    // tự bằng latch và đếm lần áp graph qua fault injection; trên schema dùng chung, worker
    // thật của context khác (poll mỗi giây) sẽ giành xử lý operation và phá tính xác định.
    private static final String SCHEMA =
            "projection_recovery_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext webContext;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProjectionReconciler reconciler;
    @Autowired AuthorizationOperationService operations;
    @Autowired IdentityEligibilitySynchronizer eligibility;
    @Autowired AuthorizationFence fence;
    @Autowired DependencyManifestResolver resolver;
    @MockitoBean PolicyGraphWriter writer;
    @MockitoBean KeycloakProvisioningClient directory;
    @MockitoBean CurrentIdentityClient identities;
    @MockitoBean ProjectionWorker scheduledWorker;

    private final Map<String, Boolean> memberships = new ConcurrentHashMap<>();

    @BeforeEach
    void nativeBoundary() {
        when(writer.apply(any()))
                .thenAnswer(call ->
                        new PolicyGraphWriter.AppliedGraph(
                                "resource:"
                                        + call.getArgument(
                                                        0,
                                                        com.giaokhoa.authorization.policy
                                                                .PolicyGraph.class)
                                                .targetKey(),
                                List.of("owner")));
        when(writer.isGraphIntact(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(true);
        when(directory.isRealmGroupMember(anyString(), anyString()))
                .thenAnswer(
                        call -> memberships.getOrDefault(call.getArgument(0, String.class), false));
        doAnswer(
                        call -> {
                            memberships.put(
                                    call.getArgument(0, String.class),
                                    call.getArgument(2, Boolean.class));
                            return null;
                        })
                .when(directory)
                .setRealmGroupMembership(anyString(), anyString(), anyBoolean());
    }

    @Test
    void anIdentityReadFailureOnAnAuthenticatedHttpRequestFailsClosed() throws Exception {
        Actor actor = actor();
        when(identities.readCurrentIdentity(actor.subject()))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(
                        post("/api/workspaces/{wid}/capabilities", actor.workspace())
                                .with(user(actor.subject()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"targets\":[{\"type\":\"WORKSPACE\",\"id\":\""
                                                + actor.workspace()
                                                + "\",\"scopes\":[\"enter\"]}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));
        verifyNoInteractions(writer);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE initiator_user_id=?",
                        Integer.class,
                        actor.user()));
    }

    @Test
    void aNewWorkerWaitsForTheOldWorkerAndLeavesTheRevocationApplied() throws Exception {
        Actor actor = actor();
        seedWorkspace(actor, List.of());
        CountDownLatch oldAtWriter = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch newStarted = new CountDownLatch(1);
        CountDownLatch newAtWriter = new CountDownLatch(1);
        AtomicBoolean nativeAllow = new AtomicBoolean();
        doAnswer(
                        _ -> {
                            oldAtWriter.countDown();
                            await(releaseOld);
                            nativeAllow.set(true);
                            return new PolicyGraphWriter.AppliedGraph("resource", List.of("owner"));
                        })
                .when(writer)
                .apply(any());
        doAnswer(
                        _ -> {
                            nativeAllow.set(false);
                            newAtWriter.countDown();
                            return null;
                        })
                .when(writer)
                .retractRoot(actor.target(), "enter");

        try (var pool = Executors.newFixedThreadPool(2)) {
            var old =
                    pool.submit(() -> reconciler.reconcilePartition(actor.workspacePartition(), 0));
            assertTrue(oldAtWriter.await(10, TimeUnit.SECONDS));
            tx().executeWithoutResult(_ -> {
                jdbc.update(
                        "UPDATE authz_partition SET desired_revision=1 WHERE id=?",
                        actor.workspacePartition());
                jdbc.update(
                        "UPDATE authz_projection SET desired_hash=? WHERE partition_id=?",
                        ProjectionReconciler.desiredHash(actor.target(), "enter", List.of()),
                        actor.workspacePartition());
            });
            var newer =
                    pool.submit(() -> {
                        newStarted.countDown();
                        reconciler.reconcilePartition(actor.workspacePartition(), 1);
                    });
            try {
                assertTrue(newStarted.await(10, TimeUnit.SECONDS));
                assertFalse(newAtWriter.await(250, TimeUnit.MILLISECONDS));
            } finally {
                releaseOld.countDown();
            }
            var failure =
                    assertThrows(ExecutionException.class, () -> old.get(10, TimeUnit.SECONDS));
            assertInstanceOf(
                    ProjectionReconciler.ReconciliationException.class, failure.getCause());
            newer.get(10, TimeUnit.SECONDS);
            assertFalse(nativeAllow.get());
            assertEquals("READY", state(actor.workspacePartition()));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT applied_revision FROM authz_partition WHERE id=?",
                            Long.class,
                            actor.workspacePartition()));
            // Worker còn giữ revision cũ phải bị chặn trước side effect, kể cả sau READY mới.
            clearInvocations(writer);
            assertThrows(
                    ProjectionReconciler.ReconciliationException.class,
                    () -> reconciler.reconcilePartition(actor.workspacePartition(), 0));
            verifyNoInteractions(writer);
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void reversedUuidDependencyClosureCompletesInOneWorkerCycle() {
        List<Actor> actors = new ArrayList<>(List.of(actor(), actor(), actor()));
        actors.sort(Comparator.comparing(a -> a.workspacePartition().toString()));
        Actor a = actors.get(1);
        Actor b = actors.get(2);
        Actor c = actors.get(0);
        for (Actor item : List.of(a, b, c)) {
            List<UUID> dependencies =
                    item.equals(b)
                            ? List.of(a.workspacePartition())
                            : item.equals(c) ? List.of(b.workspacePartition()) : List.of();
            seedWorkspace(item, dependencies);
            reconciler.reconcilePartition(item.workspacePartition());
        }
        AuthorizationOperation operation =
                operations.applyDesiredState(
                        a.user(),
                        a.workspace(),
                        "closure-" + a.user(),
                        "a".repeat(64),
                        List.of(
                                new PartitionChange(
                                        a.workspacePartition(), List.of(desired(a, List.of())))));
        worker().poll();
        assertEquals("SUCCEEDED", operationState(operation.getId()));
        for (Actor item : List.of(a, b, c)) {
            assertEquals("READY", state(item.workspacePartition()));
        }
    }

    @Test
    void losingAndRegainingVerificationKeepsOwnershipAndConvergesTheWholeClosure() {
        Actor actor = actor();
        seedWorkspace(actor, List.of(actor.account()));
        reconciler.reconcilePartition(actor.workspacePartition());
        for (boolean verified : List.of(true, false, true)) {
            when(identities.readCurrentIdentity(actor.subject()))
                    .thenReturn(
                            new CurrentIdentity(
                                    actor.subject(),
                                    "learner",
                                    "learner@example.test",
                                    verified,
                                    true));
            AuthorizationOperation operation =
                    eligibility.synchronizeEligibility(actor.user()).orElseThrow();
            worker().poll();
            assertEquals("SUCCEEDED", operationState(operation.getId()));
            assertEquals("READY", state(actor.account()));
            assertEquals("READY", state(actor.workspacePartition()));
            assertEquals(verified, memberships.get(actor.subject()));
            // Request kế tiếp không được tạo operation vô hạn sau một retract đã áp.
            assertTrue(eligibility.synchronizeEligibility(actor.user()).isEmpty());
        }
    }

    @Test
    void aCrashAfterTheGraphIsAppliedRetriesTheSameGraphInsteadOfDuplicatingTheGrant()
            throws Exception {
        Actor actor = actor();
        seedWorkspace(actor, List.of());
        reconciler.reconcilePartition(actor.workspacePartition());
        stubVerifiedIdentity(actor, true);
        AuthorizationOperation initial =
                eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        worker().poll();
        assertEquals("SUCCEEDED", operationState(initial.getId()));
        assertEquals("READY", state(actor.account()));
        assertEquals(Boolean.TRUE, memberships.get(actor.subject()));
        stubVerifiedIdentity(actor, false);
        AuthorizationOperation loss =
                eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        worker().poll();
        assertEquals("SUCCEEDED", operationState(loss.getId()));
        assertEquals("READY", state(actor.account()));
        assertEquals(Boolean.FALSE, memberships.get(actor.subject()));

        ProjectionFaultInjection faults = new ProjectionFaultInjection();
        doAnswer(faults.applyAnswer()).when(writer).apply(any());
        doAnswer(faults.directorySetAnswer(memberships))
                .when(directory)
                .setRealmGroupMembership(anyString(), anyString(), anyBoolean());
        faults.failNextDirectorySets(1);
        stubVerifiedIdentity(actor, true);
        AuthorizationOperation gain =
                eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        worker().poll();
        // Graph đã áp vào Keycloak nhưng commit revision chưa kịp chạy: operation chỉ được retry,
        // không SUCCEEDED, partition vẫn bị fence.
        assertEquals("RETRYABLE_FAILURE", operationState(gain.getId()));
        assertEquals("PENDING", state(actor.account()));
        // Một lần áp graph cho mỗi scope đọc của target account: workspace.list + history.read.
        assertEquals(2, accountApplies(faults, actor).size());
        assertEquals(Boolean.FALSE, memberships.get(actor.subject()));

        forceDue(gain.getId());
        worker().poll();
        assertEquals("SUCCEEDED", operationState(gain.getId()));
        assertEquals("READY", state(actor.account()));
        assertEquals(Boolean.TRUE, memberships.get(actor.subject()));
        // Retry áp lại đúng hai graph đã áp dở, không tạo thêm grant: tập graph của lần retry
        // bằng đúng tập graph của lần crash, theo cùng thứ tự scope.
        List<PolicyGraph> applies = accountApplies(faults, actor);
        assertEquals(4, applies.size());
        assertEquals(applies.subList(0, 2), applies.subList(2, 4));
    }

    @Test
    void aTerminalFailureKeepsTheFenceClosedUntilAnExplicitReconcileReopensIt() throws Exception {
        Actor actor = actor();
        seedWorkspace(actor, List.of());
        reconciler.reconcilePartition(actor.workspacePartition());

        ProjectionFaultInjection faults = new ProjectionFaultInjection();
        doAnswer(faults.applyAnswer()).when(writer).apply(any());
        faults.failNextApplies(Integer.MAX_VALUE);
        AuthorizationOperation operation =
                operations.applyDesiredState(
                        actor.user(),
                        actor.workspace(),
                        "terminal-" + actor.user(),
                        "b".repeat(64),
                        List.of(
                                new PartitionChange(
                                        actor.workspacePartition(),
                                        List.of(desired(actor, List.of())))));
        // Cạn ngân sách lần thử ở đúng lần này: việc đếm đủ 5 lần đã khóa bằng unit test worker.
        tx().executeWithoutResult(_ ->
                jdbc.update(
                        "UPDATE authorization_operation SET attempt_count = 4 WHERE id = ?",
                        operation.getId()));
        worker().poll();
        assertEquals("FAILED", operationState(operation.getId()));
        assertEquals("FAILED", state(actor.workspacePartition()));
        assertEquals("FAILED", projectionState(actor.workspacePartition()));
        mvc().perform(
                        get("/api/me/operations/{operationId}", operation.getId())
                                .with(user(actor.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.errorCode").value(ProjectionWorker.ERROR_RECONCILE_FAILED));
        // Request sau fail-closed 503 thay vì quyết định trên dữ kiện cũ.
        HttpProblemException notReady =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                fence.acquire(
                                        resolver.resolve(
                                                actor.user(),
                                                actor.workspace(),
                                                List.of(actor.target()))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, notReady.getStatus());
        // Worker tự chạy cũng không mở lại fence FAILED: partition vẫn FAILED và request sau
        // vẫn fail-closed 503, dù các partition khác trong cùng schema có được worker chạm tới.
        worker().poll();
        assertEquals("FAILED", state(actor.workspacePartition()));
        assertEquals("FAILED", projectionState(actor.workspacePartition()));
        assertThrows(
                HttpProblemException.class,
                () ->
                        fence.acquire(
                                resolver.resolve(
                                        actor.user(), actor.workspace(), List.of(actor.target()))));

        // Đường mở lại duy nhất là explicit reconcile sau khi sửa ngoài luồng xong.
        faults.clear();
        reconciler.reconcilePartition(actor.workspacePartition());
        assertEquals("READY", state(actor.workspacePartition()));
        assertEquals("READY", projectionState(actor.workspacePartition()));
        assertTrue(reconciler.verifyStoredGraph(actor.workspacePartition()));
        fence.acquire(resolver.resolve(actor.user(), actor.workspace(), List.of(actor.target())));
    }

    @Test
    void aCrashedRetryNeverAppliesTheOldRevisionAfterANewerRevisionLanded() {
        Actor actor = actor();
        seedWorkspace(actor, List.of());
        reconciler.reconcilePartition(actor.workspacePartition());
        stubVerifiedIdentity(actor, false);
        AuthorizationOperation initial =
                eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        worker().poll();
        assertEquals("SUCCEEDED", operationState(initial.getId()));

        ProjectionFaultInjection faults = new ProjectionFaultInjection();
        doAnswer(faults.applyAnswer()).when(writer).apply(any());
        doAnswer(faults.retractAnswer()).when(writer).retractRoot(anyString(), anyString());
        faults.failNextApplies(1);
        // Operation cũ áp graph eligibility mới nhưng crash ngay lần áp đầu tiên.
        stubVerifiedIdentity(actor, true);
        AuthorizationOperation old = eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        worker().poll();
        assertEquals("RETRYABLE_FAILURE", operationState(old.getId()));
        assertEquals(1, accountApplies(faults, actor).size());

        // Trong lúc operation cũ còn retry, một revision mới hơn thu hồi eligibility.
        stubVerifiedIdentity(actor, false);
        AuthorizationOperation newer =
                eligibility.synchronizeEligibility(actor.user()).orElseThrow();
        forceDue(old.getId());
        worker().poll();
        // Retry của revision cũ bị superseded trước mọi side effect: graph cũ không bao giờ
        // được áp lại sau khi revision mới đã thu hồi nó.
        assertEquals("FAILED", operationState(old.getId()));
        assertEquals(
                ProjectionWorker.ERROR_SUPERSEDED,
                jdbc.queryForObject(
                        "SELECT last_error_code FROM authorization_operation WHERE id = ?",
                        String.class,
                        old.getId()));
        assertEquals("SUCCEEDED", operationState(newer.getId()));
        assertEquals(1, accountApplies(faults, actor).size());
        assertEquals(
                List.of(
                        "account:" + actor.user() + "|workspace.list",
                        "account:" + actor.user() + "|history.read"),
                accountRetracts(faults, actor));
        assertEquals("READY", state(actor.account()));
        assertEquals(
                3L,
                jdbc.queryForObject(
                        "SELECT applied_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        actor.account()));
    }

    private Actor actor() {
        return tx().execute(_ -> {
            UUID user = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            String subject = "subject-" + user;
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,origin,identity_state)"
                            + " VALUES (?,?,?,'learner@example.test',true,'SELF_REGISTERED','ACTIVE')",
                    user,
                    subject,
                    "user-" + user);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    user);
            UUID account =
                    jdbc.queryForList(
                                    "SELECT id FROM authz_partition WHERE owner_key=?",
                                    UUID.class,
                                    "account:" + user)
                            .getFirst();
            UUID partition =
                    jdbc.queryForList(
                                    "SELECT id FROM authz_partition WHERE owner_key=?",
                                    UUID.class,
                                    "workspace:" + workspace)
                            .getFirst();
            jdbc.update("UPDATE authz_partition SET state='READY' WHERE id=?", account);
            return new Actor(user, workspace, subject, account, partition);
        });
    }

    private void seedWorkspace(Actor actor, List<UUID> dependencies) {
        jdbc.update(
                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,dependency_partition_ids) VALUES (?,?,?,?,?::uuid[])",
                UUID.randomUUID(),
                actor.workspacePartition(),
                actor.target(),
                ProjectionReconciler.desiredHash(actor.target(), "enter", bundle(actor)),
                "{"
                        + String.join(",", dependencies.stream().map(id -> id.toString()).toList())
                        + "}");
    }

    private DesiredProjection desired(Actor actor, List<UUID> dependencies) {
        return new DesiredProjection(actor.target(), "enter", bundle(actor), dependencies);
    }

    // Seed đúng bundle entitlement mà đường bootstrap production ghi cho personal workspace.
    private List<String> bundle(Actor actor) {
        return new PersonalEntitlementProjector(jdbc).bundleDescriptors(actor.workspace());
    }

    private List<PolicyGraph> accountApplies(ProjectionFaultInjection faults, Actor actor) {
        return faults.appliedGraphs().stream()
                .filter(graph -> ("account:" + actor.user()).equals(graph.targetKey()))
                .toList();
    }

    private List<String> accountRetracts(ProjectionFaultInjection faults, Actor actor) {
        return faults.retractedRoots().stream()
                .filter(root -> root.startsWith("account:" + actor.user() + "|"))
                .toList();
    }

    private void stubVerifiedIdentity(Actor actor, boolean verified) {
        when(identities.readCurrentIdentity(actor.subject()))
                .thenReturn(
                        new CurrentIdentity(
                                actor.subject(),
                                "learner",
                                "learner@example.test",
                                verified,
                                true));
    }

    private void forceDue(UUID operation) {
        jdbc.update(
                "UPDATE authorization_operation SET next_attempt_at = NULL WHERE id = ?",
                operation);
    }

    private String projectionState(UUID partition) {
        return jdbc.queryForList(
                        "SELECT state FROM authz_projection WHERE partition_id = ?",
                        String.class,
                        partition)
                .getFirst();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private ProjectionWorker worker() {
        return new ProjectionWorker(jdbc, reconciler, transactionManager, new AuditRecorder(jdbc));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String state(UUID partition) {
        return jdbc.queryForList(
                        "SELECT state FROM authz_partition WHERE id=?", String.class, partition)
                .getFirst();
    }

    private String operationState(UUID operation) {
        return jdbc.queryForList(
                        "SELECT state FROM authorization_operation WHERE id=?",
                        String.class,
                        operation)
                .getFirst();
    }

    private record Actor(
            UUID user, UUID workspace, String subject, UUID account, UUID workspacePartition) {
        String target() {
            return "workspace:" + workspace;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for worker");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted worker", ex);
        }
    }
}
