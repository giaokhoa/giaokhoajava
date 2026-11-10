package com.giaokhoa.authorization.repository;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationOperation.State;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AuthorizationOperationRepository
        extends JpaRepository<AuthorizationOperation, UUID>,
                AuthorizationOperationRepositoryCustom {

    Optional<AuthorizationOperation> findByInitiatorUserIdAndIdempotencyKey(
            UUID initiatorUserId, String idempotencyKey);

    Optional<AuthorizationOperation> findByIdAndInitiatorUserId(UUID id, UUID initiatorUserId);

    /** Cửa sổ quét hàng đợi: operation đang sống theo thứ tự ghi nhận toàn cục. */
    @Query(
            "select o from AuthorizationOperation o"
                    + " where o.state in :states order by o.recordedSeq asc")
    List<AuthorizationOperation> findLiveOrderByRecordedSeq(
            @Param("states") Collection<State> states, Pageable pageable);

    /**
     * Khóa ghi tập operation được claim, bỏ qua operation instance khác đang giữ (hint lock.timeout
     * = -2 tương đương SKIP LOCKED theo Hibernate User Guide §11.4), đồng thời kiểm lại điều kiện
     * claim ngay trong câu khóa.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
            "select o from AuthorizationOperation o"
                    + " where o.id in :ids"
                    + " and ((o.state in :dueStates"
                    + " and (o.nextAttemptAt is null or o.nextAttemptAt <= :now))"
                    + " or (o.state = :applying and o.updatedAt <= :staleBefore))"
                    + " order by o.recordedSeq asc")
    List<AuthorizationOperation> lockClaimableByIds(
            @Param("ids") Collection<UUID> ids,
            @Param("dueStates") Collection<State> dueStates,
            @Param("applying") State applying,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore);

    /** Các mảng partition bị ảnh hưởng của operation đang sống, phục vụ quét partition mồ côi. */
    @Query("select o.affectedPartitions from AuthorizationOperation o where o.state in :states")
    List<List<UUID>> findAffectedPartitionsByStateIn(@Param("states") Collection<State> states);

    @Query(
            "select o.id from AuthorizationOperation o"
                    + " where o.state in :states order by o.createdAt asc, o.id asc")
    List<UUID> findIdsByStateInOrderByCreatedAt(@Param("states") Collection<State> states);

    /** Chuyển SUCCEEDED có điều kiện: chỉ chủ claim hiện tại (đúng attempt) mới được kết thúc. */
    @Modifying
    @Query(
            "update AuthorizationOperation o set o.state = :succeeded, o.nextAttemptAt = null,"
                    + " o.lastErrorCode = null, o.updatedAt = current_timestamp"
                    + " where o.id = :id and o.state = :applying and o.attemptCount = :attemptCount")
    int markSucceeded(
            @Param("id") UUID id,
            @Param("succeeded") State succeeded,
            @Param("applying") State applying,
            @Param("attemptCount") int attemptCount);

    /** Chuyển trạng thái lỗi có điều kiện, cùng kỷ luật chủ claim như {@link #markSucceeded}. */
    @Modifying
    @Query(
            "update AuthorizationOperation o set o.state = :state,"
                    + " o.nextAttemptAt = :nextAttemptAt, o.lastErrorCode = :errorCode,"
                    + " o.updatedAt = current_timestamp"
                    + " where o.id = :id and o.state = :applying and o.attemptCount = :attemptCount")
    int markFailed(
            @Param("id") UUID id,
            @Param("state") State state,
            @Param("nextAttemptAt") @Nullable Instant nextAttemptAt,
            @Param("errorCode") @Nullable String errorCode,
            @Param("applying") State applying,
            @Param("attemptCount") int attemptCount);

    /** Ảnh chụp trạng thái của một yêu cầu khôi phục danh tính cho đường poll operation. */
    interface RecoveryStatus {
        String getState();

        UUID getTargetUserId();
    }

    // Điểm native tạm: bảng recovery_mutation chưa có entity trên main (entity thuộc wave
    // directory+identity); khi entity land, chuyển điểm đọc này sang repository của nó và cho
    // câu native này nghỉ hưu.
    @Query(
            nativeQuery = true,
            value =
                    "SELECT r.state AS \"state\", r.target_user_id AS \"targetUserId\""
                            + " FROM recovery_request r"
                            + " WHERE r.id = :operationId AND (r.requester_user_id = :actor"
                            + " OR EXISTS (SELECT 1 FROM recovery_mutation m"
                            + " WHERE m.request_id = r.id AND m.actor_user_id = :actor"
                            + " AND m.kind = 'RESOLVE'))")
    Optional<RecoveryStatus> findRecoveryStatus(
            @Param("operationId") UUID operationId, @Param("actor") UUID actor);
}
