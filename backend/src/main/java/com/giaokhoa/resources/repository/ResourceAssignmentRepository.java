package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.ResourceAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của {@code resource_assignment} do module resources sở hữu. */
public interface ResourceAssignmentRepository extends JpaRepository<ResourceAssignment, UUID> {

    /**
     * Các phân công ở một trạng thái khớp một resource: gán thẳng theo resource (kèm môn khớp hoặc
     * không gắn môn) hoặc gán theo môn của resource. Đây là phép khớp dùng chung cho trần quyền
     * nguồn khi chia sẻ và cho nhánh phân công của projector.
     */
    @Query(
            "select a from ResourceAssignment a where a.workspaceId = :workspaceId"
                    + " and a.state = :state"
                    + " and ((a.resourceId = :resourceId"
                    + " and (a.subjectId is null or a.subjectId = :subjectId))"
                    + " or (a.resourceId is null and a.subjectId = :subjectId))")
    List<ResourceAssignment> findMatching(
            @Param("workspaceId") UUID workspaceId,
            @Param("state") ResourceAssignment.State state,
            @Param("resourceId") UUID resourceId,
            @Param("subjectId") UUID subjectId);

    /** Như {@link #findMatching} nhưng chỉ các phân công của đúng một người dùng (trần quyền). */
    @Query(
            "select a from ResourceAssignment a where a.workspaceId = :workspaceId"
                    + " and a.userId = :userId and a.state = :state"
                    + " and ((a.resourceId = :resourceId"
                    + " and (a.subjectId is null or a.subjectId = :subjectId))"
                    + " or (a.resourceId is null and a.subjectId = :subjectId))")
    List<ResourceAssignment> findMatchingForUser(
            @Param("workspaceId") UUID workspaceId,
            @Param("userId") UUID userId,
            @Param("state") ResourceAssignment.State state,
            @Param("resourceId") UUID resourceId,
            @Param("subjectId") UUID subjectId);

    List<ResourceAssignment> findByWorkspaceIdAndStateAndResourceIdIsNotNull(
            UUID workspaceId, ResourceAssignment.State state);

    List<ResourceAssignment> findByWorkspaceIdAndStateAndResourceIdIsNull(
            UUID workspaceId, ResourceAssignment.State state);
}
