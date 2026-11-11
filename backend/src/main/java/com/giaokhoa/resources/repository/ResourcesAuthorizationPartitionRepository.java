package com.giaokhoa.resources.repository;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Điểm truy cập của module resources trên {@code authz_partition} thuộc module authorization: tra
 * partition theo (kind, owner_key) để ghi operation, và tạo GRANT partition lười cho một grant ở
 * lần ghi đầu.
 */
public interface ResourcesAuthorizationPartitionRepository
        extends JpaRepository<AuthorizationPartition, UUID> {

    Optional<AuthorizationPartition> findByKindAndOwnerKey(
            AuthorizationPartition.Kind kind, String ownerKey);

    List<AuthorizationPartition> findByKindAndOwnerKeyIn(
            AuthorizationPartition.Kind kind, Collection<String> ownerKeys);
}
