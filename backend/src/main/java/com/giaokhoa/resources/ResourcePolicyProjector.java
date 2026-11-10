package com.giaokhoa.resources;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.PolicyGraph;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projector của resource version: nguồn sự thật duy nhất về desired native graph của các target
 * {@code workspace:{wid}:resource:{rid}:version:{vid}}, dựng thuần từ các dòng bền. Các nhánh nguồn
 * tách scope theo hợp đồng authorization và thân issue: chủ sở hữu native của resource (ở personal
 * là owner của workspace kèm gate kỳ gói cho các scope mở rộng; ở center là người tạo khi còn
 * membership ACTIVE, scope mở rộng gate theo kỳ gói của center), phân công tường minh {@code
 * resource_assignment} ACTIVE của đúng workspace ngữ cảnh, license {@code share_grant} đã ACCEPTED
 * cho workspace nhận với cửa sổ hiệu lực là time policy và phạm vi workspace cho phép là invariant
 * của nhánh, và học sinh qua hoạt động đã giao trên đúng version (chỉ {@code content.read}, không
 * bao giờ {@code answer.read}). Version hệ thống đã LOCKED chỉ cho {@code library.save} trước khi
 * lưu và cho đọc/dùng/fork sau khi đã có mục thư viện không gắn grant tại workspace nhận — chưa
 * nhận cập nhật thì version mới của nguồn không có nhánh nào ở workspace nhận. Bản fork phái sinh
 * từ một grant (cạnh dependency mang dấu {@code origin=FORK} và grant) chỉ còn nhánh license phái
 * sinh giới hạn ở các scope đọc/dùng của chính grant đó: nhánh owner/tác giả bị khử trên version
 * phái sinh để quyền của grantor không bị ghi đè bởi quyền sở hữu bản sao, và khi grant bị thu hồi
 * thì bản phái sinh mất mọi nguồn.
 *
 * <p>Mỗi target được ghi thành một projection bundle duy nhất trên WORKSPACE partition của
 * workspace ngữ cảnh với scope danh nghĩa {@code content.read}; projection mang dependency là GRANT
 * partition của các grant làm nguồn cho nó để fence của protected request và phép đóng phụ thuộc
 * luôn thấy grant đang đổi trạng thái. Projector vừa tính bundle khi ghi (qua {@link
 * #recordResourceChange}), vừa dựng lại đúng bundle đó khi {@code ProjectionReconciler} đối chiếu
 * desired hash. Các bảng thư viện/phân công/hoạt động nằm dưới RLS theo workspace ngữ cảnh nên mọi
 * phép dựng bundle tự bọc trong một transaction ngắn có đặt context của đúng workspace trước truy
 * vấn đầu tiên, như các projector bundle khác.
 */
@Component
public class ResourcePolicyProjector {

    private static final String ACTOR_DESCRIPTOR = "RESOURCE_ACTOR";
    private static final String ACTOR_PERIOD_DESCRIPTOR = "RESOURCE_ACTOR_PERIOD";
    private static final String GROUP_DESCRIPTOR = "RESOURCE_GROUP";
    private static final String GROUP_PERIOD_DESCRIPTOR = "RESOURCE_GROUP_PERIOD";
    private static final String LICENSE_DESCRIPTOR = "RESOURCE_LICENSE";
    private static final String BUNDLE_SCOPE =
            PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
    private static final String FORK_ORIGIN_MARKER = "origin=FORK";

    /** Giá trị cột workspace.kind cho không gian cá nhân. */
    private static final String PERSONAL_KIND = "PERSONAL";

    /** Giá trị cột workspace.kind cho không gian trung tâm. */
    private static final String CENTER_KIND = "CENTER";

    /** Số phần tối thiểu của một canonical descriptor (loại nguồn và ít nhất một cặp khóa). */
    private static final int MIN_DESCRIPTOR_PARTS = 2;

    /** Các scope đọc mà chủ sở hữu native có không cần gate gói (hết gói vẫn xem lại được). */
    private static final List<String> OWNER_READ_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.PRACTICE.scopeName());

    /** Các scope mở rộng của chủ sở hữu native/tác giả: phải gate theo từng kỳ gói hiện hành. */
    private static final List<String> OWNER_EXPANSION_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.USE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.FORK.scopeName(),
                    PermissionCatalog.ResourceVersionScope.SHARE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.REVIEW.scopeName(),
                    PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName());

    /** Các scope đọc của tác giả tại center (không có practice trên target version). */
    private static final List<String> AUTHOR_READ_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName());

    /** Scope mà một bản phái sinh theo license còn giữ được từ grant gốc: chỉ đọc/dùng/duyệt. */
    private static final List<String> DERIVED_LICENSE_SCOPES =
            List.of(
                    PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.USE.scopeName(),
                    PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName(),
                    PermissionCatalog.ResourceVersionScope.REVIEW.scopeName());

    private final JdbcTemplate jdbc;
    private final AuthorizationOperationService operations;
    private final TransactionTemplate transactions;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} dựng sẵn: template được tạo trong
     * constructor nên projector không giữ tham chiếu tới đối tượng mutable từ bên ngoài (SpotBugs
     * EI_EXPOSE_REP2); {@code DataSource} là singleton do Spring quản lý.
     */
    public ResourcePolicyProjector(
            DataSource dataSource,
            AuthorizationOperationService operations,
            PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Scope danh nghĩa ghi trên projection row của mọi bundle resource version. */
    public static String bundleScope() {
        return BUNDLE_SCOPE;
    }

    /** Các scope mà projector này quản lý cho một target resource version, theo thứ tự catalog. */
    public static List<String> managedScopes() {
        return PermissionCatalog.names(PermissionCatalog.ResourceVersionScope.values());
    }

    /** Dấu mở đầu của {@code usage_constraints} trên cạnh provenance do fork ghi. */
    public static String forkOriginMarker() {
        return FORK_ORIGIN_MARKER;
    }

    /**
     * Map một token action lưu ở tầng grant/assignment sang scope mức version tương ứng: {@code
     * draft.edit} trên target version nghĩa là tạo bản chỉnh sửa, tức scope {@code fork}; các token
     * trùng tên scope thì giữ nguyên; token lạ không cho scope nào.
     */
    public static Optional<String> mapAction(String action) {
        return switch (action) {
            case "content.read" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName());
            case "answer.read" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.ANSWER_READ.scopeName());
            case "use" -> Optional.of(PermissionCatalog.ResourceVersionScope.USE.scopeName());
            case "review" -> Optional.of(PermissionCatalog.ResourceVersionScope.REVIEW.scopeName());
            case "share" -> Optional.of(PermissionCatalog.ResourceVersionScope.SHARE.scopeName());
            case "fork" -> Optional.of(PermissionCatalog.ResourceVersionScope.FORK.scopeName());
            case "library.save" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName());
            case "draft.edit" ->
                    Optional.of(PermissionCatalog.ResourceVersionScope.FORK.scopeName());
            default -> Optional.empty();
        };
    }

    /** Các scope mức version mà một danh sách token action lưu trữ cấp được. */
    public static List<String> mapActions(List<String> actions) {
        return actions.stream()
                .map(ResourcePolicyProjector::mapAction)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Toàn bộ descriptor của bundle của một target resource version, dựng từ dữ kiện nguồn hiện
     * hành. Rỗng khi version không còn tồn tại hoặc không nguồn nào còn hiệu lực — rỗng nghĩa là
     * target đó phải bị thu hồi (retract), không phải được giữ nguyên.
     */
    public List<String> bundleDescriptors(String targetKey) {
        NativePolicyTemplates.TargetType type =
                NativePolicyTemplates.targetTypeOf(targetKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Target key matches no fixed template: " + targetKey));
        if (type != NativePolicyTemplates.TargetType.RESOURCE_VERSION) {
            throw new IllegalArgumentException("Not a resource version target key: " + targetKey);
        }
        String[] segments = targetKey.split(":");
        UUID workspaceId = UUID.fromString(segments[1]);
        UUID resourceId = UUID.fromString(segments[3]);
        UUID versionId = UUID.fromString(segments[5]);
        List<String> bundle =
                transactions.execute(_ -> {
                    applyContext(workspaceId);
                    return buildBundle(workspaceId, resourceId, versionId, targetKey);
                });
        return Objects.requireNonNull(bundle, "bundle");
    }

    /** Các descriptor của riêng một scope trong bundle của target, dựng lại từ dữ kiện nguồn. */
    public List<String> sourcesFor(String targetKey, String scope) {
        return bundleDescriptors(targetKey).stream()
                .filter(descriptor -> descriptor.endsWith("|scope=" + scope))
                .toList();
    }

    /**
     * Mọi target resource version có thể có nguồn tại workspace: mọi version của resource thuộc
     * workspace, mọi version đã lưu/giao/được cấp grant/phân công tại workspace và mọi version phái
     * sinh của resource thuộc workspace.
     */
    public List<String> allBundleTargetKeys(UUID workspaceId) {
        List<String> targets =
                transactions.execute(_ -> {
                    applyContext(workspaceId);
                    return jdbc
                            .query(
                                    "SELECT DISTINCT resource_id, version_id FROM ("
                                            + " SELECT r.id AS resource_id, v.id AS version_id"
                                            + " FROM resource r JOIN resource_version v"
                                            + " ON v.resource_id = r.id"
                                            + " WHERE r.owner_workspace_id = ?"
                                            + " UNION SELECT resource_id, version_id"
                                            + " FROM library_item WHERE workspace_id = ?"
                                            + " UNION SELECT source_resource_id,"
                                            + " source_version_id FROM share_grant"
                                            + " WHERE recipient_workspace_id = ?"
                                            + " UNION SELECT resource_id, version_id"
                                            + " FROM sample_activity WHERE workspace_id = ?"
                                            + " UNION SELECT d.derived_resource_id,"
                                            + " d.derived_version_id FROM resource_dependency d"
                                            + " JOIN resource r ON r.id = d.derived_resource_id"
                                            + " WHERE r.owner_workspace_id = ?"
                                            + " UNION SELECT a.resource_id, v.id"
                                            + " FROM resource_assignment a"
                                            + " JOIN resource_version v"
                                            + " ON v.resource_id = a.resource_id"
                                            + " WHERE a.workspace_id = ?"
                                            + " AND a.state = 'ACTIVE'"
                                            + " AND a.resource_id IS NOT NULL"
                                            + " UNION SELECT r.id, v.id"
                                            + " FROM resource_assignment a"
                                            + " JOIN resource r ON r.subject_id = a.subject_id"
                                            + " JOIN resource_version v"
                                            + " ON v.resource_id = r.id"
                                            + " WHERE a.workspace_id = ?"
                                            + " AND a.state = 'ACTIVE'"
                                            + " AND a.resource_id IS NULL) pairs",
                                    (rs, _) ->
                                            NativePolicyTemplates.resourceVersionKey(
                                                    workspaceId,
                                                    rs.getObject("resource_id", UUID.class),
                                                    rs.getObject("version_id", UUID.class)),
                                    workspaceId,
                                    workspaceId,
                                    workspaceId,
                                    workspaceId,
                                    workspaceId,
                                    workspaceId,
                                    workspaceId)
                            .stream()
                            .sorted()
                            .toList();
                });
        return Objects.requireNonNull(targets, "targets");
    }

    /**
     * Desired projection của mọi bundle resource version hiện có của workspace, để gắn vào một
     * operation ghi kèm khi dữ kiện tiền đề của bundle (membership, kỳ gói, phân công giảng dạy,
     * hoạt động) thay đổi.
     */
    public List<AuthorizationOperationService.DesiredProjection> allBundleProjections(
            UUID workspaceId) {
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        for (String targetKey : allBundleTargetKeys(workspaceId)) {
            projections.add(desiredProjection(workspaceId, targetKey));
        }
        return List.copyOf(projections);
    }

    /**
     * Ghi operation cho một thay đổi làm đổi nguồn của các bundle resource version vừa được ghi
     * trong cùng transaction của service gọi: bump desired revision của WORKSPACE partition của
     * workspace, fence nó và lưu bundle hiện hành của từng target bị ảnh hưởng. Replay cùng
     * idempotency key và payload trả lại operation đã ghi mà không ghi lại gì, còn cùng key khác
     * payload là 409 — cả hai do cơ chế operation dùng chung xử lý.
     */
    public AuthorizationOperation recordResourceChange(
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
            projections.add(desiredProjection(workspaceId, targetKey));
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
     * Các gate native của một canonical descriptor resource: actor cụ thể cho descriptor actor,
     * group nguồn cho descriptor group, mỗi dạng kèm time policy khi descriptor mang cửa sổ kỳ
     * gói/grant; descriptor license mang đúng một audience (actor của owner ở personal hoặc group
     * thành viên ở center) AND cửa sổ hiệu lực của grant. Dùng bởi reconciliation để dựng lại đúng
     * graph đã ghi.
     */
    public static List<PolicyGraph.Gate> gatesFor(String descriptor) {
        String[] parts = descriptor.split("\\|", -1);
        if (parts.length < MIN_DESCRIPTOR_PARTS) {
            throw new IllegalArgumentException("Unsupported resource descriptor: " + descriptor);
        }
        List<PolicyGraph.Gate> gates = new ArrayList<>();
        switch (parts[0]) {
            case ACTOR_DESCRIPTOR ->
                    gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
            case ACTOR_PERIOD_DESCRIPTOR -> {
                gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
                gates.add(validityGate(parts, descriptor, true));
            }
            case GROUP_DESCRIPTOR ->
                    gates.add(
                            new PolicyGraph.Gate.GroupMembership(requireGroup(parts, descriptor)));
            case GROUP_PERIOD_DESCRIPTOR -> {
                gates.add(new PolicyGraph.Gate.GroupMembership(requireGroup(parts, descriptor)));
                gates.add(validityGate(parts, descriptor, true));
            }
            case LICENSE_DESCRIPTOR -> {
                String group = partValue(parts, "group=");
                if (group != null && !group.isBlank()) {
                    gates.add(new PolicyGraph.Gate.GroupMembership(group));
                } else {
                    gates.add(new PolicyGraph.Gate.Actor(requireSubject(parts, descriptor)));
                }
                gates.add(validityGate(parts, descriptor, false));
            }
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported resource descriptor: " + descriptor);
        }
        return List.copyOf(gates);
    }

    // ---------- Dựng nguồn ----------

    private List<String> buildBundle(
            UUID workspaceId, UUID resourceId, UUID versionId, String targetKey) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT r.owner_workspace_id, r.created_by_user_id, r.subject_id,"
                                + " v.review_state, ow.kind AS owner_kind, cw.kind AS context_kind"
                                + " FROM resource r"
                                + " JOIN resource_version v ON v.resource_id = r.id AND v.id = ?"
                                + " JOIN workspace ow ON ow.id = r.owner_workspace_id"
                                + " JOIN workspace cw ON cw.id = ?"
                                + " WHERE r.id = ?",
                        versionId,
                        workspaceId,
                        resourceId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Object> meta = rows.getFirst();
        UUID ownerWorkspaceId =
                Objects.requireNonNull((UUID) meta.get("owner_workspace_id"), "owner_workspace_id");
        UUID createdByUserId =
                Objects.requireNonNull((UUID) meta.get("created_by_user_id"), "created_by_user_id");
        UUID subjectId = Objects.requireNonNull((UUID) meta.get("subject_id"), "subject_id");
        String reviewState =
                Objects.requireNonNull((String) meta.get("review_state"), "review_state");
        String ownerKind = Objects.requireNonNull((String) meta.get("owner_kind"), "owner_kind");
        String contextKind =
                Objects.requireNonNull((String) meta.get("context_kind"), "context_kind");

        List<String> descriptors = new ArrayList<>();
        Optional<Map<String, Object>> derivedGrant = derivedForkGrant(resourceId, versionId);
        if (derivedGrant.isPresent()) {
            derivedLicenseSources(
                    descriptors, workspaceId, contextKind, derivedGrant.get(), targetKey);
            return descriptors.stream().sorted().distinct().toList();
        }
        if (ownerWorkspaceId.equals(workspaceId)) {
            if (PERSONAL_KIND.equals(contextKind)) {
                personalOwnerSources(descriptors, workspaceId, targetKey);
            } else if (CENTER_KIND.equals(contextKind)) {
                centerAuthorSources(descriptors, workspaceId, createdByUserId, targetKey);
            }
        }
        assignmentSources(descriptors, workspaceId, contextKind, resourceId, subjectId, targetKey);
        activitySources(descriptors, workspaceId, contextKind, resourceId, versionId, targetKey);
        licenseSources(descriptors, workspaceId, contextKind, resourceId, versionId, targetKey);
        if ("SYSTEM".equals(ownerKind) && "LOCKED".equals(reviewState)) {
            systemSources(descriptors, workspaceId, contextKind, resourceId, versionId, targetKey);
        }
        return descriptors.stream().sorted().distinct().toList();
    }

    /**
     * Grant làm căn cứ phái sinh của version khi version này là bản fork theo license: cạnh
     * provenance mang grant và dấu origin do fork ghi, ở bất kỳ trạng thái nào của grant — cạnh tồn
     * tại là đủ để version này là bản phái sinh bị giới hạn bởi grant đó. Rỗng khi version không
     * phái sinh từ grant nào (bản gốc hoặc fork theo quyền owner/phân công).
     */
    private Optional<Map<String, Object>> derivedForkGrant(UUID resourceId, UUID versionId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT g.id, g.state, array_to_string(g.actions, ',') AS actions_csv,"
                                + " array_to_string(g.allowed_workspace_ids, ',') AS allowed_csv,"
                                + " g.valid_from, g.expires_at FROM resource_dependency d"
                                + " JOIN share_grant g ON g.id = d.share_grant_id"
                                + " WHERE d.derived_resource_id = ? AND d.derived_version_id = ?"
                                + " AND d.share_grant_id IS NOT NULL"
                                + " AND d.usage_constraints LIKE 'origin=FORK%'"
                                + " ORDER BY d.created_at, d.id LIMIT 1",
                        resourceId, versionId);
        return rows.stream().findFirst();
    }

    /**
     * Nhánh license phái sinh: audience là phía sở hữu bản phái sinh trong giới hạn workspace cho
     * phép của grant; scope là các scope đọc/dùng mà grant gốc cấp. Khi grant không còn ACCEPTED
     * hoặc workspace không còn trong phạm vi cho phép thì không còn nguồn nào — toàn bundle rỗng,
     * nhánh owner/tác giả không hồi lại trên bản phái sinh.
     */
    private void derivedLicenseSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            Map<String, Object> grant,
            String targetKey) {
        if (!"ACCEPTED".equals(grant.get("state")) || !allowedHere(grant, workspaceId)) {
            return;
        }
        for (String scope : mapActions(csv(grant.get("actions_csv")))) {
            if (DERIVED_LICENSE_SCOPES.contains(scope)) {
                descriptors.add(
                        licenseDescriptor(workspaceId, contextKind, grant, targetKey, scope));
            }
        }
    }

    /** Nhánh chủ sở hữu native ở personal: owner đọc không gate gói, mở rộng gate theo kỳ gói. */
    private void personalOwnerSources(
            List<String> descriptors, UUID workspaceId, String targetKey) {
        Optional<OwnerRow> owner = personalOwner(workspaceId);
        if (owner.isEmpty()) {
            return;
        }
        for (String scope : OWNER_READ_SCOPES) {
            descriptors.add(
                    actorDescriptor(
                            workspaceId,
                            owner.get().userId(),
                            owner.get().subject(),
                            targetKey,
                            scope));
        }
        for (PeriodRow period : subscriptionPeriods(workspaceId)) {
            for (String scope : OWNER_EXPANSION_SCOPES) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                scope));
            }
        }
    }

    /**
     * Nhánh tác giả ở center: người tạo resource chỉ còn là nguồn khi membership của họ ở center
     * còn ACTIVE (membership đổi thì đường ghi membership ghi lại bundle này); scope mở rộng gate
     * theo kỳ gói của center, không liên quan gói cá nhân của tác giả.
     */
    private void centerAuthorSources(
            List<String> descriptors, UUID workspaceId, UUID authorUserId, String targetKey) {
        List<Map<String, Object>> authors =
                jdbc.queryForList(
                        "SELECT u.keycloak_sub FROM user_account u"
                                + " WHERE u.id = ?"
                                + " AND EXISTS (SELECT 1 FROM center_membership m"
                                + " WHERE m.workspace_id = ? AND m.user_id = u.id"
                                + " AND m.state = 'ACTIVE')",
                        authorUserId,
                        workspaceId);
        if (authors.isEmpty()) {
            return;
        }
        String subject =
                Objects.requireNonNull(
                        (String) authors.getFirst().get("keycloak_sub"), "keycloak_sub");
        for (String scope : AUTHOR_READ_SCOPES) {
            descriptors.add(actorDescriptor(workspaceId, authorUserId, subject, targetKey, scope));
        }
        for (PeriodRow period : subscriptionPeriods(workspaceId)) {
            for (String scope : OWNER_EXPANSION_SCOPES) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId, authorUserId, subject, period, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh phân công tường minh: assignment ACTIVE của đúng workspace ngữ cảnh khớp resource hoặc
     * môn của resource; ở center người được phân công phải còn membership ACTIVE. Token lưu trữ map
     * sang scope mức version qua {@link #mapAction} — {@code draft.edit} thành {@code fork}, {@code
     * content.read} giữ nguyên nhờ catalog đã nới ở migration V028.
     */
    private void assignmentSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID subjectId,
            String targetKey) {
        String sql =
                "SELECT a.user_id, u.keycloak_sub,"
                        + " array_to_string(a.actions, ',') AS actions_csv"
                        + " FROM resource_assignment a JOIN user_account u ON u.id = a.user_id"
                        + " WHERE a.workspace_id = ? AND a.state = 'ACTIVE'"
                        + " AND ((a.resource_id = ? AND (a.subject_id IS NULL OR a.subject_id = ?))"
                        + " OR (a.resource_id IS NULL AND a.subject_id = ?))";
        if (CENTER_KIND.equals(contextKind)) {
            sql +=
                    " AND EXISTS (SELECT 1 FROM center_membership m"
                            + " WHERE m.workspace_id = a.workspace_id AND m.user_id = a.user_id"
                            + " AND m.state = 'ACTIVE')";
        }
        for (Map<String, Object> assignment :
                jdbc.queryForList(sql, workspaceId, resourceId, subjectId, subjectId)) {
            UUID userId = Objects.requireNonNull((UUID) assignment.get("user_id"), "user_id");
            String subject =
                    Objects.requireNonNull((String) assignment.get("keycloak_sub"), "keycloak_sub");
            for (String scope : mapActions(csv(assignment.get("actions_csv")))) {
                descriptors.add(actorDescriptor(workspaceId, userId, subject, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh học sinh theo hoạt động: hoạt động ASSIGNED trên đúng version tại workspace cho học
     * sinh của môn–khóa đó đọc nội dung — ở center qua group học sinh của môn–khóa (membership thật
     * do projector giảng dạy đồng bộ), ở personal qua actor của từng học sinh còn ghi danh (đúng
     * hình nguồn ghi danh của projector giảng dạy personal, vì personal không có group nguồn được
     * đồng bộ). Không scope nào khác — đáp án có route và scope riêng mà nhánh này không bao giờ
     * cấp.
     */
    private void activitySources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey) {
        String readScope = PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
        for (UUID courseSubjectId :
                jdbc.queryForList(
                        "SELECT DISTINCT course_subject_id FROM sample_activity"
                                + " WHERE workspace_id = ? AND resource_id = ?"
                                + " AND version_id = ? AND state = 'ASSIGNED'",
                        UUID.class,
                        workspaceId,
                        resourceId,
                        versionId)) {
            if (CENTER_KIND.equals(contextKind)) {
                descriptors.add(
                        groupDescriptor(
                                workspaceId,
                                NativePolicyTemplates.courseSubjectStudentsGroup(
                                        workspaceId, courseSubjectId),
                                targetKey,
                                readScope));
            } else if (PERSONAL_KIND.equals(contextKind)) {
                for (Map<String, Object> student :
                        jdbc.queryForList(
                                "SELECT DISTINCT u.id AS user_id, u.keycloak_sub"
                                        + " FROM enrollment e"
                                        + " JOIN class_student cs ON cs.id = e.class_student_id"
                                        + " JOIN user_account u ON u.id = cs.student_user_id"
                                        + " WHERE e.workspace_id = ?"
                                        + " AND e.course_subject_id = ?"
                                        + " AND e.state IN ('PENDING', 'ACTIVE')",
                                workspaceId,
                                courseSubjectId)) {
                    descriptors.add(
                            actorDescriptor(
                                    workspaceId,
                                    Objects.requireNonNull(
                                            (UUID) student.get("user_id"), "user_id"),
                                    Objects.requireNonNull(
                                            (String) student.get("keycloak_sub"), "keycloak_sub"),
                                    targetKey,
                                    readScope));
                }
            }
        }
    }

    /**
     * Nhánh license: mỗi grant ACCEPTED cho workspace nhận trên đúng version cấp các scope do
     * actions của grant quyết định, trong cửa sổ hiệu lực của grant và chỉ khi workspace nhận nằm
     * trong phạm vi workspace cho phép (rỗng nghĩa là không giới hạn).
     */
    private void licenseSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey) {
        for (Map<String, Object> grant :
                jdbc.queryForList(
                        "SELECT id, array_to_string(actions, ',') AS actions_csv,"
                                + " array_to_string(allowed_workspace_ids, ',') AS allowed_csv,"
                                + " valid_from, expires_at FROM share_grant"
                                + " WHERE recipient_workspace_id = ? AND source_resource_id = ?"
                                + " AND source_version_id = ? AND state = 'ACCEPTED'"
                                + " ORDER BY created_at, id",
                        workspaceId,
                        resourceId,
                        versionId)) {
            if (!allowedHere(grant, workspaceId)) {
                continue;
            }
            for (String scope : mapActions(csv(grant.get("actions_csv")))) {
                descriptors.add(
                        licenseDescriptor(workspaceId, contextKind, grant, targetKey, scope));
            }
        }
    }

    /**
     * Nhánh version hệ thống đã công bố: {@code library.save} cho phía nhận đủ điều kiện của giấy
     * phép nền tảng trước cả khi lưu — ở personal là owner kèm gate kỳ gói cá nhân (điều kiện nhận
     * là cá nhân có gói), ở center là group thành viên không kèm kỳ gói của center (giấy phép nội
     * dung nền tảng không gắn gói center, theo bảng nguồn của hợp đồng authorization); đọc/dùng/
     * fork chỉ sau khi workspace đã có mục thư viện của chính version này không gắn grant — bản lưu
     * đứng yên ở version đã lưu, version mới của nguồn chưa được nhận thì không có nhánh đọc.
     */
    private void systemSources(
            List<String> descriptors,
            UUID workspaceId,
            String contextKind,
            UUID resourceId,
            UUID versionId,
            String targetKey) {
        String saveScope = PermissionCatalog.ResourceVersionScope.LIBRARY_SAVE.scopeName();
        if (PERSONAL_KIND.equals(contextKind)) {
            Optional<OwnerRow> owner = personalOwner(workspaceId);
            if (owner.isEmpty()) {
                return;
            }
            for (PeriodRow period : subscriptionPeriods(workspaceId)) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                saveScope));
            }
        } else if (CENTER_KIND.equals(contextKind)) {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            saveScope));
        } else {
            return;
        }
        boolean saved =
                !jdbc.queryForList(
                                "SELECT id FROM library_item WHERE workspace_id = ?"
                                        + " AND resource_id = ? AND version_id = ?"
                                        + " AND share_grant_id IS NULL LIMIT 1",
                                UUID.class,
                                workspaceId,
                                resourceId,
                                versionId)
                        .isEmpty();
        if (!saved) {
            return;
        }
        String readScope = PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
        String useScope = PermissionCatalog.ResourceVersionScope.USE.scopeName();
        String forkScope = PermissionCatalog.ResourceVersionScope.FORK.scopeName();
        if (PERSONAL_KIND.equals(contextKind)) {
            Optional<OwnerRow> owner = personalOwner(workspaceId);
            if (owner.isEmpty()) {
                return;
            }
            descriptors.add(
                    actorDescriptor(
                            workspaceId,
                            owner.get().userId(),
                            owner.get().subject(),
                            targetKey,
                            readScope));
            for (PeriodRow period : subscriptionPeriods(workspaceId)) {
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                useScope));
                descriptors.add(
                        actorPeriodDescriptor(
                                workspaceId,
                                owner.get().userId(),
                                owner.get().subject(),
                                period,
                                targetKey,
                                forkScope));
            }
        } else {
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            readScope));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            useScope));
            descriptors.add(
                    groupDescriptor(
                            workspaceId,
                            NativePolicyTemplates.membersGroup(workspaceId),
                            targetKey,
                            forkScope));
        }
    }

    // ---------- Dữ kiện nguồn dùng chung ----------

    private record OwnerRow(UUID userId, String subject) {}

    private Optional<OwnerRow> personalOwner(UUID workspaceId) {
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
        return rows.stream().findFirst();
    }

    private record PeriodRow(UUID id, Instant validFrom, Instant expiresAt) {}

    private List<PeriodRow> subscriptionPeriods(UUID workspaceId) {
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

    private boolean allowedHere(Map<String, Object> grant, UUID workspaceId) {
        List<String> allowed = csv(grant.get("allowed_csv"));
        return allowed.isEmpty() || allowed.contains(workspaceId.toString());
    }

    AuthorizationOperationService.DesiredProjection desiredProjection(
            UUID workspaceId, String targetKey) {
        String[] segments = targetKey.split(":");
        UUID resourceId = UUID.fromString(segments[3]);
        UUID versionId = UUID.fromString(segments[5]);
        return new AuthorizationOperationService.DesiredProjection(
                targetKey,
                BUNDLE_SCOPE,
                bundleDescriptors(targetKey),
                grantDependencyPartitions(workspaceId, resourceId, versionId));
    }

    /**
     * GRANT partition của các grant làm nguồn cho target này (grant trực tiếp tới workspace nhận và
     * grant của cạnh phái sinh), chỉ khi partition đó đã tồn tại: projection phụ thuộc vào nó để
     * thu hồi/revoke của grant fence đúng các request đọc/ghi trên target.
     */
    private List<UUID> grantDependencyPartitions(
            UUID workspaceId, UUID resourceId, UUID versionId) {
        return jdbc.queryForList(
                "SELECT id FROM authz_partition WHERE kind = 'GRANT' AND owner_key IN ("
                        + " SELECT 'grant:' || g.id FROM share_grant g"
                        + " WHERE g.recipient_workspace_id = ? AND g.source_resource_id = ?"
                        + " AND g.source_version_id = ?"
                        + " UNION SELECT 'grant:' || d.share_grant_id FROM resource_dependency d"
                        + " WHERE d.derived_resource_id = ? AND d.derived_version_id = ?"
                        + " AND d.share_grant_id IS NOT NULL)",
                UUID.class,
                workspaceId,
                resourceId,
                versionId,
                resourceId,
                versionId);
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
                + "|scope="
                + scope;
    }

    private static String actorPeriodDescriptor(
            UUID workspaceId,
            UUID userId,
            String subject,
            PeriodRow period,
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
     * Descriptor license: audience theo loại workspace nhận (actor của owner ở personal, group
     * thành viên ở center), grant và cửa sổ hiệu lực của nó; {@code until=-} khi grant không có mốc
     * hết hạn.
     */
    private String licenseDescriptor(
            UUID workspaceId,
            String contextKind,
            Map<String, Object> grant,
            String targetKey,
            String scope) {
        UUID grantId = Objects.requireNonNull((UUID) grant.get("id"), "grant id");
        // Time policy native chỉ có độ chính xác tới giây: cắt tròn cửa sổ của grant về tròn
        // giây ngay khi dựng descriptor để giá trị được chiếu là đúng giá trị gate kiểm.
        Instant validFrom = instantOf(grant.get("valid_from")).truncatedTo(ChronoUnit.SECONDS);
        Object expiresAt = grant.get("expires_at");
        StringBuilder descriptor =
                new StringBuilder(LICENSE_DESCRIPTOR).append("|workspace=").append(workspaceId);
        if (PERSONAL_KIND.equals(contextKind)) {
            OwnerRow owner =
                    personalOwner(workspaceId)
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Personal workspace has no owner: " + workspaceId));
            descriptor
                    .append("|user=")
                    .append(owner.userId())
                    .append("|subject=")
                    .append(owner.subject());
        } else {
            descriptor.append("|group=").append(NativePolicyTemplates.membersGroup(workspaceId));
        }
        return descriptor
                .append("|grant=")
                .append(grantId)
                .append("|from=")
                .append(validFrom)
                .append("|until=")
                .append(
                        expiresAt == null
                                ? "-"
                                : instantOf(expiresAt).truncatedTo(ChronoUnit.SECONDS).toString())
                .append("|target=")
                .append(targetKey)
                .append("|scope=")
                .append(scope)
                .toString();
    }

    private static Instant instantOf(@Nullable Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalArgumentException("Not a timestamp value: " + value);
    }

    private static List<String> csv(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text.split(","));
    }

    private static PolicyGraph.Gate.Validity validityGate(
            String[] parts, String descriptor, boolean bounded) {
        String from = partValue(parts, "from=");
        String until = partValue(parts, "until=");
        if (from == null || from.isBlank() || "-".equals(from)) {
            throw new IllegalArgumentException(
                    "Resource descriptor has no validity start: " + descriptor);
        }
        Instant validFrom = Instant.parse(from);
        Instant expiresAt = until == null || "-".equals(until) ? null : Instant.parse(until);
        if (bounded && expiresAt == null) {
            throw new IllegalArgumentException(
                    "Resource descriptor has no validity end: " + descriptor);
        }
        return new PolicyGraph.Gate.Validity(validFrom, expiresAt);
    }

    private static String requireSubject(String[] parts, String descriptor) {
        String subject = partValue(parts, "subject=");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Resource descriptor has no subject: " + descriptor);
        }
        return subject;
    }

    private static String requireGroup(String[] parts, String descriptor) {
        String group = partValue(parts, "group=");
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("Resource descriptor has no group: " + descriptor);
        }
        return group;
    }

    private static @Nullable String partValue(String[] parts, String prefix) {
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private void applyContext(UUID workspaceId) {
        jdbc.queryForObject(
                "SELECT set_config('app.workspace_id', ?, true)",
                String.class,
                workspaceId.toString());
    }
}
