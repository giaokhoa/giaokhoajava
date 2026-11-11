package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.LibraryItem;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.tenancy.model.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Cài đặt fragment {@link CanonicalResourceQueries} trên EntityManager: các phép đọc canonical là
 * projection chọn đúng cột cần (metadata không chạm cột nội dung, thân bài và đáp án là hai
 * projection scalar tách nhau), grant được nạp thành DTO {@link GrantRef} ngay trong tầng dữ liệu
 * để entity không rời khỏi tầng này.
 */
public class CanonicalResourceQueriesImpl implements CanonicalResourceQueries {

    // Container gán EntityManager qua @PersistenceContext trước mọi lời gọi nghiệp vụ;
    // NullAway không thấy đường inject này nên suppression chỉ đặt tại field này.
    @SuppressWarnings("NullAway")
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<VersionMetadata> metadata(UUID resourceId, UUID versionId) {
        List<Object[]> rows =
                entityManager
                        .createQuery(
                                "select r.ownerWorkspaceId, r.createdByUserId, r.subjectId,"
                                        + " r.kind, v.reviewState from ResourceVersion v"
                                        + " join v.resource r"
                                        + " where v.id = :versionId and v.resourceId = :resourceId",
                                Object[].class)
                        .setParameter("versionId", versionId)
                        .setParameter("resourceId", resourceId)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        UUID ownerWorkspaceId = Objects.requireNonNull((UUID) row[0], "owner_workspace_id");
        Workspace owner = entityManager.find(Workspace.class, ownerWorkspaceId);
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(
                new VersionMetadata(
                        resourceId,
                        versionId,
                        ownerWorkspaceId,
                        owner.getKind().name(),
                        Objects.requireNonNull((UUID) row[1], "created_by_user_id"),
                        Objects.requireNonNull((UUID) row[2], "subject_id"),
                        ((Resource.Kind) row[3]).name(),
                        ((ResourceVersion.ReviewState) row[4]).name()));
    }

    @Override
    public Optional<String> body(UUID resourceId, UUID versionId) {
        return entityManager
                .createQuery(
                        "select v.bodyStub from ResourceVersion v"
                                + " where v.id = :versionId and v.resourceId = :resourceId",
                        String.class)
                .setParameter("versionId", versionId)
                .setParameter("resourceId", resourceId)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public @Nullable String answer(UUID resourceId, UUID versionId) {
        List<String> rows =
                entityManager
                        .createQuery(
                                "select v.answerStub from ResourceVersion v"
                                        + " where v.id = :versionId"
                                        + " and v.resourceId = :resourceId",
                                String.class)
                        .setParameter("versionId", versionId)
                        .setParameter("resourceId", resourceId)
                        .getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public List<GrantRef> acceptedGrants(UUID workspaceId, UUID resourceId, UUID versionId) {
        return queryAcceptedGrants(workspaceId, resourceId, versionId, false);
    }

    @Override
    public List<GrantRef> acceptedGrantsInWindow(
            UUID workspaceId, UUID resourceId, UUID versionId) {
        return queryAcceptedGrants(workspaceId, resourceId, versionId, true);
    }

    private List<GrantRef> queryAcceptedGrants(
            UUID workspaceId, UUID resourceId, UUID versionId, boolean withinWindow) {
        var query =
                entityManager.createQuery(
                        "select g from ShareGrant g where g.recipientWorkspaceId = :workspaceId"
                                + " and g.sourceResourceId = :resourceId"
                                + " and g.sourceVersionId = :versionId and g.state = :state"
                                + (withinWindow
                                        ? " and g.validFrom <= :now"
                                                + " and (g.expiresAt is null or g.expiresAt > :now)"
                                        : "")
                                + " order by g.createdAt asc, g.id asc",
                        ShareGrant.class);
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("resourceId", resourceId);
        query.setParameter("versionId", versionId);
        query.setParameter("state", ShareGrant.State.ACCEPTED);
        if (withinWindow) {
            query.setParameter("now", Instant.now());
        }
        return query.getResultList().stream()
                .map(grant ->
                        new GrantRef(
                                grant.getId(), grant.getActions(), grant.getAllowedWorkspaceIds()))
                .toList();
    }

    @Override
    public Optional<UUID> findLibraryItem(UUID workspaceId, UUID resourceId, UUID versionId) {
        return entityManager
                .createQuery(
                        "select l.id from LibraryItem l where l.workspaceId = :workspaceId"
                                + " and l.resourceId = :resourceId and l.versionId = :versionId"
                                + " order by l.createdAt asc, l.id asc",
                        UUID.class)
                .setParameter("workspaceId", workspaceId)
                .setParameter("resourceId", resourceId)
                .setParameter("versionId", versionId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LibraryItemRef> libraryItem(UUID itemId) {
        LibraryItem item = entityManager.find(LibraryItem.class, itemId);
        if (item == null) {
            return Optional.empty();
        }
        return Optional.of(
                new LibraryItemRef(
                        item.getId(),
                        item.getWorkspaceId(),
                        item.getResourceId(),
                        item.getVersionId(),
                        item.getShareGrantId(),
                        item.getCustody().name()));
    }

    @Override
    public UUID insertLibraryItem(
            UUID itemId,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            @Nullable UUID shareGrantId,
            LibraryItem.Custody custody) {
        entityManager.persist(
                new LibraryItem(itemId, workspaceId, resourceId, versionId, shareGrantId, custody));
        return itemId;
    }

    @Override
    public Optional<UUID> findForkVersionId(UUID resourceId) {
        return entityManager
                .createQuery(
                        "select v.id from ResourceVersion v"
                                + " where v.resourceId = :resourceId and v.versionNo = 1",
                        UUID.class)
                .setParameter("resourceId", resourceId)
                .getResultList()
                .stream()
                .findFirst();
    }
}
