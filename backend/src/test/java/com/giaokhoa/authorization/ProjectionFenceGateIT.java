package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.*;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.IntegrationEnvironment;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các cổng thu hồi ở control-plane trên PostgreSQL thật. Chừng nào tích hợp request-fence và PDP
 * chưa được cài đặt, một dòng READY đơn thuần không bao giờ được coi là một quyết định
 * authorization.
 */
@SpringBootTest
@Transactional
class ProjectionFenceGateIT extends IntegrationEnvironment {

    // Schema cô lập riêng thay vì public dùng chung: dữ kiện của class này không còn lẫn với
    // class khác và không cần ai RESET public sau lượt chạy.

    // Mọi khẳng định của class này đều đồng bộ (trạng thái operation được lái bằng JDBC
    // trực tiếp) nên worker nền không thuộc phạm vi kiểm thử: mock để nó không tự xử lý
    // operation song song với đường lái trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    private static final String HASH = "a".repeat(64);

    private enum Scope {
        ACCOUNT,
        WORKSPACE,
        GRANT
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyService idempotency;

    @DisplayName(
            "Dependency còn PENDING chặn operation hoàn tất ngay cả khi các scope khác đã READY")
    @ParameterizedTest
    @EnumSource(Scope.class)
    void pendingDependencyBlocksCompletion(Scope pending) {
        Fixture fixture = fixture();
        ready(fixture.account());
        ready(fixture.workspacePartition());
        ready(fixture.grant());

        UUID operation = operation(fixture, 0);
        UUID changed = fixture.partition(pending);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                changed);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'SUCCEEDED' WHERE id = ?", operation);

        // DB từ chối mọi thành công giả, gồm cả dependency GRANT và ACCOUNT.
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @DisplayName("Thất bại và retry giữ operation chưa hoàn tất và revision vẫn bị fence")
    @Test
    void failureAndRetryKeepRevisionFenced() {
        Fixture fixture = fixture();
        ready(fixture.account());
        ready(fixture.grant());
        UUID operation = operation(fixture, 1);
        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                fixture.workspacePartition());

        jdbc.update(
                "UPDATE authorization_operation SET state = 'APPLYING', attempt_count = 1 WHERE id = ?",
                operation);
        jdbc.update(
                "UPDATE authorization_operation SET state = 'RETRYABLE_FAILURE',"
                        + " last_error_code = 'KEYCLOAK_UNAVAILABLE', next_attempt_at = now()"
                        + " WHERE id = ?",
                operation);
        var failed =
                idempotency.findExisting(fixture.actor(), "revocation-revoke", HASH).orElseThrow();
        assertEquals(HttpStatus.ACCEPTED, idempotency.responseFor(failed).getStatusCode());
        assertFalse(Objects.requireNonNull(idempotency.responseFor(failed).getBody()).completed());
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?",
                        String.class,
                        fixture.workspacePartition()));

        // Retry không thay đổi idempotency payload và không giả định Keycloak đã thành công.
        jdbc.update(
                "UPDATE authorization_operation SET state = 'APPLYING', attempt_count = 2,"
                        + " next_attempt_at = NULL WHERE id = ?",
                operation);
        assertEquals(
                HASH,
                jdbc.queryForObject(
                        "SELECT payload_hash FROM authorization_operation WHERE id = ?",
                        String.class,
                        operation));
        jdbc.update(
                "UPDATE authorization_operation SET state = 'SUCCEEDED', last_error_code = NULL"
                        + " WHERE id = ?",
                operation);
        assertThrows(
                DataAccessException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    }

    @DisplayName("Cùng một actor vẫn giữ workspace độc lập khi một workspace khác của họ bị revoke")
    @Test
    void actorKeepsIndependentWorkspace() {
        Fixture a = fixture();
        UUID workspaceB = centerWorkspace(a.actor());
        UUID partitionB =
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceB);
        ready(a.account());
        ready(a.workspacePartition());
        ready(Objects.requireNonNull(partitionB, "workspace partition"));

        jdbc.update(
                "UPDATE authz_partition SET desired_revision = 1, state = 'PENDING' WHERE id = ?",
                a.workspacePartition());

        assertEquals(
                "READY",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?",
                        String.class,
                        partitionB));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        partitionB));
        assertEquals(
                "READY",
                jdbc.queryForObject(
                        "SELECT state FROM authz_partition WHERE id = ?",
                        String.class,
                        a.account()));
    }

    @DisplayName(
            "Cùng một access token thấy hiệu lực native revoke mà không mất grant độc lập khác")
    @Test
    void tokenSeesRevokeKeepsOtherGrant() {
        try (var nativeGate = new NativeRevocationFixture()) {
            String token = nativeGate.userToken();
            assertFalse(nativeGate.containsGroupClaim(token));
            assertTrue(nativeGate.allowed(token, "a"));
            assertTrue(nativeGate.allowed(token, "b"));

            nativeGate.revoke("a");
            assertFalse(
                    nativeGate.allowed(token, "a"), "Revoke must invalidate the old token's allow");
            assertTrue(nativeGate.allowed(token, "b"), "Unrelated source must keep working");
        }
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,origin,identity_state)"
                        + " VALUES (?,?,?,?,true,'SELF_REGISTERED','ACTIVE')",
                actor,
                "revocation-sub-" + actor,
                "revocation-user-" + actor,
                "revocation-" + actor + "@example.invalid");
        jdbc.update(
                "INSERT INTO workspace(id,kind,owner_user_id,state) VALUES (?,'PERSONAL',?,'ACTIVE')",
                workspace,
                actor);
        jdbc.update(
                "INSERT INTO authz_partition(id,kind,owner_key) VALUES (?,'GRANT',?)",
                grant,
                "grant:" + grant);
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
        return new Fixture(
                actor,
                workspace,
                Objects.requireNonNull(account, "account partition"),
                Objects.requireNonNull(workspacePartition, "workspace partition"),
                grant);
    }

    private UUID centerWorkspace(UUID owner) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace(id,kind,center_id,state) VALUES (?,'CENTER',?,'ACTIVE')",
                workspace,
                center);
        jdbc.update(
                "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,owner_user_id,state)"
                        + " VALUES (?,?,?,'Revocation test center',?,'ACTIVE')",
                center,
                workspace,
                "revocation-org-" + center,
                owner);
        jdbc.update(
                "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                        + " VALUES (?,?,?,'ACTIVE',now())",
                membership,
                workspace,
                owner);
        jdbc.update(
                "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')", membership);
        return workspace;
    }

    private void ready(UUID partition) {
        jdbc.update("UPDATE authz_partition SET state = 'READY' WHERE id = ?", partition);
    }

    private UUID operation(Fixture fixture, long revision) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO authorization_operation(id,initiator_user_id,workspace_id,"
                        + "idempotency_key,payload_hash,affected_partitions,affected_revisions,desired_revision)"
                        + " VALUES (?,?,?,'revocation-revoke',?,ARRAY[?::uuid,?::uuid,?::uuid],"
                        + " ARRAY[?::bigint,?::bigint,?::bigint],?)",
                id,
                fixture.actor(),
                fixture.workspace(),
                HASH,
                fixture.account(),
                fixture.workspacePartition(),
                fixture.grant(),
                0L,
                revision,
                0L,
                0L);
        return id;
    }

    private record Fixture(
            UUID actor, UUID workspace, UUID account, UUID workspacePartition, UUID grant) {
        UUID partition(Scope scope) {
            return switch (scope) {
                case ACCOUNT -> account;
                case WORKSPACE -> workspacePartition;
                case GRANT -> grant;
            };
        }
    }
}
