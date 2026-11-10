package com.giaokhoa.resources;

import static org.assertj.core.api.Assertions.assertThat;

import com.giaokhoa.resources.model.LibraryItem;
import com.giaokhoa.resources.model.NotificationReceipt;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceAssignment;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.NotificationReceiptRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Khóa tầng dữ liệu JPA mới của module resources trên PostgreSQL thật: cột mảng của share_grant và
 * resource_assignment round-trip thành List giữ đúng thứ tự, phép cập nhật có điều kiện của
 * accept/revoke giữ giao thức số dòng (đúng trạng thái mới đổi được), bản sao copy-on-share được
 * vật chất hoá kèm provenance và toàn bộ closure của version nguồn, và receipt thông báo dùng khóa
 * nhúng với mốc xác nhận chỉ ghi một lần.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ResourceCopies.class)
class ResourcesJpaRepositoryIT extends IntegrationEnvironment {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ShareGrantRepository grants;
    @Autowired private ResourceRepository resources;
    @Autowired private ResourceVersionRepository versions;
    @Autowired private ResourceDependencyRepository dependencies;
    @Autowired private LibraryItemRepository libraryItems;
    @Autowired private NotificationReceiptRepository receipts;
    @Autowired private ResourceCopies copies;

    @DisplayName("Cột mảng của share_grant round-trip thành List giữ đúng thứ tự và tập rỗng")
    @Test
    void shareGrantArrayColumnsRoundTripAsLists() {
        GrantFixture fixture = grantFixture();
        UUID allowed = UUID.randomUUID();
        ShareGrant grant =
                new ShareGrant(
                        UUID.randomUUID(),
                        fixture.resourceId(),
                        fixture.versionId(),
                        fixture.grantorId(),
                        fixture.recipientWorkspaceId(),
                        ShareGrant.Mode.REVOCABLE,
                        List.of("use", "content.read", "fork"),
                        List.of(allowed),
                        Instant.now(),
                        null);
        grants.save(grant);
        entityManager.flush();
        entityManager.clear();

        ShareGrant reloaded = grants.findById(grant.getId()).orElseThrow();
        assertThat(reloaded.getActions()).containsExactly("use", "content.read", "fork");
        assertThat(reloaded.getAllowedWorkspaceIds()).containsExactly(allowed);
        assertThat(reloaded.getState()).isEqualTo(ShareGrant.State.OFFERED);

        ShareGrant openGrant =
                new ShareGrant(
                        UUID.randomUUID(),
                        fixture.resourceId(),
                        fixture.versionId(),
                        fixture.grantorId(),
                        fixture.recipientWorkspaceId(),
                        ShareGrant.Mode.LONG_TERM,
                        List.of("use"),
                        List.of(),
                        Instant.now(),
                        null);
        grants.save(openGrant);
        entityManager.flush();
        entityManager.clear();
        assertThat(grants.findById(openGrant.getId()).orElseThrow().getAllowedWorkspaceIds())
                .isEmpty();
    }

    @DisplayName(
            "Cập nhật có điều kiện: accept chỉ khi OFFERED, revoke chỉ khi ACCEPTED và REVOCABLE")
    @Test
    void guardedShareUpdatesFollowTheStateProtocol() {
        GrantFixture fixture = grantFixture();
        ShareGrant revocable = saveGrant(fixture, ShareGrant.Mode.REVOCABLE);
        ShareGrant longTerm = saveGrant(fixture, ShareGrant.Mode.LONG_TERM);

        assertThat(
                        grants.acceptIfOffered(
                                revocable.getId(),
                                Instant.now(),
                                ShareGrant.State.OFFERED,
                                ShareGrant.State.ACCEPTED))
                .isEqualTo(1);
        entityManager.clear();
        ShareGrant accepted = grants.findById(revocable.getId()).orElseThrow();
        assertThat(accepted.getState()).isEqualTo(ShareGrant.State.ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
        assertThat(accepted.getVersion()).isEqualTo(1);
        assertThat(
                        grants.acceptIfOffered(
                                revocable.getId(),
                                Instant.now(),
                                ShareGrant.State.OFFERED,
                                ShareGrant.State.ACCEPTED))
                .isEqualTo(0);

        assertThat(
                        grants.acceptIfOffered(
                                longTerm.getId(),
                                Instant.now(),
                                ShareGrant.State.OFFERED,
                                ShareGrant.State.ACCEPTED))
                .isEqualTo(1);
        assertThat(
                        grants.revokeIfAcceptedRevocable(
                                longTerm.getId(),
                                Instant.now(),
                                ShareGrant.State.ACCEPTED,
                                ShareGrant.State.REVOKED,
                                ShareGrant.Mode.REVOCABLE))
                .isEqualTo(0);
        assertThat(
                        grants.revokeIfAcceptedRevocable(
                                revocable.getId(),
                                Instant.now(),
                                ShareGrant.State.ACCEPTED,
                                ShareGrant.State.REVOKED,
                                ShareGrant.Mode.REVOCABLE))
                .isEqualTo(1);
        entityManager.clear();
        ShareGrant revoked = grants.findById(revocable.getId()).orElseThrow();
        assertThat(revoked.getState()).isEqualTo(ShareGrant.State.REVOKED);
        assertThat(revoked.getRevokedAt()).isNotNull();
    }

    @DisplayName("Actions của resource_assignment round-trip thành List giữ đúng thứ tự")
    @Test
    void assignmentActionsRoundTripAsList() {
        UUID userId = user();
        UUID workspaceId = personalWorkspaceOf(userId);
        ResourceAssignment assignment =
                new ResourceAssignment(
                        UUID.randomUUID(),
                        workspaceId,
                        userId,
                        null,
                        UUID.randomUUID(),
                        List.of("content.read", "use"));
        entityManager.persist(assignment);
        entityManager.flush();
        entityManager.clear();

        ResourceAssignment reloaded =
                entityManager.find(ResourceAssignment.class, assignment.getId());
        assertThat(reloaded.getActions()).containsExactly("content.read", "use");
    }

    @DisplayName("Copy-on-share vật chất hoá resource, version, cạnh provenance và closure nguồn")
    @Test
    void copyVersionMaterializesProvenanceAndClosure() {
        UUID grantorId = user();
        UUID sourceWorkspaceId = personalWorkspaceOf(grantorId);
        UUID recipientUserId = user();
        UUID recipientWorkspaceId = personalWorkspaceOf(recipientUserId);
        UUID subjectId = UUID.randomUUID();
        UUID sourceId = resource(sourceWorkspaceId, grantorId, subjectId);
        UUID sourceVersionId = version(sourceId, 1);
        UUID replacementVersionId = version(sourceId, 2);
        UUID componentOneId = resource(sourceWorkspaceId, grantorId, UUID.randomUUID());
        UUID componentOneVersionId = version(componentOneId, 1);
        UUID componentTwoId = resource(sourceWorkspaceId, grantorId, UUID.randomUUID());
        UUID componentTwoVersionId = version(componentTwoId, 1);
        UUID grantId = grantRow(sourceId, sourceVersionId, grantorId, recipientWorkspaceId);
        dependency(sourceId, sourceVersionId, componentOneId, componentOneVersionId, grantId);
        dependency(sourceId, sourceVersionId, componentTwoId, componentTwoVersionId, null);

        VersionMetadata metadata =
                new VersionMetadata(
                        sourceId,
                        sourceVersionId,
                        sourceWorkspaceId,
                        "PERSONAL",
                        grantorId,
                        subjectId,
                        "EXAM",
                        "DRAFT");
        UUID copyResourceId = UUID.randomUUID();
        UUID copyVersionId = UUID.randomUUID();
        ResourceCopies.CopyRef copy =
                copies.copyVersion(
                        copyResourceId,
                        copyVersionId,
                        grantorId,
                        recipientWorkspaceId,
                        metadata,
                        "Bản sao",
                        "Nội dung",
                        "Đáp án",
                        ResourceVersion.ReviewState.DRAFT,
                        null,
                        1,
                        grantId,
                        "origin=SHARE;grant=" + grantId + ";actions=use;allowed=-");
        entityManager.flush();
        entityManager.clear();

        Resource copied = resources.findById(copyResourceId).orElseThrow();
        assertThat(copied.getOwnerWorkspaceId()).isEqualTo(recipientWorkspaceId);
        assertThat(copied.getCreatedByUserId()).isEqualTo(grantorId);
        assertThat(copied.getSubjectId()).isEqualTo(subjectId);
        ResourceVersion copiedVersion = versions.findById(copyVersionId).orElseThrow();
        assertThat(copiedVersion.getBodyStub()).isEqualTo("Nội dung");
        assertThat(copiedVersion.getAnswerStub()).isEqualTo("Đáp án");
        assertThat(copiedVersion.getResource().getId()).isEqualTo(copyResourceId);

        List<ResourceDependency> edges =
                dependencies.findByDerivedResourceIdAndDerivedVersionId(
                        copyResourceId, copyVersionId);
        assertThat(edges).hasSize(3);
        assertThat(edges)
                .anySatisfy(edge -> {
                    assertThat(edge.getSourceResourceId()).isEqualTo(sourceId);
                    assertThat(edge.getSourceVersionId()).isEqualTo(sourceVersionId);
                    assertThat(edge.getShareGrantId()).isEqualTo(grantId);
                    assertThat(edge.getUsageConstraints()).startsWith("origin=SHARE");
                });
        assertThat(edges)
                .extracting(ResourceDependency::getSourceVersionId)
                .containsExactlyInAnyOrder(
                        sourceVersionId, componentOneVersionId, componentTwoVersionId);
        assertThat(copies.findCopy(sourceId, sourceVersionId, grantId, recipientWorkspaceId))
                .contains(copy);
        assertThat(copies.findCopy(sourceId, sourceVersionId, grantId, sourceWorkspaceId))
                .isEmpty();

        // Nhận cập nhật trên bản sao: thêm version sao của version thay thế, không trỏ nguồn sống.
        VersionMetadata replacement =
                new VersionMetadata(
                        sourceId,
                        replacementVersionId,
                        sourceWorkspaceId,
                        "PERSONAL",
                        grantorId,
                        subjectId,
                        "EXAM",
                        "DRAFT");
        UUID updateVersionId = UUID.randomUUID();
        copies.appendVersion(
                copyResourceId,
                updateVersionId,
                replacement,
                "Bản thay thế",
                "Nội dung mới",
                "Đáp án mới",
                ResourceVersion.ReviewState.DRAFT,
                null,
                copies.nextVersionNo(copyResourceId),
                grantId,
                "origin=SHARE;grant=" + grantId + ";actions=use;allowed=-");
        entityManager.flush();
        entityManager.clear();
        assertThat(versions.findById(updateVersionId).orElseThrow().getVersionNo()).isEqualTo(2);
        assertThat(
                        copies.copyVersionIdForSourceVersion(
                                copyResourceId, sourceId, replacementVersionId, grantId))
                .contains(updateVersionId);

        LibraryItem item =
                libraryItems.save(
                        new LibraryItem(
                                UUID.randomUUID(),
                                recipientWorkspaceId,
                                copyResourceId,
                                copyVersionId,
                                grantId,
                                LibraryItem.Custody.LICENSED));
        assertThat(libraryItems.findWorkspaceIdsByShareGrantId(grantId))
                .containsExactly(recipientWorkspaceId);
        assertThat(item.getId()).isNotNull();
    }

    @DisplayName("Receipt dùng khóa nhúng và mốc xác nhận chỉ ghi một lần")
    @Test
    void notificationReceiptUsesEmbeddedIdAndAcknowledgesOnce() {
        UUID creatorId = user();
        UUID workspaceId = personalWorkspaceOf(creatorId);
        UUID recipientId = user();
        UUID recipientWorkspaceId = personalWorkspaceOf(recipientId);
        UUID resourceId = resource(workspaceId, creatorId, UUID.randomUUID());
        UUID affectedId = version(resourceId, 1);
        UUID replacementId = version(resourceId, 2);
        UUID noticeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO correction_notice"
                        + " (id, resource_id, affected_version_id, replacement_version_id, reason)"
                        + " VALUES (?, ?, ?, ?, 'Sai đáp án')",
                noticeId,
                resourceId,
                affectedId,
                replacementId);

        NotificationReceipt receipt = new NotificationReceipt(noticeId, recipientWorkspaceId);
        receipts.save(receipt);
        entityManager.flush();
        entityManager.clear();

        NotificationReceipt reloaded =
                receipts.findById(new NotificationReceipt.Key(noticeId, recipientWorkspaceId))
                        .orElseThrow();
        assertThat(reloaded.getNoticeId()).isEqualTo(noticeId);
        assertThat(reloaded.getAcknowledgedAt()).isNull();
        Instant first = Instant.parse("2026-11-10T00:00:00Z");
        reloaded.acknowledge(first);
        reloaded.acknowledge(Instant.parse("2026-11-11T00:00:00Z"));
        entityManager.flush();
        entityManager.clear();
        assertThat(
                        receipts.findById(
                                        new NotificationReceipt.Key(noticeId, recipientWorkspaceId))
                                .orElseThrow()
                                .getAcknowledgedAt())
                .isEqualTo(first);
    }

    // ---------- Dữ kiện ----------

    private record GrantFixture(
            UUID resourceId, UUID versionId, UUID grantorId, UUID recipientWorkspaceId) {}

    private GrantFixture grantFixture() {
        UUID grantorId = user();
        UUID recipientId = user();
        UUID resourceId = resource(personalWorkspaceOf(grantorId), grantorId, UUID.randomUUID());
        return new GrantFixture(
                resourceId, version(resourceId, 1), grantorId, personalWorkspaceOf(recipientId));
    }

    private ShareGrant saveGrant(GrantFixture fixture, ShareGrant.Mode mode) {
        ShareGrant grant =
                new ShareGrant(
                        UUID.randomUUID(),
                        fixture.resourceId(),
                        fixture.versionId(),
                        fixture.grantorId(),
                        fixture.recipientWorkspaceId(),
                        mode,
                        List.of("use"),
                        List.of(),
                        Instant.now(),
                        null);
        grants.save(grant);
        entityManager.flush();
        return grant;
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

    private UUID resource(UUID ownerWorkspaceId, UUID creatorId, UUID subjectId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource"
                        + " (id, owner_workspace_id, created_by_user_id, kind, subject_id, state)"
                        + " VALUES (?, ?, ?, 'EXAM', ?, 'ACTIVE')",
                id,
                ownerWorkspaceId,
                creatorId,
                subjectId);
        return id;
    }

    private UUID version(UUID resourceId, int versionNo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO resource_version"
                        + " (id, resource_id, version_no, label, body_stub, answer_stub,"
                        + " review_state, locked_at)"
                        + " VALUES (?, ?, ?, ?, 'Nội dung', 'Đáp án', 'DRAFT', NULL)",
                id,
                resourceId,
                versionNo,
                "Bản " + versionNo);
        return id;
    }

    private UUID grantRow(
            UUID resourceId, UUID versionId, UUID grantorId, UUID recipientWorkspaceId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO share_grant"
                        + " (id, source_resource_id, source_version_id, grantor_user_id,"
                        + " recipient_workspace_id, mode, state, actions,"
                        + " allowed_workspace_ids, valid_from, accepted_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'REVOCABLE', 'ACCEPTED', '{use}'::text[],"
                        + " '{}'::uuid[], now(), now())",
                id,
                resourceId,
                versionId,
                grantorId,
                recipientWorkspaceId);
        return id;
    }

    private void dependency(
            UUID derivedResourceId,
            UUID derivedVersionId,
            UUID sourceResourceId,
            UUID sourceVersionId,
            UUID grantId) {
        jdbc.update(
                "INSERT INTO resource_dependency"
                        + " (id, derived_resource_id, derived_version_id, source_resource_id,"
                        + " source_version_id, share_grant_id, usage_constraints)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'origin=COMPONENT')",
                UUID.randomUUID(),
                derivedResourceId,
                derivedVersionId,
                sourceResourceId,
                sourceVersionId,
                grantId);
    }
}
