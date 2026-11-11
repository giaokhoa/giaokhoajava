package com.giaokhoa.verification;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.model.SampleActivity;
import com.giaokhoa.verification.repository.SampleActivityRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationPartitionRepository;
import com.giaokhoa.verification.repository.VerificationCenterMembershipRepository;
import com.giaokhoa.verification.repository.VerificationEnrollmentRepository;
import com.giaokhoa.verification.repository.VerificationUserAccountRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import java.util.ArrayList;
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
 * động và ghi danh của môn–khóa của hoạt động). Ở task này projector chỉ quản lý scope {@code
 * submit} theo hợp đồng HTTP của SUBMIT_ACTIVITY: nguồn duy nhất là chính học sinh đang ghi danh ở
 * môn–khóa của hoạt động — ở center thêm điều kiện membership ACTIVE kèm role STUDENT hiện hành, ở
 * personal chỉ cần ghi danh của lớp personal — đúng mẫu nhánh học sinh của {@link
 * ResultPolicyProjector}. Các scope còn lại của target hoạt động ({@code read}, {@code
 * schedule.extend}, {@code attempt.open}, {@code reassign}) chưa có nguồn nào ở task này nên không
 * thuộc bundle: quyết định PDP trên chúng luôn từ chối, fail-closed cho tới khi action tương ứng
 * được cài.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace với scope danh nghĩa {@code submit}, cùng hình dạng với bundle kết quả. Projector này
 * vừa tính bundle khi ghi (qua {@link #recordActivityChange}), vừa dựng lại đúng bundle đó khi
 * {@code ProjectionReconciler} đối chiếu desired hash. Bundle được ghi lại ngay trước quyết định
 * nộp bài nên thu hồi ghi danh/membership được tôn trọng ở lượt nộp tiếp theo mà không cần đưa
 * bundle hoạt động vào các contributor thu hồi.
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
    private static final String BUNDLE_SCOPE = PermissionCatalog.ActivityScope.SUBMIT.scopeName();

    private final SampleActivityRepository activities;
    private final VerificationAuthorizationPartitionRepository partitions;
    private final VerificationEnrollmentRepository enrollments;
    private final VerificationWorkspaceRepository workspaces;
    private final VerificationUserAccountRepository userAccounts;
    private final VerificationCenterMembershipRepository memberships;
    private final WorkspaceTransaction workspaceTransaction;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    public ActivityPolicyProjector(
            SampleActivityRepository activities,
            VerificationAuthorizationPartitionRepository partitions,
            VerificationEnrollmentRepository enrollments,
            VerificationWorkspaceRepository workspaces,
            VerificationUserAccountRepository userAccounts,
            VerificationCenterMembershipRepository memberships,
            WorkspaceTransaction workspaceTransaction,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.partitions = partitions;
        this.enrollments = enrollments;
        this.workspaces = workspaces;
        this.userAccounts = userAccounts;
        this.memberships = memberships;
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
        return List.of(BUNDLE_SCOPE);
    }

    /**
     * Toàn bộ descriptor của bundle của một target hoạt động, dựng từ dữ kiện nguồn hiện hành. Rỗng
     * khi hoạt động không còn tồn tại hoặc không học sinh nào còn ghi danh hợp lệ — rỗng nghĩa là
     * target đó phải bị thu hồi (retract), không phải được giữ nguyên.
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
                                .map(UserAccount::getKeycloakSub)
                                .ifPresent(subject ->
                                        descriptors.add(
                                                actorDescriptor(
                                                        workspaceId,
                                                        studentUserId,
                                                        subject,
                                                        targetKey,
                                                        BUNDLE_SCOPE)));
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
                        .map(AuthorizationPartition::getId)
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
     * Các gate native của một canonical descriptor hoạt động: actor cụ thể của học sinh đang ghi
     * danh. Dùng bởi reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2 || !ACTOR_DESCRIPTOR.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported activity descriptor: " + descriptor);
        }
        String subject = partValue(parts, "subject=");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Activity descriptor has no subject: " + descriptor);
        }
        return List.of(new PolicyGraph.Gate.Actor(subject));
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
