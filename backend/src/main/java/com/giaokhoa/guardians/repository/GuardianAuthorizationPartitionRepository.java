package com.giaokhoa.guardians.repository;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Điểm đọc partition qua entity của module authorization; repository đầy đủ thuộc authorization.
 */
public interface GuardianAuthorizationPartitionRepository
        extends JpaRepository<AuthorizationPartition, UUID> {

    List<AuthorizationPartition> findByKindAndOwnerKey(
            AuthorizationPartition.Kind kind, String ownerKey);
}
