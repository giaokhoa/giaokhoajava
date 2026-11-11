package com.giaokhoa.verification.repository;

import com.giaokhoa.tenancy.model.CenterMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc membership qua entity của module tenancy; repository đầy đủ thuộc tenancy. */
public interface VerificationCenterMembershipRepository
        extends JpaRepository<CenterMembership, UUID> {

    Optional<CenterMembership> findByWorkspaceIdAndUserIdAndState(
            UUID workspaceId, UUID userId, CenterMembership.State state);

    /** Mọi membership ở một trạng thái của workspace, kèm vai trò để tầng gọi lọc theo vai trò. */
    List<CenterMembership> findByWorkspaceIdAndState(
            UUID workspaceId, CenterMembership.State state);
}
