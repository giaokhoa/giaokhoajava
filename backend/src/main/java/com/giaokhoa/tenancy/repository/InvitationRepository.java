package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.Invitation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    /** Đọc khóa ghi theo hash của token trình ra, cho đường chấp nhận lời mời. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invitation i WHERE i.tokenHash = :tokenHash")
    Optional<Invitation> findForUpdateByTokenHash(String tokenHash);
}
