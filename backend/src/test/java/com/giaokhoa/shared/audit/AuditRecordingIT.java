package com.giaokhoa.shared.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.identity.RecoveryService;
import com.giaokhoa.identity.RecoveryService.Decision;
import com.giaokhoa.identity.RecoveryService.RequestView;
import com.giaokhoa.subscriptions.SubscriptionService;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.InvitationService;
import com.giaokhoa.tenancy.InvitationService.InvitationIssued;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng điểm ghi audit dùng chung trên PostgreSQL thật: mỗi biến động thuộc danh mục của
 * data-model (gói, khôi phục, đặc quyền qua chấp nhận lời mời) để lại đúng một dòng {@code
 * audit_event} trong cùng transaction của nghiệp vụ, đủ actor/action/target/operation/outcome, và
 * replay theo idempotency không ghi dòng thứ hai. Các flow qua Keycloak (tạo center, cấp học sinh)
 * được khẳng định audit ngay trong IT riêng của chúng.
 */
@SpringBootTest
class AuditRecordingIT extends IntegrationEnvironment {
    private static final String HASH = "a".repeat(64);

    // Worker nền không thuộc phạm vi: mọi khẳng định đều đọc dòng audit đã commit đồng bộ.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SubscriptionService subscriptions;
    @Autowired RecoveryService recovery;
    @Autowired InvitationService invitations;

    @Test
    void subscriptionConfirmRecordsOneAuditRowAndReplayDoesNotDuplicate() {
        UUID operator = seedAccount();
        UUID workspace = seedCenter(seedAccount());
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String key = "audit-sub-" + UUID.randomUUID();

        AuthorizationOperation operation =
                subscriptions.confirm(
                        operator,
                        workspace,
                        from,
                        from.plus(30, ChronoUnit.DAYS),
                        "audit-ref-" + UUID.randomUUID(),
                        key,
                        HASH);
        UUID period =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM subscription_period WHERE workspace_id = ?",
                                UUID.class,
                                workspace));

        List<Map<String, Object>> rows = auditRows("SUBSCRIPTION_CHANGE", period);
        assertEquals(1, rows.size(), "Xác nhận gói ghi đúng một dòng audit");
        Map<String, Object> row = rows.getFirst();
        assertEquals(operator, row.get("actor_user_id"));
        assertEquals(workspace, row.get("workspace_id"));
        assertEquals("SUBSCRIPTION", row.get("target_type"));
        assertEquals(operation.getId(), row.get("operation_id"));
        assertEquals("PENDING", row.get("outcome"));

        subscriptions.confirm(
                operator,
                workspace,
                from,
                from.plus(30, ChronoUnit.DAYS),
                "audit-ref-khac",
                key,
                HASH);
        assertEquals(
                1,
                auditRows("SUBSCRIPTION_CHANGE", period).size(),
                "Replay cùng key và payload không ghi dòng audit thứ hai");
    }

    @Test
    void recoveryResolveRecordsOneAuditRow() {
        UUID operator = seedAccount();
        UUID target = seedAccount();
        RequestView submitted = recovery.submit(target, target, null, "Mất quyền truy cập email");

        // COMPLETE chỉ hợp lệ sau khi yêu cầu đã VERIFIED: đi đúng hợp đồng hai bước
        // (APPROVE xác minh rồi mới COMPLETE), dòng audit kết thúc là dòng SUCCEEDED.
        recovery.resolve(operator, submitted.id(), Decision.APPROVE, "Đã xác minh danh tính");
        RequestView resolved =
                recovery.resolve(
                        operator, submitted.id(), Decision.COMPLETE, "Đã khôi phục và bàn giao");

        List<Map<String, Object>> rows = auditRows("RECOVERY", target);
        List<Map<String, Object>> succeeded =
                rows.stream().filter(row -> "SUCCEEDED".equals(row.get("outcome"))).toList();
        assertEquals(1, succeeded.size(), "Resolve khôi phục ghi đúng một dòng audit kết thúc");
        Map<String, Object> row = succeeded.getFirst();
        assertEquals(operator, row.get("actor_user_id"));
        assertNull(row.get("workspace_id"));
        assertEquals("ACCOUNT", row.get("target_type"));
        assertNull(row.get("operation_id"));
        assertEquals("SUCCEEDED", row.get("outcome"));
        assertEquals(resolved.targetUserId(), target);
    }

    @Test
    void invitationAcceptRecordsOneAuditRowForTheMembershipChange() {
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID acceptor = seedAccount();

        InvitationIssued issued =
                invitations.invite(
                        owner,
                        workspace,
                        null,
                        acceptor,
                        Set.of(MembershipRole.Role.STUDENT),
                        "audit-invite-" + UUID.randomUUID(),
                        HASH);
        AuthorizationOperation operation =
                invitations.accept(
                        acceptor,
                        Objects.requireNonNull(issued.token()),
                        "audit-accept-" + UUID.randomUUID(),
                        HASH);
        UUID membership =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT id FROM center_membership WHERE workspace_id = ?"
                                        + " AND user_id = ?",
                                UUID.class,
                                workspace,
                                acceptor));

        List<Map<String, Object>> rows = auditRows("PRIVILEGE_CHANGE", membership);
        assertEquals(1, rows.size(), "Chấp nhận lời mời ghi đúng một dòng audit");
        Map<String, Object> row = rows.getFirst();
        assertEquals(acceptor, row.get("actor_user_id"));
        assertEquals(workspace, row.get("workspace_id"));
        assertEquals("MEMBERSHIP", row.get("target_type"));
        assertEquals(operation.getId(), row.get("operation_id"));
        assertEquals("PENDING", row.get("outcome"));
    }

    private List<Map<String, Object>> auditRows(String action, UUID targetId) {
        return jdbc.queryForList(
                "SELECT actor_user_id, workspace_id, target_type, operation_id, outcome"
                        + " FROM audit_event WHERE action = ? AND target_id = ?",
                action,
                targetId);
    }

    private UUID seedAccount() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    "audit-user-" + actor,
                    "audit-user-" + actor,
                    null,
                    false,
                    "SELF_REGISTERED");
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state) VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "audit-org-" + center,
                    "Center audit",
                    ownerUserId);
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

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }
}
