package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleSubmission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SampleSubmissionRepository extends JpaRepository<SampleSubmission, UUID> {

    Optional<SampleSubmission> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    long countByWorkspaceIdAndActivityIdAndStudentUserId(
            UUID workspaceId, UUID activityId, UUID studentUserId);
}
