package com.giaokhoa.teaching;

import com.giaokhoa.authorization.keycloak.KeycloakProvisioningClient;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của giảng dạy: nguồn sự thật duy nhất về desired native graph của các target lớp, khóa
 * và môn–khóa của một CENTER workspace, dựng thuần từ các dòng bền (membership/role hiện hành,
 * roster, ghi danh, phân công và các kỳ gói đã xác nhận). Nguồn là các group cố định của hợp đồng —
 * owners/managers của workspace cho các scope quản trị, group chủ nhiệm của lớp, group giáo viên và
 * học sinh của từng môn–khóa — và các scope mở rộng (thêm roster/ghi danh/phân công, tạo khóa, giao
 * bài, soạn học liệu) còn AND thêm cửa sổ hiệu lực của từng kỳ gói như bundle của center: hết hạn
 * gói thì time policy tự từ chối, không cần cờ active hay cron. Scope đọc và scope thu hồi không
 * qua gate gói để gói hết hạn vẫn xem và thu hồi được.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace: tập descriptor canonical của mọi scope mà target đó quản lý, với scope danh nghĩa
 * {@code read}. Projector này vừa tính tập đó khi ghi (qua các service ghi danh/phân công), vừa
 * dựng lại đúng tập đó khi {@code ProjectionReconciler} đối chiếu desired hash, và đồng bộ
 * membership thật của từng user vào các realm group giảng dạy khi reconcile — group policy chỉ cấp
 * cho người thật sự đang ở trong group, và group giảng dạy chỉ chứa người còn membership ACTIVE kèm
 * role tiền đề đúng (TEACHER cho phân công, STUDENT cho ghi danh) tại thời điểm đối chiếu.
 *
 * <p>Các bảng giảng dạy nằm dưới RLS theo context workspace của transaction, mà phía reconcile
 * không có transaction bao quanh sẵn: mọi phép dựng bundle và đồng bộ group ở đây tự bọc trong một
 * transaction ngắn có đặt context của đúng workspace qua {@code set_config} trước truy vấn đầu
 * tiên. Phía ghi đã có transaction của service (context do service đặt cùng workspace) thì phép bọc
 * này chỉ nối vào, không đổi ngữ nghĩa.
 *
 * <p>Workspace PERSONAL chưa có bundle giảng dạy ở đây: nguồn personal (owner + ghi danh cá nhân)
 * thuộc projector riêng của personal, service ghi danh/phân công của center không ghi projection
 * nào cho personal.
 */
@Component
public class TeachingPolicyProjector {

    private static final String GROUP_DESCRIPTOR = "TEACHING_GROUP";
    private static final String PERIOD_DESCRIPTOR = "TEACHING_GROUP_PERIOD";
    private static final String BUNDLE_SCOPE = PermissionCatalog.ClassScope.READ.scopeName();

    private final JdbcTemplate jdbc;
    private final KeycloakProvisioningClient directory;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    public TeachingPolicyProjector(
            JdbcTemplate jdbc,
            KeycloakProvisioningClient directory,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle giảng dạy. */
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
     * Toàn bộ descriptor của bundle của một target giảng dạy, dựng từ dữ kiện nguồn hiện hành. Rỗng
     * khi workspace không phải CENTER, target không còn tồn tại, hoặc không nguồn nào còn hiệu lực
     * — rỗng nghĩa là target đó phải bị thu hồi (retract), không phải được giữ nguyên.
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
                    if (!isCenter(workspaceId)) {
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
                .filter(descriptor -> descriptor.endsWith("|scope=" + scope))
                .toList();
    }

    /**
     * Mọi target giảng dạy hiện có của workspace: mọi lớp, khóa và môn–khóa. Các thay đổi không
     * thuộc riêng một target nào mà vẫn đổi bundle của mọi target — membership của owner/manager
     * (nguồn quản trị có mặt trong mọi bundle) và các kỳ gói (gate của các scope mở rộng) — ghi lại
     * bundle cho toàn bộ danh sách này trong cùng operation.
     */
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
     * Desired projection của mọi bundle giảng dạy hiện có của workspace, để gắn vào một operation.
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
     * Ghi operation cho một thay đổi giảng dạy vừa được ghi trong cùng transaction của service:
     * bump desired revision của WORKSPACE partition của workspace, fence nó và lưu bundle hiện hành
     * của từng target bị ảnh hưởng. Replay cùng idempotency key và payload trả lại operation đã ghi
     * mà không ghi lại gì, còn cùng key khác payload là 409 — cả hai do cơ chế operation dùng chung
     * xử lý.
     */
    public AuthorizationOperation recordTeachingChange(
            UUID initiatorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            List<String> targetKeys) {
        List<UUID> partitions =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace authorization partition is missing: " + workspaceId);
        }
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : targetKeys) {
            projections.add(
                    new AuthorizationOperationService.DesiredProjection(
                            targetKey, BUNDLE_SCOPE, bundleDescriptors(targetKey), List.of()));
        }
        return operations.applyDesiredState(
                initiatorUserId,
                workspaceId,
                idempotencyKey,
                payloadHash,
                List.of(
                        new AuthorizationOperationService.PartitionChange(
                                partitions.getFirst(), List.copyOf(projections))));
    }

    /**
     * Đưa membership thật trong các realm group giảng dạy của workspace về đúng dữ kiện nguồn: mọi
     * user từng có quan hệ giảng dạy ở workspace này thuộc đúng các group mà phân công/ghi danh
     * hiện hành, membership ACTIVE và role tiền đề hiện hành của họ suy ra, không thuộc group giảng
     * dạy nào khác của workspace. Chỉ ghi khi live đang lệch, để reconcile không đổi gì vẫn là kiểm
     * chứng đọc.
     */
    public void syncGroupMemberships(UUID workspaceId) {
        transactions.executeWithoutResult(_ -> {
            applyContext(workspaceId);
            List<String> groupPaths = teachingGroupPaths(workspaceId);
            if (groupPaths.isEmpty()) {
                return;
            }
            Map<UUID, Set<String>> desiredByUser = desiredGroupsByUser(workspaceId);
            List<Map<String, Object>> users =
                    jdbc.queryForList(
                            "SELECT id, keycloak_sub FROM user_account WHERE id IN"
                                    + " (SELECT teacher_user_id FROM teaching_assignment"
                                    + " WHERE workspace_id = ?"
                                    + " UNION SELECT teacher_user_id FROM homeroom_assignment"
                                    + " WHERE workspace_id = ?"
                                    + " UNION SELECT cs.student_user_id FROM enrollment e"
                                    + " JOIN class_student cs ON cs.id = e.class_student_id"
                                    + " WHERE e.workspace_id = ?)",
                            workspaceId,
                            workspaceId,
                            workspaceId);
            for (Map<String, Object> user : users) {
                UUID userId = (UUID) Objects.requireNonNull(user.get("id"), "id");
                String subject =
                        Objects.requireNonNull((String) user.get("keycloak_sub"), "keycloak_sub");
                Set<String> desired = desiredByUser.getOrDefault(userId, Set.of());
                for (String groupPath : groupPaths) {
                    boolean shouldBeMember = desired.contains(groupPath);
                    if (directory.isRealmGroupMember(subject, groupPath) != shouldBeMember) {
                        directory.setRealmGroupMembership(subject, groupPath, shouldBeMember);
                    }
                }
            }
        });
    }

    /**
     * Các gate native của một canonical descriptor giảng dạy: group nguồn, cộng cửa sổ hiệu lực khi
     * nguồn bị gate bởi kỳ gói. Dùng bởi reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !(GROUP_DESCRIPTOR.equals(parts[0]) || PERIOD_DESCRIPTOR.equals(parts[0]))) {
            throw new IllegalArgumentException("Unsupported teaching descriptor: " + descriptor);
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
            throw new IllegalArgumentException("Teaching descriptor has no group: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        gates.add(new PolicyGraph.Gate.GroupMembership(groupPath));
        boolean periodGated = PERIOD_DESCRIPTOR.equals(parts[0]);
        if (periodGated) {
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
        String targetKey = NativePolicyTemplates.classKey(workspaceId, classId);
        Sources sources = new Sources(workspaceId, targetKey);
        String owners = NativePolicyTemplates.ownersGroup(workspaceId);
        String managers = NativePolicyTemplates.managersGroup(workspaceId);
        String homeroom = NativePolicyTemplates.homeroomGroup(workspaceId, classId);
        boolean ownersActive = sources.adminGroupActive(workspaceId, "OWNER");
        boolean managersActive = sources.adminGroupActive(workspaceId, "MANAGER");
        boolean homeroomPresent =
                rowExists(
                        "SELECT id FROM homeroom_assignment WHERE workspace_id = ?"
                                + " AND class_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        classId);
        sources.add("read", owners, ownersActive, false);
        sources.add("read", managers, managersActive, false);
        sources.add("read", homeroom, homeroomPresent, false);
        sources.add("update", owners, ownersActive, true);
        sources.add("update", managers, managersActive, true);
        sources.add("course.list", owners, ownersActive, false);
        sources.add("course.list", managers, managersActive, false);
        sources.add("course.list", homeroom, homeroomPresent, false);
        sources.add("course.create", owners, ownersActive, true);
        sources.add("course.create", managers, managersActive, true);
        sources.add("roster.read", owners, ownersActive, false);
        sources.add("roster.read", managers, managersActive, false);
        sources.add("roster.read", homeroom, homeroomPresent, false);
        sources.add("roster.add", owners, ownersActive, true);
        sources.add("roster.add", managers, managersActive, true);
        sources.add("roster.revoke", owners, ownersActive, false);
        sources.add("roster.revoke", managers, managersActive, false);
        sources.add("homeroom.assign", owners, ownersActive, true);
        sources.add("homeroom.assign", managers, managersActive, true);
        sources.add("homeroom.revoke", owners, ownersActive, false);
        sources.add("homeroom.revoke", managers, managersActive, false);
        sources.add("completion.read", owners, ownersActive, false);
        sources.add("completion.read", managers, managersActive, false);
        sources.add("completion.read", homeroom, homeroomPresent, false);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSources(UUID workspaceId, UUID courseId) {
        List<UUID> classIds =
                jdbc.queryForList(
                        "SELECT class_id FROM course WHERE workspace_id = ? AND id = ?",
                        UUID.class,
                        workspaceId,
                        courseId);
        if (classIds.isEmpty()) {
            return Map.of();
        }
        UUID classId = classIds.getFirst();
        String targetKey = NativePolicyTemplates.courseKey(workspaceId, courseId);
        Sources sources = new Sources(workspaceId, targetKey);
        String owners = NativePolicyTemplates.ownersGroup(workspaceId);
        String managers = NativePolicyTemplates.managersGroup(workspaceId);
        String homeroom = NativePolicyTemplates.homeroomGroup(workspaceId, classId);
        boolean ownersActive = sources.adminGroupActive(workspaceId, "OWNER");
        boolean managersActive = sources.adminGroupActive(workspaceId, "MANAGER");
        boolean homeroomPresent =
                rowExists(
                        "SELECT id FROM homeroom_assignment WHERE workspace_id = ?"
                                + " AND class_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        classId);
        sources.add("read", owners, ownersActive, false);
        sources.add("read", managers, managersActive, false);
        sources.add("read", homeroom, homeroomPresent, false);
        sources.add("update", owners, ownersActive, false);
        sources.add("update", managers, managersActive, false);
        sources.add("subject.add", owners, ownersActive, true);
        sources.add("subject.add", managers, managersActive, true);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSubjectSources(UUID workspaceId, UUID courseSubjectId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT class_id FROM course_subject WHERE workspace_id = ? AND id = ?",
                        workspaceId,
                        courseSubjectId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        UUID classId = (UUID) Objects.requireNonNull(rows.getFirst().get("class_id"), "class_id");
        String targetKey = NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
        Sources sources = new Sources(workspaceId, targetKey);
        String owners = NativePolicyTemplates.ownersGroup(workspaceId);
        String managers = NativePolicyTemplates.managersGroup(workspaceId);
        String homeroom = NativePolicyTemplates.homeroomGroup(workspaceId, classId);
        String teachers =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspaceId, courseSubjectId);
        String students =
                NativePolicyTemplates.courseSubjectStudentsGroup(workspaceId, courseSubjectId);
        boolean ownersActive = sources.adminGroupActive(workspaceId, "OWNER");
        boolean managersActive = sources.adminGroupActive(workspaceId, "MANAGER");
        boolean homeroomPresent =
                rowExists(
                        "SELECT id FROM homeroom_assignment WHERE workspace_id = ?"
                                + " AND class_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        classId);
        boolean teachersPresent =
                rowExists(
                        "SELECT id FROM teaching_assignment WHERE workspace_id = ?"
                                + " AND course_subject_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        courseSubjectId);
        boolean studentsPresent =
                rowExists(
                        "SELECT id FROM enrollment WHERE workspace_id = ?"
                                + " AND course_subject_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        courseSubjectId);
        sources.add("read", owners, ownersActive, false);
        sources.add("read", managers, managersActive, false);
        sources.add("read", teachers, teachersPresent, false);
        sources.add("read", students, studentsPresent, false);
        sources.add("roster.read", owners, ownersActive, false);
        sources.add("roster.read", managers, managersActive, false);
        sources.add("roster.read", teachers, teachersPresent, false);
        sources.add("enrollment.add", owners, ownersActive, true);
        sources.add("enrollment.add", managers, managersActive, true);
        sources.add("enrollment.revoke", owners, ownersActive, false);
        sources.add("enrollment.revoke", managers, managersActive, false);
        sources.add("teacher.assign", owners, ownersActive, true);
        sources.add("teacher.assign", managers, managersActive, true);
        sources.add("teacher.revoke", owners, ownersActive, false);
        sources.add("teacher.revoke", managers, managersActive, false);
        sources.add("resource.create", teachers, teachersPresent, true);
        sources.add("activity.assign", owners, ownersActive, true);
        sources.add("activity.assign", managers, managersActive, true);
        sources.add("activity.assign", teachers, teachersPresent, true);
        sources.add("activity.assign", homeroom, homeroomPresent, true);
        sources.add("completion.read", owners, ownersActive, false);
        sources.add("completion.read", managers, managersActive, false);
        sources.add("completion.read", teachers, teachersPresent, false);
        sources.add("completion.read", homeroom, homeroomPresent, false);
        return sources.byScope();
    }

    // ---------- Đồng bộ group ----------

    private List<String> teachingGroupPaths(UUID workspaceId) {
        Set<String> paths = new TreeSet<>();
        jdbc.queryForList(
                        "SELECT id FROM school_class WHERE workspace_id = ?",
                        UUID.class,
                        workspaceId)
                .forEach(classId ->
                        paths.add(NativePolicyTemplates.homeroomGroup(workspaceId, classId)));
        jdbc.queryForList(
                        "SELECT id FROM course_subject WHERE workspace_id = ?",
                        UUID.class,
                        workspaceId)
                .forEach(courseSubjectId -> {
                    paths.add(
                            NativePolicyTemplates.courseSubjectTeachersGroup(
                                    workspaceId, courseSubjectId));
                    paths.add(
                            NativePolicyTemplates.courseSubjectStudentsGroup(
                                    workspaceId, courseSubjectId));
                });
        return List.copyOf(paths);
    }

    private Map<UUID, Set<String>> desiredGroupsByUser(UUID workspaceId) {
        Map<UUID, Set<String>> desired = new LinkedHashMap<>();
        jdbc.queryForList(
                        "SELECT h.teacher_user_id AS user_id, h.class_id AS object_id"
                                + " FROM homeroom_assignment h"
                                + " WHERE h.workspace_id = ? AND h.state IN ('PENDING', 'ACTIVE')"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = h.workspace_id"
                                + " AND m.user_id = h.teacher_user_id AND m.state = 'ACTIVE'"
                                + " AND r.role = 'TEACHER')",
                        workspaceId)
                .forEach(row ->
                        desired.computeIfAbsent((UUID) row.get("user_id"), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.homeroomGroup(
                                                workspaceId,
                                                Objects.requireNonNull(
                                                        (UUID) row.get("object_id")))));
        jdbc.queryForList(
                        "SELECT t.teacher_user_id AS user_id, t.course_subject_id AS object_id"
                                + " FROM teaching_assignment t"
                                + " WHERE t.workspace_id = ? AND t.state IN ('PENDING', 'ACTIVE')"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = t.workspace_id"
                                + " AND m.user_id = t.teacher_user_id AND m.state = 'ACTIVE'"
                                + " AND r.role = 'TEACHER')",
                        workspaceId)
                .forEach(row ->
                        desired.computeIfAbsent((UUID) row.get("user_id"), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.courseSubjectTeachersGroup(
                                                workspaceId,
                                                Objects.requireNonNull(
                                                        (UUID) row.get("object_id")))));
        jdbc.queryForList(
                        "SELECT cs.student_user_id AS user_id, e.course_subject_id AS object_id"
                                + " FROM enrollment e"
                                + " JOIN class_student cs ON cs.id = e.class_student_id"
                                + " WHERE e.workspace_id = ? AND e.state IN ('PENDING', 'ACTIVE')"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " JOIN membership_role r ON r.membership_id = m.id"
                                + " WHERE m.workspace_id = e.workspace_id"
                                + " AND m.user_id = cs.student_user_id AND m.state = 'ACTIVE'"
                                + " AND r.role = 'STUDENT')",
                        workspaceId)
                .forEach(row ->
                        desired.computeIfAbsent((UUID) row.get("user_id"), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.courseSubjectStudentsGroup(
                                                workspaceId,
                                                Objects.requireNonNull(
                                                        (UUID) row.get("object_id")))));
        return desired;
    }

    // ---------- Tiện ích dựng descriptor ----------

    /** Bộ tích lũy descriptor của một target: nguồn không hiệu lực thì không sinh descriptor. */
    private final class Sources {
        private final UUID workspaceId;
        private final String targetKey;
        private final Map<String, List<String>> sourcesByScope = new LinkedHashMap<>();
        private List<SubscriptionPeriodRow> loadedPeriods = List.of();
        private boolean periodsLoaded;

        private Sources(UUID workspaceId, String targetKey) {
            this.workspaceId = workspaceId;
            this.targetKey = targetKey;
        }

        private boolean adminGroupActive(UUID workspaceId, String role) {
            return rowExists(
                    "SELECT m.id FROM center_membership m"
                            + " JOIN membership_role r ON r.membership_id = m.id"
                            + " WHERE m.workspace_id = ? AND m.state = 'ACTIVE' AND r.role = ?"
                            + " LIMIT 1",
                    workspaceId,
                    role);
        }

        private void add(String scope, String groupPath, boolean present, boolean gated) {
            if (!present) {
                return;
            }
            List<String> descriptors =
                    sourcesByScope.computeIfAbsent(scope, _ -> new ArrayList<>());
            if (!gated) {
                descriptors.add(groupDescriptor(workspaceId, groupPath, targetKey, scope));
                return;
            }
            for (SubscriptionPeriodRow period : periods()) {
                descriptors.add(periodDescriptor(workspaceId, groupPath, period, targetKey, scope));
            }
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

    private record SubscriptionPeriodRow(UUID id, Instant validFrom, Instant expiresAt) {}

    private static String groupDescriptor(
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

    private static String periodDescriptor(
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

    private boolean rowExists(String sql, Object... args) {
        return !jdbc.queryForList(sql, UUID.class, args).isEmpty();
    }

    private boolean isCenter(UUID workspaceId) {
        return rowExists("SELECT id FROM workspace WHERE id = ? AND kind = 'CENTER'", workspaceId);
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
