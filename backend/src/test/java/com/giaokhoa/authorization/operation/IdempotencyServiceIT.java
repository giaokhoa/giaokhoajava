package com.giaokhoa.authorization.operation;

import static org.junit.jupiter.api.Assertions.*;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Transactional
class IdempotencyServiceIT extends IntegrationEnvironment {

    // Class này có khẳng định đếm khóa trên toàn bảng: tính cô lập dữ kiện dựa vào việc
    // schema dùng chung được xoá sạch trước mỗi class (xem IntegrationEnvironment).

    // Mọi khẳng định của class này đều đồng bộ nên worker nền không thuộc phạm vi kiểm
    // thử: mock để nó không tự xử lý operation song song với đường lái trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired IdempotencyService idempotency;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;

    @DisplayName(
            "Cùng actor và cùng key tái sử dụng operation PENDING mà không nhân đôi mục trong hàng đợi")
    @Test
    void sameKeyReusesPendingOperation() {
        UUID actor = user();
        UUID partition = workspacePartition(actor);
        var first = operation(actor, partition, "key-one", "a".repeat(64));
        var same = operation(actor, partition, "key-one", "a".repeat(64));

        AtomicInteger mutations = new AtomicInteger();
        assertEquals(
                first.getId(), idempotency.executeOnce(first, mutations::incrementAndGet).getId());
        var replay = idempotency.executeOnce(same, mutations::incrementAndGet);
        assertEquals(1, mutations.get());
        assertEquals(first.getId(), replay.getId());
        assertEquals(HttpStatus.ACCEPTED, idempotency.responseFor(replay).getStatusCode());
        assertFalse(Objects.requireNonNull(idempotency.responseFor(replay).getBody()).completed());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE initiator_user_id=?",
                        Integer.class,
                        actor));
        var conflict =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                idempotency.executeOnce(
                                        operation(actor, partition, "key-one", "b".repeat(64)),
                                        () -> fail("No mutation on conflict")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    @Test
    void completedResponseRequiresSucceededState() {
        UUID actor = user();
        UUID partition = workspacePartition(actor);
        var operation = operation(actor, partition, "key-complete", "a".repeat(64));
        idempotency.executeOnce(operation, () -> {});

        jdbc.update("UPDATE authz_partition SET state='READY' WHERE id=?", partition);
        jdbc.update(
                "UPDATE authorization_operation SET state='SUCCEEDED' WHERE id=?",
                operation.getId());
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entities.clear();

        var completed =
                idempotency.findExisting(actor, "key-complete", "a".repeat(64)).orElseThrow();
        assertEquals(HttpStatus.OK, idempotency.responseFor(completed).getStatusCode());
        assertTrue(
                Objects.requireNonNull(idempotency.responseFor(completed).getBody()).completed());
    }

    @Test
    void sameKeyCanBeUsedByDifferentActors() {
        UUID first = user();
        UUID second = user();
        idempotency.executeOnce(
                operation(first, workspacePartition(first), "key-two", "a".repeat(64)), () -> {});
        idempotency.executeOnce(
                operation(second, workspacePartition(second), "key-two", "b".repeat(64)), () -> {});
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE idempotency_key='key-two'",
                        Integer.class));
    }

    @DisplayName("Hai retry đồng thời cùng thấy key chưa có nhưng chỉ một lần áp dụng mutation")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRetriesApplyOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID actor = tx.execute(_ -> user());
        UUID partition = workspacePartition(actor);
        UUID accountPartition =
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                        UUID.class,
                        "account:" + actor);
        UUID workspace =
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ?", UUID.class, actor);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<UUID> attempt =
                () ->
                        tx.execute(_ -> {
                            assertTrue(
                                    idempotency
                                            .findExisting(actor, "concurrent-key", "a".repeat(64))
                                            .isEmpty());
                            ready.countDown();
                            await(start);
                            boolean reversed = attempts.getAndIncrement() != 0;
                            var candidate =
                                    new AuthorizationOperation(
                                            UUID.randomUUID(),
                                            actor,
                                            workspace,
                                            "concurrent-key",
                                            "a".repeat(64),
                                            reversed
                                                    ? List.of(accountPartition, partition)
                                                    : List.of(partition, accountPartition),
                                            reversed ? List.of(0L, 1L) : List.of(1L, 0L),
                                            reversed ? 0L : 1L);
                            return idempotency
                                    .executeOnce(
                                            candidate,
                                            () ->
                                                    jdbc.update(
                                                            "UPDATE authz_partition SET desired_revision = desired_revision + 1, state = 'PENDING'"
                                                                    + " WHERE id = ?",
                                                            partition))
                                    .getId();
                        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT desired_revision FROM authz_partition WHERE id = ?",
                            Long.class,
                            partition));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                            Integer.class,
                            actor));
        } finally {
            start.countDown();
        }
    }

    @DisplayName("Mutation thất bại rollback cả operation lẫn desired state cùng nhau")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failingMutationRollsBackTogether() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID actor = tx.execute(_ -> user());
        UUID partition = workspacePartition(actor);
        UUID workspace =
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ?", UUID.class, actor);
        var operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        actor,
                        workspace,
                        "callback-failure",
                        "a".repeat(64),
                        List.of(partition),
                        List.of(1L),
                        1L);

        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(_ ->
                                idempotency.executeOnce(
                                        operation,
                                        () -> {
                                            jdbc.update(
                                                    "UPDATE authz_partition SET desired_revision = 1 WHERE id = ?",
                                                    partition);
                                            throw new IllegalStateException(
                                                    "Simulated mutation failure");
                                        })));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM authorization_operation WHERE initiator_user_id = ?",
                        Integer.class,
                        actor));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        partition));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent mutation test timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private AuthorizationOperation operation(UUID actor, UUID partition, String key, String hash) {
        return new AuthorizationOperation(
                UUID.randomUUID(),
                actor,
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id=?", UUID.class, actor),
                key,
                hash,
                List.of(partition),
                List.of(0L),
                0L);
    }

    private UUID workspacePartition(UUID actor) {
        UUID workspace =
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id=?", UUID.class, actor);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key=?",
                        UUID.class,
                        "workspace:" + workspace));
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
}
