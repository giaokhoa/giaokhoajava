package com.giaokhoa.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.identity.model.RecoveryRequest;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Schema và entity của recovery_request trên schema dùng chung của suite: vòng đời REQUESTED →
 * VERIFIED → RESOLVED/REJECTED được khóa tại DB qua cặp verified_by/resolved_at, và lịch sử xác
 * minh không bị hard-delete hay đổi định danh. Workflow gửi/xử lý yêu cầu thuộc các task sau; class
 * này chỉ khóa record nền.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecoveryRequestIT extends IntegrationEnvironment {

    // Worker không thuộc phạm vi kiểm thử của class này (mọi khẳng định đều qua service đồng
    // bộ): mock để worker nền không tự xử lý operation song song với các lời gọi service
    // trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    @Test
    void entityPersistsAsRequestedAndReloads() {
        UUID requester = seedAccount();
        UUID target = seedAccount();
        UUID id = UUID.randomUUID();

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    entityManager.persist(
                            new RecoveryRequest(id, requester, null, target, "Quên mật khẩu"));
                    entityManager.flush();
                });

        RecoveryRequest reloaded =
                new TransactionTemplate(transactionManager)
                        .execute(_ -> {
                            entityManager.clear();
                            return entityManager.find(RecoveryRequest.class, id);
                        });
        assertNotNull(reloaded);
        assertEquals(requester, reloaded.getRequesterUserId());
        assertNull(reloaded.getWorkspaceId());
        assertEquals(target, reloaded.getTargetUserId());
        assertEquals("Quên mật khẩu", reloaded.getReason());
        assertEquals(RecoveryRequest.State.REQUESTED, reloaded.getState());
        assertNull(reloaded.getVerifiedBy());
        assertNull(reloaded.getResolvedAt());
        assertNull(reloaded.getVerificationNote());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
        assertEquals(0, reloaded.getVersion());
    }

    @Test
    void verifiedAndResolvedRowsAreAccepted() {
        UUID requester = seedAccount();
        UUID target = seedAccount();
        UUID operator = seedAccount();
        UUID workspace = personalWorkspaceOf(requester);

        insertRequest(
                UUID.randomUUID(),
                requester,
                workspace,
                target,
                "VERIFIED",
                operator,
                null,
                "Đã đối chiếu qua kênh hỗ trợ");
        insertRequest(
                UUID.randomUUID(),
                requester,
                null,
                target,
                "RESOLVED",
                operator,
                Instant.now(),
                "Đã đặt credential tạm");

        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM recovery_request WHERE target_user_id = ?",
                        Integer.class,
                        target);
        assertEquals(2, count);
    }

    @Test
    void stateMustMatchVerificationFields() {
        UUID requester = seedAccount();
        UUID target = seedAccount();
        UUID operator = seedAccount();

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                target,
                                "REQUESTED",
                                operator,
                                null,
                                null));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                target,
                                "VERIFIED",
                                null,
                                null,
                                null));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                target,
                                "VERIFIED",
                                operator,
                                Instant.now(),
                                null));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                target,
                                "RESOLVED",
                                operator,
                                null,
                                null));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                target,
                                "PENDING",
                                null,
                                null,
                                null));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        insertRequest(
                                UUID.randomUUID(),
                                requester,
                                null,
                                UUID.randomUUID(),
                                "REQUESTED",
                                null,
                                null,
                                null));
    }

    @Test
    void historyCannotBeDeletedOrReidentifiedButTransitionsStillWork() {
        UUID requester = seedAccount();
        UUID target = seedAccount();
        UUID other = seedAccount();
        UUID operator = seedAccount();
        UUID id = UUID.randomUUID();
        insertRequest(id, requester, null, target, "REQUESTED", null, null, null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM recovery_request WHERE id = ?", id));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "UPDATE recovery_request SET target_user_id = ? WHERE id = ?",
                                other,
                                id));

        jdbc.update(
                "UPDATE recovery_request SET state = 'VERIFIED', verified_by = ?,"
                        + " verification_note = ? WHERE id = ?",
                operator,
                "Đã xác minh danh tính",
                id);
        assertEquals(
                "VERIFIED",
                jdbc.queryForObject(
                        "SELECT state FROM recovery_request WHERE id = ?", String.class, id));
    }

    // Bất biến của directory: user_account chỉ commit khi personal workspace tồn tại trong
    // cùng transaction, nên seed phải chèn cả hai trong một TransactionTemplate.
    private UUID seedAccount() {
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    jdbc.update(
                            "INSERT INTO user_account(id,keycloak_sub,username,email,"
                                    + "email_verified,origin,identity_state)"
                                    + " VALUES (?,?,?,NULL,FALSE,'SELF_REGISTERED','ACTIVE')",
                            user,
                            "recovery-" + user,
                            "recovery-user-" + user);
                    jdbc.update(
                            "INSERT INTO workspace(id,kind,owner_user_id,state)"
                                    + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                            workspace,
                            user);
                });
        return user;
    }

    private UUID personalWorkspaceOf(UUID user) {
        UUID workspace =
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        user);
        assertNotNull(workspace);
        return workspace;
    }

    private void insertRequest(
            UUID id,
            UUID requester,
            @Nullable UUID workspace,
            UUID target,
            String state,
            @Nullable UUID verifiedBy,
            @Nullable Instant resolvedAt,
            @Nullable String note) {
        jdbc.update(
                "INSERT INTO recovery_request(id,requester_user_id,workspace_id,target_user_id,"
                        + "state,verified_by,resolved_at,verification_note)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                requester,
                workspace,
                target,
                state,
                verifiedBy,
                resolvedAt == null ? null : Timestamp.from(resolvedAt),
                note);
    }
}
