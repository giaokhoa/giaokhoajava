package com.giaokhoa.authorization.repository;

import com.giaokhoa.authorization.model.AuthorizationProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthzProjectionRepository
        extends JpaRepository<AuthorizationProjection, UUID>, AuthzProjectionRepositoryCustom {

    List<AuthorizationProjection> findByPartitionIdOrderByTargetKeyAsc(UUID partitionId);

    /** Desired hash hiện hành tại cơ sở dữ liệu, đọc thẳng cột nên không qua entity đã nạp. */
    @Query("select p.desiredHash from AuthorizationProjection p where p.id = :id")
    Optional<String> findDesiredHashById(@Param("id") UUID id);

    Optional<AuthorizationProjection> findByTargetKey(String targetKey);

    /** Mọi projection hiện có của một tập target key, nạp một lần cho đường upsert theo lô. */
    List<AuthorizationProjection> findByTargetKeyIn(Collection<String> targetKeys);

    List<AuthorizationProjection> findByPartitionIdIn(Collection<UUID> partitionIds);

    void deleteByPartitionIdAndTargetKey(UUID partitionId, String targetKey);

    @Modifying
    @Query(
            "update AuthorizationProjection p set p.state = :pending"
                    + " where p.partitionId = :partitionId and p.state = :ready")
    int fenceReadyByPartitionId(
            @Param("partitionId") UUID partitionId,
            @Param("pending") AuthorizationProjection.State pending,
            @Param("ready") AuthorizationProjection.State ready);

    /** Projection còn PENDING khi operation kết thúc FAILED thì chuyển FAILED theo nó. */
    @Modifying
    @Query(
            "update AuthorizationProjection p set p.state = :failed"
                    + " where p.partitionId = :partitionId and p.state = :pending")
    int markFailedIfPendingByPartitionId(
            @Param("partitionId") UUID partitionId,
            @Param("failed") AuthorizationProjection.State failed,
            @Param("pending") AuthorizationProjection.State pending);
}
