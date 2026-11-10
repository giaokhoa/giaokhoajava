package com.giaokhoa.resources;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.resources.data.CanonicalResourceRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service chia sẻ resource của US7: cấp (share), nhận (accept) và thu hồi (revoke) một version cho
 * một workspace nhận theo đúng hợp đồng authorization và hợp đồng HTTP. Cấp quyền chỉ diễn ra sau
 * quyết định PDP {@code share} trên target version tại workspace nguồn, và invariant của service
 * chỉ có thể thu hẹp thêm chứ không cấp thay PDP: lượt cấp không được rộng hơn nguồn — hành động
 * xin cấp phải nằm trong trần quyền nguồn của chính người cấp tại workspace này (chủ sở hữu thì
 * toàn catalog; người giữ version theo grant hoặc theo phân công thì bị trần bởi actions của
 * grant/phân công đó), và mọi thành phần mà version xin chia sẻ phụ thuộc qua một grant đều phải
 * cho phép chia tiếp tới đúng nơi nhận và phạm vi đó (FR-035, FR-041). Lượt cấp ghi ở trạng thái
 * {@code OFFERED}: nơi nhận chưa có quyền nào cho tới khi người có thẩm quyền của nơi nhận accept
 * qua quyết định {@code accept} trên target {@code grant:{id}}. Thu hồi chỉ áp cho mode {@code
 * REVOCABLE} đã {@code ACCEPTED} qua quyết định {@code revoke} của chính người cấp; grant {@code
 * LONG_TERM} không có nguồn revoke trong graph nên bị từ chối 403 và không nối membership/gói của
 * người cấp — người cấp rời đi hay hết gói không đụng được lượt cấp lâu dài (FR-037). Khi thu hồi,
 * toàn bộ dependency closure của grant (nơi nhận, bản đã lưu, bản fork phái sinh) được ghi lại
 * trong cùng operation qua {@link ResourceRevocationContributor} và action chỉ báo thành công khi
 * operation đó {@code SUCCEEDED}; lịch sử không bị xóa ở bất kỳ bước nào.
 *
 * <p>Mọi action ghi biên nhận idempotency của chính mình trong cùng transaction với mutation (bảng
 * {@code sample_action_mutation}, cùng giao thức với service thư viện): replay cùng key và cùng
 * payload trả lại kết quả đầu tiên, khác payload trả 409. Trước khi hỏi PDP, service ghi lại bundle
 * hiện hành của target qua projector tương ứng bằng operation dùng chung và chờ chiếu xong — chờ
 * không xong thì fail-closed 503, không bao giờ báo quyền đã đồng bộ khi operation còn PENDING.
 */
@Service
public class ResourceShareService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String OPERATION_FAILED = "FAILED";
    private static final String RECEIPT_TARGET_TYPE = "SHARE_GRANT";
    private static final List<String> MODES = List.of("REVOCABLE", "LONG_TERM");

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector projector;
    private final SharePolicyProjector shareProjector;
    private final ResourceRevocationContributor revocation;
    private final CanonicalResourceRepository repository;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} và repository dựng sẵn, cùng mẫu
     * EI_EXPOSE_REP2 của {@link ResourceLibraryService}.
     */
    public ResourceShareService(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector projector,
            SharePolicyProjector shareProjector,
            ResourceRevocationContributor revocation,
            IdempotencyService idempotency,
            DataSource dataSource,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.projector = projector;
        this.shareProjector = shareProjector;
        this.revocation = revocation;
        this.repository = new CanonicalResourceRepository(dataSource);
        this.idempotency = idempotency;
        this.jdbc = new JdbcTemplate(dataSource);
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Chia sẻ một version cho một workspace nhận sau quyết định {@code share}: trả định danh của
     * lượt cấp vừa tạo ở trạng thái {@code OFFERED}.
     */
    public UUID shareVersion(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            @Nullable OffsetDateTime expiresAt,
            String idempotencyKey,
            String payloadHash) {
        validateSharePayload(mode, actions, allowedWorkspaceIds, recipientWorkspaceId, expiresAt);
        UUID actorUserId = executor.actorUserId(authentication);
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(workspaceId, resourceId, versionId);
        ensureVersionProjection(actorUserId, workspaceId, idempotencyKey + "#share", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.SHARE.scopeName()));
        UUID grantId =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () -> {
                                                    UUID candidateId = UUID.randomUUID();
                                                    return claimReceipt(
                                                                    actorUserId,
                                                                    workspaceId,
                                                                    idempotencyKey,
                                                                    payloadHash,
                                                                    candidateId)
                                                            .orElseGet(() ->
                                                                    applyShare(
                                                                            actorUserId,
                                                                            workspaceId,
                                                                            resourceId,
                                                                            versionId,
                                                                            recipientWorkspaceId,
                                                                            mode,
                                                                            actions,
                                                                            allowedWorkspaceIds,
                                                                            expiresAt,
                                                                            candidateId));
                                                }),
                                        "share grant"));
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#share-sync", grantId);
        return grantId;
    }

    private UUID applyShare(
            UUID actorUserId,
            UUID workspaceId,
            UUID resourceId,
            UUID versionId,
            UUID recipientWorkspaceId,
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            @Nullable OffsetDateTime expiresAt,
            UUID grantId) {
        CanonicalResourceRepository.VersionMetadata metadata =
                repository
                        .metadata(resourceId, versionId)
                        .orElseThrow(ResourceShareService::notFound);
        if (jdbc.queryForList(
                        "SELECT id FROM workspace WHERE id = ?", UUID.class, recipientWorkspaceId)
                .isEmpty()) {
            throw notFound();
        }
        enforceSourceCeiling(actorUserId, workspaceId, metadata, actions);
        enforceComponentConstraints(
                resourceId, versionId, actions, recipientWorkspaceId, allowedWorkspaceIds);
        jdbc.update(connection -> {
            var statement =
                    connection.prepareStatement(
                            "INSERT INTO share_grant(id, source_resource_id,"
                                    + " source_version_id, grantor_user_id,"
                                    + " recipient_workspace_id, mode, state, actions,"
                                    + " allowed_workspace_ids, valid_from, expires_at)"
                                    + " VALUES (?,?,?,?,?,?,'OFFERED',?,?,?,?)");
            statement.setObject(1, grantId);
            statement.setObject(2, resourceId);
            statement.setObject(3, versionId);
            statement.setObject(4, actorUserId);
            statement.setObject(5, recipientWorkspaceId);
            statement.setString(6, mode);
            statement.setArray(7, connection.createArrayOf("text", actions.toArray(new String[0])));
            statement.setArray(
                    8, connection.createArrayOf("uuid", allowedWorkspaceIds.toArray(new UUID[0])));
            statement.setObject(9, OffsetDateTime.now(ZoneOffset.UTC));
            statement.setObject(10, expiresAt);
            return statement;
        });
        return grantId;
    }

    /**
     * Trần quyền nguồn của người cấp tại workspace nguồn (FR-035): chủ sở hữu của version thì toàn
     * catalog scope mức version; ngược lại, actions xin cấp (đã map sang scope) phải nằm trong hợp
     * của các scope mà các grant đã ACCEPTED giao version này cho workspace và các phân công tường
     * minh ACTIVE của chính người cấp cấp được. PDP đã cho qua scope {@code share} không tự làm
     * trần này rộng hơn dữ kiện nguồn.
     */
    private void enforceSourceCeiling(
            UUID actorUserId,
            UUID workspaceId,
            CanonicalResourceRepository.VersionMetadata metadata,
            List<String> actions) {
        List<String> requestedScopes = ResourcePolicyProjector.mapActions(actions);
        boolean derivedFromGrant =
                !jdbc.queryForList(
                                "SELECT id FROM resource_dependency WHERE derived_resource_id = ?"
                                        + " AND derived_version_id = ?"
                                        + " AND share_grant_id IS NOT NULL"
                                        + " AND usage_constraints LIKE 'origin=FORK%'",
                                UUID.class, metadata.resourceId(), metadata.versionId())
                        .isEmpty();
        if (!derivedFromGrant && metadata.ownerWorkspaceId().equals(workspaceId)) {
            return;
        }
        Set<String> ceiling = new HashSet<>();
        for (CanonicalResourceRepository.GrantRef grant :
                repository.acceptedGrants(
                        workspaceId, metadata.resourceId(), metadata.versionId())) {
            ceiling.addAll(ResourcePolicyProjector.mapActions(grant.actions()));
        }
        String assignmentSql =
                "SELECT array_to_string(actions, ',') AS actions_csv FROM resource_assignment"
                        + " WHERE workspace_id = ? AND user_id = ? AND state = 'ACTIVE'"
                        + " AND ((resource_id = ? AND (subject_id IS NULL OR subject_id = ?))"
                        + " OR (resource_id IS NULL AND subject_id = ?))";
        if ("CENTER".equals(workspaceKind(workspaceId))) {
            assignmentSql +=
                    " AND EXISTS (SELECT 1 FROM center_membership m"
                            + " WHERE m.workspace_id = resource_assignment.workspace_id"
                            + " AND m.user_id = resource_assignment.user_id"
                            + " AND m.state = 'ACTIVE')";
        }
        for (Map<String, Object> assignment :
                jdbc.queryForList(
                        assignmentSql,
                        workspaceId,
                        actorUserId,
                        metadata.resourceId(),
                        metadata.subjectId(),
                        metadata.subjectId())) {
            ceiling.addAll(
                    ResourcePolicyProjector.mapActions(
                            splitCsv((String) assignment.get("actions_csv"))));
        }
        if (!ceiling.containsAll(requestedScopes)) {
            throw forbidden();
        }
    }

    /**
     * Ràng buộc thành phần (FR-041): duyệt toàn bộ closure phụ thuộc của version xin chia sẻ bằng
     * truy vấn đệ quy theo mẫu WITH RECURSIVE của PostgreSQL — mọi cạnh mang một grant đều là nguồn
     * giới hạn của version này. Grant đó phải còn {@code ACCEPTED}, phải tự cho phép chia tiếp
     * (scope {@code share}), và phạm vi mới không được rộng hơn phạm vi của nó: nơi nhận phải nằm
     * trong danh sách workspace cho phép của grant thành phần khi danh sách đó không rỗng, và danh
     * sách cho phép của lượt cấp mới phải là tập con của nó; actions của lượt cấp mới cũng phải là
     * tập con của actions của grant thành phần.
     */
    private void enforceComponentConstraints(
            UUID resourceId,
            UUID versionId,
            List<String> actions,
            UUID recipientWorkspaceId,
            List<UUID> allowedWorkspaceIds) {
        List<String> requestedScopes = ResourcePolicyProjector.mapActions(actions);
        List<Map<String, Object>> governing =
                jdbc.queryForList(
                        "WITH RECURSIVE closure (resource_id, version_id) AS ("
                                + " SELECT ?, ?"
                                + " UNION"
                                + " SELECT d.source_resource_id, d.source_version_id"
                                + " FROM resource_dependency d"
                                + " JOIN closure c ON d.derived_resource_id = c.resource_id"
                                + " AND d.derived_version_id = c.version_id)"
                                + " SELECT g.state, array_to_string(g.actions, ',') AS actions_csv,"
                                + " array_to_string(g.allowed_workspace_ids, ',') AS allowed_csv"
                                + " FROM resource_dependency d"
                                + " JOIN share_grant g ON g.id = d.share_grant_id"
                                + " WHERE (d.derived_resource_id, d.derived_version_id) IN"
                                + " (SELECT resource_id, version_id FROM closure)",
                        resourceId,
                        versionId);
        for (Map<String, Object> grant : governing) {
            if (!"ACCEPTED".equals(grant.get("state"))) {
                throw forbidden();
            }
            List<String> grantScopes =
                    ResourcePolicyProjector.mapActions(splitCsv((String) grant.get("actions_csv")));
            if (!grantScopes.contains(PermissionCatalog.ResourceVersionScope.SHARE.scopeName())
                    || !grantScopes.containsAll(requestedScopes)) {
                throw forbidden();
            }
            List<UUID> grantAllowed =
                    splitCsv((String) grant.get("allowed_csv")).stream()
                            .map(UUID::fromString)
                            .toList();
            if (!grantAllowed.isEmpty()
                    && (!grantAllowed.contains(recipientWorkspaceId)
                            || allowedWorkspaceIds.isEmpty()
                            || !grantAllowed.containsAll(allowedWorkspaceIds))) {
                throw forbidden();
            }
        }
    }

    /**
     * Nhận một lượt cấp vào kho của workspace nhận sau quyết định {@code accept} trên target của
     * grant: route phải là đúng workspace nhận của grant và grant phải còn {@code OFFERED} trong
     * cửa sổ hiệu lực của nó. Trả định danh grant.
     */
    public UUID acceptShare(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID grantId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        GrantRow grant = grantRow(grantId).orElseThrow(ResourceShareService::notFound);
        if (!grant.recipientWorkspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        String targetKey = NativePolicyTemplates.grantKey(grantId);
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#accept", grantId);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.GrantScope.ACCEPT.scopeName()));
        UUID accepted =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () ->
                                                        claimReceipt(
                                                                        actorUserId,
                                                                        workspaceId,
                                                                        idempotencyKey,
                                                                        payloadHash,
                                                                        grantId)
                                                                .orElseGet(
                                                                        () -> applyAccept(grant))),
                                        "share grant"));
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#accept-sync", grantId);
        return accepted;
    }

    private UUID applyAccept(GrantRow grant) {
        // Hết hạn là trạng thái suy ra từ thời gian (không ghi state EXPIRED ở đường này), nên
        // một offer đã qua cửa sổ thì không còn nhận được dù state vẫn OFFERED trên dòng.
        if (grant.expiresAt() != null
                && !OffsetDateTime.now(ZoneOffset.UTC).isBefore(grant.expiresAt())) {
            throw notAcceptable();
        }
        int updated =
                jdbc.update(
                        "UPDATE share_grant SET state = 'ACCEPTED', accepted_at = now(),"
                                + " version = version + 1, updated_at = now()"
                                + " WHERE id = ? AND state = 'OFFERED'",
                        grant.id());
        if (updated == 0) {
            throw notAcceptable();
        }
        return grant.id();
    }

    /**
     * Thu hồi một lượt cấp {@code REVOCABLE} đã {@code ACCEPTED} sau quyết định {@code revoke} của
     * chính người cấp, từ route của workspace nguồn: trạng thái grant đổi trước, rồi toàn bộ
     * dependency closure được ghi lại và chờ đồng bộ xong mới trả thành công. Trả định danh grant.
     */
    public UUID revokeShare(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID grantId,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        GrantRow grant = grantRow(grantId).orElseThrow(ResourceShareService::notFound);
        if (!grant.sourceWorkspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        String targetKey = NativePolicyTemplates.grantKey(grantId);
        ensureShareProjection(actorUserId, workspaceId, idempotencyKey + "#revoke", grantId);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey, List.of(PermissionCatalog.GrantScope.REVOKE.scopeName()));
        UUID revoked =
                executor.execute(
                        authentication,
                        httpRequest,
                        workspaceId,
                        List.of(target),
                        () ->
                                Objects.requireNonNull(
                                        workspaces.inWorkspace(
                                                workspaceId,
                                                () ->
                                                        claimReceipt(
                                                                        actorUserId,
                                                                        workspaceId,
                                                                        idempotencyKey,
                                                                        payloadHash,
                                                                        grantId)
                                                                .orElseGet(
                                                                        () -> applyRevoke(grant))),
                                        "share grant"));
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        revocation.recordShareRevocation(
                                actorUserId,
                                workspaceId,
                                idempotencyKey + "#revoke-sync",
                                projectionPayloadHash(workspaceId, targetKey),
                                grantId));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
        return revoked;
    }

    private UUID applyRevoke(GrantRow grant) {
        int updated =
                jdbc.update(
                        "UPDATE share_grant SET state = 'REVOKED', revoked_at = now(),"
                                + " version = version + 1, updated_at = now()"
                                + " WHERE id = ? AND state = 'ACCEPTED' AND mode = 'REVOCABLE'",
                        grant.id());
        if (updated == 0) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "SHARE_NOT_REVOCABLE",
                    "Lượt cấp không ở trạng thái có thể thu hồi.");
        }
        return grant.id();
    }

    // ---------- Dữ kiện grant và kiểm tra payload ----------

    /** Các trường của một grant mà các quyết định invariant của service cần, đọc canonical. */
    private record GrantRow(
            UUID id,
            UUID recipientWorkspaceId,
            UUID sourceWorkspaceId,
            @Nullable OffsetDateTime expiresAt) {}

    private Optional<GrantRow> grantRow(UUID grantId) {
        return jdbc
                .queryForList(
                        "SELECT g.recipient_workspace_id, r.owner_workspace_id"
                                + " AS source_workspace_id, g.expires_at FROM share_grant g"
                                + " JOIN resource r ON r.id = g.source_resource_id"
                                + " WHERE g.id = ?",
                        grantId)
                .stream()
                .findFirst()
                .map(row ->
                        new GrantRow(
                                grantId,
                                Objects.requireNonNull(
                                        (UUID) row.get("recipient_workspace_id"),
                                        "recipient_workspace_id"),
                                Objects.requireNonNull(
                                        (UUID) row.get("source_workspace_id"),
                                        "source_workspace_id"),
                                (OffsetDateTime) row.get("expires_at")));
    }

    private void validateSharePayload(
            String mode,
            List<String> actions,
            List<UUID> allowedWorkspaceIds,
            UUID recipientWorkspaceId,
            @Nullable OffsetDateTime expiresAt) {
        if (mode == null || !MODES.contains(mode)) {
            throw invalid();
        }
        if (actions == null
                || actions.isEmpty()
                || actions.stream().distinct().count() != actions.size()
                || actions.stream()
                        .anyMatch(action -> ResourcePolicyProjector.mapAction(action).isEmpty())) {
            throw invalid();
        }
        if (allowedWorkspaceIds == null
                || (!allowedWorkspaceIds.isEmpty()
                        && !allowedWorkspaceIds.contains(recipientWorkspaceId))) {
            throw invalid();
        }
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalid();
        }
    }

    private String workspaceKind(UUID workspaceId) {
        return jdbc
                .queryForList("SELECT kind FROM workspace WHERE id = ?", String.class, workspaceId)
                .stream()
                .findFirst()
                .orElseThrow(ResourceShareService::notFound);
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    // ---------- Biên nhận idempotency của chính action ----------

    /**
     * Chèn biên nhận của action trong cùng transaction với mutation, cùng giao thức với service thư
     * viện: chèn được thì biên nhận chưa tồn tại và mutation chạy tiếp; đã có biên nhận cùng
     * payload thì trả định danh đích đầu tiên thay vì ghi lại; khác payload thì 409.
     */
    private Optional<UUID> claimReceipt(
            UUID actorUserId,
            UUID workspaceId,
            String idempotencyKey,
            String payloadHash,
            UUID candidateTargetId) {
        int inserted =
                jdbc.update(
                        "INSERT INTO sample_action_mutation(actor_user_id, idempotency_key,"
                                + " payload_hash, workspace_id, target_type, target_id)"
                                + " VALUES (?,?,?,?,?,?)"
                                + " ON CONFLICT (actor_user_id, idempotency_key) DO NOTHING",
                        actorUserId,
                        idempotencyKey,
                        payloadHash,
                        workspaceId,
                        RECEIPT_TARGET_TYPE,
                        candidateTargetId);
        if (inserted != 0) {
            return Optional.empty();
        }
        Map<String, Object> previous =
                jdbc.queryForMap(
                        "SELECT payload_hash, target_id FROM sample_action_mutation"
                                + " WHERE actor_user_id = ? AND idempotency_key = ?",
                        actorUserId,
                        idempotencyKey);
        if (!payloadHash.equals(previous.get("payload_hash"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of(Objects.requireNonNull((UUID) previous.get("target_id"), "target_id"));
    }

    // ---------- Ghi projection trước khi hỏi PDP ----------

    /** Ghi lại bundle hiện hành của target version qua operation dùng chung và chờ chiếu xong. */
    private void ensureVersionProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, String targetKey) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        projector.recordResourceChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(workspaceId, targetKey),
                                List.of(targetKey)));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    /** Ghi lại bundle hiện hành của grant và của version tại nơi nhận, rồi chờ chiếu xong. */
    private void ensureShareProjection(
            UUID actorUserId, UUID workspaceId, String idempotencyKey, UUID grantId) {
        AuthorizationOperation operation =
                transactions.execute(_ ->
                        shareProjector.recordShareChange(
                                actorUserId,
                                workspaceId,
                                idempotencyKey,
                                projectionPayloadHash(
                                        workspaceId, NativePolicyTemplates.grantKey(grantId)),
                                grantId));
        awaitProjection(Objects.requireNonNull(operation, "operation"));
    }

    private String projectionPayloadHash(UUID workspaceId, String targetKey) {
        var node = JSON.createObjectNode();
        node.putArray("projectionTargets").add(targetKey);
        return idempotency.payloadHash(
                "POST", "/api/workspaces/" + workspaceId + "/samples/actions", node);
    }

    private void awaitProjection(AuthorizationOperation operation) {
        long deadline = System.nanoTime() + PROJECTION_WAIT.toNanos();
        while (true) {
            String state =
                    jdbc.queryForObject(
                            "SELECT state FROM authorization_operation WHERE id = ?",
                            String.class,
                            operation.getId());
            if (OPERATION_SUCCEEDED.equals(state)) {
                return;
            }
            if (OPERATION_FAILED.equals(state) || System.nanoTime() > deadline) {
                throw AuthorizationFence.notReady();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw AuthorizationFence.notReady(ex);
            }
        }
    }

    // ---------- Lỗi dùng chung ----------

    private static HttpProblemException forbidden() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Không có quyền thực hiện thao tác.");
    }

    private static HttpProblemException notFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    }

    private static HttpProblemException invalid() {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SAMPLE_ACTION",
                "Hành động mẫu thiếu hoặc sai trường bắt buộc.");
    }

    private static HttpProblemException notAcceptable() {
        return new HttpProblemException(
                HttpStatus.CONFLICT,
                "SHARE_NOT_ACCEPTABLE",
                "Lượt cấp không còn ở trạng thái chờ nhận hoặc đã hết cửa sổ hiệu lực.");
    }
}
