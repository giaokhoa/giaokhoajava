package com.giaokhoa.subscriptions;

import com.giaokhoa.subscriptions.service.SubscriptionService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng tầng service của xác nhận kỳ gói thủ công: kỳ và audit chỉ ghi đúng một lần theo
 * idempotency, mốc kết thúc phải sau bắt đầu ở độ chính xác giây của time policy native, và thay
 * đổi hiệu lực được ghi thành desired bundle trên WORKSPACE partition của center. Operation chỉ
 * SUCCEEDED sau khi worker đối chiếu xong graph — trạng thái đó thuộc về gate của stack, test này
 * chỉ khóa các dữ kiện bền đã ghi tại thời điểm xác nhận.
 */
@SpringBootTest
class SubscriptionServiceIT extends IntegrationEnvironment {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    // Mọi khẳng định của class này đều đồng bộ nên worker nền không thuộc phạm vi kiểm
    // thử: mock để nó không tự xử lý operation song song với đường lái trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SubscriptionService service;
    @Autowired CenterPolicyProjector projector;
    @Autowired PersonalEntitlementProjector personalProjector;

    @DisplayName("Các lần confirm đồng thời giữ nguyên toàn bộ desired bundle")
    @Test
    void concurrentConfirmsKeepBundle() throws Exception {
        UUID operator = seedAccount();
        UUID workspace = seedCenter(seedAccount());
        UUID partition = partitionOf(workspace);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        java.util.concurrent.CompletableFuture<AuthorizationOperation> first;
        java.util.concurrent.CompletableFuture<AuthorizationOperation> second;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement =
                    connection.prepareStatement(
                            "SELECT pg_backend_pid() FROM authz_partition WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, partition);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("Partition của fixture không tồn tại");
                    }
                    blockerPid = result.getInt(1);
                }
            }
            first =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                            service.confirm(
                                    operator,
                                    workspace,
                                    from,
                                    from.plus(30, ChronoUnit.DAYS),
                                    "review-ref-a-" + workspace,
                                    "review-key-a-" + workspace,
                                    HASH_A));
            second =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                            service.confirm(
                                    operator,
                                    workspace,
                                    from,
                                    from.plus(60, ChronoUnit.DAYS),
                                    "review-ref-b-" + workspace,
                                    "review-key-b-" + workspace,
                                    HASH_B));
            // Cả hai confirm phải nằm trong chuỗi chờ khóa của đúng connection giữ partition;
            // pg_blocking_pids không phụ thuộc cú pháp SQL hay loại khóa Hibernate sinh ra.
            try {
                org.awaitility.Awaitility.await()
                        .atMost(java.time.Duration.ofSeconds(20))
                        .until(() ->
                                Objects.requireNonNull(
                                                jdbc.queryForObject(
                                                        """
                                                        WITH RECURSIVE blocked(pid) AS (
                                                          VALUES (CAST(? AS integer))
                                                          UNION
                                                          SELECT a.pid FROM pg_stat_activity a
                                                          JOIN blocked b ON b.pid = ANY(pg_blocking_pids(a.pid))
                                                        )
                                                        SELECT count(*) FROM blocked WHERE pid <> ?
                                                        """,
                                                        Integer.class,
                                                        blockerPid,
                                                        blockerPid))
                                        >= 2);
            } catch (org.awaitility.core.ConditionTimeoutException timeout) {
                String waiting =
                        jdbc.queryForList(
                                                "SELECT pid, wait_event, query"
                                                        + " FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                                                        + " AND wait_event_type = 'Lock'")
                                        .toString()
                                + " firstDone="
                                + first.isDone()
                                + " secondDone="
                                + second.isDone();
                throw new AssertionError("Lock observation diagnostics: " + waiting, timeout);
            }
            connection.commit();
        }
        first.get(30, java.util.concurrent.TimeUnit.SECONDS);
        second.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, periodCount(workspace));
        String target = "workspace:" + workspace;
        assertEquals(
                ProjectionReconciler.desiredHash(
                        target, "enter", projector.bundleDescriptors(workspace)),
                jdbc.queryForObject(
                        "SELECT desired_hash FROM authz_projection WHERE target_key = ?",
                        String.class,
                        target),
                "Hai confirmation đã commit phải cho hash chứa cả hai kỳ");
    }

    @DisplayName("Confirm cho center ghi period, audit và desired bundle")
    @Test
    void confirmCenterRecordsBundle() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant until = from.plus(365, ChronoUnit.DAYS);

        AuthorizationOperation operation =
                service.confirm(
                        operator,
                        workspace,
                        from,
                        until,
                        "confirm-ref-1",
                        "sub-key-1-" + UUID.randomUUID(),
                        HASH_A);

        Map<String, Object> period =
                jdbc.queryForMap(
                        "SELECT workspace_id, confirmation_ref, confirmed_by, confirmed_at"
                                + " FROM subscription_period WHERE workspace_id = ?",
                        workspace);
        assertEquals(
                from,
                Objects.requireNonNull(
                                jdbc.queryForObject(
                                        "SELECT valid_from FROM subscription_period WHERE workspace_id = ?",
                                        OffsetDateTime.class,
                                        workspace))
                        .toInstant());
        assertEquals(
                until,
                Objects.requireNonNull(
                                jdbc.queryForObject(
                                        "SELECT expires_at FROM subscription_period WHERE workspace_id = ?",
                                        OffsetDateTime.class,
                                        workspace))
                        .toInstant());
        assertEquals("confirm-ref-1", period.get("confirmation_ref"));
        assertEquals(operator, period.get("confirmed_by"));
        assertNotNull(period.get("confirmed_at"), "Audit phải ghi thời điểm xác nhận");

        UUID partition = partitionOf(workspace);
        List<UUID> affected = uuidArray(operation.getId(), "affected_partitions");
        assertEquals(List.of(partition), affected);
        List<Long> revisions =
                jdbc.queryForObject(
                        "SELECT affected_revisions FROM authorization_operation WHERE id = ?",
                        (rs, _) -> {
                            Array raw = rs.getArray(1);
                            List<Long> out = new ArrayList<>();
                            for (Object item : (Object[]) raw.getArray()) {
                                out.add(((Number) item).longValue());
                            }
                            return out;
                        },
                        operation.getId());
        assertEquals(List.of(1L), revisions);
        String targetKey = "workspace:" + workspace;
        String storedHash =
                jdbc.queryForObject(
                        "SELECT desired_hash FROM authz_projection WHERE target_key = ?",
                        String.class,
                        targetKey);
        assertEquals(
                ProjectionReconciler.desiredHash(
                        targetKey, "enter", projector.bundleDescriptors(workspace)),
                storedHash);
        UUID periodId =
                jdbc.queryForObject(
                        "SELECT id FROM subscription_period WHERE workspace_id = ?",
                        UUID.class,
                        workspace);
        assertTrue(
                projector.bundleDescriptors(workspace).stream()
                        .anyMatch(descriptor ->
                                descriptor.startsWith("CENTER_GROUP_PERIOD")
                                        && descriptor.contains("period=" + periodId)),
                "Bundle phải gate các scope cấp quyền bằng đúng kỳ vừa xác nhận");
    }

    @DisplayName("Replay confirm trả đúng operation cũ mà không nhân đôi")
    @Test
    void confirmReplayReturnsSameOperation() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String key = "sub-key-replay-" + UUID.randomUUID();

        AuthorizationOperation first =
                service.confirm(
                        operator,
                        workspace,
                        from,
                        from.plus(30, ChronoUnit.DAYS),
                        "ref-replay",
                        key,
                        HASH_A);
        AuthorizationOperation replay =
                service.confirm(
                        operator,
                        workspace,
                        from,
                        from.plus(30, ChronoUnit.DAYS),
                        "ref-replay",
                        key,
                        HASH_A);

        assertEquals(first.getId(), replay.getId());
        assertEquals(1, periodCount(workspace));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        partitionOf(workspace)));
    }

    @Test
    void confirmSameKeyDifferentPayloadIsConflict() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String key = "sub-key-conflict-" + UUID.randomUUID();
        service.confirm(
                operator,
                workspace,
                from,
                from.plus(30, ChronoUnit.DAYS),
                "ref-conflict",
                key,
                HASH_A);

        HttpProblemException conflict =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.confirm(
                                        operator,
                                        workspace,
                                        from,
                                        from.plus(60, ChronoUnit.DAYS),
                                        "ref-conflict-2",
                                        key,
                                        HASH_B));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, periodCount(workspace));
    }

    @Test
    void confirmRejectsPeriodWithoutPositiveRange() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        HttpProblemException equal =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.confirm(
                                        operator,
                                        workspace,
                                        from,
                                        from,
                                        "ref-equal",
                                        "sub-key-equal-" + UUID.randomUUID(),
                                        HASH_A));
        assertEquals(HttpStatus.BAD_REQUEST, equal.getStatus());
        assertEquals("INVALID_SUBSCRIPTION_PERIOD", equal.getCode());
        HttpProblemException reversed =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.confirm(
                                        operator,
                                        workspace,
                                        from,
                                        from.minusSeconds(1),
                                        "ref-reversed",
                                        "sub-key-reversed-" + UUID.randomUUID(),
                                        HASH_A));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.getStatus());
        assertEquals(0, periodCount(workspace));
    }

    @Test
    void confirmTruncatesBoundsToWholeSeconds() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(321);

        service.confirm(
                operator,
                workspace,
                from,
                from.plus(10, ChronoUnit.DAYS),
                "ref-truncate",
                "sub-key-truncate-" + UUID.randomUUID(),
                HASH_A);

        OffsetDateTime storedFrom =
                jdbc.queryForObject(
                        "SELECT valid_from FROM subscription_period WHERE workspace_id = ?",
                        OffsetDateTime.class,
                        workspace);
        assertEquals(
                from.truncatedTo(ChronoUnit.SECONDS),
                Objects.requireNonNull(storedFrom).toInstant());
    }

    @Test
    void confirmReusedConfirmationRefIsConflict() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        service.confirm(
                operator,
                workspace,
                from,
                from.plus(30, ChronoUnit.DAYS),
                "ref-shared",
                "sub-key-a-" + UUID.randomUUID(),
                HASH_A);

        HttpProblemException conflict =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.confirm(
                                        operator,
                                        workspace,
                                        from,
                                        from.plus(90, ChronoUnit.DAYS),
                                        "ref-shared",
                                        "sub-key-b-" + UUID.randomUUID(),
                                        HASH_B));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("SUBSCRIPTION_CONFIRMATION_REF_USED", conflict.getCode());
        assertEquals(1, periodCount(workspace));
    }

    @DisplayName("Confirm cho center không khôi phục các group mà thành viên duy nhất đã bị revoke")
    @Test
    void confirmSkipsRevokedOnlyGroups() {
        UUID operator = seedAccount();
        UUID owner = seedAccount();
        UUID workspace = seedCenter(owner);
        UUID revokedUser = seedAccount();
        seedRevokedManagerAndParent(workspace, revokedUser);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        service.confirm(
                operator,
                workspace,
                from,
                from.plus(365, ChronoUnit.DAYS),
                "ref-revoked-member",
                "sub-key-revoked-member-" + UUID.randomUUID(),
                HASH_A);

        List<String> bundle = projector.bundleDescriptors(workspace);
        String managersGroup = NativePolicyTemplates.managersGroup(workspace);
        String parentsGroup =
                NativePolicyTemplates.prerequisiteRoleGroup(
                        workspace, NativePolicyTemplates.PrerequisiteRole.PARENTS);
        String ownersGroup = NativePolicyTemplates.ownersGroup(workspace);
        assertTrue(
                bundle.stream().noneMatch(d -> d.contains("group=" + managersGroup)),
                "Manager duy nhất đã bị thu hồi thì group managers không được có nguồn trong bundle");
        assertTrue(
                bundle.stream().noneMatch(d -> d.contains("group=" + parentsGroup)),
                "Phụ huynh duy nhất đã bị thu hồi thì group parents không được có nguồn trong bundle");
        assertTrue(
                bundle.stream().anyMatch(d -> d.contains("group=" + ownersGroup)),
                "Owner còn ACTIVE thì group owners vẫn phải có nguồn trong bundle");
    }

    @DisplayName("Confirm cho personal workspace ghi period và entitlement bundle")
    @Test
    void confirmPersonalRecordsBundle() {
        UUID operator = seedAccount();
        UUID user = seedAccount();
        UUID personal = personalWorkspaceOf(user);
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        AuthorizationOperation operation =
                service.confirm(
                        operator,
                        personal,
                        from,
                        from.plus(365, ChronoUnit.DAYS),
                        "ref-personal",
                        "sub-key-personal-" + UUID.randomUUID(),
                        HASH_A);

        assertEquals(1, periodCount(personal));
        // Kỳ mới làm descriptor gate của bundle entitlement đổi nên xác nhận ghi PartitionChange
        // thật trên partition của chính workspace: bump revision (seed tạo partition ở revision
        // 0) và lưu desired là bundle entitlement hiện hành, kỳ gói nằm trong tập descriptor.
        UUID partition = partitionOf(personal);
        assertEquals(List.of(partition), uuidArray(operation.getId(), "affected_partitions"));
        assertEquals(List.of(1L), longArray(operation.getId(), "affected_revisions"));
        assertEquals(1L, operation.getDesiredRevision());
        assertEquals(
                1L,
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT desired_revision FROM authz_partition WHERE id = ?",
                                Long.class,
                                partition)));
        assertEquals(
                ProjectionReconciler.desiredHash(
                        "workspace:" + personal,
                        "enter",
                        personalProjector.bundleDescriptors(personal)),
                jdbc.queryForObject(
                        "SELECT desired_hash FROM authz_projection WHERE target_key = ?",
                        String.class,
                        "workspace:" + personal));
    }

    private int periodCount(UUID workspaceId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM subscription_period WHERE workspace_id = ?",
                        Integer.class,
                        workspaceId));
    }

    private UUID partitionOf(UUID workspaceId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE' AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId));
    }

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        userId));
    }

    private List<UUID> uuidArray(UUID operationId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM authorization_operation WHERE id = ?",
                (rs, _) -> {
                    Array raw = rs.getArray(1);
                    if (raw == null) {
                        return List.of();
                    }
                    List<UUID> out = new ArrayList<>();
                    for (Object item : (Object[]) raw.getArray()) {
                        out.add((UUID) item);
                    }
                    return out;
                },
                operationId);
    }

    private List<Long> longArray(UUID operationId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM authorization_operation WHERE id = ?",
                (rs, _) -> {
                    Array raw = rs.getArray(1);
                    if (raw == null) {
                        return List.of();
                    }
                    List<Long> out = new ArrayList<>();
                    for (Object item : (Object[]) raw.getArray()) {
                        out.add(((Number) item).longValue());
                    }
                    return out;
                },
                operationId);
    }

    private UUID seedAccount() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                    actor,
                    "sub-user-" + actor,
                    "sub-user-" + actor,
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
                    "center-org-" + center,
                    "Center test",
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

    private void seedRevokedManagerAndParent(UUID workspaceId, UUID userId) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at,"
                            + "revoked_at) VALUES (?,?,?,'REVOKED',now() - interval '1 day',now())",
                    membership,
                    workspaceId,
                    userId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'MANAGER')",
                    membership);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'PARENT')",
                    membership);
            return null;
        });
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(_ -> action.get());
    }
}
