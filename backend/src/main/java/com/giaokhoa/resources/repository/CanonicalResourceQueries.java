package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.LibraryItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Fragment truy vấn canonical của {@link CanonicalResourceRepository}: các phép cần projection vô
 * hướng tách cột thân/đáp án và phép ghi mục thư viện, cài ở {@link CanonicalResourceQueriesImpl}
 * theo mẫu fragment của Spring Data.
 *
 * <p>Mọi phép đọc ở fragment này thuộc đường đọc canonical của HỆ THỐNG: caller phải ghim ngữ cảnh
 * hệ thống tường minh trước khi gọi (xem ghi chú ở {@link CanonicalResourceRepository}).
 */
public interface CanonicalResourceQueries {

    Optional<VersionMetadata> metadata(UUID resourceId, UUID versionId);

    Optional<String> body(UUID resourceId, UUID versionId);

    @Nullable String answer(UUID resourceId, UUID versionId);

    List<GrantRef> acceptedGrants(UUID recipientWorkspaceId, UUID resourceId, UUID versionId);

    List<GrantRef> acceptedGrantsInWindow(
            UUID recipientWorkspaceId, UUID resourceId, UUID versionId);

    Optional<UUID> findLibraryItem(UUID workspaceId, UUID resourceId, UUID versionId);

    Optional<LibraryItemRef> libraryItem(UUID libraryItemId);

    UUID insertLibraryItem(
            UUID id,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            @Nullable UUID shareGrantId,
            LibraryItem.Custody custody);

    Optional<UUID> findForkVersionId(UUID resourceId);
}
