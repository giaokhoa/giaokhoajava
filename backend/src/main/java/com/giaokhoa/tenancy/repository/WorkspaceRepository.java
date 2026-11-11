package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.Workspace;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    List<Workspace> findByKindAndOwnerUserId(Workspace.Kind kind, UUID ownerUserId);

    List<Workspace> findByIdInAndKindAndState(
            Collection<UUID> ids, Workspace.Kind kind, String state);

    boolean existsByIdAndKind(UUID id, Workspace.Kind kind);
}
