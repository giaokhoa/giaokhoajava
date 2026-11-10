package com.giaokhoa.tenancy.repository;

import com.giaokhoa.tenancy.model.Center;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CenterRepository extends JpaRepository<Center, UUID> {
    List<Center> findAllByOrderByCreatedAtAscIdAsc(Pageable pageable);

    /** Trang keyset sau mốc (created_at, id) của cursor, đúng thứ tự của trang đầu. */
    @Query(
            "SELECT c FROM Center c"
                    + " WHERE c.createdAt > :createdAt"
                    + " OR (c.createdAt = :createdAt AND c.id > :anchorId)"
                    + " ORDER BY c.createdAt ASC, c.id ASC")
    List<Center> findAfter(Instant createdAt, UUID anchorId, Pageable pageable);
}
