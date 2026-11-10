package com.giaokhoa.identity.repository;

import com.giaokhoa.identity.model.RecoveryRequest;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RecoveryRequestRepository extends JpaRepository<RecoveryRequest, UUID> {
    List<RecoveryRequest> findAllByOrderByCreatedAtAscIdAsc();

    /** Đọc khóa ghi cho đường resolve: version được kiểm If-Match trên chính hàng đã khóa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RecoveryRequest r WHERE r.id = :id")
    Optional<RecoveryRequest> findForUpdateById(UUID id);
}
