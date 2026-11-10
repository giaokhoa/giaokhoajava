package com.giaokhoa.guardians.repository;

import com.giaokhoa.tenancy.model.Workspace;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc workspace qua entity của module tenancy; repository đầy đủ thuộc tenancy. */
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {}
