package com.giaokhoa.verification.repository;

import com.giaokhoa.resources.model.ResourceVersion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Điểm đọc version qua entity của module resources; repository đầy đủ thuộc resources. */
public interface ResourceVersionRepository extends JpaRepository<ResourceVersion, UUID> {

    /** Version dùng được cho giao bài: thuộc đúng resource, workspace, môn và đã được duyệt. */
    @Query(
            "select count(v) from ResourceVersion v, Resource r"
                    + " where r.id = v.resourceId and v.id = :versionId"
                    + " and v.resourceId = :resourceId and r.ownerWorkspaceId = :workspaceId"
                    + " and r.subjectId = :subjectId and v.reviewState = :reviewState")
    long countUsableVersion(
            @Param("versionId") UUID versionId,
            @Param("resourceId") UUID resourceId,
            @Param("workspaceId") UUID workspaceId,
            @Param("subjectId") UUID subjectId,
            @Param("reviewState") ResourceVersion.ReviewState reviewState);
}
