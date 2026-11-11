package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.ShareGrant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của {@code share_grant} do module resources sở hữu. */
public interface ShareGrantRepository extends JpaRepository<ShareGrant, UUID> {

    List<ShareGrant> findByRecipientWorkspaceId(UUID recipientWorkspaceId);

    /** Resource đã từng được chia sẻ là lịch sử phải bảo lưu, kể cả khi grant đã đóng. */
    boolean existsBySourceResourceId(UUID sourceResourceId);

    List<ShareGrant> findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionId(
            UUID recipientWorkspaceId, UUID sourceResourceId, UUID sourceVersionId);

    List<ShareGrant>
            findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionIdAndStateOrderByCreatedAtAscIdAsc(
                    UUID recipientWorkspaceId,
                    UUID sourceResourceId,
                    UUID sourceVersionId,
                    ShareGrant.State state);

    @Query("select g.recipientWorkspaceId from ShareGrant g where g.id = :id")
    Optional<UUID> findRecipientWorkspaceIdById(@Param("id") UUID id);

    /**
     * Chuyển OFFERED sang ACCEPTED bằng cập nhật có điều kiện trạng thái, giữ đúng giao thức cũ:
     * trả số dòng đã đổi — 0 dòng nghĩa là grant không còn ở trạng thái chờ nhận và caller ánh xạ
     * sang 409. Cột version được tăng tường minh như câu lệnh cũ thay vì đi qua kiểm tra
     * {@code @Version} của entity, để bề mặt lỗi đồng thời không đổi.
     */
    @Modifying
    @Query(
            "update ShareGrant g set g.state = :accepted, g.acceptedAt = :now,"
                    + " g.version = g.version + 1, g.updatedAt = :now"
                    + " where g.id = :id and g.state = :offered")
    int acceptIfOffered(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("offered") ShareGrant.State offered,
            @Param("accepted") ShareGrant.State accepted);

    /**
     * Chuyển ACCEPTED + REVOCABLE sang REVOKED bằng cập nhật có điều kiện, cùng giao thức như
     * {@link #acceptIfOffered}: 0 dòng thì caller ánh xạ sang 409 SHARE_NOT_REVOCABLE.
     */
    @Modifying
    @Query(
            "update ShareGrant g set g.state = :revoked, g.revokedAt = :now,"
                    + " g.version = g.version + 1, g.updatedAt = :now"
                    + " where g.id = :id and g.state = :accepted and g.mode = :revocable")
    int revokeIfAcceptedRevocable(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("accepted") ShareGrant.State accepted,
            @Param("revoked") ShareGrant.State revoked,
            @Param("revocable") ShareGrant.Mode revocable);
}
