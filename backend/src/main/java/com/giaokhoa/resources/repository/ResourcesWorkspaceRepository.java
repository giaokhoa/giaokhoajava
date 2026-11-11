package com.giaokhoa.resources.repository;

import com.giaokhoa.tenancy.model.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Điểm đọc của module resources trên entity {@code workspace} thuộc module tenancy: chỉ khai báo
 * đúng các phép đọc module này cần, không nhân bản repository của module sở hữu.
 */
public interface ResourcesWorkspaceRepository extends Repository<Workspace, UUID> {

    Optional<Workspace> findById(UUID id);

    boolean existsById(UUID id);

    /**
     * Personal workspace của một chủ sở hữu — nơi hàng lịch sử luyện tập hệ thống luôn thuộc về.
     */
    Optional<Workspace> findByOwnerUserIdAndKind(UUID ownerUserId, Workspace.Kind kind);
}
