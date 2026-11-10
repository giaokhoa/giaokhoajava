package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.OperationController;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.identity.model.RecoveryMutation;
import com.giaokhoa.identity.repository.RecoveryMutationRepository;
import com.giaokhoa.identity.repository.RecoveryRequestRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test tương đương cho giao thức mặt phẳng điều khiển authorization sau khi cài lại trên JPA: claim
 * operation bằng khóa entity + SKIP LOCKED, fence closure giữa các partition, ánh xạ xung
 * đột @Version và round-trip cột mảng qua repository. Replay/conflict của idempotency đã được
 * IdempotencyServiceIT khóa riêng.
 */
@SpringBootTest
@Transactional
class AuthorizationJpaProtocolIT extends IntegrationEnvironment {

    // Worker nền không thuộc phạm vi: các test ở đây lái giao thức trực tiếp qua repository.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired AuthzPartitionRepository partitions;
    @Autowired AuthzProjectionRepository projections;
    @Autowired AuthorizationOperationRepository operations;
    @Autowired AuthorizationOperationService operationService;
    @Autowired OperationController operationController;
    @Autowired RecoveryRequestRepository recoveryRequests;
    @Autowired RecoveryMutationRepository recoveryMutations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;

    @DisplayName(
            "Cột mảng đọc lại qua repository giữ đúng thứ tự phần tử, phân biệt rỗng với có phần tử"
                    + " và recorded_seq do database sinh được nạp")
    @Test
    void arrayColumnsRoundTripThroughRepositories() {
        UUID actor = user();
        UUID workspace = workspaceOf(actor);
        UUID accountPartition = partitionByOwnerKey("account:" + actor);
        UUID workspacePartition = partitionByOwnerKey("workspace:" + workspace);

        UUID projectionId = UUID.randomUUID();
        projections.saveAndFlush(
                new AuthorizationProjection(
                        projectionId,
                        workspacePartition,
                        "workspace:" + workspace + ":result:" + UUID.randomUUID(),
                        null,
                        List.of("policy.b", "policy.a"),
                        List.of(accountPartition),
                        "a".repeat(64),
                        null,
                        AuthorizationProjection.State.PENDING));
        UUID emptyProjectionId = UUID.randomUUID();
        projections.saveAndFlush(
                new AuthorizationProjection(
                        emptyProjectionId,
                        workspacePartition,
                        "workspace:" + workspace + ":result:" + UUID.randomUUID(),
                        null,
                        List.of(),
                        List.of(),
                        "b".repeat(64),
                        null,
                        AuthorizationProjection.State.PENDING));
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        actor,
                        workspace,
                        "round-trip-key",
                        "c".repeat(64),
                        List.of(accountPartition, workspacePartition),
                        List.of(0L, 1L),
                        0L);
        operations.saveAndFlush(operation);
        entities.clear();

        AuthorizationProjection loaded = projections.findById(projectionId).orElseThrow();
        assertEquals(List.of("policy.b", "policy.a"), loaded.getPolicyNames());
        assertEquals(List.of(accountPartition), loaded.getDependencyPartitionIds());
        assertEquals(
                List.of(), projections.findById(emptyProjectionId).orElseThrow().getPolicyNames());
        AuthorizationOperation loadedOperation =
                operations.findById(operation.getId()).orElseThrow();
        assertEquals(
                List.of(accountPartition, workspacePartition),
                loadedOperation.getAffectedPartitions());
        assertEquals(List.of(0L, 1L), loadedOperation.getAffectedRevisions());
        assertTrue(loadedOperation.getRecordedSeq() > 0);
    }

    @DisplayName(
            "Quét mảng partition bị ảnh hưởng chỉ trả operation thuộc tập trạng thái được lọc,"
                    + " bỏ qua operation đã kết thúc")
    @Test
    void affectedPartitionsScanFiltersByState() {
        UUID actor = user();
        UUID workspace = workspaceOf(actor);
        UUID pendingFirst = grantPartition();
        UUID pendingSecond = grantPartition();
        UUID retryPartition = grantPartition();
        UUID succeededPartition = grantPartition();

        operationInState(
                actor,
                workspace,
                "scan-pending",
                AuthorizationOperation.State.PENDING,
                pendingFirst,
                pendingSecond);
        operationInState(
                actor,
                workspace,
                "scan-retry",
                AuthorizationOperation.State.RETRYABLE_FAILURE,
                retryPartition);
        operationInState(
                actor,
                workspace,
                "scan-succeeded",
                AuthorizationOperation.State.SUCCEEDED,
                succeededPartition);
        entities.clear();

        // Tập trạng thái đang sống đúng như cửa sổ quét partition mồ côi của worker.
        List<List<UUID>> live =
                operations.findAffectedPartitionsByStateIn(
                        List.of(
                                AuthorizationOperation.State.PENDING,
                                AuthorizationOperation.State.APPLYING,
                                AuthorizationOperation.State.RETRYABLE_FAILURE));
        assertTrue(live.contains(List.of(pendingFirst, pendingSecond)));
        assertTrue(live.contains(List.of(retryPartition)));
        assertTrue(live.stream().noneMatch(affected -> affected.contains(succeededPartition)));

        List<List<UUID>> succeeded =
                operations.findAffectedPartitionsByStateIn(
                        List.of(AuthorizationOperation.State.SUCCEEDED));
        assertTrue(succeeded.contains(List.of(succeededPartition)));
        assertTrue(succeeded.stream().noneMatch(affected -> affected.contains(pendingFirst)));
    }

    @DisplayName(
            "Trạng thái khôi phục trên PostgreSQL thật: người gửi thấy trạng thái đã ánh xạ"
                    + " SUCCEEDED/FAILED, operator có mutation RESOLVE cũng đọc được yêu cầu,"
                    + " người ngoài nhận 404")
    @Test
    void recoveryStatusReadsRealRows() {
        UUID requester = user();
        UUID target = user();
        UUID operator = user();
        UUID outsider = user();

        UUID resolved = finishedRecoveryRequest(requester, target, operator, "RESOLVED");
        UUID rejected = finishedRecoveryRequest(requester, target, operator, "REJECTED");
        jdbc.update(
                "INSERT INTO recovery_mutation(actor_user_id,idempotency_key,payload_hash,"
                        + "request_id,kind) VALUES (?,?,?,?,'RESOLVE')",
                operator,
                "resolve-resolved",
                "a".repeat(64),
                resolved);

        // Đọc qua entity của module identity: trạng thái gốc và tài khoản đích của yêu
        // cầu đã xử lý đọc đúng từ RecoveryRequest.
        var byRequester = recoveryRequests.findById(resolved).orElseThrow();
        assertEquals("RESOLVED", byRequester.getState().name());
        assertEquals(target, byRequester.getTargetUserId());
        // Operator không phải người gửi nhưng là actor của mutation RESOLVE nên nhánh
        // EXISTS cho thấy cùng yêu cầu; trên yêu cầu rejected operator chỉ là người xác
        // minh (verified_by), không có mutation RESOLVE nên không thấy.
        assertTrue(
                recoveryMutations.existsByRequestIdAndActorAndKind(
                        resolved, operator, RecoveryMutation.Kind.RESOLVE));
        assertFalse(
                recoveryMutations.existsByRequestIdAndActorAndKind(
                        rejected, operator, RecoveryMutation.Kind.RESOLVE));

        // Qua controller: trạng thái yêu cầu ánh xạ sang vòng đời operation.
        var resolvedView =
                Objects.requireNonNull(
                        operationController
                                .getStatus(resolved, authenticationFor(requester))
                                .getBody());
        assertEquals("SUCCEEDED", resolvedView.state());
        assertTrue(resolvedView.completed());
        assertEquals("PROFILE", resolvedView.targetType());
        assertEquals(target, resolvedView.targetId());
        assertNull(resolvedView.errorCode());

        var rejectedView =
                Objects.requireNonNull(
                        operationController
                                .getStatus(rejected, authenticationFor(requester))
                                .getBody());
        assertEquals("FAILED", rejectedView.state());
        assertTrue(rejectedView.completed());
        assertEquals("RECOVERY_REJECTED", rejectedView.errorCode());

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> operationController.getStatus(resolved, authenticationFor(outsider)));
        assertEquals(HttpStatus.NOT_FOUND, problem.getStatus());
    }

    @DisplayName(
            "Hai bản ghi cùng một partition lệch version: lần ghi sau bị từ chối bằng ngoại lệ"
                    + " khóa lạc quan (ProblemDetailsAdvice ánh xạ thành 409)")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleEntityWriteFailsWithOptimisticLock() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID actor = tx.execute(_ -> user());
        UUID workspacePartition =
                tx.execute(_ -> partitionByOwnerKey("workspace:" + workspaceOf(actor)));

        // Hai lần bump liên tiếp để bản sao cũ mang version khác 0: save() của Spring Data
        // khi đó đi đường merge và kiểm tra version lúc flush thay vì persist như entity mới.
        tx.executeWithoutResult(_ -> bumpDesiredRevision(workspacePartition));
        AuthorizationPartition stale =
                tx.execute(_ -> partitions.findById(workspacePartition)).orElseThrow();
        tx.executeWithoutResult(_ -> bumpDesiredRevision(workspacePartition));
        stale.setDesiredRevision(stale.getDesiredRevision() + 1);

        assertThrows(
                OptimisticLockingFailureException.class,
                () ->
                        tx.executeWithoutResult(_ -> {
                            partitions.save(stale);
                            partitions.flush();
                        }));
    }

    @DisplayName(
            "Hai instance claim đồng thời cùng một operation: chỉ một instance giữ được operation"
                    + " và attempt chỉ tăng đúng một lần")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsTakeTheOperationOnlyOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID actor = tx.execute(_ -> user());
        UUID workspace = tx.execute(_ -> workspaceOf(actor));
        UUID workspacePartition = tx.execute(_ -> partitionByOwnerKey("workspace:" + workspace));
        UUID operationId =
                tx.execute(_ -> {
                    var operation =
                            new AuthorizationOperation(
                                    UUID.randomUUID(),
                                    actor,
                                    workspace,
                                    "claim-key",
                                    "d".repeat(64),
                                    List.of(workspacePartition),
                                    List.of(1L),
                                    1L);
                    return operations.saveAndFlush(operation).getId();
                });

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Boolean> firstClaimed = new AtomicReference<>();
        AtomicReference<Boolean> secondClaimed = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(() ->
                            tx.executeWithoutResult(_ -> {
                                boolean claimed = claim(operationId);
                                firstClaimed.set(claimed);
                                firstLocked.countDown();
                                await(releaseFirst);
                            }));
            assertTrue(firstLocked.await(15, TimeUnit.SECONDS));
            var second =
                    executor.submit(() ->
                            tx.executeWithoutResult(_ -> {
                                secondClaimed.set(claim(operationId));
                            }));
            // Instance thứ hai claim trong lúc instance đầu còn giữ khóa ghi: SKIP LOCKED
            // làm operation biến mất khỏi tập claimable của nó thay vì chờ hay claim trùng.
            second.get(20, TimeUnit.SECONDS);
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }

        assertEquals(Boolean.TRUE, firstClaimed.get());
        assertEquals(Boolean.FALSE, secondClaimed.get());
        AuthorizationOperation stored =
                tx.execute(_ -> operations.findById(operationId)).orElseThrow();
        assertEquals(AuthorizationOperation.State.APPLYING, stored.getState());
        assertEquals(1, stored.getAttemptCount());
    }

    private void bumpDesiredRevision(UUID partitionId) {
        AuthorizationPartition current = partitions.findById(partitionId).orElseThrow();
        current.setDesiredRevision(current.getDesiredRevision() + 1);
    }

    /**
     * Một lượt claim đúng giao thức worker: quét recorded_seq, khóa SKIP LOCKED, đổi trạng thái.
     */
    private boolean claim(UUID operationId) {
        Instant now = Instant.now();
        var candidates =
                operations.findLiveOrderByRecordedSeq(
                        List.of(
                                AuthorizationOperation.State.PENDING,
                                AuthorizationOperation.State.APPLYING,
                                AuthorizationOperation.State.RETRYABLE_FAILURE),
                        PageRequest.of(0, 100));
        var locked =
                operations.lockClaimableByIds(
                        candidates.stream().map(AuthorizationOperation::getId).toList(),
                        List.of(
                                AuthorizationOperation.State.PENDING,
                                AuthorizationOperation.State.RETRYABLE_FAILURE),
                        AuthorizationOperation.State.APPLYING,
                        now,
                        now.minusSeconds(300));
        Optional<AuthorizationOperation> target =
                locked.stream()
                        .filter(operation -> operation.getId().equals(operationId))
                        .findFirst();
        if (target.isEmpty()) {
            return false;
        }
        AuthorizationOperation operation = target.get();
        operation.setState(AuthorizationOperation.State.APPLYING);
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setNextAttemptAt(null);
        operation.setLastErrorCode(null);
        return true;
    }

    @DisplayName(
            "Đổi nguồn ở một partition fence cả projection READY của partition khác phụ thuộc nó"
                    + " và ghi partition đó vào operation tại revision hiện tại")
    @Test
    void sourceChangeFencesDependentProjectionAcrossPartitions() {
        UUID actor = user();
        UUID workspace = workspaceOf(actor);
        UUID workspacePartition = partitionByOwnerKey("workspace:" + workspace);

        UUID grantPartition = UUID.randomUUID();
        partitions.saveAndFlush(
                new AuthorizationPartition(
                        grantPartition,
                        AuthorizationPartition.Kind.GRANT,
                        "grant:" + grantPartition,
                        0,
                        0,
                        AuthorizationPartition.State.PENDING));
        String grantKey = "grant:" + UUID.randomUUID();
        UUID grantProjection = UUID.randomUUID();
        projections.saveAndFlush(
                new AuthorizationProjection(
                        grantProjection,
                        grantPartition,
                        grantKey,
                        "keycloak-resource",
                        List.of(),
                        List.of(workspacePartition),
                        "e".repeat(64),
                        "e".repeat(64),
                        AuthorizationProjection.State.PENDING));
        // Partition nguồn phải READY (desired = applied = 0, chưa có projection) thì
        // projection phụ thuộc mới được publish READY theo deferred trigger.
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", workspacePartition);
        jdbc.update("UPDATE authz_projection SET state = 'READY' WHERE id = ?", grantProjection);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", grantPartition);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entities.clear();

        AuthorizationOperation operation =
                operationService.applyDesiredState(
                        actor,
                        workspace,
                        "closure-key",
                        "f".repeat(64),
                        List.of(
                                new AuthorizationOperationService.PartitionChange(
                                        workspacePartition,
                                        List.of(
                                                new AuthorizationOperationService.DesiredProjection(
                                                        "workspace:" + workspace,
                                                        "enter",
                                                        List.of("source"),
                                                        List.of())))));
        entities.clear();

        assertEquals(
                List.of(workspacePartition, grantPartition), operation.getAffectedPartitions());
        assertEquals(List.of(1L, 0L), operation.getAffectedRevisions());
        assertEquals(
                AuthorizationProjection.State.PENDING,
                projections.findById(grantProjection).orElseThrow().getState());
        assertEquals(
                AuthorizationPartition.State.PENDING,
                partitions.findById(grantPartition).orElseThrow().getState());
    }

    private UUID grantPartition() {
        UUID id = UUID.randomUUID();
        partitions.saveAndFlush(
                new AuthorizationPartition(
                        id,
                        AuthorizationPartition.Kind.GRANT,
                        "grant:" + id,
                        0,
                        0,
                        AuthorizationPartition.State.PENDING));
        return id;
    }

    private void operationInState(
            UUID actor,
            UUID workspace,
            String key,
            AuthorizationOperation.State state,
            UUID... affected) {
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        actor,
                        workspace,
                        key,
                        "a".repeat(64),
                        List.of(affected),
                        List.of(affected).stream().map(_ -> 1L).toList(),
                        1L);
        operation.setState(state);
        operations.saveAndFlush(operation);
    }

    private UUID finishedRecoveryRequest(UUID requester, UUID target, UUID verifier, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO recovery_request(id,requester_user_id,target_user_id,state,"
                        + "verified_by,resolved_at) VALUES (?,?,?,?,?,?)",
                id,
                requester,
                target,
                state,
                verifier,
                Timestamp.from(Instant.now()));
        return id;
    }

    private static Authentication authenticationFor(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated("sub-" + userId, null, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent claim test timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private UUID user() {
        UUID actor = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,false,'CENTER_ISSUED','PENDING')",
                actor,
                "sub-" + actor,
                "user-" + actor);
        jdbc.update(
                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                UUID.randomUUID(),
                actor);
        return actor;
    }

    private UUID workspaceOf(UUID actor) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ?", UUID.class, actor));
    }

    private UUID partitionByOwnerKey(String ownerKey) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                        UUID.class,
                        ownerKey));
    }
}
