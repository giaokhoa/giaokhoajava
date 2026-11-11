package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleActivity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SampleActivityRepository extends JpaRepository<SampleActivity, UUID> {

    Optional<SampleActivity> findByWorkspaceIdAndId(UUID workspaceId, UUID id);
}
