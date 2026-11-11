package com.giaokhoa.guardians.repository;

import com.giaokhoa.guardians.model.GuardianLink;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianLinkRepository extends JpaRepository<GuardianLink, UUID> {

    Optional<GuardianLink> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /**
     * Đọc khóa ghi cho đường quyết định/gỡ: hàng bị khóa tới hết transaction như SELECT FOR UPDATE
     * (Spring Data JPA Reference, "Locking": @Lock gắn LockModeType lên query method).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from GuardianLink l where l.workspaceId = :workspaceId and l.id = :id")
    Optional<GuardianLink> findByWorkspaceIdAndIdForUpdate(
            @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

    boolean existsByWorkspaceIdAndParentUserIdAndStudentUserIdAndStateIn(
            UUID workspaceId, UUID parentUserId, UUID studentUserId, Collection<String> states);

    List<GuardianLink> findByWorkspaceIdAndParentUserIdOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID parentUserId);

    List<GuardianLink> findByWorkspaceIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, Collection<String> states);

    List<GuardianLink> findByWorkspaceIdAndParentUserIdAndState(
            UUID workspaceId, UUID parentUserId, String state);

    List<GuardianLink> findByWorkspaceIdAndStudentUserIdAndState(
            UUID workspaceId, UUID studentUserId, String state);

    /** Mọi học sinh từng có liên kết trong workspace, không trùng. */
    @Query(
            "select distinct l.studentUserId from GuardianLink l"
                    + " where l.workspaceId = :workspaceId")
    List<UUID> findDistinctStudentUserIds(@Param("workspaceId") UUID workspaceId);

    /** Mọi phụ huynh từng có liên kết trong workspace, không trùng. */
    @Query(
            "select distinct l.parentUserId from GuardianLink l"
                    + " where l.workspaceId = :workspaceId")
    List<UUID> findDistinctParentUserIds(@Param("workspaceId") UUID workspaceId);
}
