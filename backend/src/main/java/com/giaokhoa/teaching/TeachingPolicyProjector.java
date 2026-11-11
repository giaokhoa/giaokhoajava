package com.giaokhoa.teaching;

import com.giaokhoa.authorization.keycloak.AuthorizationProvisioningClient;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.SubscriptionPeriod;
import com.giaokhoa.teaching.repository.TeachingScopePreconditions.UserAccountRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * {@code read}. Projector này tính tập đó để {@link
 * com.giaokhoa.authorization.projection.SourceRevocationCoordinator} ghi vào operation khi các
 * service ghi danh/phân công đổi dữ kiện nguồn, và dựng lại đúng tập đó khi {@code
 * ProjectionReconciler} đối chiếu desired hash, và đồng bộ membership thật của từng user vào các
 * realm group giảng dạy khi reconcile — group policy chỉ cấp cho người thật sự đang ở trong group,
 * và group giảng dạy chỉ chứa người còn membership ACTIVE kèm role tiền đề đúng (TEACHER cho phân
 * công, STUDENT cho ghi danh) tại thời điểm đối chiếu.
 *
 * <p>Các bảng giảng dạy nằm trong schema tenant của workspace, mà phía reconcile không có
 * transaction bao quanh sẵn: mọi phép dựng bundle và đồng bộ group ở đây tự bọc trong một
 * transaction ngắn có đặt context của đúng workspace qua {@link WorkspaceTransaction} trước truy
 * vấn đầu tiên. Phía ghi đã có transaction của service (context do service đặt cùng workspace) thì
 * phép bọc này chỉ nối vào, không đổi ngữ nghĩa. Dữ kiện giảng dạy đọc qua các repository của
 * module; dữ kiện thuộc module khác (membership, tài khoản, kỳ gói) đọc qua {@link
 * TeachingScopePreconditions} và được lắp ở tầng ứng dụng theo danh sách ID, không join xuyên mặt
 * phẳng như tầng JDBC cũ.
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
    private static final List<String> CURRENT_STATES = List.of("PENDING", "ACTIVE");

    private final SchoolClassRepository schoolClasses;
    private final CourseRepository courses;
    private final CourseSubjectRepository courseSubjects;
    private final HomeroomAssignmentRepository homeroomAssignments;
    private final TeachingAssignmentRepository teachingAssignments;
    private final EnrollmentRepository enrollments;
    private final TeachingScopePreconditions preconditions;
    private final AuthorizationProvisioningClient directory;
    private final WorkspaceTransaction workspaceTransaction;
    private final TransactionTemplate transactions;

    public TeachingPolicyProjector(
            SchoolClassRepository schoolClasses,
            CourseRepository courses,
            CourseSubjectRepository courseSubjects,
            HomeroomAssignmentRepository homeroomAssignments,
            TeachingAssignmentRepository teachingAssignments,
            EnrollmentRepository enrollments,
            TeachingScopePreconditions preconditions,
            AuthorizationProvisioningClient directory,
            WorkspaceTransaction workspaceTransaction,
            PlatformTransactionManager transactionManager) {
        this.schoolClasses = schoolClasses;
        this.courses = courses;
        this.courseSubjects = courseSubjects;
        this.homeroomAssignments = homeroomAssignments;
        this.teachingAssignments = teachingAssignments;
        this.enrollments = enrollments;
        this.preconditions = preconditions;
        this.directory = directory;
        this.workspaceTransaction = workspaceTransaction;
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
                    workspaceTransaction.applyContext(workspaceId);
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
                .filter(descriptor -> descriptor.endsWith(PolicyGraph.SCOPE_MARKER + scope))
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
                    workspaceTransaction.applyContext(workspaceId);
                    List<String> keys = new ArrayList<>();
                    schoolClasses
                            .findIdsByWorkspaceId(workspaceId)
                            .forEach(classId ->
                                    keys.add(NativePolicyTemplates.classKey(workspaceId, classId)));
                    courses.findIdsByWorkspaceId(workspaceId)
                            .forEach(courseId ->
                                    keys.add(
                                            NativePolicyTemplates.courseKey(
                                                    workspaceId, courseId)));
                    courseSubjects
                            .findIdsByWorkspaceId(workspaceId)
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
     * Đưa membership thật trong các realm group giảng dạy của workspace về đúng dữ kiện nguồn: mọi
     * user từng có quan hệ giảng dạy ở workspace này thuộc đúng các group mà phân công/ghi danh
     * hiện hành, membership ACTIVE và role tiền đề hiện hành của họ suy ra, không thuộc group giảng
     * dạy nào khác của workspace. Chỉ ghi khi live đang lệch, để reconcile không đổi gì vẫn là kiểm
     * chứng đọc.
     */
    public void syncGroupMemberships(UUID workspaceId) {
        transactions.executeWithoutResult(_ -> {
            workspaceTransaction.applyContext(workspaceId);
            List<String> groupPaths = teachingGroupPaths(workspaceId);
            if (groupPaths.isEmpty()) {
                return;
            }
            Map<UUID, Set<String>> desiredByUser = desiredGroupsByUser(workspaceId);
            // Tập user từng có quan hệ giảng dạy được lấy theo ID ở mặt phẳng tenant trước,
            // rồi subject Keycloak của họ được phân giải theo lô ở mặt phẳng hệ thống.
            Set<UUID> userIds = new LinkedHashSet<>();
            userIds.addAll(teachingAssignments.findTeacherUserIdByWorkspaceId(workspaceId));
            userIds.addAll(homeroomAssignments.findTeacherUserIdByWorkspaceId(workspaceId));
            userIds.addAll(enrollments.findDistinctStudentUserIdsByWorkspaceId(workspaceId));
            List<UserAccountRef> users = preconditions.userAccountsByIds(userIds);
            for (UserAccountRef user : users) {
                UUID userId = user.id();
                String subject = user.keycloakSub();
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
        if (!schoolClasses.existsByWorkspaceIdAndId(workspaceId, classId)) {
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
                homeroomAssignments.existsByWorkspaceIdAndClassIdAndStateIn(
                        workspaceId, classId, CURRENT_STATES);
        sources.add(PermissionCatalog.ClassScope.READ.scopeName(), owners, ownersActive, false);
        sources.add(PermissionCatalog.ClassScope.READ.scopeName(), managers, managersActive, false);
        sources.add(
                PermissionCatalog.ClassScope.READ.scopeName(), homeroom, homeroomPresent, false);
        sources.add(PermissionCatalog.ClassScope.UPDATE.scopeName(), owners, ownersActive, true);
        sources.add(
                PermissionCatalog.ClassScope.UPDATE.scopeName(), managers, managersActive, true);
        sources.add(
                PermissionCatalog.ClassScope.COURSE_LIST.scopeName(), owners, ownersActive, false);
        sources.add(
                PermissionCatalog.ClassScope.COURSE_LIST.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.COURSE_LIST.scopeName(),
                homeroom,
                homeroomPresent,
                false);
        sources.add(
                PermissionCatalog.ClassScope.COURSE_CREATE.scopeName(), owners, ownersActive, true);
        sources.add(
                PermissionCatalog.ClassScope.COURSE_CREATE.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_READ.scopeName(), owners, ownersActive, false);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_READ.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_READ.scopeName(),
                homeroom,
                homeroomPresent,
                false);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_ADD.scopeName(), owners, ownersActive, true);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_ADD.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_REVOKE.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.ROSTER_REVOKE.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.HOMEROOM_ASSIGN.scopeName(),
                owners,
                ownersActive,
                true);
        sources.add(
                PermissionCatalog.ClassScope.HOMEROOM_ASSIGN.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.ClassScope.HOMEROOM_REVOKE.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.HOMEROOM_REVOKE.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.COMPLETION_READ.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.COMPLETION_READ.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.ClassScope.COMPLETION_READ.scopeName(),
                homeroom,
                homeroomPresent,
                false);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSources(UUID workspaceId, UUID courseId) {
        var classId = courses.findClassIdByWorkspaceIdAndId(workspaceId, courseId);
        if (classId.isEmpty()) {
            return Map.of();
        }
        String targetKey = NativePolicyTemplates.courseKey(workspaceId, courseId);
        Sources sources = new Sources(workspaceId, targetKey);
        String owners = NativePolicyTemplates.ownersGroup(workspaceId);
        String managers = NativePolicyTemplates.managersGroup(workspaceId);
        String homeroom = NativePolicyTemplates.homeroomGroup(workspaceId, classId.get());
        boolean ownersActive = sources.adminGroupActive(workspaceId, "OWNER");
        boolean managersActive = sources.adminGroupActive(workspaceId, "MANAGER");
        boolean homeroomPresent =
                homeroomAssignments.existsByWorkspaceIdAndClassIdAndStateIn(
                        workspaceId, classId.get(), CURRENT_STATES);
        sources.add(PermissionCatalog.CourseScope.READ.scopeName(), owners, ownersActive, false);
        sources.add(
                PermissionCatalog.CourseScope.READ.scopeName(), managers, managersActive, false);
        sources.add(
                PermissionCatalog.CourseScope.READ.scopeName(), homeroom, homeroomPresent, false);
        sources.add(PermissionCatalog.CourseScope.UPDATE.scopeName(), owners, ownersActive, false);
        sources.add(
                PermissionCatalog.CourseScope.UPDATE.scopeName(), managers, managersActive, false);
        sources.add(
                PermissionCatalog.CourseScope.SUBJECT_ADD.scopeName(), owners, ownersActive, true);
        sources.add(
                PermissionCatalog.CourseScope.SUBJECT_ADD.scopeName(),
                managers,
                managersActive,
                true);
        return sources.byScope();
    }

    private Map<String, List<String>> courseSubjectSources(UUID workspaceId, UUID courseSubjectId) {
        var classId = courseSubjects.findClassIdByWorkspaceIdAndId(workspaceId, courseSubjectId);
        if (classId.isEmpty()) {
            return Map.of();
        }
        String targetKey = NativePolicyTemplates.courseSubjectKey(workspaceId, courseSubjectId);
        Sources sources = new Sources(workspaceId, targetKey);
        String owners = NativePolicyTemplates.ownersGroup(workspaceId);
        String managers = NativePolicyTemplates.managersGroup(workspaceId);
        String homeroom = NativePolicyTemplates.homeroomGroup(workspaceId, classId.get());
        String teachers =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspaceId, courseSubjectId);
        String students =
                NativePolicyTemplates.courseSubjectStudentsGroup(workspaceId, courseSubjectId);
        boolean ownersActive = sources.adminGroupActive(workspaceId, "OWNER");
        boolean managersActive = sources.adminGroupActive(workspaceId, "MANAGER");
        boolean homeroomPresent =
                homeroomAssignments.existsByWorkspaceIdAndClassIdAndStateIn(
                        workspaceId, classId.get(), CURRENT_STATES);
        boolean teachersPresent =
                teachingAssignments.existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
                        workspaceId, courseSubjectId, CURRENT_STATES);
        boolean studentsPresent =
                enrollments.existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
                        workspaceId, courseSubjectId, CURRENT_STATES);
        sources.add(
                PermissionCatalog.CourseSubjectScope.READ.scopeName(), owners, ownersActive, false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.READ.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.READ.scopeName(),
                teachers,
                teachersPresent,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.READ.scopeName(),
                students,
                studentsPresent,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ROSTER_READ.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ROSTER_READ.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ROSTER_READ.scopeName(),
                teachers,
                teachersPresent,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ENROLLMENT_ADD.scopeName(),
                owners,
                ownersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ENROLLMENT_ADD.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ENROLLMENT_REVOKE.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ENROLLMENT_REVOKE.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.TEACHER_ASSIGN.scopeName(),
                owners,
                ownersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.TEACHER_ASSIGN.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.TEACHER_REVOKE.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.TEACHER_REVOKE.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.RESOURCE_CREATE.scopeName(),
                teachers,
                teachersPresent,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName(),
                owners,
                ownersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName(),
                managers,
                managersActive,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName(),
                teachers,
                teachersPresent,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.ACTIVITY_ASSIGN.scopeName(),
                homeroom,
                homeroomPresent,
                true);
        sources.add(
                PermissionCatalog.CourseSubjectScope.COMPLETION_READ.scopeName(),
                owners,
                ownersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.COMPLETION_READ.scopeName(),
                managers,
                managersActive,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.COMPLETION_READ.scopeName(),
                teachers,
                teachersPresent,
                false);
        sources.add(
                PermissionCatalog.CourseSubjectScope.COMPLETION_READ.scopeName(),
                homeroom,
                homeroomPresent,
                false);
        return sources.byScope();
    }

    // ---------- Đồng bộ group ----------

    private List<String> teachingGroupPaths(UUID workspaceId) {
        Set<String> paths = new TreeSet<>();
        schoolClasses
                .findIdsByWorkspaceId(workspaceId)
                .forEach(classId ->
                        paths.add(NativePolicyTemplates.homeroomGroup(workspaceId, classId)));
        courseSubjects
                .findIdsByWorkspaceId(workspaceId)
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
        // Điều kiện membership ACTIVE kèm role tiền đề được kiểm ở tầng ứng dụng trên tập
        // user theo role, thay EXISTS lồng trong từng truy vấn của tầng JDBC cũ.
        Set<UUID> activeTeachers = preconditions.activeMemberIdsWithRole(workspaceId, "TEACHER");
        Set<UUID> activeStudents = preconditions.activeMemberIdsWithRole(workspaceId, "STUDENT");
        Map<UUID, Set<String>> desired = new LinkedHashMap<>();
        homeroomAssignments.findPairsByWorkspaceIdAndStateIn(workspaceId, CURRENT_STATES).stream()
                .filter(pair -> activeTeachers.contains(pair.getTeacherUserId()))
                .forEach(pair ->
                        desired.computeIfAbsent(pair.getTeacherUserId(), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.homeroomGroup(
                                                workspaceId, pair.getClassId())));
        teachingAssignments.findPairsByWorkspaceIdAndStateIn(workspaceId, CURRENT_STATES).stream()
                .filter(pair -> activeTeachers.contains(pair.getTeacherUserId()))
                .forEach(pair ->
                        desired.computeIfAbsent(pair.getTeacherUserId(), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.courseSubjectTeachersGroup(
                                                workspaceId, pair.getCourseSubjectId())));
        enrollments.findEnrolledPairsByWorkspaceIdAndStateIn(workspaceId, CURRENT_STATES).stream()
                .filter(pair -> activeStudents.contains(pair.getStudentUserId()))
                .forEach(pair ->
                        desired.computeIfAbsent(pair.getStudentUserId(), _ -> new TreeSet<>())
                                .add(
                                        NativePolicyTemplates.courseSubjectStudentsGroup(
                                                workspaceId, pair.getCourseSubjectId())));
        return desired;
    }

    // ---------- Tiện ích dựng descriptor ----------

    /** Bộ tích lũy descriptor của một target: nguồn không hiệu lực thì không sinh descriptor. */
    private final class Sources {
        private final UUID workspaceId;
        private final String targetKey;
        private final Map<String, List<String>> sourcesByScope = new LinkedHashMap<>();
        private List<SubscriptionPeriod> loadedPeriods = List.of();
        private boolean periodsLoaded;

        private Sources(UUID workspaceId, String targetKey) {
            this.workspaceId = workspaceId;
            this.targetKey = targetKey;
        }

        private boolean adminGroupActive(UUID workspaceId, String role) {
            return preconditions.existsActiveMemberWithRole(workspaceId, role);
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
            for (SubscriptionPeriod period : periods()) {
                descriptors.add(periodDescriptor(workspaceId, groupPath, period, targetKey, scope));
            }
        }

        private List<SubscriptionPeriod> periods() {
            if (!periodsLoaded) {
                loadedPeriods = preconditions.subscriptionPeriods(workspaceId);
                periodsLoaded = true;
            }
            return loadedPeriods;
        }

        private Map<String, List<String>> byScope() {
            return sourcesByScope;
        }
    }

    private static String groupDescriptor(
            UUID workspaceId, String groupPath, String targetKey, String scope) {
        return GROUP_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|group="
                + groupPath
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static String periodDescriptor(
            UUID workspaceId,
            String groupPath,
            SubscriptionPeriod period,
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
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private boolean isCenter(UUID workspaceId) {
        return preconditions
                .workspaceInfo(workspaceId)
                .map(info -> "CENTER".equals(info.kind()))
                .orElse(false);
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
