package com.giaokhoa.resources;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.repository.CanonicalResourceRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phát hành đính chính nguồn của US7 (FR-033, FR-034): nguồn công bố một version thay thế cho một
 * version bị lỗi bằng một {@code correction_notice} bất biến và thông báo tới từng nơi từng nhận
 * version bị lỗi qua {@link CorrectionNotificationService}. Đính chính không sửa version bị lỗi,
 * không đụng mục thư viện, hạn nộp, bài nộp hay kết quả đã phát sinh: bản đã lưu và bài đã giao
 * đứng yên ở version cũ, và bên nhận chỉ dùng bản thay thế khi chủ động nhận cập nhật cho lần dùng
 * sau qua {@code RECEIVE_UPDATE} của {@link ResourceLibraryService} — cơ chế nhận thay thế đã có,
 * service này không cài đường nhận thứ hai.
 *
 * <p>Thẩm quyền nguồn khóa hai lớp như các action nguồn khác: route phải là đúng workspace sở hữu
 * resource (workspace khác thì 403), và quyết định PDP {@code review} trên target của version bị
 * ảnh hưởng tại workspace đó phải cho phép — {@code review} là scope phát hành/duyệt version mà
 * nhánh chủ sở hữu và tác giả của {@link ResourcePolicyProjector} cấp tại workspace nguồn, còn
 * target {@code resource:{id}} với scope {@code version.create} của hợp đồng chưa có projector (đã
 * ghi ở Sổ B84(iii), thuộc luồng soạn thật). Trước khi hỏi PDP, service ghi lại bundle hiện hành
 * của target qua projector bằng operation dùng chung và chờ chiếu xong — chờ không xong thì
 * fail-closed 503. Notice và toàn bộ receipt ghi trong cùng transaction với biên nhận idempotency
 * của action (bảng {@code sample_action_mutation}, đích {@code CORRECTION_NOTICE}): replay cùng key
 * và cùng payload trả lại đúng notice đầu tiên, khác payload trả 409. Đính chính không đổi dữ kiện
 * nguồn nào của graph nên không cần ghi lại bundle sau mutation.
 */
@Service
public class CorrectionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(60);
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";
    private static final String OPERATION_FAILED = "FAILED";
    private static final String RECEIPT_TARGET_TYPE = "CORRECTION_NOTICE";

    private final ProtectedActionExecutor executor;
    private final ResourcePolicyProjector projector;
    private final CanonicalResourceRepository repository;
    private final CorrectionNotificationService notifications;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final WorkspaceTransaction workspaces;
    private final TransactionTemplate transactions;

    /**
     * Nhận {@link DataSource} thay vì {@code JdbcTemplate} dựng sẵn, cùng mẫu EI_EXPOSE_REP2 của
     * {@link ResourceLibraryService}.
     */
    public CorrectionService(
            ProtectedActionExecutor executor,
            ResourcePolicyProjector projector,
            CorrectionNotificationService notifications,
            IdempotencyService idempotency,
            DataSource dataSource,
            WorkspaceTransaction workspaces,
            PlatformTransactionManager transactionManager,
            CanonicalResourceRepository repository) {
        this.executor = executor;
        this.projector = projector;
        this.repository = repository;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.jdbc = new JdbcTemplate(dataSource);
        this.workspaces = workspaces;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Metadata tối thiểu của một notice để trả về cho phía gọi: đúng các trường của data-model. */
    public record CorrectionNoticeView(
            UUID id,
            UUID resourceId,
            UUID affectedVersionId,
            UUID replacementVersionId,
            String reason,
            OffsetDateTime createdAt) {}

    /**
     * Phát hành đính chính cho một version bị lỗi sau quyết định PDP của thẩm quyền nguồn: trả
     * metadata của notice vừa tạo, hoặc của notice đầu tiên khi replay cùng key và payload.
     */
    public CorrectionNoticeView correctSource(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID workspaceId,
            UUID resourceId,
            UUID affectedVersionId,
            UUID replacementVersionId,
            String reason,
            String idempotencyKey,
            String payloadHash) {
        UUID actorUserId = executor.actorUserId(authentication);
        VersionMetadata affected =
                repository
                        .metadata(resourceId, affectedVersionId)
                        .orElseThrow(CorrectionService::notFound);
        if (repository.metadata(resourceId, replacementVersionId).isEmpty()) {
            throw invalid();
        }
        if (affectedVersionId.equals(replacementVersionId)) {
            throw invalid();
        }
        if (!affected.ownerWorkspaceId().equals(workspaceId)) {
            throw forbidden();
        }
        String targetKey =
                NativePolicyTemplates.resourceVersionKey(
                        workspaceId, resourceId, affectedVersionId);
        ensureProjection(actorUserId, workspaceId, idempotencyKey + "#correct", targetKey);
        var target =
                new ProtectedActionExecutor.Target(
                        targetKey,
                        List.of(PermissionCatalog.ResourceVersionScope.REVIEW.scopeName()));
        return executor.execute(
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
                                                            applyCorrection(
                                                                    resourceId,
                                                                    affected,
                                                                    replacementVersionId,
                                                                    reason,
                                                                    candidateId));
                                        }),
                                "correction notice"));
    }

    private CorrectionNoticeView applyCorrection(
            UUID resourceId,
            VersionMetadata affected,
            UUID replacementVersionId,
            String reason,
            UUID noticeId) {
        jdbc.update(
                "INSERT INTO correction_notice(id, resource_id, affected_version_id,"
                        + " replacement_version_id, reason) VALUES (?,?,?,?,?)",
                noticeId,
                resourceId,
                affected.versionId(),
                replacementVersionId,
                reason);
        notifications.notifyRecipients(
                noticeId,
                notifications.affectedRecipients(
                        resourceId,
                        affected.versionId(),
                        affected.subjectId(),
                        affected.ownerWorkspaceId()));
        return noticeView(noticeId);
    }

    /**
     * Đọc lại notice đã ghi để trả metadata đúng như đã lưu, dùng chung cho đường mới và replay.
     */
    private CorrectionNoticeView noticeView(UUID noticeId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT resource_id, affected_version_id, replacement_version_id,"
                                + " reason, created_at FROM correction_notice WHERE id = ?",
                        noticeId);
        return new CorrectionNoticeView(
                noticeId,
                Objects.requireNonNull((UUID) row.get("resource_id"), "resource_id"),
                Objects.requireNonNull(
                        (UUID) row.get("affected_version_id"), "affected_version_id"),
                Objects.requireNonNull(
                        (UUID) row.get("replacement_version_id"), "replacement_version_id"),
                Objects.requireNonNull((String) row.get("reason"), "reason"),
                Objects.requireNonNull((OffsetDateTime) row.get("created_at"), "created_at"));
    }

    // ---------- Biên nhận idempotency của chính action ----------

    /**
     * Chèn biên nhận của action trong cùng transaction với mutation, cùng giao thức với các sample
     * action khác: chèn được thì biên nhận chưa tồn tại và mutation chạy tiếp; đã có biên nhận cùng
     * payload thì trả notice đầu tiên thay vì ghi lại; khác payload thì 409.
     */
    private Optional<CorrectionNoticeView> claimReceipt(
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
        return Optional.of(
                noticeView(Objects.requireNonNull((UUID) previous.get("target_id"), "target_id")));
    }

    // ---------- Ghi projection trước khi hỏi PDP ----------

    /** Ghi lại bundle hiện hành của target version qua operation dùng chung và chờ chiếu xong. */
    private void ensureProjection(
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
                "Version thay thế không thuộc resource hoặc trùng version bị ảnh hưởng.");
    }
}
