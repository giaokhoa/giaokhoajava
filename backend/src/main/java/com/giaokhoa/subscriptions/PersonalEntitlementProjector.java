package com.giaokhoa.subscriptions;

import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projector của entitlement cá nhân: nguồn sự thật duy nhất về desired native graph của một
 * PERSONAL workspace, dựng thuần từ các dòng bền (chủ sở hữu của workspace và các kỳ gói đã xác
 * nhận của chính workspace đó). Nguồn là chính chủ sở hữu qua user policy native — không group,
 * không role nghề nghiệp toàn cục, không dữ kiện membership của center — và scope mở rộng duy nhất
 * của workspace cá nhân, {@code class.create}, còn AND thêm cửa sổ hiệu lực của từng kỳ gói qua
 * time policy: chưa có gói hoặc gói hết hạn thì PDP tự từ chối, không cần cờ active hay cron. Scope
 * đọc ({@code enter}, {@code read}, {@code class.list}) không qua gate gói để gói hết hạn vẫn vào
 * xem và thu hồi được. Mọi scope quản trị thành viên/manager/guardian của catalog không có nguồn cá
 * nhân nào: workspace personal không có membership nên các scope đó luôn bị thu hồi.
 *
 * <p>Desired graph của cả workspace được ghi thành một projection bundle duy nhất trên target
 * {@code workspace:{id}} với scope danh nghĩa {@code enter} — cùng hình dạng bundle của center ở
 * {@link com.giaokhoa.tenancy.CenterPolicyProjector}: projector này vừa tính tập descriptor khi ghi
 * (bootstrap eligibility và xác nhận gói), vừa dựng lại đúng tập đó khi {@code
 * ProjectionReconciler} đối chiếu desired hash.
 */
@Component
public class PersonalEntitlementProjector {

    private static final String PERIOD_DESCRIPTOR = "PERSONAL_OWNER_PERIOD";
    private static final String BUNDLE_SCOPE = PermissionCatalog.WorkspaceScope.ENTER.scopeName();

    /** Các scope mà chủ sở hữu là nguồn: đọc không gate gói, tạo lớp có gate gói. */
    private static final Set<String> OWNER_SCOPES =
            Set.of(
                    PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                    PermissionCatalog.WorkspaceScope.READ.scopeName(),
                    PermissionCatalog.WorkspaceScope.CLASS_LIST.scopeName(),
                    PermissionCatalog.WorkspaceScope.CLASS_CREATE.scopeName());

    private final JdbcTemplate jdbc;

    public PersonalEntitlementProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Scope danh nghĩa ghi trên projection row của bundle entitlement cá nhân. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho workspace target, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.WorkspaceScope.values());
    }

    /**
     * Các canonical source descriptor của một scope trên workspace này, dựng từ dữ kiện nguồn hiện
     * hành. Rỗng khi workspace không phải PERSONAL, chủ sở hữu không còn, hoặc scope không có nguồn
     * cá nhân — rỗng nghĩa là scope đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> sourcesFor(UUID workspaceId, String scope) {
        if (!OWNER_SCOPES.contains(scope)) {
            return List.of();
        }
        OwnerRow owner = ownerOf(workspaceId);
        if (owner == null) {
            return List.of();
        }
        String targetKey = NativePolicyTemplates.workspaceKey(workspaceId);
        if (!PermissionCatalog.WorkspaceScope.CLASS_CREATE.scopeName().equals(scope)) {
            return List.of(
                    ProjectionReconciler.personalOwnerDescriptor(
                            workspaceId, owner.userId(), owner.keycloakSub(), targetKey, scope));
        }
        List<String> descriptors = new ArrayList<>();
        for (SubscriptionPeriodRow period : subscriptionPeriods(workspaceId)) {
            descriptors.add(periodDescriptor(workspaceId, owner, period, targetKey, scope));
        }
        return descriptors.stream().sorted().distinct().toList();
    }

    /**
     * Toàn bộ descriptor của bundle entitlement: mọi scope của catalog nhân với nguồn hiện hành của
     * chúng. Tập này là desired state trọn vẹn của target; mutation nào lên workspace partition của
     * personal cũng ghi lại cả tập để không scope nào giữ hash của dữ kiện nguồn đã cũ.
     */
    public List<String> bundleDescriptors(UUID workspaceId) {
        List<String> bundle = new ArrayList<>();
        for (String scope : managedScopes()) {
            bundle.addAll(sourcesFor(workspaceId, scope));
        }
        return bundle.stream().sorted().distinct().toList();
    }

    /**
     * Các gate native của một canonical descriptor bị gate bởi kỳ gói của projector này: user
     * policy của chính chủ sở hữu AND time policy của cửa sổ kỳ gói. Dùng bởi reconciliation để
     * dựng lại đúng graph đã ghi. Descriptor owner không gate gói giữ đường actor gate dùng chung
     * của reconciliation.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2 || !PERIOD_DESCRIPTOR.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "Unsupported personal entitlement descriptor: " + descriptor);
        }
        String subject = null;
        Instant validFrom = null;
        Instant expiresAt = null;
        for (String part : parts) {
            if (part.startsWith("subject=")) {
                subject = part.substring("subject=".length());
            } else if (part.startsWith("from=")) {
                validFrom = Instant.parse(part.substring("from=".length()));
            } else if (part.startsWith("until=")) {
                expiresAt = Instant.parse(part.substring("until=".length()));
            }
        }
        if (subject == null || subject.isBlank() || validFrom == null || expiresAt == null) {
            throw new IllegalArgumentException(
                    "Personal entitlement descriptor is incomplete: " + descriptor);
        }
        return List.of(
                new PolicyGraph.Gate.Actor(subject),
                new PolicyGraph.Gate.Validity(validFrom, expiresAt));
    }

    private record OwnerRow(UUID userId, String keycloakSub) {}

    private @Nullable OwnerRow ownerOf(UUID workspaceId) {
        List<OwnerRow> rows =
                jdbc.query(
                        "SELECT u.id, u.keycloak_sub FROM workspace w"
                                + " JOIN user_account u ON u.id = w.owner_user_id"
                                + " WHERE w.id = ? AND w.kind = 'PERSONAL'",
                        (rs, _) ->
                                new OwnerRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("keycloak_sub")),
                        workspaceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

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

    /** Canonical descriptor của nguồn chủ sở hữu bị gate bởi một kỳ gói cụ thể. */
    private static String periodDescriptor(
            UUID workspaceId,
            OwnerRow owner,
            SubscriptionPeriodRow period,
            String targetKey,
            String scope) {
        return PERIOD_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|user="
                + owner.userId()
                + "|subject="
                + owner.keycloakSub()
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
