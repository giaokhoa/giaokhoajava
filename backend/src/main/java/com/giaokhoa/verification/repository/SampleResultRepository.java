package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleResult;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SampleResultRepository extends JpaRepository<SampleResult, UUID> {

    Optional<SampleResult> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** Đọc khóa ghi cho đường chấm/công bố: hàng bị khóa tới hết transaction như FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SampleResult r where r.workspaceId = :workspaceId and r.id = :id")
    Optional<SampleResult> findByWorkspaceIdAndIdForUpdate(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    @Query("select r.id from SampleResult r where r.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** Neo của một cursor phân trang: đúng một kết quả đã công bố của đúng học sinh. */
    Optional<SampleResultCandidateView> findByWorkspaceIdAndStudentUserIdAndStateAndId(
            UUID workspaceId, UUID studentUserId, String state, UUID id);

    /** Batch quét đầu tiên của đường đọc đã công bố, mới nhất trước. */
    @Query(
            "select r.id as id, r.publishedAt as publishedAt from SampleResult r"
                    + " where r.workspaceId = :workspaceId and r.studentUserId = :studentUserId"
                    + " and r.state = 'PUBLISHED'"
                    + " order by r.publishedAt desc, r.id desc")
    List<SampleResultCandidateView> findPublishedBatch(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            Pageable pageable);

    /** Batch quét tiếp theo sau neo keyset (published_at, id), cùng thứ tự với batch đầu. */
    @Query(
            "select r.id as id, r.publishedAt as publishedAt from SampleResult r"
                    + " where r.workspaceId = :workspaceId and r.studentUserId = :studentUserId"
                    + " and r.state = 'PUBLISHED'"
                    + " and (r.publishedAt < :anchorAt"
                    + " or (r.publishedAt = :anchorAt and r.id < :anchorId))"
                    + " order by r.publishedAt desc, r.id desc")
    List<SampleResultCandidateView> findPublishedBatchAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            @Param("anchorAt") Instant anchorAt,
            @Param("anchorId") UUID anchorId,
            Pageable pageable);

    /** Các kết quả đã công bố của một trang đã được PDP cho phép, giữ thứ tự hiển thị. */
    @Query(
            "select r from SampleResult r"
                    + " where r.workspaceId = :workspaceId and r.studentUserId = :studentUserId"
                    + " and r.state = 'PUBLISHED' and r.id in :ids"
                    + " order by r.publishedAt desc, r.id desc")
    List<SampleResult> findPublishedByIds(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            @Param("ids") List<UUID> ids);
}
