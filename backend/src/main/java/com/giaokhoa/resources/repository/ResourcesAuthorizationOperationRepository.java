package com.giaokhoa.resources.repository;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Điểm đọc của module resources trên {@code authorization_operation} thuộc module authorization:
 * chỉ trạng thái của operation, phục vụ vòng chờ chiếu (fail-closed) của các service resource.
 */
public interface ResourcesAuthorizationOperationRepository
        extends Repository<AuthorizationOperation, UUID> {

    @Query("select o.state from AuthorizationOperation o where o.id = :id")
    Optional<AuthorizationOperation.State> findStateById(@Param("id") UUID id);
}
