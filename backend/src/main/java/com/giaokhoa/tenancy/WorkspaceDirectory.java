package com.giaokhoa.tenancy;

import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thư mục workspace của một actor: chỉ trả nơi hợp lệ — personal workspace của chính actor và các
 * CENTER workspace mà actor còn membership ACTIVE. Không trả metadata của center (tên, chủ sở hữu):
 * danh sách này là dữ kiện điều hướng của actor, không phải kênh lộ thông tin center cho người
 * ngoài. Role luôn trả theo từng workspace: cùng một account có thể là MANAGER ở A và STUDENT ở B
 * mà hai vai trò không cộng thành quyền tổng.
 */
@Service
public class WorkspaceDirectory {
    private final JdbcTemplate jdbc;

    public WorkspaceDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc.query(
                "SELECT w.id, w.kind, w.state, w.center_id FROM workspace w WHERE w.state = 'ACTIVE'"
                        + " AND ((w.kind = 'PERSONAL' AND w.owner_user_id = ?)"
                        + " OR (w.kind = 'CENTER' AND EXISTS (SELECT 1 FROM center_membership m"
                        + " WHERE m.workspace_id = w.id AND m.user_id = ? AND m.state = 'ACTIVE')))"
                        + " ORDER BY CASE w.kind WHEN 'PERSONAL' THEN 0 ELSE 1 END, w.id",
                (rows, _) ->
                        new ListedWorkspace(
                                (UUID) Objects.requireNonNull(rows.getObject("id"), "id"),
                                Workspace.Kind.valueOf(
                                        (String)
                                                Objects.requireNonNull(
                                                        rows.getObject("kind"), "kind")),
                                (String) Objects.requireNonNull(rows.getObject("state"), "state"),
                                (UUID) rows.getObject("center_id")),
                actorUserId,
                actorUserId);
    }

    /** Personal workspace của actor, nếu account đã được bootstrap. */
    @Transactional(readOnly = true)
    public Optional<UUID> personalWorkspace(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        actorUserId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /**
     * Role của actor trong một workspace cụ thể, chỉ tính membership ACTIVE. Trả rỗng khi actor
     * không còn là thành viên: rời một center là mất role ở đó, không kéo theo role ở nơi khác.
     */
    @Transactional(readOnly = true)
    public Set<MembershipRole.Role> rolesIn(UUID actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        return Set.copyOf(
                jdbc
                        .queryForList(
                                "SELECT r.role FROM center_membership m"
                                        + " JOIN membership_role r ON r.membership_id = m.id"
                                        + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                        + " AND m.state = 'ACTIVE'",
                                String.class,
                                workspaceId,
                                actorUserId)
                        .stream()
                        .map(MembershipRole.Role::valueOf)
                        .toList());
    }

    /**
     * Role của actor tại nhiều workspace bằng một truy vấn duy nhất, cho các màn hình liệt kê:
     * workspace không có membership ACTIVE thì vắng mặt trong map trả về.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Set<MembershipRole.Role>> rolesIn(
            UUID actorUserId, Collection<UUID> workspaceIds) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(workspaceIds, "workspaceIds");
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = workspaceIds.stream().map(_ -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(workspaceIds.size() + 1);
        args.add(actorUserId);
        args.addAll(workspaceIds);
        Map<UUID, Set<MembershipRole.Role>> roles = new HashMap<>();
        jdbc.query(
                "SELECT m.workspace_id, r.role FROM center_membership m"
                        + " JOIN membership_role r ON r.membership_id = m.id"
                        + " WHERE m.user_id = ? AND m.state = 'ACTIVE' AND m.workspace_id IN ("
                        + placeholders
                        + ")",
                rs -> {
                    UUID workspaceId = (UUID) rs.getObject("workspace_id");
                    MembershipRole.Role role = MembershipRole.Role.valueOf(rs.getString("role"));
                    roles.computeIfAbsent(
                                    workspaceId, _ -> EnumSet.noneOf(MembershipRole.Role.class))
                            .add(role);
                },
                args.toArray());
        return roles;
    }
}
