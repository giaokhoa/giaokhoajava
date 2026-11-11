package com.giaokhoa.verification;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.subscriptions.model.SubscriptionPeriod;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.model.SampleActivity;
import com.giaokhoa.verification.repository.SampleActivityRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationPartitionRepository;
import com.giaokhoa.verification.repository.VerificationCenterMembershipRepository;
import com.giaokhoa.verification.repository.VerificationEnrollmentRepository;
import com.giaokhoa.verification.repository.VerificationHomeroomAssignmentRepository;
import com.giaokhoa.verification.repository.VerificationSubscriptionPeriodRepository;
import com.giaokhoa.verification.repository.VerificationTeachingAssignmentRepository;
import com.giaokhoa.verification.repository.VerificationUserAccountRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của hoạt động mẫu: nguồn sự thật duy nhất về desired native graph của các target hoạt
 * động ({@code workspace:{wid}:activity:{id}}) của một workspace, dựng thuần từ các dòng bền (hoạt
 * động và ghi danh của môn–khóa của hoạt động). Projector quản lý scope {@code submit} theo hợp
 * đồng HTTP của SUBMIT_ACTIVITY: nguồn duy nhất là chính học sinh đang ghi danh ở môn–khóa của hoạt
 * động — ở center thêm điều kiện membership ACTIVE kèm role STUDENT hiện hành, ở personal chỉ cần
 * ghi danh của lớp personal — đúng mẫu nhánh học sinh của {@link ResultPolicyProjector}. Ba scope
 * quản lý hoạt động ({@code schedule.extend}, {@code attempt.open}, {@code reassign}) dùng chung
 * một tập nguồn là tập thẩm quyền giao bài của hợp đồng authorization: ở center là giáo viên đang
 * được phân công ở môn–khóa của hoạt động, chủ nhiệm của lớp chứa môn–khóa đó và chủ sở hữu/quản lý
 * của center (mọi nguồn đều đòi membership ACTIVE hiện hành); ở personal là chủ sở hữu của
 * workspace. Mọi nguồn quản lý còn AND thêm cửa sổ hiệu lực của từng kỳ gói đã xác nhận của
 * workspace qua time policy native: gói hết hạn thì chính PDP từ chối gia hạn, mở lượt mới và giao
 * lại, còn {@code submit} là đường hoàn tất bài đã giao nên không qua gate gói — nguồn của nó chỉ
 * theo ghi danh và membership hiện hành như trên. Vì bundle được ghi lại trước mọi quyết định, một
 * kỳ gói mới chỉ mở lại các scope quản lý cho người còn giữ thẩm quyền giao bài ở lần dựng lại đó;
 * nguồn đã bị thu hồi không hồi lại theo kỳ mới. Scope {@code read} của target hoạt động chưa có
 * action nào dùng nên chưa thuộc bundle: quyết định PDP trên nó luôn từ chối, fail-closed.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace với scope danh nghĩa {@code submit}, cùng hình dạng với bundle kết quả. Projector này
 * vừa tính bundle khi ghi (qua {@link #recordActivityChange}), vừa dựng lại đúng bundle đó khi
 * {@code ProjectionReconciler} đối chiếu desired hash. Bundle được ghi lại ngay trước quyết định
 * nên thu hồi ghi danh/membership/phân công được tôn trọng ở lượt quyết định tiếp theo mà không cần
 * đưa bundle hoạt động vào các contributor thu hồi.
 *
 * <p>Các bảng hoạt động/ghi danh nằm theo context workspace của transaction, mà phía reconcile
 * không có transaction bao quanh sẵn: mọi phép dựng bundle ở đây tự bọc trong một transaction ngắn
 * có đặt context của đúng workspace trước truy vấn đầu tiên, như projector kết quả. Dữ kiện của
 * module khác (ghi danh, workspace, tài khoản, membership) được đọc qua repository của module sở
 * tại và phân giải theo định danh ở tầng ứng dụng.
 */
@Component
public class ActivityPolicyProjector {

    private static final String ACTOR_DESCRIPTOR = "ACTIVITY_ACTOR";
    private static final String ACTOR_PERIOD_DESCRIPTOR = "ACTIVITY_ACTOR_PERIOD";
    private static final String BUNDLE_SCOPE = PermissionCatalog.ActivityScope.SUBMIT.scopeName();

    /** Các scope quản lý hoạt động dùng chung một tập nguồn thẩm quyền giao bài. */
    private static final List<String> MANAGEMENT_SCOPES =
            List.of(
                    PermissionCatalog.ActivityScope.SCHEDULE_EXTEND.scopeName(),
                    PermissionCatalog.ActivityScope.ATTEMPT_OPEN.scopeName(),
                    PermissionCatalog.ActivityScope.REASSIGN.scopeName());

    private final SampleActivityRepository activities;
    private final VerificationAuthorizationPartitionRepository partitions;
    private final VerificationEnrollmentRepository enrollments;
    private final VerificationSubscriptionPeriodRepository periods;
    private final VerificationWorkspaceRepository workspaces;
    private final VerificationUserAccountRepository userAccounts;
    private final VerificationCenterMembershipRepository memberships;
    private final VerificationTeachingAssignmentRepository assignments;
    private final VerificationHomeroomAssignmentRepository homeroomAssignments;
    private final WorkspaceTransaction workspaceTransaction;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    public ActivityPolicyProjector(
            SampleActivityRepository activities,
            VerificationAuthorizationPartitionRepository partitions,
            VerificationEnrollmentRepository enrollments,
            VerificationSubscriptionPeriodRepository periods,
            VerificationWorkspaceRepository workspaces,
            VerificationUserAccountRepository userAccounts,
            VerificationCenterMembershipRepository memberships,
            VerificationTeachingAssignmentRepository assignments,
            VerificationHomeroomAssignmentRepository homeroomAssignments,
            WorkspaceTransaction workspaceTransaction,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.partitions = partitions;
        this.enrollments = enrollments;
        this.periods = periods;
        this.workspaces = workspaces;
        this.userAccounts = userAccounts;
        this.memberships = memberships;
        this.assignments = assignments;
        this.homeroomAssignments = homeroomAssignments;
        this.workspaceTransaction = workspaceTransaction;
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle hoạt động. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một target hoạt động, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return List.of(
                PermissionCatalog.ActivityScope.SUBMIT.scopeName(),
                PermissionCatalog.ActivityScope.SCHEDULE_EXTEND.scopeName(),
                PermissionCatalog.ActivityScope.ATTEMPT_OPEN.scopeName(),
                PermissionCatalog.ActivityScope.REASSIGN.scopeName());
    }

    /**
     * Toàn bộ descriptor của bundle của một target hoạt động, dựng từ dữ kiện nguồn hiện hành. Rỗng
     * khi hoạt động không còn tồn tại hoặc không nguồn nào (học sinh ghi danh, thẩm quyền quản lý)
     * còn hiệu lực — rỗng nghĩa là target đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String targetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        if (type != NativePolicyTemplates.TargetType.ACTIVITY) {
            throw new IllegalArgumentException("Not an activity target key: " + targetKey);
        }
        UUID workspaceId = workspaceIdOf(targetKey);
        UUID activityId = targetIdOf(targetKey);
        List<String> bundle =
                transactions.execute(_ -> {
                    workspaceTransaction.applyContext(workspaceId);
                    SampleActivity activity =
                            activities.findByWorkspaceIdAndId(workspaceId, activityId).orElse(null);
                    if (activity == null) {
                        return List.of();
                    }
                    boolean center = isCenter(workspaceId);
                    List<String> descriptors = new ArrayList<>();
                    for (UUID studentUserId :
                            enrollments.findEnrolledStudentIdsByCourseSubject(
                                    workspaceId, activity.getCourseSubjectId())) {
                        if (center
                                && !activeRoles(studentUserId, workspaceId)
                                        .contains(MembershipRole.Role.STUDENT)) {
                            continue;
                        }
                        userAccounts
                                .findById(studentUserId)
                                .map(user -> user.getKeycloakSub())
                                .ifPresent(subject ->
                                        descriptors.add(
                                                actorDescriptor(
                                                        workspaceId,
                                                        studentUserId,
                                                        subject,
                                                        targetKey,
                                                        BUNDLE_SCOPE)));
                    }
                    List<SubscriptionPeriod> workspacePeriods =
                            periods.findByWorkspaceIdOrderByValidFromAscIdAsc(workspaceId);
                    for (ManagerSource manager :
                            activityManagers(workspaceId, activity.getCourseSubjectId(), center)) {
                        for (String scope : MANAGEMENT_SCOPES) {
                            for (SubscriptionPeriod period : workspacePeriods) {
                                descriptors.add(
                                        actorPeriodDescriptor(
                                                workspaceId,
                                                manager.userId(),
                                                manager.subject(),
                                                period,
                                                targetKey,
                                                scope));
                            }
                        }
                    }
                    return descriptors.stream().sorted().distinct().toList();
                });
        // Lambda ở trên luôn trả danh sách; execute chỉ trả null khi callback trả null.
        return Objects.requireNonNull(bundle, "bundle");
    }

    /** Các descriptor của riêng một scope trong bundle của target, dựng lại từ dữ kiện nguồn. */
    public List<String> sourcesFor(String targetKey, String scope) {
        return bundleDescriptors(targetKey).stream()
                .filter(descriptor -> descriptor.endsWith(PolicyGraph.SCOPE_MARKER + scope))
                .toList();
    }

    /**
     * Mọi target hoạt động hiện có của workspace: mọi hàng {@code sample_activity} của workspace.
     */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    workspaceTransaction.applyContext(workspaceId);
                    return activities.findIdsByWorkspaceId(workspaceId).stream()
                            .map(activityId ->
                                    NativePolicyTemplates.activityKey(workspaceId, activityId))
                            .toList();
                });
        return Objects.requireNonNull(targets, "targets");
    }

    /**
     * Desired projection của mọi bundle hoạt động hiện có của workspace, để gắn vào một operation
     * ghi kèm khi dữ kiện tiền đề của bundle (kỳ gói, phân công, ghi danh) thay đổi: descriptor
     * quản lý nhúng từng kỳ gói nên thêm kỳ mà không ghi lại bundle sẽ làm reconcile chết {@code
     * DESIRED_SOURCE_MISMATCH}.
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
     * Ghi operation cho một thay đổi làm đổi nguồn của các bundle hoạt động vừa được ghi trong cùng
     * transaction của service gọi: bump desired revision của WORKSPACE partition của workspace,
     * fence nó và lưu bundle hiện hành của từng target bị ảnh hưởng. Replay cùng idempotency key và
     * payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác payload là 409 — cả
     * hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordActivityChange(
            UUID initiatorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            List<String> targetKeys) {
        List<UUID> partitionIds =
                partitions
                        .findByKindAndOwnerKey(
                                AuthorizationPartition.Kind.WORKSPACE, "workspace:" + workspaceId)
                        .stream()
                        .map(partition -> partition.getId())
                        .toList();
        if (partitionIds.isEmpty()) {
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
                                partitionIds.getFirst(), List.copyOf(projections))));
    }

    /**
     * Các gate native của một canonical descriptor hoạt động: actor cụ thể của nguồn, cộng cửa sổ
     * hiệu lực của kỳ gói với descriptor của các scope quản lý. Dùng bởi reconciliation để dựng lại
     * đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !(ACTOR_DESCRIPTOR.equals(parts[0])
                        || ACTOR_PERIOD_DESCRIPTOR.equals(parts[0]))) {
            throw new IllegalArgumentException("Unsupported activity descriptor: " + descriptor);
        }
        String subject = partValue(parts, "subject=");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Activity descriptor has no subject: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        gates.add(new PolicyGraph.Gate.Actor(subject));
        if (ACTOR_PERIOD_DESCRIPTOR.equals(parts[0])) {
            String from = partValue(parts, "from=");
            String until = partValue(parts, "until=");
            if (from == null || until == null) {
                throw new IllegalArgumentException(
                        "Activity period descriptor is incomplete: " + descriptor);
            }
            gates.add(new PolicyGraph.Gate.Validity(Instant.parse(from), Instant.parse(until)));
        }
        return List.copyOf(gates);
    }

    // ---------- Dựng nguồn ----------

    /**
     * Vai trò hiện hành của một tài khoản trong workspace, đọc thẳng từ dòng membership ACTIVE qua
     * repository, cùng mẫu projector kết quả (đọc qua service tenancy sẽ khép vòng khởi tạo bean
     * qua chuỗi chiếu).
     */
    private Set<MembershipRole.Role> activeRoles(UUID userId, UUID workspaceId) {
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, userId, CenterMembership.State.ACTIVE)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    private boolean isCenter(UUID workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace -> workspace.getKind() == Workspace.Kind.CENTER)
                .orElse(false);
    }

    /** Một người có thẩm quyền giao bài kèm định danh phiên của họ, là nguồn của scope quản lý. */
    private record ManagerSource(UUID userId, String subject) {}

    /**
     * Mọi người có thẩm quyền giao bài trên môn–khóa của hoạt động, là nguồn của ba scope quản lý
     * hoạt động: ở personal là chủ sở hữu của workspace; ở center là giáo viên đang được phân công
     * ở môn–khóa, chủ nhiệm của lớp chứa môn–khóa và chủ sở hữu/quản lý của center, mọi nguồn đều
     * đòi membership ACTIVE hiện hành như các projector khác của center.
     */
    private List<ManagerSource> activityManagers(
            UUID workspaceId, UUID courseSubjectId, boolean center) {
        if (!center) {
            return workspaces
                    .findById(workspaceId)
                    .filter(workspace -> workspace.getKind() == Workspace.Kind.PERSONAL)
                    .map(workspace -> workspace.getOwnerUserId())
                    .flatMap(userAccounts::findById)
                    .map(owner -> List.of(new ManagerSource(owner.getId(), owner.getKeycloakSub())))
                    .orElse(List.of());
        }
        Set<UUID> candidateIds = new HashSet<>();
        candidateIds.addAll(
                assignments.findTeacherUserIdsByCourseSubject(workspaceId, courseSubjectId));
        candidateIds.addAll(
                homeroomAssignments.findTeacherUserIdsByCourseSubject(
                        workspaceId, courseSubjectId));
        memberships
                .findByWorkspaceIdAndState(workspaceId, CenterMembership.State.ACTIVE)
                .forEach(membership -> {
                    Set<MembershipRole.Role> roles = Set.copyOf(membership.getRoles());
                    if (roles.contains(MembershipRole.Role.OWNER)
                            || roles.contains(MembershipRole.Role.MANAGER)) {
                        candidateIds.add(membership.getUserId());
                    }
                });
        List<ManagerSource> managers = new ArrayList<>();
        for (UUID candidateId : candidateIds) {
            // Mọi nguồn center đều đòi membership ACTIVE hiện hành của chính người đó.
            if (memberships
                    .findByWorkspaceIdAndUserIdAndState(
                            workspaceId, candidateId, CenterMembership.State.ACTIVE)
                    .isEmpty()) {
                continue;
            }
            userAccounts
                    .findById(candidateId)
                    .ifPresent(account ->
                            managers.add(
                                    new ManagerSource(account.getId(), account.getKeycloakSub())));
        }
        return managers;
    }

    // ---------- Tiện ích dựng descriptor ----------

    private static String actorDescriptor(
            UUID workspaceId, UUID userId, String subject, String targetKey, String scope) {
        return ACTOR_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + subject
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    /** Canonical descriptor của một nguồn quản lý hoạt động bị gate bởi một kỳ gói cụ thể. */
    private static String actorPeriodDescriptor(
            UUID workspaceId,
            UUID userId,
            String subject,
            SubscriptionPeriod period,
            String targetKey,
            String scope) {
        return ACTOR_PERIOD_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|user="
                + userId
                + "|subject="
                + subject
                + "|period="
                + period.getId()
                + "|from="
                + period.getValidFrom()
                + "|until="
                + period.getExpiresAt()
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

    private static @Nullable String partValue(String[] parts, String prefix) {
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private static UUID workspaceIdOf(String targetKey) {
        // Mọi target hoạt động đều bắt đầu bằng "workspace:{id}:".
        return UUID.fromString(
                targetKey.substring("workspace:".length(), "workspace:".length() + 36));
    }

    private static UUID targetIdOf(String targetKey) {
        return UUID.fromString(targetKey.substring(targetKey.lastIndexOf(':') + 1));
    }
}
