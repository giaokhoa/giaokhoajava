package com.giaokhoa.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.IntegrationEnvironment;
import com.giaokhoa.verification.model.SystemPracticeAttemptSample;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PSQLException;
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
 * Lịch sử practice trên schema dùng chung của suite: trigger chỉ cho practice version đã khoá của
 * resource hệ thống vào đúng personal workspace của người làm, và lịch sử không bị hard-delete hay
 * đổi định danh.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SystemPracticeHistoryIT extends IntegrationEnvironment {
    // Worker không thuộc phạm vi kiểm thử của class này (mọi khẳng định đều qua transaction
    // đồng bộ): mock để worker nền không tự xử lý operation song song với các transaction
    // trực tiếp của class.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @Test
    void entityRoundTripPersistsAttempt() {
        UUID userId = userWithPersonalWorkspace();
        UUID workspaceId = personalWorkspaceOf(userId);
        PublishedVersion published = publishedVersion();
        UUID id = UUID.randomUUID();
        // TIMESTAMPTZ chỉ giữ tới micro giây nên chuẩn hoá ngay từ đầu để so sánh chính xác.
        Instant attemptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ ->
                        entityManager.persist(
                                new SystemPracticeAttemptSample(
                                        id,
                                        workspaceId,
                                        userId,
                                        published.resourceId(),
                                        published.versionId(),
                                        attemptedAt,
                                        "Bài làm mẫu",
                                        null)));

        SystemPracticeAttemptSample reloaded =
                new TransactionTemplate(transactionManager)
                        .execute(_ -> {
                            entityManager.clear();
                            return entityManager.find(SystemPracticeAttemptSample.class, id);
                        });
        if (reloaded == null) {
            throw new AssertionError("Không tìm thấy practice attempt vừa lưu");
        }
        assertThat(reloaded.getPersonalWorkspaceId()).isEqualTo(workspaceId);
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getSystemResourceId()).isEqualTo(published.resourceId());
        assertThat(reloaded.getVersionId()).isEqualTo(published.versionId());
        assertThat(reloaded.getAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(reloaded.getContentStub()).isEqualTo("Bài làm mẫu");
        assertThat(reloaded.getOutcomeStub()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void onlyLockedVersionCanBePracticed() {
        UUID userId = userWithPersonalWorkspace();
        UUID workspaceId = personalWorkspaceOf(userId);
        UUID resourceId = systemResource();
        UUID draft = version(resourceId, "DRAFT", null);
        UUID approved = version(resourceId, "APPROVED", null);

        assertThatThrownBy(() -> insertAttempt(workspaceId, userId, resourceId, draft))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
        assertThatThrownBy(() -> insertAttempt(workspaceId, userId, resourceId, approved))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
        insertAttempt(
                workspaceId, userId, resourceId, version(resourceId, "LOCKED", Instant.now()));
        assertThat(countAsOwner(workspaceId)).isEqualTo(1);
    }

    @Test
    void rejectsResourceOutsideSystemWorkspace() {
        UUID userId = userWithPersonalWorkspace();
        UUID workspaceId = personalWorkspaceOf(userId);
        UUID ownResourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource"
                        + " (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)"
                        + " VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                ownResourceId,
                workspaceId,
                userId,
                UUID.randomUUID());
        UUID locked = version(ownResourceId, "LOCKED", Instant.now());

        assertThatThrownBy(() -> insertAttempt(workspaceId, userId, ownResourceId, locked))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
    }

    @Test
    void rejectsVersionOfAnotherResource() {
        UUID userId = userWithPersonalWorkspace();
        UUID workspaceId = personalWorkspaceOf(userId);
        PublishedVersion published = publishedVersion();
        PublishedVersion other = publishedVersion();

        assertThatThrownBy(() ->
                        insertAttempt(
                                workspaceId, userId, published.resourceId(), other.versionId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
    }

    @DisplayName("Từ chối attempt vào personal workspace của người dùng khác")
    @Test
    void rejectsAttemptIntoForeignPersonal() {
        UUID userId = userWithPersonalWorkspace();
        UUID otherUser = userWithPersonalWorkspace();
        UUID otherWorkspace = personalWorkspaceOf(otherUser);
        PublishedVersion published = publishedVersion();

        assertThatThrownBy(() -> insertAttempt(otherWorkspace, userId, published))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
        assertThatThrownBy(() -> insertAttempt(published.systemWorkspaceId(), userId, published))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> assertRejectedByTrigger(failure, "validate_practice_attempt"));
    }

    @Test
    void historyIsImmutableExceptOutcome() {
        UUID userId = userWithPersonalWorkspace();
        UUID workspaceId = personalWorkspaceOf(userId);
        PublishedVersion published = publishedVersion();
        UUID id = insertAttempt(workspaceId, userId, published);

        jdbc.update(
                "UPDATE system_practice_attempt_sample SET outcome_stub = 'Đạt' WHERE id = ?", id);
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE system_practice_attempt_sample SET user_id = ?"
                                        + " WHERE id = ?",
                                userWithPersonalWorkspace(),
                                id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertRejectedByTrigger(failure, "guard_practice_history"));
        assertThatThrownBy(() ->
                        jdbc.update("DELETE FROM system_practice_attempt_sample WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertRejectedByTrigger(failure, "guard_practice_history"));
        assertThat(countAsOwner(workspaceId)).isEqualTo(1);
    }

    /**
     * Khẳng định lệnh ghi bị DB chặn bởi đúng hàm trigger với SQLState 23514 (check_violation):
     * danh tính của lỗi thay cho câu chữ trong thông điệp RAISE, để đổi lời văn của trigger không
     * làm đỏ test, còn đổi cơ chế chặn thì có.
     */
    private static void assertRejectedByTrigger(Throwable failure, String triggerFunction) {
        Throwable cursor = failure;
        while (cursor != null && !(cursor instanceof PSQLException)) {
            cursor = cursor.getCause();
        }
        assertThat(cursor)
                .as("chuỗi cause phải chứa lỗi PostgreSQL do trigger %s ném", triggerFunction)
                .isNotNull();
        PSQLException pgFailure = Objects.requireNonNull((PSQLException) cursor);
        assertThat(pgFailure.getSQLState()).isEqualTo("23514");
        assertThat(Objects.requireNonNull(pgFailure.getServerErrorMessage()).getWhere())
                .contains("PL/pgSQL function " + triggerFunction + "()");
    }

    private record PublishedVersion(UUID systemWorkspaceId, UUID resourceId, UUID versionId) {}

    private PublishedVersion publishedVersion() {
        UUID systemWorkspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace (id, kind, state) VALUES (?, 'SYSTEM', 'ACTIVE')",
                systemWorkspaceId);
        UUID resourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource"
                        + " (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)"
                        + " VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                resourceId,
                systemWorkspaceId,
                userWithPersonalWorkspace(),
                UUID.randomUUID());
        return new PublishedVersion(
                systemWorkspaceId, resourceId, version(resourceId, "LOCKED", Instant.now()));
    }

    private UUID systemResource() {
        UUID systemWorkspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspace (id, kind, state) VALUES (?, 'SYSTEM', 'ACTIVE')",
                systemWorkspaceId);
        UUID resourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource"
                        + " (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)"
                        + " VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                resourceId,
                systemWorkspaceId,
                userWithPersonalWorkspace(),
                UUID.randomUUID());
        return resourceId;
    }

    private UUID version(UUID resourceId, String reviewState, @Nullable Instant lockedAt) {
        UUID id = UUID.randomUUID();
        Integer existing =
                jdbc.queryForObject(
                        "SELECT count(*) FROM resource_version WHERE resource_id = ?",
                        Integer.class,
                        resourceId);
        int versionNo = (existing == null ? 0 : existing) + 1;
        jdbc.update(
                "INSERT INTO resource_version"
                        + " (id, resource_id, version_no, label, body_stub, answer_stub,"
                        + " review_state, locked_at)"
                        + " VALUES (?, ?, ?, ?, 'Nội dung', 'Đáp án', ?, ?)",
                id,
                resourceId,
                versionNo,
                "Bản " + versionNo,
                reviewState,
                lockedAt == null ? null : Timestamp.from(lockedAt));
        return id;
    }

    private UUID userWithPersonalWorkspace() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(_ -> {
                    jdbc.update(
                            "INSERT INTO user_account"
                                    + " (id, keycloak_sub, username, email_verified,"
                                    + " origin, identity_state)"
                                    + " VALUES (?, ?, ?, false,"
                                    + " 'SELF_REGISTERED', 'ACTIVE')",
                            userId,
                            "sub-" + userId,
                            "user-" + userId);
                    jdbc.update(
                            "INSERT INTO workspace (id, kind, owner_user_id, state)"
                                    + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                            workspaceId,
                            userId);
                });
        return userId;
    }

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId));
    }

    private UUID insertAttempt(UUID workspaceId, UUID userId, PublishedVersion published) {
        return insertAttempt(
                jdbc, workspaceId, userId, published.resourceId(), published.versionId());
    }

    private UUID insertAttempt(UUID workspaceId, UUID userId, UUID resourceId, UUID versionId) {
        return insertAttempt(jdbc, workspaceId, userId, resourceId, versionId);
    }

    private UUID insertAttempt(
            JdbcTemplate template, UUID workspaceId, UUID userId, UUID resourceId, UUID versionId) {
        UUID id = UUID.randomUUID();
        template.update(
                "INSERT INTO system_practice_attempt_sample"
                        + " (id, personal_workspace_id, user_id, system_resource_id, version_id,"
                        + " attempted_at, content_stub)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'Bài làm mẫu')",
                id,
                workspaceId,
                userId,
                resourceId,
                versionId,
                Timestamp.from(Instant.now()));
        return id;
    }

    private int countAsOwner(UUID workspaceId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT count(*) FROM system_practice_attempt_sample"
                                + " WHERE personal_workspace_id = ?",
                        Integer.class,
                        workspaceId));
    }
}
