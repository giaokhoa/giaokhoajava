package com.giaokhoa.resources.repository;

import com.giaokhoa.verification.model.SampleActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Điểm đọc của module resources trên {@code sample_activity} thuộc module verification: nhánh học
 * sinh của projector chỉ cần môn–khóa có hoạt động ASSIGNED trên đúng version và tập cặp (resource,
 * version) đã giao tại một workspace.
 */
public interface ResourcesSampleActivityRepository extends Repository<SampleActivity, UUID> {

    @Query(
            "select distinct a.courseSubjectId from SampleActivity a"
                    + " where a.workspaceId = :workspaceId and a.resourceId = :resourceId"
                    + " and a.versionId = :versionId and a.state = 'ASSIGNED'")
    List<UUID> findAssignedCourseSubjectIds(
            @Param("workspaceId") UUID workspaceId,
            @Param("resourceId") UUID resourceId,
            @Param("versionId") UUID versionId);

    /** Chiếu cặp định danh của một hoạt động đã giao, cho phép liệt kê target của projector. */
    interface ActivityPair {
        UUID getResourceId();

        UUID getVersionId();
    }

    List<ActivityPair> findDistinctByWorkspaceId(UUID workspaceId);
}
