package com.giaokhoa.subscriptions;

import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projector của gói: dựng desired native graph của một CENTER workspace từ các dòng bền, trong đó
 * các kỳ gói đã xác nhận là cửa sổ hiệu lực của các nguồn cấp quyền mới. Nguồn là các realm group
 * cố định của hợp đồng — members cho mọi thành viên ACTIVE, owners/managers theo role, group role
 * tiền đề cho phụ huynh — và các scope cấp quyền mới (tạo lớp, mời, gán role, cấp học sinh) AND
 * thêm cửa sổ của từng kỳ gói: hết hạn gói thì time policy tự từ chối theo đúng cửa sổ nửa mở
 * [validFrom, expiresAt), không cần cờ active hay cron. Scope thu hồi không qua gate gói để gói hết
 * hạn vẫn thu hồi được.
 *
 * <p>Bundle được dựng lại chỉ từ membership ACTIVE và các kỳ gói đã ghi: membership đã REVOKED
 * không đóng góp nguồn nào, nên xác nhận hay gia hạn gói không bao giờ tự phục hồi một nguồn đã thu
 * hồi — muốn cấp lại phải qua luồng cấp mới của membership. Workspace không phải CENTER không có
 * bundle ở projector này; nguồn personal thuộc về projector riêng của entitlement cá nhân.
 */
@Component
public class SubscriptionPolicyProjector {

    /** Nhóm nguồn của một scope: các group cố định được cấp, kèm cờ gate kỳ gói hay không. */
    private record ScopeSources(List<String> groupKinds, boolean subscriptionGated) {}

    private static final String MEMBERS = "members";
    private static final String OWNERS = "owners";
    private static final String MANAGERS = "managers";
    private static final String PARENTS = "parents";

    // Bảng nguồn cố định theo hợp đồng authorization: owner/manager là nguồn quản trị của
    // workspace, member là nguồn nền của mọi thành viên, parent là nguồn tiền đề của liên kết
    // phụ huynh. MANAGER không bao giờ là nguồn của manager.assign/manager.revoke: bổ nhiệm và
    // gỡ manager chỉ thuộc owner.
    private static final Map<String, ScopeSources> SOURCES_BY_SCOPE = sourcesByScope();

    private static Map<String, ScopeSources> sourcesByScope() {
        Map<String, ScopeSources> sources = new LinkedHashMap<>();
        for (PermissionCatalog.WorkspaceScope scope : PermissionCatalog.WorkspaceScope.values()) {
            String name = scope.scopeName();
            sources.put(
                    name,
                    switch (scope) {
                        case ENTER, READ, MEMBERSHIP_LIST ->
                                new ScopeSources(List.of(MEMBERS), false);
                        case CLASS_LIST,
                                MEMBERSHIP_ROLE_REVOKE,
                                MEMBERSHIP_REVOKE,
                                GUARDIAN_REVOKE ->
                                new ScopeSources(List.of(OWNERS, MANAGERS), false);
                        case CLASS_CREATE,
                                MEMBERSHIP_INVITE,
                                MEMBERSHIP_ROLE_ASSIGN,
                                STUDENT_ISSUE,
                                GUARDIAN_APPROVE ->
                                new ScopeSources(List.of(OWNERS, MANAGERS), true);
                        case MANAGER_ASSIGN -> new ScopeSources(List.of(OWNERS), true);
                        case MANAGER_REVOKE -> new ScopeSources(List.of(OWNERS), false);
                        case GUARDIAN_REQUEST -> new ScopeSources(List.of(PARENTS), false);
                    });
        }
        return Map.copyOf(sources);
    }

    private final JdbcTemplate jdbc;

    public SubscriptionPolicyProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Các canonical source descriptor của một scope trên workspace này, dựng từ dữ kiện nguồn hiện
     * hành. Rỗng khi workspace không phải CENTER hoặc không nguồn nào của scope còn hiệu lực — rỗng
     * nghĩa là scope đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> sourcesFor(UUID workspaceId, String scope) {
        ScopeSources rule = SOURCES_BY_SCOPE.get(scope);
        if (rule == null || !isCenter(workspaceId)) {
            return List.of();
        }
        String targetKey = NativePolicyTemplates.workspaceKey(workspaceId);
        List<String> descriptors = new ArrayList<>();
        for (String groupKind : rule.groupKinds()) {
            if (!groupHasActiveMember(workspaceId, groupKind)) {
                continue;
            }
            String groupPath = groupPath(workspaceId, groupKind);
            if (!rule.subscriptionGated()) {
                descriptors.add(groupDescriptor(workspaceId, groupPath, targetKey, scope));
                continue;
            }
            for (PeriodRow period : periods(workspaceId)) {
                descriptors.add(periodDescriptor(workspaceId, groupPath, period, targetKey, scope));
            }
        }
        return descriptors.stream().sorted().distinct().toList();
    }

    /**
     * Toàn bộ descriptor của bundle workspace: mọi scope của catalog nhân với nguồn hiện hành của
     * chúng. Tập này là desired state trọn vẹn của target; mutation nào lên workspace partition
     * cũng ghi lại cả tập để không scope nào giữ hash của dữ kiện nguồn đã cũ.
     */
    public List<String> bundleDescriptors(UUID workspaceId) {
        List<String> bundle = new ArrayList<>();
        for (String scope : SOURCES_BY_SCOPE.keySet()) {
            bundle.addAll(sourcesFor(workspaceId, scope));
        }
        return bundle.stream().sorted().distinct().toList();
    }

    /**
     * Thay đổi partition cần ghi cho một thay đổi gói trên workspace này: bundle trọn vẹn trên
     * WORKSPACE partition của chính workspace. Rỗng khi workspace không phải CENTER (nguồn personal
     * chưa có bundle ở đây) hoặc khi partition chưa tồn tại.
     */
    public Optional<AuthorizationOperationService.PartitionChange> partitionChange(
            UUID workspaceId) {
        if (!isCenter(workspaceId)) {
            return Optional.empty();
        }
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            return Optional.empty();
        }
        var projection =
                new AuthorizationOperationService.DesiredProjection(
                        NativePolicyTemplates.workspaceKey(workspaceId),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                        bundleDescriptors(workspaceId),
                        List.of());
        return Optional.of(
                new AuthorizationOperationService.PartitionChange(
                        partitions.getFirst(), List.of(projection)));
    }

    private boolean isCenter(UUID workspaceId) {
        return !jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ? AND kind = 'CENTER'",
                        UUID.class,
                        workspaceId)
                .isEmpty();
    }

    private boolean groupHasActiveMember(UUID workspaceId, String groupKind) {
        String sql =
                MEMBERS.equals(groupKind)
                        ? "SELECT id FROM center_membership WHERE workspace_id = ?"
                                + " AND state = 'ACTIVE' LIMIT 1"
                        : "SELECT m.id FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.state = 'ACTIVE'"
                                + " AND r.role = ? LIMIT 1";
        List<UUID> found =
                MEMBERS.equals(groupKind)
                        ? jdbc.queryForList(sql, UUID.class, workspaceId)
                        : jdbc.queryForList(
                                sql,
                                UUID.class,
                                workspaceId,
                                switch (groupKind) {
                                    case OWNERS -> "OWNER";
                                    case MANAGERS -> "MANAGER";
                                    case PARENTS -> "PARENT";
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "Unknown group kind: " + groupKind);
                                });
        return !found.isEmpty();
    }

    private record PeriodRow(UUID id, Instant validFrom, Instant expiresAt) {}

    private List<PeriodRow> periods(UUID workspaceId) {
        return jdbc.query(
                "SELECT id, valid_from, expires_at FROM subscription_period"
                        + " WHERE workspace_id = ? ORDER BY valid_from, id",
                (rs, _) ->
                        new PeriodRow(
                                rs.getObject("id", UUID.class),
                                Objects.requireNonNull(
                                                rs.getObject("valid_from", OffsetDateTime.class))
                                        .toInstant(),
                                Objects.requireNonNull(
                                                rs.getObject("expires_at", OffsetDateTime.class))
                                        .toInstant()),
                workspaceId);
    }

    private static String groupPath(UUID workspaceId, String groupKind) {
        return switch (groupKind) {
            case MEMBERS -> NativePolicyTemplates.membersGroup(workspaceId);
            case OWNERS -> NativePolicyTemplates.ownersGroup(workspaceId);
            case MANAGERS -> NativePolicyTemplates.managersGroup(workspaceId);
            case PARENTS ->
                    NativePolicyTemplates.prerequisiteRoleGroup(
                            workspaceId, NativePolicyTemplates.PrerequisiteRole.PARENTS);
            default -> throw new IllegalArgumentException("Unknown group kind: " + groupKind);
        };
    }

    /** Canonical descriptor của một nguồn group không qua gate gói. */
    public static String groupDescriptor(
            UUID workspaceId, String groupPath, String targetKey, String scope) {
        return "CENTER_GROUP|workspace="
                + workspaceId
                + "|group="
                + groupPath
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }

    /** Canonical descriptor của một nguồn group bị gate bởi một kỳ gói cụ thể. */
    public static String periodDescriptor(
            UUID workspaceId, String groupPath, PeriodRow period, String targetKey, String scope) {
        return "CENTER_GROUP_PERIOD|workspace="
                + workspaceId
                + "|group="
                + groupPath
                + "|period="
                + period.id()
                + "|from="
                + period.validFrom()
                + "|until="
                + period.expiresAt()
                + "|target="
                + targetKey
                + "|scope="
                + scope;
    }
}
