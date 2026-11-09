package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.*;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.model.AuthorizationProjection;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Slice JPA của Boot 4 trên PostgreSQL thật của stack TEST: replace = NONE giữ nguyên
// datasource đã khai (không thay bằng embedded); Flyway không thuộc slice nên phải import
// tường minh để migration chạy trước khi Hibernate validate schema. Slice không nạp
// worker/scheduler nên không còn actor nào khóa chéo với DROP SCHEMA ở @AfterAll.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class AuthorizationControlSchemaIT extends IntegrationEnvironment {
    private static final String SCHEMA =
            "authorization_control_" + UUID.randomUUID().toString().replace("-", "");

    // Schema cô lập riêng thay vì public dùng chung: dữ kiện của class này không còn lẫn với
    // class khác và không cần ai RESET public sau lượt chạy.
    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;

    @Test
    void accountAndWorkspaceScopesAreAutomaticallyCreatedAndFenced() {
        UUID user = user();
        UUID personal = personal(user);
        assertScope("ACCOUNT", "account:" + user);
        assertScope("WORKSPACE", "workspace:" + personal);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO authz_partition(id,kind,owner_key) VALUES (?, 'ACCOUNT', ?)",
                                UUID.randomUUID(),
                                "account:" + user));
    }

    @Test
    void partitionsCannotClaimReadyWithUnappliedRevision() {
        UUID partition = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authz_partition SET state = 'READY', desired_revision = 1 WHERE id = ?",
                                partition));
    }

    @Test
    void partitionsCannotClaimReadyWhileProjectionIsPending() {
        UUID partition = partition();
        projection(partition, "grant:" + UUID.randomUUID(), new UUID[0], false);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", partition);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void projectionRequiresAppliedHashBeforeReady() {
        UUID partition = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,applied_hash,"
                                        + "keycloak_resource_id,state) VALUES (?,?,?,?,?,'opaque-keycloak-id','READY')",
                                UUID.randomUUID(),
                                partition,
                                "grant:" + UUID.randomUUID(),
                                HASH_A,
                                HASH_B));
    }

    @Test
    void readyProjectionRejectsANullAppliedHash() {
        UUID partition = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,"
                                        + "keycloak_resource_id,state) VALUES (?,?,?,?,'opaque-keycloak-id','READY')",
                                UUID.randomUUID(),
                                partition,
                                "grant:" + UUID.randomUUID(),
                                HASH_A));
    }

    @Test
    void projectionRequiresKeycloakLocatorBeforeReady() {
        UUID partition = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO authz_projection(id,partition_id,target_key,desired_hash,applied_hash,state)"
                                        + " VALUES (?,?,?,?,?,'READY')",
                                UUID.randomUUID(),
                                partition,
                                "grant:" + UUID.randomUUID(),
                                HASH_A,
                                HASH_A));
    }

    @Test
    void projectionCannotBeReadyWhileDependencyPending() {
        UUID parent = partition();
        UUID dependency = partition();
        projection(parent, "grant:" + UUID.randomUUID(), new UUID[] {dependency}, true);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void synchronizedProjectionAndPartitionCanBecomeReady() {
        UUID partition = partition();
        UUID id = projection(partition, "grant:" + UUID.randomUUID(), new UUID[0], true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", partition);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        AuthorizationPartition loaded = entities.find(AuthorizationPartition.class, partition);
        AuthorizationProjection projected = entities.find(AuthorizationProjection.class, id);
        assertEquals(AuthorizationPartition.State.READY, loaded.getState());
        assertEquals(AuthorizationProjection.State.READY, projected.getState());
        assertArrayEquals(new String[] {"scope.permission"}, projected.getPolicyNames());
        assertArrayEquals(new UUID[0], projected.getDependencyPartitionIds());
    }

    @Test
    void changingSourceRequiresFencingDependentProjection() {
        UUID source = partition();
        UUID owner = partition();
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", source);
        projection(owner, "grant:" + UUID.randomUUID(), new UUID[] {source}, true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", owner);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                                source));
    }

    @Test
    void sourceRevisionCanAdvanceWhenDependentsAreFenced() {
        UUID source = partition();
        UUID owner = partition();
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", source);
        UUID projection =
                projection(owner, "grant:" + UUID.randomUUID(), new UUID[] {source}, true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", owner);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL DEFERRED");

        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                owner);
        jdbc.update("UPDATE authz_projection SET state = 'PENDING' WHERE id = ?", projection);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                source);
        assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void desiredProjectionCannotChangeBehindReadyFence() {
        UUID partition = partition();
        UUID id = projection(partition, "grant:" + UUID.randomUUID(), new UUID[0], true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", partition);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authz_projection SET desired_hash = ? WHERE id = ?",
                                HASH_B,
                                id));
    }

    @Test
    void operationIsDurableQueueAndJpaCanReadUuidArrays() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        UUID id = operation(initiator, workspace, "request-1", HASH_A, partition);
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM authorization_operation WHERE id = ?",
                        String.class,
                        id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT attempt_count FROM authorization_operation WHERE id = ?",
                        Integer.class,
                        id));
        jdbc.update(
                "UPDATE authorization_operation SET state = 'APPLYING', attempt_count = 1 WHERE id = ?",
                id);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'RETRYABLE_FAILURE', "
                        + "last_error_code = 'KEYCLOAK_UNAVAILABLE', next_attempt_at = now() WHERE id = ?",
                id);
        AuthorizationOperation entity = entities.find(AuthorizationOperation.class, id);
        assertEquals(AuthorizationOperation.State.RETRYABLE_FAILURE, entity.getState());
        assertArrayEquals(new UUID[] {partition}, entity.getAffectedPartitions());
        assertEquals(1, entity.getAttemptCount());
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedForTheSameActor() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        operation(initiator, workspace, "same-key", HASH_A, partition);
        assertThrows(
                DataAccessException.class,
                () -> operation(initiator, workspace, "same-key", HASH_B, partition));
    }

    @Test
    void operationRejectsUnknownAffectedPartitions() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        assertThrows(
                DataAccessException.class,
                () ->
                        operation(
                                initiator,
                                workspace,
                                "invalid-refs",
                                HASH_A,
                                partition,
                                UUID.randomUUID()));
    }

    @Test
    void operationRejectsDuplicateAffectedPartitions() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        assertThrows(
                DataAccessException.class,
                () ->
                        operation(
                                initiator,
                                workspace,
                                "duplicate-refs",
                                HASH_A,
                                partition,
                                partition));
    }

    @Test
    void projectionRejectsUnknownDependency() {
        UUID owner = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        projection(
                                owner,
                                "grant:" + UUID.randomUUID(),
                                new UUID[] {UUID.randomUUID()},
                                false));
    }

    @Test
    void revisionsCannotRollBackToAnOlderAppliedState() {
        UUID partition = partition();
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 2, applied_revision = 1 WHERE id = ?",
                partition);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1, state = 'READY'"
                                        + " WHERE id = ?",
                                partition));
    }

    @Test
    void idempotencyPayloadIsImmutableDuringRetries() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        UUID id = operation(initiator, workspace, "fixed-key", HASH_A, partition);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authorization_operation SET payload_hash = ? WHERE id = ?",
                                HASH_B,
                                id));
    }

    @Test
    void operationCannotSucceedBeforePartitionRevisionIsReady() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        UUID id = operation(initiator, workspace, "pending-key", HASH_A, partition);
        jdbc.update("UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", id);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void operationSucceedsAfterSameRevisionIsApplied() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        UUID id = operation(initiator, workspace, "completed-key", HASH_A, partition);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1, "
                        + "state = 'READY' WHERE id = ?",
                partition);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'APPLYING', attempt_count = 1 WHERE id = ?",
                id);
        jdbc.update("UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", id);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT state FROM authorization_operation WHERE id = ?",
                        String.class,
                        id));
    }

    @Test
    void readyProjectionCannotChangeKeycloakLocatorWithoutFence() {
        UUID owner = partition();
        UUID projection = projection(owner, "grant:" + UUID.randomUUID(), new UUID[0], true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", owner);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authz_projection SET keycloak_resource_id = 'locator-changed' WHERE id = ?",
                                projection));
    }

    @Test
    void operationCanSucceedWithIndependentPartitionRevisions() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID first = partition();
        UUID second = partition();
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1,"
                        + " state = 'READY' WHERE id = ?",
                first);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 2, applied_revision = 2,"
                        + " state = 'READY' WHERE id = ?",
                second);
        UUID operation =
                operationWithRevisions(
                        initiator,
                        workspace,
                        "mixed-revisions",
                        HASH_A,
                        new UUID[] {first, second},
                        1L,
                        2L);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", operation);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT state FROM authorization_operation WHERE id = ?",
                        String.class,
                        operation));
        assertArrayEquals(
                new Long[] {1L, 2L},
                entities.find(AuthorizationOperation.class, operation).getAffectedRevisions());
    }

    @Test
    void succeededOperationCannotReturnToPending() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID owner = scope("WORKSPACE", "workspace:" + workspace);
        UUID operation = operation(initiator, workspace, "final-key", HASH_A, owner);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1,"
                        + " state = 'READY' WHERE id = ?",
                owner);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", operation);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authorization_operation SET state = 'PENDING' WHERE id = ?",
                                operation));
    }

    @Test
    void keycloakLocatorCanChangeWhilePartitionIsFenced() {
        UUID owner = partition();
        UUID projection = projection(owner, "grant:" + UUID.randomUUID(), new UUID[0], true);
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", owner);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                owner);
        jdbc.update(
                "UPDATE authz_projection SET state = 'PENDING',"
                        + " keycloak_resource_id = 'reconciled-locator' WHERE id = ?",
                projection);
        assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void operationCannotSucceedWithOneStaleAffectedPartition() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID first = partition();
        UUID second = partition();
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1,"
                        + " state = 'READY' WHERE id = ?",
                first);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 2, applied_revision = 2,"
                        + " state = 'READY' WHERE id = ?",
                second);
        UUID operation =
                operationWithRevisions(
                        initiator,
                        workspace,
                        "stale-dependency",
                        HASH_A,
                        new UUID[] {first, second},
                        1L,
                        3L);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", operation);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @Test
    void failedOperationCannotRestartSilently() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID owner = scope("WORKSPACE", "workspace:" + workspace);
        UUID operation = operation(initiator, workspace, "failed-key", HASH_A, owner);
        jdbc.update("UPDATE authorization_operation SET state = 'FAILED' WHERE id = ?", operation);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authorization_operation SET state = 'PENDING' WHERE id = ?",
                                operation));
    }

    @Test
    void operationRejectsRevisionVectorWithWrongLength() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID first = partition();
        UUID second = partition();
        assertThrows(
                DataAccessException.class,
                () ->
                        operationWithRevisions(
                                initiator,
                                workspace,
                                "bad-vector",
                                HASH_A,
                                new UUID[] {first, second},
                                1L));
    }

    @Test
    void operationRevisionSnapshotCannotBeRewritten() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID partition = scope("WORKSPACE", "workspace:" + workspace);
        UUID operation = operation(initiator, workspace, "snapshot-key", HASH_A, partition);
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                "UPDATE authorization_operation SET affected_revisions = ARRAY[2]::bigint[],"
                                        + " desired_revision = 2 WHERE id = ?",
                                operation));
    }

    @Test
    void operationRejectsZeroBasedRevisionArrayEvenWhenPartitionsAreReady() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID owner = scope("WORKSPACE", "workspace:" + workspace);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, applied_revision = 1,"
                        + " state = 'READY' WHERE id = ?",
                owner);
        assertThrows(
                DataAccessException.class,
                () -> {
                    insertRawOperation(initiator, workspace, "{" + owner + "}", "[0:0]={1}", 999);
                    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                });
    }

    @Test
    void operationRejectsZeroBasedPartitionArray() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID owner = scope("WORKSPACE", "workspace:" + workspace);
        assertThrows(
                DataAccessException.class,
                () -> insertRawOperation(initiator, workspace, "[0:0]={" + owner + "}", "{1}", 1));
    }

    @Test
    void operationRejectsMultidimensionalRevisionArray() {
        UUID initiator = user();
        UUID workspace = personal(initiator);
        UUID owner = scope("WORKSPACE", "workspace:" + workspace);
        assertThrows(
                DataAccessException.class,
                () -> insertRawOperation(initiator, workspace, "{" + owner + "}", "{{1}}", 999));
    }

    private void insertRawOperation(
            UUID initiator,
            UUID workspace,
            String partitions,
            String revisions,
            long desiredRevision) {
        jdbc.update(
                "INSERT INTO authorization_operation(id,initiator_user_id,workspace_id,"
                        + "idempotency_key,payload_hash,affected_partitions,affected_revisions,desired_revision)"
                        + " VALUES (?,?,?,?,?,?::uuid[],?::bigint[],?)",
                UUID.randomUUID(),
                initiator,
                workspace,
                UUID.randomUUID().toString(),
                HASH_A,
                partitions,
                revisions,
                desiredRevision);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,false,'SELF_REGISTERED','PENDING')",
                id,
                "sub-" + id,
                "user-" + id);
        jdbc.update(
                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                UUID.randomUUID(),
                id);
        return id;
    }

    private UUID personal(UUID user) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ?", UUID.class, user));
    }

    private UUID partition() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO authz_partition(id,kind,owner_key) VALUES (?, 'GRANT', ?)",
                id,
                "grant:" + UUID.randomUUID());
        return id;
    }

    private UUID scope(String kind, String key) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE kind = ? AND owner_key = ?",
                        UUID.class,
                        kind,
                        key));
    }

    private void assertScope(String kind, String key) {
        UUID id = scope(kind, key);
        assertNotNull(id);
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?", String.class, id));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        id));
    }

    private UUID projection(UUID partition, String target, UUID[] dependencies, boolean ready) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO authz_projection(id,partition_id,target_key,keycloak_resource_id,"
                        + "policy_names,dependency_partition_ids,desired_hash,applied_hash,state)"
                        + " VALUES (?,?,?,'opaque-keycloak-id',ARRAY['scope.permission'],?::uuid[],?,?,?)",
                id,
                partition,
                target,
                uuidArray(dependencies),
                HASH_A,
                ready ? HASH_A : null,
                ready ? "READY" : "PENDING");
        return id;
    }

    private UUID operation(
            UUID initiator, UUID workspace, String key, String hash, UUID... partitions) {
        Long[] revisions = new Long[partitions.length];
        Arrays.fill(revisions, 1L);
        return operationWithRevisions(initiator, workspace, key, hash, partitions, revisions);
    }

    private UUID operationWithRevisions(
            UUID initiator,
            UUID workspace,
            String key,
            String hash,
            UUID[] partitions,
            Long... revisions) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO authorization_operation(id,initiator_user_id,workspace_id,"
                        + "idempotency_key,payload_hash,affected_partitions,affected_revisions,desired_revision)"
                        + " VALUES (?,?,?,?,?,?::uuid[],?::bigint[],?)",
                id,
                initiator,
                workspace,
                key,
                hash,
                uuidArray(partitions),
                longArray(revisions),
                revisions.length == 0 ? Long.valueOf(0L) : revisions[0]);
        return id;
    }

    private static String longArray(Long... revisions) {
        return Arrays.stream(revisions)
                .map(String::valueOf)
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String uuidArray(UUID... ids) {
        return Arrays.stream(ids)
                .map(id -> Objects.requireNonNull(id).toString())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
