package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.ResourceVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của {@code resource_version} do module resources sở hữu. */
public interface ResourceVersionRepository extends JpaRepository<ResourceVersion, UUID> {

    List<ResourceVersion> findByResourceIdIn(Collection<UUID> resourceIds);

    /** Mọi định danh version của một resource, cho phép kiểm tra lịch sử theo từng version. */
    @Query("select v.id from ResourceVersion v where v.resourceId = :resourceId")
    List<UUID> findIdsByResourceId(@Param("resourceId") UUID resourceId);

    Optional<ResourceVersion> findByResourceIdAndId(UUID resourceId, UUID id);

    Optional<ResourceVersion> findByResourceIdAndVersionNo(UUID resourceId, int versionNo);

    boolean existsByResourceIdAndId(UUID resourceId, UUID id);

    /** Số version lớn nhất hiện có của resource; 0 khi chưa có version nào (để thêm bản sao). */
    @Query(
            "select coalesce(max(v.versionNo), 0) from ResourceVersion v"
                    + " where v.resourceId = :resourceId")
    int maxVersionNo(@Param("resourceId") UUID resourceId);
}
