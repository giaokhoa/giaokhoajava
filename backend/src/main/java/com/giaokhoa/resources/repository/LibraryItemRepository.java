package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.LibraryItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của {@code library_item} do module resources sở hữu. */
public interface LibraryItemRepository extends JpaRepository<LibraryItem, UUID> {

    /**
     * Mục thư viện của đúng bộ ba workspace/resource/version, cũ nhất trước: chưa có unique
     * constraint trên bộ ba nên phép tra không giả định duy nhất và lấy dòng đầu như kỷ luật cũ.
     */
    Optional<LibraryItem> findFirstByWorkspaceIdAndResourceIdAndVersionIdOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID resourceId, UUID versionId);

    List<LibraryItem> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndResourceIdAndVersionIdAndShareGrantIdIsNull(
            UUID workspaceId, UUID resourceId, UUID versionId);

    /** Một version đã được lưu vào kho của bất kỳ workspace nào là lịch sử phải bảo lưu. */
    boolean existsByResourceIdAndVersionId(UUID resourceId, UUID versionId);

    @Query("select distinct l.workspaceId from LibraryItem l where l.shareGrantId = :grantId")
    List<UUID> findWorkspaceIdsByShareGrantId(@Param("grantId") UUID grantId);
}
