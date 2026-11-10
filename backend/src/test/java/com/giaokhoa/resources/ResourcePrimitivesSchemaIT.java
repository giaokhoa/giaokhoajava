package com.giaokhoa.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// Slice JPA của Boot 4 trên PostgreSQL thật của stack TEST: replace = NONE giữ nguyên
// datasource đã khai (không thay bằng embedded); Flyway không thuộc slice nên phải import
// tường minh để migration chạy trước khi Hibernate validate schema. Slice không nạp
// worker/scheduler và chạy trên schema dùng chung: các slice cùng cấu hình dùng chung một
// context đã cache của Spring, mỗi test chạy trong giao dịch riêng và rollback khi kết thúc
// nên dữ kiện không lẫn sang class khác.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class ResourcePrimitivesSchemaIT extends IntegrationEnvironment {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void entitiesRoundTripCanonicalPrimitives() {
        UUID creatorId = user();
        UUID workspaceId = personalWorkspaceOf(creatorId);
        Resource resource =
                new Resource(
                        UUID.randomUUID(),
                        workspaceId,
                        creatorId,
                        Resource.Kind.QUESTION,
                        UUID.randomUUID(),
                        "ACTIVE");
        entityManager.persist(resource);
        ResourceVersion version =
                new ResourceVersion(
                        UUID.randomUUID(),
                        resource.getId(),
                        1,
                        "Bản đầu",
                        "Nội dung câu hỏi",
                        "Đáp án",
                        ResourceVersion.ReviewState.DRAFT,
                        null);
        entityManager.persist(version);
        entityManager.flush();
        entityManager.clear();

        Resource reloaded = entityManager.find(Resource.class, resource.getId());
        assertThat(reloaded.getOwnerWorkspaceId()).isEqualTo(workspaceId);
        assertThat(reloaded.getCreatedByUserId()).isEqualTo(creatorId);
        assertThat(reloaded.getKind()).isEqualTo(Resource.Kind.QUESTION);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        ResourceVersion reloadedVersion =
                entityManager.find(ResourceVersion.class, version.getId());
        assertThat(reloadedVersion.getResourceId()).isEqualTo(resource.getId());
        assertThat(reloadedVersion.getVersionNo()).isEqualTo(1);
        assertThat(reloadedVersion.getReviewState()).isEqualTo(ResourceVersion.ReviewState.DRAFT);
        assertThat(reloadedVersion.getLockedAt()).isNull();
    }

    @Test
    void rejectsDuplicateVersionNumberWithinOneResource() {
        UUID resourceId = resource();
        version(resourceId, 1, "DRAFT", null);
        jdbc.update("SAVEPOINT duplicate_version");
        assertThatThrownBy(() -> version(resourceId, 1, "DRAFT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT duplicate_version");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM resource_version WHERE resource_id = ?",
                                Integer.class,
                                resourceId))
                .isEqualTo(1);
    }

    @Test
    void rejectsKindOutsideCatalog() {
        UUID creatorId = user();
        UUID workspaceId = personalWorkspaceOf(creatorId);
        assertThatThrownBy(() ->
                        jdbc.update(
                                "INSERT INTO resource"
                                        + " (id, owner_workspace_id, created_by_user_id,"
                                        + " kind, subject_id, state)"
                                        + " VALUES (?, ?, ?, 'ESSAY', ?, 'ACTIVE')",
                                UUID.randomUUID(),
                                workspaceId,
                                creatorId,
                                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOwnerWorkspaceChange() {
        UUID resourceId = resource();
        UUID otherWorkspace = personalWorkspaceOf(user());
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE resource SET owner_workspace_id = ? WHERE id = ?",
                                otherWorkspace,
                                resourceId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Resource owner workspace cannot change");
    }

    @Test
    void lockCoherenceIsEnforced() {
        UUID resourceId = resource();
        jdbc.update("SAVEPOINT locked_without_timestamp");
        assertThatThrownBy(() -> version(resourceId, 1, "LOCKED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT locked_without_timestamp");
        assertThatThrownBy(() -> version(resourceId, 1, "DRAFT", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reviewStateOnlyAdvancesThroughApproval() {
        UUID resourceId = resource();
        UUID versionId = version(resourceId, 1, "DRAFT", null);
        jdbc.update("SAVEPOINT skip_approval");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE resource_version SET review_state = 'LOCKED',"
                                        + " locked_at = CURRENT_TIMESTAMP WHERE id = ?",
                                versionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("must advance DRAFT -> APPROVED -> LOCKED");
        jdbc.update("ROLLBACK TO SAVEPOINT skip_approval");
        jdbc.update(
                "UPDATE resource_version SET review_state = 'APPROVED' WHERE id = ?", versionId);
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE resource_version SET review_state = 'DRAFT'"
                                        + " WHERE id = ?",
                                versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lockedVersionCannotBeEditedInPlace() {
        UUID resourceId = resource();
        UUID versionId = version(resourceId, 1, "DRAFT", null);
        jdbc.update(
                "UPDATE resource_version SET review_state = 'APPROVED' WHERE id = ?", versionId);
        jdbc.update(
                "UPDATE resource_version SET review_state = 'LOCKED',"
                        + " locked_at = CURRENT_TIMESTAMP WHERE id = ?",
                versionId);
        jdbc.update("SAVEPOINT edit_locked");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE resource_version SET body_stub = 'Đổi nội dung'"
                                        + " WHERE id = ?",
                                versionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Locked resource version cannot be modified");
        jdbc.update("ROLLBACK TO SAVEPOINT edit_locked");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE resource_version SET review_state = 'DRAFT',"
                                        + " locked_at = NULL WHERE id = ?",
                                versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shareGrantActionsCatalogCoversVersionLevelTokens() {
        UUID resourceId = resource();
        UUID versionId = version(resourceId, 1, "DRAFT", null);
        UUID grantorId = user();
        UUID recipientWorkspace = personalWorkspaceOf(user());
        jdbc.update(
                "INSERT INTO share_grant"
                        + " (id, source_resource_id, source_version_id, grantor_user_id,"
                        + " recipient_workspace_id, mode, state, actions,"
                        + " allowed_workspace_ids, valid_from)"
                        + " VALUES (?, ?, ?, ?, ?, 'REVOCABLE', 'OFFERED',"
                        + " '{content.read,use,library.save,fork}'::text[], '{}'::uuid[], now())",
                UUID.randomUUID(),
                resourceId,
                versionId,
                grantorId,
                recipientWorkspace);
        jdbc.update("SAVEPOINT unknown_action");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "INSERT INTO share_grant"
                                        + " (id, source_resource_id, source_version_id,"
                                        + " grantor_user_id, recipient_workspace_id, mode,"
                                        + " state, actions, allowed_workspace_ids, valid_from)"
                                        + " VALUES (?, ?, ?, ?, ?, 'REVOCABLE', 'OFFERED',"
                                        + " '{bogus}'::text[], '{}'::uuid[], now())",
                                UUID.randomUUID(),
                                resourceId,
                                versionId,
                                grantorId,
                                recipientWorkspace))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT unknown_action");
    }

    @Test
    void sampleActionReceiptAcceptsLibraryAndShareTargets() {
        UUID actorId = user();
        UUID workspaceId = personalWorkspaceOf(actorId);
        jdbc.update(
                "INSERT INTO sample_action_mutation"
                        + " (actor_user_id, idempotency_key, payload_hash, workspace_id,"
                        + " target_type, target_id) VALUES (?, 'k-share', 'h', ?,"
                        + " 'SHARE_GRANT', ?)",
                actorId,
                workspaceId,
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO sample_action_mutation"
                        + " (actor_user_id, idempotency_key, payload_hash, workspace_id,"
                        + " target_type, target_id) VALUES (?, 'k-library', 'h', ?,"
                        + " 'LIBRARY_ITEM', ?)",
                actorId,
                workspaceId,
                UUID.randomUUID());
        jdbc.update("SAVEPOINT unknown_target_type");
        assertThatThrownBy(() ->
                        jdbc.update(
                                "INSERT INTO sample_action_mutation"
                                        + " (actor_user_id, idempotency_key, payload_hash,"
                                        + " workspace_id, target_type, target_id) VALUES (?,"
                                        + " 'k-other', 'h', ?, 'WHATEVER', ?)",
                                actorId,
                                workspaceId,
                                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("ROLLBACK TO SAVEPOINT unknown_target_type");
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account"
                        + " (id, keycloak_sub, username, email_verified, origin, identity_state)"
                        + " VALUES (?, ?, ?, false, 'SELF_REGISTERED', 'PENDING')",
                id,
                "sub-" + id,
                "user-" + id);
        jdbc.update(
                "INSERT INTO workspace (id, kind, owner_user_id, state)"
                        + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                UUID.randomUUID(),
                id);
        return id;
    }

    private UUID personalWorkspaceOf(UUID userId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM workspace WHERE owner_user_id = ? AND kind = 'PERSONAL'",
                        UUID.class,
                        userId));
    }

    private UUID resource() {
        UUID creatorId = user();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource"
                        + " (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)"
                        + " VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                id,
                personalWorkspaceOf(creatorId),
                creatorId,
                UUID.randomUUID());
        return id;
    }

    private UUID version(
            UUID resourceId, int versionNo, String reviewState, @Nullable Instant lockedAt) {
        UUID id = UUID.randomUUID();
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
}
