package com.giaokhoa.tenancy.service;

import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư mục workspace của một actor: chỉ trả nơi hợp lệ — personal workspace của chính actor và các
 * CENTER workspace mà actor còn membership ACTIVE. Không trả metadata của center (tên, chủ sở hữu):
 * danh sách này là dữ kiện điều hướng của actor, không phải kênh lộ thông tin center cho người
 * ngoài. Role luôn trả theo từng workspace: cùng một account có thể là MANAGER ở A và STUDENT ở B
 * mà hai vai trò không cộng thành quyền tổng.
 *
 * <p>Membership là dữ kiện thuộc schema tenant còn workspace thuộc schema hệ thống, nên các đường
 * đọc ở đây luôn hai bước theo ID (membership hiện hành trước, workspace sau) thay vì một join
 * xuyên hai mặt phẳng như trước.
 */
@Service
public class WorkspaceDirectoryService {
    private static final String ACTIVE = "ACTIVE";

    private final WorkspaceRepository workspaces;
    private final CenterMembershipRepository memberships;

    public WorkspaceDirectoryService(
            WorkspaceRepository workspaces, CenterMembershipRepository memberships) {
        this.workspaces = workspaces;
        this.memberships = memberships;
    }

    /** Một nơi hợp lệ trong thư mục của actor: chỉ id, kind, state và center liên kết. */
    public record ListedWorkspace(
            UUID id, Workspace.Kind kind, String state, @Nullable UUID centerId) {}

    /**
     * Mọi nơi hợp lệ của actor: personal workspace trước, rồi các center theo id ổn định. Chỉ
     * workspace ACTIVE và membership ACTIVE mới hợp lệ; membership PENDING hay REVOKED không mở
     * quyền vào.
     */
    @Transactional(readOnly = true)
    public List<ListedWorkspace> listWorkspaces(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        List<ListedWorkspace> listed = new ArrayList<>();
        for (Workspace personal :
                workspaces.findByKindAndOwnerUserId(Workspace.Kind.PERSONAL, actorUserId)) {
            if (ACTIVE.equals(personal.getState())) {
                listed.add(listed(personal));
            }
        }
        List<UUID> centerWorkspaceIds =
                memberships
                        .findByUserIdAndState(actorUserId, CenterMembership.State.ACTIVE)
                        .stream()
                        .map(CenterMembership::getWorkspaceId)
                        .distinct()
                        .toList();
        workspaces
                .findByIdInAndKindAndState(centerWorkspaceIds, Workspace.Kind.CENTER, ACTIVE)
                .stream()
                // Thứ tự id theo đúng cách PostgreSQL so sánh uuid (theo byte), không theo
                // UUID.compareTo của Java vốn so sánh long có dấu.
                .sorted(Comparator.comparing(workspace -> workspace.getId().toString()))
                .forEach(workspace -> listed.add(listed(workspace)));
        return List.copyOf(listed);
    }

    /** Personal workspace của actor, nếu account đã được bootstrap. */
    @Transactional(readOnly = true)
    public Optional<UUID> personalWorkspace(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        return workspaces.findByKindAndOwnerUserId(Workspace.Kind.PERSONAL, actorUserId).stream()
                .findFirst()
                .map(Workspace::getId);
    }

    /**
     * Role của actor trong một workspace cụ thể, chỉ tính membership ACTIVE. Trả rỗng khi actor
     * không còn là thành viên: rời một center là mất role ở đó, không kéo theo role ở nơi khác.
     */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> rolesIn(UUID actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, actorUserId, CenterMembership.State.ACTIVE)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    /**
     * Role của actor tại nhiều workspace trong một lần đọc, cho các màn hình liệt kê: workspace
     * không có membership ACTIVE thì vắng mặt trong map trả về.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Set<MembershipRole.Role>> rolesIn(
            UUID actorUserId, Collection<UUID> workspaceIds) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceIds, "workspaceIds");
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<MembershipRole.Role>> roles = new HashMap<>();
        for (CenterMembership membership :
                memberships.findByUserIdAndStateAndWorkspaceIdIn(
                        actorUserId, CenterMembership.State.ACTIVE, workspaceIds)) {
            if (!membership.getRoles().isEmpty()) {
                roles.computeIfAbsent(
                                membership.getWorkspaceId(),
                                _ -> EnumSet.noneOf(MembershipRole.Role.class))
                        .addAll(membership.getRoles());
            }
        }
        return roles;
    }

    private static ListedWorkspace listed(Workspace workspace) {
        return new ListedWorkspace(
                workspace.getId(),
                workspace.getKind(),
                workspace.getState(),
                workspace.getCenterId());
    }
}
