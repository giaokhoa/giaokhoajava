package com.giaokhoa.verification.repository;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Điểm đọc operation qua entity của module authorization; repository đầy đủ thuộc authorization.
 */
public interface VerificationAuthorizationOperationRepository
        extends JpaRepository<AuthorizationOperation, UUID> {

    /** Trạng thái hiện hành của một operation, cho vòng chờ chiếu của sample action. */
    @Query("select o.state from AuthorizationOperation o where o.id = :id")
    Optional<AuthorizationOperation.State> findStateById(@Param("id") UUID id);
}
