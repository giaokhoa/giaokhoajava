package com.giaokhoa.resources.data;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository canonical của resource/version theo data-model: resource và version không bị nhân
 * bản khi lưu về kho khác, nên repository này là điểm truy cập duy nhất của tầng service tới các
 * bảng {@code resource}, {@code resource_version}, {@code library_item}, {@code share_grant} và
 * {@code resource_dependency}. Việc resolve metadata phục vụ dựng authorization key chỉ đọc các
 * cột định danh/phân loại; {@code body_stub} và {@code answer_stub} là hai cột nội dung tách
 * bạch, chỉ được đọc qua {@link #content} sau khi quyết định PDP tương ứng đã cho phép — không có
 * đường nào trong repository trả cả hai cột cho một lời gọi đọc thân bài hay đáp án ở tầng trên.
 *
 * <p>Các phép đọc/ghi trên {@code library_item} và {@code resource_assignment} chịu RLS theo
 * workspace nhận: caller phải đặt context workspace của transaction trước khi gọi (qua {@code
 * WorkspaceTransaction}), repository không tự đặt context.
 */
@Repository
public class CanonicalResourceRepository {

    private final JdbcTemplate jdbc;

    public CanonicalResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Metadata tối thiểu của một version để dựng authorization key và kiểm invariant. */
    public record VersionMetadata(
            UUID resourceId,
            UUID versionId,
            UUID ownerWorkspaceId,
            String ownerKind,
            UUID createdByUserId,
            UUID subjectId,
            String kind,
            String reviewState) {}

    /** Nội dung canonical của một version: thân bài và đáp án là hai cột tách bạch. */
    public record VersionContent(String body, @Nullable String answer) {}

    /** Một grant đã ACCEPTED trỏ tới đúng version, làm căn cứ license cho lưu/fork. */
    public record GrantRef(UUID id, List<String> actions, List<UUID> allowedWorkspaceIds) {
        public GrantRef {
            actions = List.copyOf(actions);
            allowedWorkspaceIds = List.copyOf(allowedWorkspaceIds);
        }
    }

    /** Một mục thư viện đã lưu, kèm căn cứ custody để nhận bản cập nhật giữ nguyên căn cứ đó. */
    public record LibraryItemRef(
            UUID id, UUID workspaceId, UUID resourceId, UUID versionId,
            @Nullable UUID shareGrantId, String custody) {}

    public Optional<VersionMetadata> metadata(UUID resourceId, UUID versionId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT r.owner_workspace_id, ow.kind AS owner_kind,"
                                + " r.created_by_user_id, r.subject_id, r.kind,"
                                + " v.review_state FROM resource r"
                                + " JOIN resource_version v ON v.resource_id = r.id AND v.id = ?"
                                + " JOIN workspace ow ON ow.id = r.owner_workspace_id"
                                + " WHERE r.id = ?",
                        versionId,
                        resourceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(
                new VersionMetadata(
                        resourceId,
                        versionId,
                        Objects.requireNonNull(
                                (UUID) row.get("owner_workspace_id"), "owner_workspace_id"),
                        Objects.requireNonNull((String) row.get("owner_kind"), "owner_kind"),
                        Objects.requireNonNull(
                                (UUID) row.get("created_by_user_id"), "created_by_user_id"),
                        Objects.requireNonNull((UUID) row.get("subject_id"), "subject_id"),
                        Objects.requireNonNull((String) row.get("kind"), "kind"),
                        Objects.requireNonNull((String) row.get("review_state"), "review_state")));
    }

    /** Nội dung của version; chỉ gọi sau quyết định PDP cho scope nội dung tương ứng. */
    public Optional<VersionContent> content(UUID resourceId, UUID versionId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT body_stub, answer_stub FROM resource_version"
                                + " WHERE resource_id = ? AND id = ?",
                        resourceId,
                        versionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(
                new VersionContent(
                        Objects.requireNonNull((String) row.get("body_stub"), "body_stub"),
                        (String) row.get("answer_stub")));
    }

    /** Mục thư viện của đúng bộ ba workspace/resource/version nếu đã lưu; lưu trùng tái dùng nó. */
    public Optional<UUID> findLibraryItem(UUID workspaceId, UUID resourceId, UUID versionId) {
        return jdbc
                .queryForList(
                        "SELECT id FROM library_item WHERE workspace_id = ?"
                                + " AND resource_id = ? AND version_id = ?"
                                + " ORDER BY created_at, id",
                        UUID.class,
                        workspaceId,
                        resourceId,
                        versionId)
                .stream()
                .findFirst();
    }

    /** Mục thư viện theo định danh, để nhận cập nhật kiểm item thuộc đúng workspace của route. */
    public Optional<LibraryItemRef> libraryItem(UUID itemId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, workspace_id, resource_id, version_id, share_grant_id,"
                                + " custody FROM library_item WHERE id = ?",
                        itemId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(
                new LibraryItemRef(
                        Objects.requireNonNull((UUID) row.get("id"), "id"),
                        Objects.requireNonNull((UUID) row.get("workspace_id"), "workspace_id"),
                        Objects.requireNonNull((UUID) row.get("resource_id"), "resource_id"),
                        Objects.requireNonNull((UUID) row.get("version_id"), "version_id"),
                        (UUID) row.get("share_grant_id"),
                        Objects.requireNonNull((String) row.get("custody"), "custody")));
    }

    public UUID insertLibraryItem(
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            @Nullable UUID shareGrantId,
            String custody) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO library_item(id, workspace_id, resource_id, version_id,"
                        + " share_grant_id, custody) VALUES (?,?,?,?,?,?)",
                itemId,
                workspaceId,
                resourceId,
                versionId,
                shareGrantId,
                custody);
        return itemId;
    }

    /** Mọi grant ACCEPTED mà workspace nhận là bên nhận, trỏ đúng version này, cũ nhất trước. */
    public List<GrantRef> acceptedGrants(UUID workspaceId, UUID resourceId, UUID versionId) {
        return jdbc.query(
                "SELECT id, array_to_string(actions, ',') AS actions_csv,"
                        + " array_to_string(allowed_workspace_ids, ',') AS allowed_csv"
                        + " FROM share_grant WHERE recipient_workspace_id = ?"
                        + " AND source_resource_id = ? AND source_version_id = ?"
                        + " AND state = 'ACCEPTED' ORDER BY created_at, id",
                (rs, _) ->
                        new GrantRef(
                                rs.getObject("id", UUID.class),
                                splitCsv(rs.getString("actions_csv")),
                                splitCsv(rs.getString("allowed_csv")).stream()
                                        .map(UUID::fromString)
                                        .toList()),
                workspaceId,
                resourceId,
                versionId);
    }

    /** Version đầu (version_no 1) của một resource, để dựng target key của bản fork khi replay. */
    public Optional<UUID> findForkVersionId(UUID resourceId) {
        return jdbc
                .queryForList(
                        "SELECT id FROM resource_version WHERE resource_id = ? AND version_no = 1",
                        UUID.class,
                        resourceId)
                .stream()
                .findFirst();
    }

    /**
     * Tạo resource fork: resource mới thuộc workspace nhận, người tạo là actor, giữ đúng kind và
     * môn của nguồn (fork đúng môn theo invariant), version đầu ở DRAFT mang nội dung sao từ
     * version nguồn, một cạnh provenance về đúng version nguồn kèm grant đã cấp quyền fork (nếu
     * căn cứ là license), và sao toàn bộ cạnh dependency mà chính version nguồn đang mang để bản
     * fork giữ nguyên source dependencies của nguồn. Caller serialize việc tạo cạnh theo resource
     * nguồn trong cùng fence transaction.
     */
    public UUID insertFork(
            UUID forkResourceId,
            UUID forkVersionId,
            UUID actorUserId,
            UUID destinationWorkspaceId,
            VersionMetadata source,
            String label,
            VersionContent sourceContent,
            @Nullable UUID authorizingGrantId,
            String usageConstraints) {
        jdbc.update(
                "INSERT INTO resource(id, owner_workspace_id, created_by_user_id, kind,"
                        + " subject_id, state) VALUES (?,?,?,?,?,'ACTIVE')",
                forkResourceId,
                destinationWorkspaceId,
                actorUserId,
                source.kind(),
                source.subjectId());
        jdbc.update(
                "INSERT INTO resource_version(id, resource_id, version_no, label, body_stub,"
                        + " answer_stub, review_state) VALUES (?,?,1,?,?,?,'DRAFT')",
                forkVersionId,
                forkResourceId,
                label,
                sourceContent.body(),
                sourceContent.answer());
        jdbc.update(
                "INSERT INTO resource_dependency(id, derived_resource_id, derived_version_id,"
                        + " source_resource_id, source_version_id, share_grant_id,"
                        + " usage_constraints) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                forkResourceId,
                forkVersionId,
                source.resourceId(),
                source.versionId(),
                authorizingGrantId,
                usageConstraints);
        jdbc.update(
                "INSERT INTO resource_dependency(id, derived_resource_id, derived_version_id,"
                        + " source_resource_id, source_version_id, share_grant_id,"
                        + " usage_constraints)"
                        + " SELECT gen_random_uuid(), ?, ?, source_resource_id,"
                        + " source_version_id, share_grant_id, usage_constraints"
                        + " FROM resource_dependency"
                        + " WHERE derived_resource_id = ? AND derived_version_id = ?",
                forkResourceId,
                forkVersionId,
                source.resourceId(),
                source.versionId());
        return forkResourceId;
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }
}
