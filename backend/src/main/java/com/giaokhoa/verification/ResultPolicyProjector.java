package com.giaokhoa.verification;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.guardians.model.GuardianLink;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.verification.model.SampleResult;
import com.giaokhoa.verification.repository.SampleResultRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationPartitionRepository;
import com.giaokhoa.verification.repository.VerificationCenterMembershipRepository;
import com.giaokhoa.verification.repository.VerificationEnrollmentRepository;
import com.giaokhoa.verification.repository.VerificationTeachingAssignmentRepository;
import com.giaokhoa.verification.repository.VerificationUserAccountRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của kết quả mẫu: nguồn sự thật duy nhất về desired native graph của các target kết quả
 * ({@code workspace:{wid}:result:{id}}) của một workspace, dựng thuần từ các dòng bền (kết quả, ghi
 * danh, phân công giảng dạy, liên kết phụ huynh đã duyệt và owner của workspace). Các scope của kết
 * quả tách bạch theo hợp đồng: {@code read.published} chỉ nối vào kết quả đã {@code PUBLISHED}
 * (publication gate nằm ở tầng chiếu, không tin trạng thái phía client khai) và có bốn nguồn độc
 * lập — chính học sinh sở hữu kết quả khi còn ghi danh ở môn–khóa của kết quả, group phụ huynh được
 * duyệt của học sinh đó, group giáo viên của môn–khóa, và owner của lớp personal; {@code
 * read}/{@code grade}/{@code publish} chỉ có nguồn giáo viên đúng môn–khóa ở center hoặc owner ở
 * personal, không có nhánh owner/manager của center đứng riêng.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace với scope danh nghĩa {@code read}, cùng hình dạng với bundle giảng dạy. Projector này
 * vừa tính bundle khi ghi (qua {@link #recordResultChange}), vừa dựng lại đúng bundle đó khi {@code
 * ProjectionReconciler} đối chiếu desired hash.
 *
 * <p>Nguồn phụ huynh dùng đúng descriptor canonical {@code GUARDIAN_GROUP} của {@code
 * GuardianPolicyProjector} (gói guardians): membership thật của group phụ huynh do projector đó
 * đồng bộ từ liên kết APPROVED — descriptor ở đây chỉ được phát khi có ít nhất một liên kết
 * APPROVED mà cả phụ huynh (membership ACTIVE kèm role PARENT) và học sinh (membership ACTIVE kèm
 * role STUDENT) còn hiệu lực, tức đúng điều kiện group có thành viên thật khi writer áp graph.
 *
 * <p>Các bảng kết quả/liên kết nằm trong schema tenant của workspace, mà phía reconcile không có
 * transaction bao quanh sẵn: mọi phép dựng bundle ở đây tự bọc trong một transaction ngắn có đặt
 * context của đúng workspace trước truy vấn đầu tiên, như projector giảng dạy. Dữ kiện của module
 * khác (ghi danh, phân công, workspace, tài khoản, liên kết phụ huynh) được đọc qua repository của
 * module sở tại và phân giải theo định danh ở tầng ứng dụng.
 */
@Component
public class ResultPolicyProjector {

    private static final String GROUP_DESCRIPTOR = "RESULT_GROUP";
    private static final String ACTOR_DESCRIPTOR = "RESULT_ACTOR";
    private static final String GUARDIAN_DESCRIPTOR = "GUARDIAN_GROUP";
    private static final String BUNDLE_SCOPE = PermissionCatalog.ResultScope.READ.scopeName();

    private final SampleResultRepository results;
    private final VerificationAuthorizationPartitionRepository partitions;
    private final VerificationTeachingAssignmentRepository assignments;
    private final VerificationEnrollmentRepository enrollments;
    private final VerificationWorkspaceRepository workspaces;
    private final VerificationUserAccountRepository userAccounts;
    private final GuardianLinkRepository guardianLinks;
    private final VerificationCenterMembershipRepository memberships;
    private final WorkspaceTransaction workspaceTransaction;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    public ResultPolicyProjector(
            SampleResultRepository results,
            VerificationAuthorizationPartitionRepository partitions,
            VerificationTeachingAssignmentRepository assignments,
            VerificationEnrollmentRepository enrollments,
            VerificationWorkspaceRepository workspaces,
            VerificationUserAccountRepository userAccounts,
            GuardianLinkRepository guardianLinks,
            VerificationCenterMembershipRepository memberships,
            WorkspaceTransaction workspaceTransaction,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.results = results;
        this.partitions = partitions;
        this.assignments = assignments;
        this.enrollments = enrollments;
        this.workspaces = workspaces;
        this.userAccounts = userAccounts;
        this.guardianLinks = guardianLinks;
        this.memberships = memberships;
        this.workspaceTransaction = workspaceTransaction;
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle kết quả. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một target kết quả, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.ResultScope.values());
    }

    /**
     * Toàn bộ descriptor của bundle của một target kết quả, dựng từ dữ kiện nguồn hiện hành. Rỗng
     * khi kết quả không còn tồn tại hoặc không nguồn nào còn hiệu lực — rỗng nghĩa là target đó
     * phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String targetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        if (type != NativePolicyTemplates.TargetType.RESULT) {
            throw new IllegalArgumentException("Not a result target key: " + targetKey);
        }
        UUID workspaceId = workspaceIdOf(targetKey);
        UUID resultId = targetIdOf(targetKey);
        List<String> bundle =
                transactions.execute(_ -> {
                    workspaceTransaction.applyContext(workspaceId);
                    SampleResult result =
                            results.findByWorkspaceIdAndId(workspaceId, resultId).orElse(null);
                    if (result == null) {
                        return List.of();
                    }
                    UUID studentUserId = result.getStudentUserId();
                    UUID courseSubjectId = result.getCourseSubjectId();
                    boolean published = "PUBLISHED".equals(result.getState());
                    List<String> descriptors = new ArrayList<>();
                    if (isCenter(workspaceId)) {
                        centerSources(
                                descriptors,
                                workspaceId,
                                targetKey,
                                studentUserId,
                                courseSubjectId,
                                published);
                    } else if (isPersonal(workspaceId)) {
                        personalSources(
                                descriptors,
                                workspaceId,
                                targetKey,
                                studentUserId,
                                courseSubjectId,
                                published);
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

    /** Mọi target kết quả hiện có của workspace: mọi hàng {@code sample_result} của workspace. */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    workspaceTransaction.applyContext(workspaceId);
                    return results.findIdsByWorkspaceId(workspaceId).stream()
                            .map(resultId -> NativePolicyTemplates.resultKey(workspaceId, resultId))
                            .toList();
                });
        return Objects.requireNonNull(targets, "targets");
    }

    /**
     * Desired projection của mọi bundle kết quả hiện có của workspace, để gắn vào một operation.
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
     * Ghi operation cho một thay đổi làm đổi nguồn của các bundle kết quả vừa được ghi trong cùng
     * transaction của service gọi: bump desired revision của WORKSPACE partition của workspace,
     * fence nó và lưu bundle hiện hành của từng target bị ảnh hưởng. Replay cùng idempotency key và
     * payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác payload là 409 — cả
     * hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordResultChange(
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
     * Các gate native của một canonical descriptor kết quả: group nguồn cho descriptor group (gồm
     * cả descriptor {@code GUARDIAN_GROUP} của nguồn phụ huynh, cùng một gate membership trong
     * group của nó), và actor cụ thể cho descriptor actor (học sinh sở hữu kết quả hoặc owner của
     * personal). Dùng bởi reconciliation để dựng lại đúng graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < 2
                || !(GROUP_DESCRIPTOR.equals(parts[0])
                        || ACTOR_DESCRIPTOR.equals(parts[0])
                        || GUARDIAN_DESCRIPTOR.equals(parts[0]))) {
            throw new IllegalArgumentException("Unsupported result descriptor: " + descriptor);
        }
        if (GROUP_DESCRIPTOR.equals(parts[0]) || GUARDIAN_DESCRIPTOR.equals(parts[0])) {
            String groupPath = partValue(parts, "group=");
            if (groupPath == null || groupPath.isBlank()) {
                throw new IllegalArgumentException("Result descriptor has no group: " + descriptor);
            }
            return List.of(new PolicyGraph.Gate.GroupMembership(groupPath));
        }
        String subject = partValue(parts, "subject=");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Result descriptor has no subject: " + descriptor);
        }
        return List.of(new PolicyGraph.Gate.Actor(subject));
    }

    // ---------- Dựng nguồn ----------

    private void centerSources(
            List<String> descriptors,
            UUID workspaceId,
            String targetKey,
            UUID studentUserId,
            UUID courseSubjectId,
            boolean published) {
        String teachers =
                NativePolicyTemplates.courseSubjectTeachersGroup(workspaceId, courseSubjectId);
        boolean teachersPresent =
                assignments.existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
                        workspaceId, courseSubjectId, List.of("PENDING", "ACTIVE"));
        if (teachersPresent) {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            teachers,
                            targetKey,
                            PermissionCatalog.ResultScope.READ.scopeName()));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            teachers,
                            targetKey,
                            PermissionCatalog.ResultScope.GRADE.scopeName()));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            teachers,
                            targetKey,
                            PermissionCatalog.ResultScope.PUBLISH.scopeName()));
        }
        if (!published) {
            return;
        }
        // Publication gate: các nguồn của read.published chỉ tồn tại khi kết quả đã PUBLISHED.
        if (teachersPresent) {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            teachers,
                            targetKey,
                            PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName()));
        }
        if (guardianSourcePresent(workspaceId, studentUserId)) {
            descriptors.add(
                    guardianGroupDescriptor(
                            workspaceId,
                            studentUserId,
                            targetKey,
                            PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName()));
        }
        studentSubject(workspaceId, studentUserId, courseSubjectId, true)
                .ifPresent(subject ->
                        descriptors.add(
                                actorDescriptor(
                                        workspaceId,
                                        studentUserId,
                                        subject,
                                        targetKey,
                                        PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName())));
    }

    private void personalSources(
            List<String> descriptors,
            UUID workspaceId,
            String targetKey,
            UUID studentUserId,
            UUID courseSubjectId,
            boolean published) {
        Workspace workspace = workspaces.findById(workspaceId).orElse(null);
        if (workspace != null && workspace.getKind() == Workspace.Kind.PERSONAL) {
            UUID ownerId = workspace.getOwnerUserId();
            UserAccount owner =
                    ownerId == null ? null : userAccounts.findById(ownerId).orElse(null);
            if (owner != null) {
                String subject = Objects.requireNonNull(owner.getKeycloakSub(), "keycloak_sub");
                descriptors.add(
                        actorDescriptor(
                                workspaceId,
                                ownerId,
                                subject,
                                targetKey,
                                PermissionCatalog.ResultScope.READ.scopeName()));
                descriptors.add(
                        actorDescriptor(
                                workspaceId,
                                ownerId,
                                subject,
                                targetKey,
                                PermissionCatalog.ResultScope.GRADE.scopeName()));
                descriptors.add(
                        actorDescriptor(
                                workspaceId,
                                ownerId,
                                subject,
                                targetKey,
                                PermissionCatalog.ResultScope.PUBLISH.scopeName()));
                if (published) {
                    descriptors.add(
                            actorDescriptor(
                                    workspaceId,
                                    ownerId,
                                    subject,
                                    targetKey,
                                    PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName()));
                }
            }
        }
        if (!published) {
            return;
        }
        studentSubject(workspaceId, studentUserId, courseSubjectId, false)
                .ifPresent(subject ->
                        descriptors.add(
                                actorDescriptor(
                                        workspaceId,
                                        studentUserId,
                                        subject,
                                        targetKey,
                                        PermissionCatalog.ResultScope.READ_PUBLISHED.scopeName())));
    }

    /**
     * Vai trò hiện hành của một tài khoản trong workspace, đọc thẳng từ dòng membership ACTIVE qua
     * repository: projector này nằm trong chuỗi chiếu mà {@code MembershipService} cũng phụ thuộc
     * vào (qua {@code MembershipPolicyProjector} và {@code SourceRevocationCoordinator}), nên một
     * phụ thuộc service ngược về tenancy sẽ khép vòng khởi tạo bean.
     */
    private Set<MembershipRole.Role> activeRoles(UUID userId, UUID workspaceId) {
        return memberships
                .findByWorkspaceIdAndUserIdAndState(
                        workspaceId, userId, CenterMembership.State.ACTIVE)
                .map(membership -> Set.copyOf(membership.getRoles()))
                .orElse(Set.of());
    }

    /**
     * Subject của học sinh sở hữu kết quả khi nhánh học sinh còn hiệu lực: còn ghi danh ở môn–khóa
     * của kết quả và, ở center, còn membership ACTIVE kèm role STUDENT hiện hành. Rỗng khi nhánh đã
     * mất hiệu lực thì nguồn học sinh không được chiếu.
     */
    private Optional<String> studentSubject(
            UUID workspaceId, UUID studentUserId, UUID courseSubjectId, boolean center) {
        List<UUID> enrolled =
                enrollments.findEnrolledStudentIds(workspaceId, courseSubjectId, studentUserId);
        if (enrolled.isEmpty()) {
            return Optional.empty();
        }
        if (center
                && !activeRoles(studentUserId, workspaceId).contains(MembershipRole.Role.STUDENT)) {
            return Optional.empty();
        }
        return userAccounts.findById(studentUserId).map(user -> user.getKeycloakSub());
    }

    /**
     * Nguồn phụ huynh của một học sinh chỉ tồn tại khi có ít nhất một liên kết APPROVED mà cả phụ
     * huynh (membership ACTIVE kèm role PARENT) và học sinh (membership ACTIVE kèm role STUDENT)
     * còn hiệu lực — đúng điều kiện mà phép đồng bộ group của {@code GuardianPolicyProjector} áp
     * dụng, để group của descriptor luôn có ít nhất một thành viên thật khi writer áp graph.
     */
    private boolean guardianSourcePresent(UUID workspaceId, UUID studentUserId) {
        if (!activeRoles(studentUserId, workspaceId).contains(MembershipRole.Role.STUDENT)) {
            return false;
        }
        return guardianLinks
                .findByWorkspaceIdAndStudentUserIdAndState(
                        workspaceId, studentUserId, GuardianLink.APPROVED)
                .stream()
                .anyMatch(link ->
                        activeRoles(link.getParentUserId(), workspaceId)
                                .contains(MembershipRole.Role.PARENT));
    }

    // ---------- Tiện ích dựng descriptor ----------

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

    /**
     * Descriptor canonical của nguồn phụ huynh cho một target kết quả: khớp từng ký tự với {@code
     * GuardianPolicyProjector.guardianGroupDescriptor} của gói guardians — một định dạng duy nhất
     * cho nguồn này, để desired hash của bundle kết quả ổn định bất kể projector nào dựng lại nó.
     */
    private static String guardianGroupDescriptor(
            UUID workspaceId, UUID studentUserId, String targetKey, String scope) {
        return GUARDIAN_DESCRIPTOR
                + "|workspace="
                + workspaceId
                + "|group="
                + NativePolicyTemplates.guardiansGroup(workspaceId, studentUserId)
                + "|target="
                + targetKey
                + PolicyGraph.SCOPE_MARKER
                + scope;
    }

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

    private boolean isCenter(UUID workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace -> workspace.getKind() == Workspace.Kind.CENTER)
                .orElse(false);
    }

    private boolean isPersonal(UUID workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace -> workspace.getKind() == Workspace.Kind.PERSONAL)
                .orElse(false);
    }

    private static UUID workspaceIdOf(String targetKey) {
        // Mọi target kết quả đều bắt đầu bằng "workspace:{id}:".
        return UUID.fromString(
                targetKey.substring("workspace:".length(), "workspace:".length() + 36));
    }

    private static UUID targetIdOf(String targetKey) {
        return UUID.fromString(targetKey.substring(targetKey.lastIndexOf(':') + 1));
    }
}
