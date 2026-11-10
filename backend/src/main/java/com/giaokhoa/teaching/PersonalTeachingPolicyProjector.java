package com.giaokhoa.teaching;

import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.subscriptions.SubscriptionPeriodRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của giảng dạy cá nhân: nguồn sự thật duy nhất về desired native graph của các target
 * lớp, khóa và môn–khóa của một PERSONAL workspace, dựng thuần từ các dòng bền (chủ sở hữu của
 * workspace, roster và ghi danh hiện hành, các kỳ gói đã xác nhận). Mọi nguồn đều là user policy
 * native của một người cụ thể — chủ sở hữu cho các scope quản trị, từng học sinh đang ghi danh cho
 * scope đọc của môn–khóa — không group, không membership center, không role nghề nghiệp toàn cục;
 * namespace và dữ kiện tách hẳn khỏi bundle giảng dạy của center ở {@link TeachingPolicyProjector}.
 *
 * <p>Gate gói đi theo đúng hình của bundle center: các scope mở rộng (tạo khóa, thêm roster, ghi
 * danh, soạn học liệu, giao bài) AND thêm cửa sổ hiệu lực của từng kỳ gói qua time policy, còn
 * scope đọc, scope thu hồi và {@code completion.read} không qua gate gói — gói hết hạn không chặn
 * việc theo dõi hoàn thành của bài đã giao, và nguồn đọc của học sinh chỉ phụ thuộc ghi danh hiện
 * hành của chính học sinh đó, không phụ thuộc cửa sổ gói. Phân công giáo viên/chủ nhiệm không áp
 * dụng cho personal nên hai scope đó không có nguồn nào.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace với scope danh nghĩa {@code read}, cùng hình dạng bundle giảng dạy của center:
 * projector này tính tập descriptor để {@link
 * com.giaokhoa.authorization.projection.SourceRevocationCoordinator} ghi vào operation khi các
 * service lớp/khóa/roster/ghi danh đổi dữ kiện nguồn, và dựng lại đúng tập đó khi {@code
 * ProjectionReconciler} đối chiếu desired hash. Các bảng giảng dạy nằm dưới RLS theo context
 * workspace nên mọi phép dựng bundle ở đây tự bọc trong một transaction ngắn có đặt context của
 * đúng workspace, cùng mẫu {@link TeachingPolicyProjector}.
 */
@Component
public class PersonalTeachingPolicyProjector {

    private static final String OWNER_DESCRIPTOR = "PERSONAL_TEACHING_OWNER";
    private static final String OWNER_PERIOD_DESCRIPTOR = "PERSONAL_TEACHING_OWNER_PERIOD";
    private static final String ENROLLMENT_DESCRIPTOR = "PERSONAL_TEACHING_ENROLLMENT";
    private static final String BUNDLE_SCOPE = PermissionCatalog.ClassScope.READ.scopeName();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PersonalTeachingPolicyProjector(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle giảng dạy cá nhân. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một loại target giảng dạy, theo thứ tự catalog. */
    public static List<String> managedScopes(NativePolicyTemplates.TargetType type) {
        return switch (type) {
            case CLASS -> PermissionCatalog.names(PermissionCatalog.ClassScope.values());
            case COURSE -> PermissionCatalog.names(PermissionCatalog.CourseScope.values());
            case COURSE_SUBJECT ->
                    PermissionCatalog.names(PermissionCatalog.CourseSubjectScope.values());
            default -> throw new IllegalArgumentException("Not a teaching target type: " + type);
        };
    }

    /**
     * Toàn bộ descriptor của bundle của một target giảng dạy cá nhân, dựng từ dữ kiện nguồn hiện
     * hành. Rỗng khi workspace không phải PERSONAL, target không còn tồn tại, hoặc không nguồn nào
     * còn hiệu lực — rỗng nghĩa là target đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String targetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        UUID workspaceId = workspaceIdOf(targetKey);
        List<String> bundle =
                transactions.execute(_ -> {
                    applyContext(workspaceId);
                    if (!isPersonal(workspaceId)) {
                        return List.of();
                    }
                    Map<String, List<String>> byScope =
                            switch (type) {
                                case CLASS -> classSources(workspaceId, targetIdOf(targetKey));
                                case COURSE -> courseSources(workspaceId, targetIdOf(targetKey));
                                case COURSE_SUBJECT ->
                                        courseSubjectSources(workspaceId, targetIdOf(targetKey));
                                default ->
                                        throw new IllegalArgumentException(
                                                "Not a teaching target type: " + type);
                            };
                    List<String> all = new ArrayList<>();
                    byScope.values().forEach(all::addAll);
                    return all.stream().sorted().distinct().toList();
                });
        // Lambda ở trên luôn trả danh sách; execute chỉ trả null khi callback trả null.
        return Objects.requireNonNull(bundle, "bundle");
    }

    /** Các descriptor của riêng một scope trong bundle của target, dựng lại từ dữ kiện nguồn. */
    public List<String> sourcesFor(String targetKey, String scope) {
        List<String> bundle = bundleDescriptors(targetKey);
        return bundle.stream()
                .filter(descriptor -> descriptor.endsWith(PolicyGraph.SCOPE_MARKER + scope))
                .toList();
    }

    /** Mọi target giảng dạy hiện có của workspace: mọi lớp, khóa và môn–khóa. */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    applyContext(workspaceId);
                    List<String> keys = new ArrayList<>();
                    jdbc.queryForList(
                                    "SELECT id FROM school_class WHERE workspace_id = ?",
                                    UUID.class,
                                    workspaceId)
                            .forEach(classId ->
                                    keys.add(NativePolicyTemplates.classKey(workspaceId, classId)));
                    jdbc.queryForList(
                                    "SELECT id FROM course WHERE workspace_id = ?",
                                    UUID.class,
                                    workspaceId)
                            .forEach(courseId ->
                                    keys.add(
                                            NativePolicyTemplates.courseKey(
                                                    workspaceId, courseId)));
                    jdbc.queryForList(
                                    "SELECT id FROM course_subject WHERE workspace_id = ?",
                                    UUID.class,
                                    workspaceId)
                            .forEach(courseSubjectId ->
                                    keys.add(
                                            NativePolicyTemplates.courseSubjectKey(
                                                    workspaceId, courseSubjectId)));
                    return keys;
                });
        return Objects.requireNonNull(targets, "targets");
    }

    /**
     * Desired projection của mọi bundle giảng dạy cá nhân hiện có của workspace, để gắn vào một
     * operation. Đổi kỳ gói đổi gate của các scope mở rộng trong mọi bundle này nên xác nhận gói
     * ghi lại cả tập trong cùng operation.
     */
    public List<AuthorizationOperationService.DesiredProjection> allBundleProjections(
            UUID workspaceId) {
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : allBundleTargetKeys(workspaceId)) {
            projections.add(
                    new AuthorizationOperationService.DesiredProjection(
                            targetKey, BUNDLE_SCOPE, bundleDescriptors(targetKey), List.of()));
        }
        return List.copyOf(projections);
    }

    /**
     * Các gate native của một canonical descriptor giảng dạy cá nhân: user policy của đúng người là
     * nguồn, cộng cửa sổ hiệu lực khi nguồn chủ sở hữu bị gate bởi kỳ gói. Dùng bởi reconciliation
     * để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !(OWNER_DESCRIPTOR.equals(parts[0])
                        || OWNER_PERIOD_DESCRIPTOR.equals(parts[0])
                        || ENROLLMENT_DESCRIPTOR.equals(parts[0]))) {
            throw new IllegalArgumentException(
                    "Unsupported personal teaching descriptor: " + descriptor);
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
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "Personal teaching descriptor has no subject: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        gates.add(new PolicyGraph.Gate.Actor(subject));
        if (OWNER_PERIOD_DESCRIPTOR.equals(parts[0])) {
            if (validFrom == null || expiresAt == null) {
                throw new IllegalArgumentException(
                        "Personal teaching period descriptor is incomplete: " + descriptor);
            }
            gates.add(new PolicyGraph.Gate.Validity(validFrom, expiresAt));
        }
        return List.copyOf(gates);
    }

    // ---------- Dựng nguồn theo từng loại target ----------

    private Map<String, List<String>> classSources(UUID workspaceId, UUID classId) {
        if (!rowExists(
                "SELECT id FROM school_class WHERE workspace_id = ? AND id = ?",
                workspaceId,
                classId)) {
            return Map.of();
        }
        Sources sources =
                new Sources(workspaceId, NativePolicyTemplates.classKey(workspaceId, classId));
        if (sources.owner == null) {
            return Map.of();
        }
        sources.addOwner(PermissionCatalog.ClassScope.READ.scopeName(), false);
        sources.addOwner(PermissionCatalog.ClassScope.UPDATE.scopeName(), true);
        sources.addOwner(PermissionCatalog.ClassScope.COURSE_LIST.scopeName(), false);
        sources.addOwner(PermissionCatalog.ClassScope.COURSE_CREATE.scopeName(), true);
        sources.addOwner(PermissionCatalog.ClassScope.ROSTER_READ.scopeName(), false);
        sources.addOwner(PermissionCatalog.ClassScope.ROSTER_ADD.scopeName(), true);
        sources.addOwner(PermissionCatalog.ClassScope.ROSTER_REVOKE.scopeName(), false);
        sources.addOwner(PermissionCatalog.ClassScope.COMPLETION_READ.scopeName(), false);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSources(UUID workspaceId, UUID courseId) {
        if (!rowExists(
                "SELECT id FROM course WHERE workspace_id = ? AND id = ?", workspaceId, courseId)) {
            return Map.of();
        }
        Sources sources =
                new Sources(workspaceId, NativePolicyTemplates.courseKey(workspaceId, courseId));
        if (sources.owner == null) {
            return Map.of();
        }
        sources.addOwner(PermissionCatalog.CourseScope.READ.scopeName(), false);
        sources.addOwner(PermissionCatalog.CourseScope.UPDATE.scopeName(), false);
        sources.addOwner(PermissionCatalog.CourseScope.SUBJECT_ADD.scopeName(), true);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSubjectSources(UUID workspaceId, UUID courseSubjectId) {
        if (!rowExists(
                "SELECT id FROM course_subject WHERE workspace_id = ? AND id = ?",
                workspaceId,
                courseSubjectId)) {
            return Map.of();
        }
        String targetKey = NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
        Sources sources = new Sources(workspaceId, targetKey);
        if (sources.owner == null) {
            return Map.of();
        }
        sources.addOwner(PermissionCatalog.CourseSubjectScope.READ.scopeName(), false);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.ROSTER_READ.scopeName(), false);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.ENROLLMENT_ADD.scopeName(), true);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.ENROLLMENT_REVOKE.scopeName(), false);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.RESOURCE_CREATE.scopeName(), true);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName(), true);
        sources.addOwner(PermissionCatalog.CourseSubjectScope.COMPLETION_READ.scopeName(), false);
        for (ActorRow student : enrolledStudents(workspaceId, courseSubjectId)) {
            sources.addEnrollment(PermissionCatalog.CourseSubjectScope.READ.scopeName(), student);
        }
        return sources.byScope();
    }

    /** Học sinh đang ghi danh hiện hành vào môn–khóa: ghi danh còn hiệu lực qua roster của lớp. */
    private List<ActorRow> enrolledStudents(UUID workspaceId, UUID courseSubjectId) {
        return jdbc.query(
                "SELECT DISTINCT u.id, u.keycloak_sub FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " JOIN user_account u ON u.id = cs.student_user_id"
                        + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                        + " AND e.state IN ('PENDING', 'ACTIVE') ORDER BY u.id",
                (rs, _) ->
                        new ActorRow(rs.getObject("id", UUID.class), rs.getString("keycloak_sub")),
                workspaceId,
                courseSubjectId);
    }

    // ---------- Tiện ích dựng descriptor ----------

    private record ActorRow(UUID userId, String keycloakSub) {}

    /** Bộ tích lũy descriptor của một target: chủ sở hữu là nguồn quản trị duy nhất. */
    private final class Sources {
        private final UUID workspaceId;
        private final String targetKey;
        private final Map<String, List<String>> sourcesByScope = new LinkedHashMap<>();
        private final @Nullable ActorRow owner;
        private List<SubscriptionPeriodRow> loadedPeriods = List.of();
        private boolean periodsLoaded;

        private Sources(UUID workspaceId, String targetKey) {
            this.workspaceId = workspaceId;
            this.targetKey = targetKey;
            this.owner = ownerOf(workspaceId);
        }

        private void addOwner(String scope, boolean gated) {
            ActorRow current = Objects.requireNonNull(owner, "owner");
            List<String> descriptors =
                    sourcesByScope.computeIfAbsent(scope, _ -> new ArrayList<>());
            if (!gated) {
                descriptors.add(ownerDescriptor(workspaceId, current, targetKey, scope));
                return;
            }
            for (SubscriptionPeriodRow period : periods()) {
                descriptors.add(
                        ownerPeriodDescriptor(workspaceId, current, period, targetKey, scope));
            }
        }

        private void addEnrollment(String scope, ActorRow student) {
            sourcesByScope
                    .computeIfAbsent(scope, _ -> new ArrayList<>())
                    .add(enrollmentDescriptor(workspaceId, student, targetKey, scope));
        }

        private List<SubscriptionPeriodRow> periods() {
            if (!periodsLoaded) {
                loadedPeriods =
                        jdbc.query(
                                "SELECT id, valid_from, expires_at FROM subscription_period"
                                        + " WHERE workspace_id = ? ORDER BY valid_from, id",
                                (rs, _) ->
                                        new SubscriptionPeriodRow(
                                                rs.getObject("id", UUID.class),
                                                Objects.requireNonNull(
                                                                rs.getObject(
                                                                        "valid_from",
                                                                        OffsetDateTime.class))
                                                        .toInstant(),
                                                Objects.requireNonNull(
                                                                rs.getObject(
                                                                        "expires_at",
                                                                        OffsetDateTime.class))
                                                        .toInstant()),
                                workspaceId);
                periodsLoaded = true;
            }
            return loadedPeriods;
        }

        private Map<String, List<String>> byScope() {
            return sourcesByScope;
        }
    }

    private @Nullable ActorRow ownerOf(UUID workspaceId) {
        List<ActorRow> rows =
                jdbc.query(
                        "SELECT u.id, u.keycloak_sub FROM workspace w"
                                + " JOIN user_account u ON u.id = w.owner_user_id"
                                + " WHERE w.id = ? AND w.kind = 'PERSONAL'",
                        (rs, _) ->
                                new ActorRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("keycloak_sub")),
                        workspaceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String ownerDescriptor(
            UUID workspaceId, ActorRow owner, String targetKey, String scope) {
        return OWNER_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|user="
                + owner.userId()
                + "|subject="
                + owner.keycloakSub()
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static String ownerPeriodDescriptor(
            UUID workspaceId,
            ActorRow owner,
            SubscriptionPeriodRow period,
            String targetKey,
            String scope) {
        return OWNER_PERIOD_DESCRIPTOR
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
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static String enrollmentDescriptor(
            UUID workspaceId, ActorRow student, String targetKey, String scope) {
        return ENROLLMENT_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|user="
                + student.userId()
                + "|subject="
                + student.keycloakSub()
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private boolean rowExists(String sql, Object... args) {
        return !jdbc.queryForList(sql, UUID.class, args).isEmpty();
    }

    private boolean isPersonal(UUID workspaceId) {
        return rowExists(
                "SELECT id FROM workspace WHERE id = ? AND kind = 'PERSONAL'", workspaceId);
    }

    private void applyContext(UUID workspaceId) {
        jdbc.queryForObject(
                "SELECT set_config('app.workspace_id', ?, true)",
                String.class,
                workspaceId.toString());
    }

    private static UUID workspaceIdOf(String targetKey) {
        // Mọi target giảng dạy đều bắt đầu bằng "workspace:{id}:".
        return UUID.fromString(
                targetKey.substring("workspace:".length(), "workspace:".length() + 36));
    }

    private static UUID targetIdOf(String targetKey) {
        return UUID.fromString(targetKey.substring(targetKey.lastIndexOf(':') + 1));
    }
}
