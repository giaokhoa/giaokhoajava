package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.ResourceDependency;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của {@code resource_dependency} do module resources sở hữu. */
public interface ResourceDependencyRepository extends JpaRepository<ResourceDependency, UUID> {

    List<ResourceDependency> findByDerivedResourceIdAndDerivedVersionId(
            UUID derivedResourceId, UUID derivedVersionId);

    List<ResourceDependency> findByDerivedResourceIdIn(Collection<UUID> derivedResourceIds);

    /** Nạp một tầng của phép duyệt closure: mọi cạnh có đầu derived thuộc tập version cho trước. */
    List<ResourceDependency> findByDerivedVersionIdIn(Collection<UUID> derivedVersionIds);

    List<ResourceDependency> findByShareGrantId(UUID shareGrantId);

    List<ResourceDependency> findBySourceResourceIdAndSourceVersionIdAndShareGrantId(
            UUID sourceResourceId, UUID sourceVersionId, UUID shareGrantId);

    /**
     * Mọi cạnh provenance trỏ từ một (resource, version) nguồn, xuyên mọi grant: bề mặt cho
     * fan-out đính chính tới các workspace đang giữ bản sao hoặc bản phái sinh của version nguồn.
     */
    List<ResourceDependency> findBySourceResourceIdAndSourceVersionId(
            UUID sourceResourceId, UUID sourceVersionId);

    /**
     * Cạnh provenance theo grant sớm nhất của một version phái sinh: cạnh mang grant và dấu nguồn
     * do fork ({@code origin=FORK}) hoặc copy-on-share ({@code origin=SHARE}) ghi. Pageable giới
     * hạn một dòng sau khi sắp theo thứ tự tạo, đúng phép lấy LIMIT 1 của đường JDBC cũ.
     */
    @Query(
            "select d from ResourceDependency d where d.derivedResourceId = :resourceId"
                    + " and d.derivedVersionId = :versionId and d.shareGrantId is not null"
                    + " and (d.usageConstraints like 'origin=FORK%'"
                    + " or d.usageConstraints like 'origin=SHARE%')"
                    + " order by d.createdAt asc, d.id asc")
    List<ResourceDependency> findGrantProvenance(
            @Param("resourceId") UUID resourceId,
            @Param("versionId") UUID versionId,
            Pageable pageable);

    /** Version này có phải bản phái sinh theo một grant nào không (cạnh provenance mang grant). */
    @Query(
            "select count(d) > 0 from ResourceDependency d where d.derivedResourceId = :resourceId"
                    + " and d.derivedVersionId = :versionId and d.shareGrantId is not null"
                    + " and (d.usageConstraints like 'origin=FORK%'"
                    + " or d.usageConstraints like 'origin=SHARE%')")
    boolean existsGrantProvenance(
            @Param("resourceId") UUID resourceId, @Param("versionId") UUID versionId);

    /** Cạnh provenance copy-on-share của đúng một version sao (dấu {@code origin=SHARE}). */
    @Query(
            "select d from ResourceDependency d where d.derivedResourceId = :resourceId"
                    + " and d.derivedVersionId = :versionId and d.shareGrantId is not null"
                    + " and d.usageConstraints like 'origin=SHARE%'"
                    + " order by d.createdAt asc, d.id asc")
    List<ResourceDependency> findShareProvenance(
            @Param("resourceId") UUID resourceId,
            @Param("versionId") UUID versionId,
            Pageable pageable);
}
