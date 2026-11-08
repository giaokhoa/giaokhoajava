package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projector của center: nguồn sự thật duy nhất về desired native graph của một CENTER workspace,
 * dựng thuần từ các dòng bền (membership/role hiện hành và các kỳ gói đã xác nhận). Nguồn là các
 * realm group cố định của hợp đồng — members cho mọi thành viên ACTIVE, owners/managers theo role,
 * group role tiền đề cho phụ huynh — và các scope cấp quyền mới (tạo lớp, mời, gán role, cấp học
 * sinh) còn AND thêm cửa sổ hiệu lực của từng kỳ gói: hết hạn gói thì time policy tự từ chối, không
 * cần cờ active hay cron. Scope thu hồi không qua gate gói để gói hết hạn vẫn thu hồi được.
 *
 * <p>Desired graph của cả workspace được ghi thành một projection bundle duy nhất trên target
 * {@code workspace:{id}}: tập descriptor canonical của mọi scope. Projector này vừa tính tập đó khi
 * ghi (qua {@link CenterService}), vừa dựng lại đúng tập đó khi {@code ProjectionReconciler} đối
 * chiếu desired hash, và đồng bộ membership thật của từng user vào các realm group khi reconcile —
 * group policy chỉ cấp cho người thật sự đang ở trong group.
 */
@Component
public class CenterPolicyProjector {

    /** Nhóm nguồn của một scope: các group cố định được cấp, kèm cờ gate kỳ gói hay không. */
    private record ScopeSources(List<String> groupKinds, boolean subscriptionGated) {}

    private static final String MEMBERS = "members";
    private static final String OWNERS = "owners";
    private static final String MANAGERS = "managers";
    private static final String PARENTS = "parents";
    private static final String GROUP_DESCRIPTOR = "CENTER_GROUP";
    private static final String PERIOD_DESCRIPTOR = "CENTER_GROUP_PERIOD";

    // Bảng nguồn cố định theo hợp đồng authorization: owner/manager là nguồn quản trị của
    // workspace, member là nguồn nền của mọi thành viên, parent là nguồn tiền đề của liên kết
    // phụ huynh. MANAGER không bao giờ là nguồn của manager.assign/manager.revoke: bổ nhiệm và
    // gỡ manager chỉ thuộc owner.
    private static final Map<String, ScopeSources> SOURCES_BY_SCOPE = sourcesByScope();

    private static Map<String, ScopeSources> sourcesByScope() {
        Map<String, ScopeSources> sources = new LinkedHashMap<>();
        PermissionCatalog.WorkspaceScope[] scopes = PermissionCatalog.WorkspaceScope.values();
        for (PermissionCatalog.WorkspaceScope scope : scopes) {
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
    private final KeycloakProvisioningClient directory;

    public CenterPolicyProjector(JdbcTemplate jdbc, KeycloakProvisioningClient directory) {
        this.jdbc = jdbc;
        this.directory = directory;
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
            for (SubscriptionPeriodRow period : subscriptionPeriods(workspaceId)) {
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

    /** Các scope mà projector này quản lý cho workspace target, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return List.copyOf(SOURCES_BY_SCOPE.keySet());
    }

    /**
     * Đưa membership thật trong các realm group của workspace về đúng dữ kiện nguồn: mọi user từng
     * có membership ở workspace này thuộc đúng các group mà membership ACTIVE và role hiện hành của
     * họ suy ra, không thuộc group nào khác của workspace. Membership REVOKED nghĩa là không thuộc
     * group nào. Chỉ ghi khi live đang lệch, để reconcile không đổi gì vẫn là kiểm chứng đọc.
     */
    public void syncGroupMemberships(UUID workspaceId) {
        List<String> groupPaths =
                List.of(
                        NativePolicyTemplates.membersGroup(workspaceId),
                        NativePolicyTemplates.ownersGroup(workspaceId),
                        NativePolicyTemplates.managersGroup(workspaceId),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                workspaceId, NativePolicyTemplates.PrerequisiteRole.TEACHERS),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                workspaceId, NativePolicyTemplates.PrerequisiteRole.STUDENTS),
                        NativePolicyTemplates.prerequisiteRoleGroup(
                                workspaceId, NativePolicyTemplates.PrerequisiteRole.PARENTS));
        List<Map<String, Object>> members =
                jdbc.queryForList(
                        "SELECT DISTINCT m.user_id, u.keycloak_sub FROM center_membership m"
                                + " JOIN user_account u ON u.id = m.user_id"
                                + " WHERE m.workspace_id = ?",
                        workspaceId);
        for (Map<String, Object> member : members) {
            UUID userId = (UUID) Objects.requireNonNull(member.get("user_id"), "user_id");
            String subject =
                    Objects.requireNonNull((String) member.get("keycloak_sub"), "keycloak_sub");
            Set<String> desired = desiredGroups(workspaceId, userId);
            for (String groupPath : groupPaths) {
                boolean shouldBeMember = desired.contains(groupPath);
                if (directory.isRealmGroupMember(subject, groupPath) != shouldBeMember) {
                    directory.setRealmGroupMembership(subject, groupPath, shouldBeMember);
                }
            }
        }
    }

    private Set<String> desiredGroups(UUID workspaceId, UUID userId) {
        Set<String> groups = new java.util.HashSet<>();
        if (jdbc.queryForList(
                        "SELECT id FROM center_membership WHERE workspace_id = ? AND user_id = ?"
                                + " AND state = 'ACTIVE'",
                        UUID.class,
                        workspaceId,
                        userId)
                .isEmpty()) {
            return Set.of();
        }
        groups.add(NativePolicyTemplates.membersGroup(workspaceId));
        Set<MembershipRole.Role> roles = EnumSet.noneOf(MembershipRole.Role.class);
        jdbc.queryForList(
                        "SELECT r.role FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                + " AND m.state = 'ACTIVE'",
                        String.class,
                        workspaceId,
                        userId)
                .forEach(role -> roles.add(MembershipRole.Role.valueOf(role)));
        if (roles.contains(MembershipRole.Role.OWNER)) {
            groups.add(NativePolicyTemplates.ownersGroup(workspaceId));
        }
        if (roles.contains(MembershipRole.Role.MANAGER)) {
            groups.add(NativePolicyTemplates.managersGroup(workspaceId));
        }
        if (roles.contains(MembershipRole.Role.TEACHER)) {
            groups.add(
                    NativePolicyTemplates.prerequisiteRoleGroup(
                            workspaceId, NativePolicyTemplates.PrerequisiteRole.TEACHERS));
        }
        if (roles.contains(MembershipRole.Role.STUDENT)) {
            groups.add(
                    NativePolicyTemplates.prerequisiteRoleGroup(
                            workspaceId, NativePolicyTemplates.PrerequisiteRole.STUDENTS));
        }
        if (roles.contains(MembershipRole.Role.PARENT)) {
            groups.add(
                    NativePolicyTemplates.prerequisiteRoleGroup(
                            workspaceId, NativePolicyTemplates.PrerequisiteRole.PARENTS));
        }
        return groups;
    }

    private boolean isCenter(UUID workspaceId) {
        return !jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ? AND kind = 'CENTER'",
                        UUID.class,
                        workspaceId)
                .isEmpty();
    }

    private boolean groupHasActiveMember(UUID workspaceId, String groupKind) {
        String condition =
                switch (groupKind) {
                    case MEMBERS -> "";
                    case OWNERS -> " AND r.role = 'OWNER'";
                    case MANAGERS -> " AND r.role = 'MANAGER'";
                    case PARENTS -> " AND r.role = 'PARENT'";
                    default ->
                            throw new IllegalArgumentException("Unknown group kind: " + groupKind);
                };
        String sql =
                MEMBERS.equals(groupKind)
                        ? "SELECT id FROM center_membership WHERE workspace_id = ?"
                                + " AND state = 'ACTIVE' LIMIT 1"
                        : "SELECT m.id FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = ? AND m.state = 'ACTIVE'"
                                + condition
                                + " LIMIT 1";
        return !jdbc.queryForList(sql, UUID.class, workspaceId).isEmpty();
    }

    private record SubscriptionPeriodRow(UUID id, Instant validFrom, Instant expiresAt) {}

    private List<SubscriptionPeriodRow> subscriptionPeriods(UUID workspaceId) {
        return jdbc.query(
                "SELECT id, valid_from, expires_at FROM subscription_period"
                        + " WHERE workspace_id = ? ORDER BY valid_from, id",
                (rs, _) ->
                        new SubscriptionPeriodRow(
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
        return GROUP_DESCRIPTOR
                + "|workspace="
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
            UUID workspaceId,
            String groupPath,
            SubscriptionPeriodRow period,
            String targetKey,
            String scope) {
        return PERIOD_DESCRIPTOR
                + "|workspace="
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

    /**
     * Các gate native của một canonical descriptor của center: group nguồn, cộng cửa sổ hiệu lực
     * khi nguồn bị gate bởi kỳ gói. Dùng bởi reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !(GROUP_DESCRIPTOR.equals(parts[0]) || PERIOD_DESCRIPTOR.equals(parts[0]))) {
            throw new IllegalArgumentException("Unsupported center descriptor: " + descriptor);
        }
        String groupPath = null;
        Instant validFrom = null;
        Instant expiresAt = null;
        for (String part : parts) {
            if (part.startsWith("group=")) {
                groupPath = part.substring("group=".length());
            } else if (part.startsWith("from=")) {
                validFrom = Instant.parse(part.substring("from=".length()));
            } else if (part.startsWith("until=")) {
                expiresAt = Instant.parse(part.substring("until=".length()));
            }
        }
        if (groupPath == null || groupPath.isBlank()) {
            throw new IllegalArgumentException("Center descriptor has no group: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        gates.add(new PolicyGraph.Gate.GroupMembership(groupPath));
        boolean periodGated = PERIOD_DESCRIPTOR.equals(parts[0]);
        if (periodGated) {
            gates.add(new PolicyGraph.Gate.Validity(validFrom, expiresAt));
        }
        return List.copyOf(gates);
    }
}
