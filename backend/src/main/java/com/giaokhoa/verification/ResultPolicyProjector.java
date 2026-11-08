package com.giaokhoa.verification;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Các bảng kết quả/liên kết nằm dưới RLS theo context workspace của transaction, mà phía
 * reconcile không có transaction bao quanh sẵn: mọi phép dựng bundle ở đây tự bọc trong một
 * transaction ngắn có đặt context của đúng workspace trước truy vấn đầu tiên, như projector giảng
 * dạy.
 */
@Component
public class ResultPolicyProjector {

    private static final String GROUP_DESCRIPTOR = "RESULT_GROUP";
    private static final String ACTOR_DESCRIPTOR = "RESULT_ACTOR";
    private static final String GUARDIAN_DESCRIPTOR = "GUARDIAN_GROUP";
    private static final String BUNDLE_SCOPE = PermissionCatalog.ResultScope.READ.scopeName();

    private final JdbcTemplate jdbc;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    public ResultPolicyProjector(
            JdbcTemplate jdbc,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
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
                    applyContext(workspaceId);
                    List<Map<String, Object>> rows =
                            jdbc.queryForList(
                                    "SELECT student_user_id, course_subject_id, state"
                                            + " FROM sample_result WHERE workspace_id = ?"
                                            + " AND id = ?",
                                    workspaceId,
                                    resultId);
                    if (rows.isEmpty()) {
                        return List.of();
                    }
                    Map<String, Object> result = rows.getFirst();
                    UUID studentUserId =
                            Objects.requireNonNull(
                                    (UUID) result.get("student_user_id"), "student_user_id");
                    UUID courseSubjectId =
                            Objects.requireNonNull(
                                    (UUID) result.get("course_subject_id"), "course_subject_id");
                    boolean published = "PUBLISHED".equals(result.get("state"));
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
                .filter(descriptor -> descriptor.endsWith("|scope=" + scope))
                .toList();
    }

    /** Mọi target kết quả hiện có của workspace: mọi hàng {@code sample_result} của workspace. */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    applyContext(workspaceId);
                    return jdbc
                            .queryForList(
                                    "SELECT id FROM sample_result WHERE workspace_id = ?",
                                    UUID.class,
                                    workspaceId)
                            .stream()
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
                rowExists(
                        "SELECT id FROM teaching_assignment WHERE workspace_id = ?"
                                + " AND course_subject_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        courseSubjectId);
        if (teachersPresent) {
            descriptors.add(groupDescriptor(workspaceId, teachers, targetKey, "read"));
            descriptors.add(groupDescriptor(workspaceId, teachers, targetKey, "grade"));
            descriptors.add(groupDescriptor(workspaceId, teachers, targetKey, "publish"));
        }
        if (!published) {
            return;
        }
        // Publication gate: các nguồn của read.published chỉ tồn tại khi kết quả đã PUBLISHED.
        if (teachersPresent) {
            descriptors.add(groupDescriptor(workspaceId, teachers, targetKey, "read.published"));
        }
        if (guardianSourcePresent(workspaceId, studentUserId)) {
            descriptors.add(
                    guardianGroupDescriptor(
                            workspaceId, studentUserId, targetKey, "read.published"));
        }
        studentSubject(workspaceId, studentUserId, courseSubjectId, true)
                .ifPresent(subject ->
                        descriptors.add(
                                actorDescriptor(
                                        workspaceId,
                                        studentUserId,
                                        subject,
                                        targetKey,
                                        "read.published")));
    }

    private void personalSources(
            List<String> descriptors,
            UUID workspaceId,
            String targetKey,
            UUID studentUserId,
            UUID courseSubjectId,
            boolean published) {
        List<Map<String, Object>> owners =
                jdbc.queryForList(
                        "SELECT u.id AS user_id, u.keycloak_sub AS subject FROM workspace w"
                                + " JOIN user_account u ON u.id = w.owner_user_id"
                                + " WHERE w.id = ? AND w.kind = 'PERSONAL'",
                        workspaceId);
        if (!owners.isEmpty()) {
            Map<String, Object> owner = owners.getFirst();
            UUID ownerId = Objects.requireNonNull((UUID) owner.get("user_id"), "user_id");
            String subject = Objects.requireNonNull((String) owner.get("subject"), "keycloak_sub");
            descriptors.add(actorDescriptor(workspaceId, ownerId, subject, targetKey, "read"));
            descriptors.add(actorDescriptor(workspaceId, ownerId, subject, targetKey, "grade"));
            descriptors.add(actorDescriptor(workspaceId, ownerId, subject, targetKey, "publish"));
            if (published) {
                descriptors.add(
                        actorDescriptor(
                                workspaceId, ownerId, subject, targetKey, "read.published"));
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
                                        "read.published")));
    }

    /**
     * Subject của học sinh sở hữu kết quả khi nhánh học sinh còn hiệu lực: còn ghi danh ở môn–khóa
     * của kết quả và, ở center, còn membership ACTIVE kèm role STUDENT hiện hành. Rỗng khi nhánh đã
     * mất hiệu lực thì nguồn học sinh không được chiếu.
     */
    private Optional<String> studentSubject(
            UUID workspaceId, UUID studentUserId, UUID courseSubjectId, boolean center) {
        String sql =
                "SELECT u.keycloak_sub FROM enrollment e"
                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                        + " JOIN user_account u ON u.id = cs.student_user_id"
                        + " WHERE e.workspace_id = ? AND e.course_subject_id = ?"
                        + " AND cs.student_user_id = ? AND e.state IN ('PENDING', 'ACTIVE')";
        if (center) {
            sql +=
                    " AND EXISTS (SELECT 1 FROM center_membership m"
                            + " JOIN membership_role r ON r.membership_id = m.id"
                            + " WHERE m.workspace_id = e.workspace_id AND m.user_id = ?"
                            + " AND m.state = 'ACTIVE' AND r.role = 'STUDENT')";
            return jdbc
                    .queryForList(
                            sql,
                            String.class,
                            workspaceId,
                            courseSubjectId,
                            studentUserId,
                            studentUserId)
                    .stream()
                    .findFirst();
        }
        return jdbc
                .queryForList(sql, String.class, workspaceId, courseSubjectId, studentUserId)
                .stream()
                .findFirst();
    }

    /**
     * Nguồn phụ huynh của một học sinh chỉ tồn tại khi có ít nhất một liên kết APPROVED mà cả phụ
     * huynh (membership ACTIVE kèm role PARENT) và học sinh (membership ACTIVE kèm role STUDENT)
     * còn hiệu lực — đúng điều kiện mà phép đồng bộ group của {@code GuardianPolicyProjector} áp
     * dụng, để group của descriptor luôn có ít nhất một thành viên thật khi writer áp graph.
     */
    private boolean guardianSourcePresent(UUID workspaceId, UUID studentUserId) {
        return rowExists(
                "SELECT l.id FROM guardian_link l"
                        + " WHERE l.workspace_id = ? AND l.student_user_id = ?"
                        + " AND l.state = 'APPROVED'"
                        + " AND EXISTS (SELECT 1 FROM center_membership m"
                        + " JOIN membership_role r ON r.membership_id = m.id"
                        + " WHERE m.workspace_id = l.workspace_id"
                        + " AND m.user_id = l.parent_user_id AND m.state = 'ACTIVE'"
                        + " AND r.role = 'PARENT')"
                        + " AND EXISTS (SELECT 1 FROM center_membership m"
                        + " JOIN membership_role r ON r.membership_id = m.id"
                        + " WHERE m.workspace_id = l.workspace_id"
                        + " AND m.user_id = l.student_user_id AND m.state = 'ACTIVE'"
                        + " AND r.role = 'STUDENT')"
                        + " LIMIT 1",
                workspaceId,
                studentUserId);
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
                + "|scope="
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
                + "|scope="
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
                + "|scope="
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

    private boolean rowExists(String sql, Object... args) {
        return !jdbc.queryForList(sql, UUID.class, args).isEmpty();
    }

    private boolean isCenter(UUID workspaceId) {
        return rowExists("SELECT id FROM workspace WHERE id = ? AND kind = 'CENTER'", workspaceId);
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
        // Mọi target kết quả đều bắt đầu bằng "workspace:{id}:".
        return UUID.fromString(
                targetKey.substring("workspace:".length(), "workspace:".length() + 36));
    }

    private static UUID targetIdOf(String targetKey) {
        return UUID.fromString(targetKey.substring(targetKey.lastIndexOf(':') + 1));
    }
}
