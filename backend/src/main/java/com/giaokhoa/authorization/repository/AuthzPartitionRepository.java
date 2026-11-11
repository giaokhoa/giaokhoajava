package com.giaokhoa.authorization.repository;

import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.tenancy.model.Workspace;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository của partition authorization. Các điểm đọc UserAccount/Workspace ở cuối là điểm đọc mặt
 * phẳng hệ thống neo theo partition mà mặt phẳng điều khiển cần (xác minh graph, eligibility); khi
 * repository của module directory land, điều phối có thể chuyển các điểm đọc này sang đó mà không
 * đổi chữ ký service của module authorization.
 */
public interface AuthzPartitionRepository extends JpaRepository<AuthorizationPartition, UUID> {

    Optional<AuthorizationPartition> findByKindAndOwnerKey(
            AuthorizationPartition.Kind kind, String ownerKey);

    /** Khóa chia sẻ mọi partition theo thứ tự id: ảnh chụp nhất quán cho fence đọc. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from AuthorizationPartition p where p.id in :ids order by p.id asc")
    List<AuthorizationPartition> lockAllShared(@Param("ids") Collection<UUID> ids);

    /** Khóa ghi mọi partition theo thứ tự id: serialize điểm ghi operation trên cùng partition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AuthorizationPartition p where p.id in :ids order by p.id asc")
    List<AuthorizationPartition> lockAllForUpdate(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AuthorizationPartition p where p.id = :id")
    Optional<AuthorizationPartition> lockById(@Param("id") UUID id);

    @Query("select p.desiredRevision from AuthorizationPartition p where p.id = :id")
    Optional<Long> findDesiredRevisionById(@Param("id") UUID id);

    /** Partition còn PENDING khi operation kết thúc FAILED thì chuyển FAILED theo nó. */
    @Modifying
    @Query(
            "update AuthorizationPartition p set p.state = :failed"
                    + " where p.id = :id and p.state = :pending")
    int markFailedIfPending(
            @Param("id") UUID id,
            @Param("failed") AuthorizationPartition.State failed,
            @Param("pending") AuthorizationPartition.State pending);

    @Query(
            "select p.id from AuthorizationPartition p"
                    + " where p.state = :state order by p.id asc")
    List<UUID> findIdsByStateOrderByIdAsc(@Param("state") AuthorizationPartition.State state);

    /**
     * ID các partition PENDING đã từng có ý định trạng thái (revision đã rời 0 hoặc đã có ít nhất
     * một projection), theo thứ tự id: partition do trigger tạo ở PENDING với revision 0 và chưa
     * một projection nào thì chưa có gì để đối chiếu, nên đường quét orphan không thử.
     */
    @Query(
            "select p.id from AuthorizationPartition p where p.state = :state"
                    + " and (p.desiredRevision > 0 or p.appliedRevision > 0"
                    + " or exists (select 1 from AuthorizationProjection pr"
                    + " where pr.partitionId = p.id)) order by p.id asc")
    List<UUID> findIdsByStateWithIntentOrderByIdAsc(
            @Param("state") AuthorizationPartition.State state);

    @Modifying
    @Query(
            "update AuthorizationPartition p set p.state = :pending,"
                    + " p.updatedAt = current_timestamp"
                    + " where p.id = :id and p.state = :ready")
    int fenceIfReady(
            @Param("id") UUID id,
            @Param("pending") AuthorizationPartition.State pending,
            @Param("ready") AuthorizationPartition.State ready);

    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findAccountById(@Param("id") UUID id);

    @Query("select u.id from UserAccount u where u.keycloakSub = :keycloakSub")
    Optional<UUID> findUserIdByKeycloakSub(@Param("keycloakSub") String keycloakSub);

    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> findWorkspaceById(@Param("id") UUID id);
}
