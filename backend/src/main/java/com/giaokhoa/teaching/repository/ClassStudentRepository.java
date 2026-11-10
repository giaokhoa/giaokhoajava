package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.ClassStudent;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository của aggregate dòng roster (class_student). */
public interface ClassStudentRepository extends JpaRepository<ClassStudent, UUID> {

    Optional<ClassStudent> findByWorkspaceIdAndClassIdAndStudentUserIdAndStateIn(
            UUID workspaceId, UUID classId, UUID studentUserId, Collection<String> states);

    /** Bản khóa của dòng roster cho đường mutation: giữ hàng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT cs FROM ClassStudent cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.classId = :classId AND cs.id = :id")
    Optional<ClassStudent> lockByWorkspaceIdAndClassIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("id") UUID id);

    /** Roster hiện hành của một lớp, theo thứ tự vào lớp ổn định. */
    List<ClassStudent> findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId, UUID classId, Collection<String> states);

    /** Một dòng roster theo id trong đúng workspace, bất kể trạng thái. */
    Optional<ClassStudent> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    boolean existsByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** Mốc thời gian của hàng anchor của trang roster; hàng đã thu hồi vẫn làm mốc được. */
    @Query(
            "SELECT cs.createdAt FROM ClassStudent cs WHERE cs.workspaceId = :workspaceId"
                    + " AND cs.classId = :classId AND cs.id = :id")
    Optional<Instant> findCreatedAtByWorkspaceIdAndClassIdAndId(
            @Param("workspaceId") UUID workspaceId,
            @Param("classId") UUID classId,
            @Param("id") UUID id);

    Window<RosterListItem> findByWorkspaceIdAndClassIdAndStateInOrderByCreatedAtAscIdAsc(
            UUID workspaceId,
            UUID classId,
            Collection<String> states,
            ScrollPosition position,
            Limit limit);

    /** Thu hồi mọi dòng roster hiện hành của một người trong workspace, giữ nguyên lịch sử. */
    @Modifying
    @Query(
            "UPDATE ClassStudent cs SET cs.state = 'REVOKED', cs.revokedAt = CURRENT_TIMESTAMP,"
                    + " cs.version = cs.version + 1, cs.updatedAt = CURRENT_TIMESTAMP"
                    + " WHERE cs.workspaceId = :workspaceId AND cs.studentUserId = :studentUserId"
                    + " AND cs.state IN :states")
    int revokeCurrentByWorkspaceIdAndStudentUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("studentUserId") UUID studentUserId,
            @Param("states") Collection<String> states);
}
