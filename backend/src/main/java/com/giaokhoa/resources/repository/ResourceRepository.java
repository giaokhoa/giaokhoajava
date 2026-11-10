package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.Resource;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate {@code resource} do module resources sở hữu. */
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    @Query("select r.id from Resource r where r.ownerWorkspaceId = :workspaceId")
    List<UUID> findIdsByOwnerWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("select r.id from Resource r where r.subjectId in :subjectIds")
    List<UUID> findIdsBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    @Query("select r.ownerWorkspaceId from Resource r where r.id in :ids")
    List<UUID> findOwnerWorkspaceIdsByIdIn(@Param("ids") Collection<UUID> ids);
}
