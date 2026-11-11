package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleActivity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SampleActivityRepository extends JpaRepository<SampleActivity, UUID> {

    Optional<SampleActivity> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    @Query("select a.id from SampleActivity a where a.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
