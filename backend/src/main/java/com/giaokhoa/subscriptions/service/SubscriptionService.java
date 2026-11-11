package com.giaokhoa.subscriptions.service;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.shared.AdvisoryLocks;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.subscriptions.model.SubscriptionPeriod;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.teaching.PersonalTeachingPolicyProjector;
import com.giaokhoa.teaching.TeachingPolicyProjector;
import com.giaokhoa.tenancy.CenterPolicyProjector;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác nhận kỳ gói thủ công bởi platform operator: operator là người thật xác nhận ngoài hệ thống
 * (không có payment gateway, không có giá gói trong bản đầu), service chỉ ghi nhận kỳ đã xác nhận
 * kèm confirmationRef và audit người xác nhận/thời điểm, rồi đưa hiệu lực của kỳ vào desired native
 * graph qua authorization operation như mọi thay đổi quyền khác. Kỳ chỉ có hiệu lực khi operation
 * đã SUCCEEDED; service không tự tính allow thay Keycloak PDP và không báo đồng bộ khi operation
 * còn PENDING.
 *
 * <p>Idempotency theo hợp đồng operation: replay cùng key và cùng payload trả lại đúng operation đã
 * ghi — không ghi kỳ hay audit thứ hai — còn cùng key khác payload là 409. Vì dữ kiện nguồn nằm
 * ngoài callback của operation, mutation tự tuần tự hoá trên (actor, idempotency key) bằng advisory
 * lock trong chính transaction rồi mới kiểm tra replay, nên hai lời gọi đồng thời cùng key không
 * thể cùng đi qua điểm kiểm tra. Kỳ gói là lịch sử bất biến: chỉ INSERT, không sửa hay xoá; gia hạn
 * là một kỳ mới, không mở lại kỳ cũ và không phục hồi nguồn đã thu hồi.
 *
 * <p>Dữ kiện directory/gói đọc/ghi qua repository JPA; {@link JdbcOperations} chỉ còn phục vụ
 * advisory lock và điểm tra partition thuộc mặt phẳng authorization, cho tới khi repository của mặt
 * phẳng đó land cùng đợt chuyển đổi của module authorization.
 */
@Service
public class SubscriptionService {
    /** Khớp độ dài cột confirmation_ref VARCHAR(255) ở migration. */
    private static final int MAX_CONFIRMATION_REF_LENGTH = 255;

    private final JdbcOperations locks;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final SubscriptionPeriodRepository periods;
    private final IdempotencyService idempotency;
    private final AuthorizationOperationService operations;
    private final CenterPolicyProjector centerProjector;
    private final TeachingPolicyProjector teachingProjector;
    private final PersonalEntitlementProjector entitlementProjector;
    private final PersonalTeachingPolicyProjector personalTeachingProjector;
    private final AuditRecorder audit;
    private final ResourcePolicyProjector resourceProjector;

    private final ResourceMutationCoordinator coordinator;

    public SubscriptionService(
            JdbcOperations locks,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            SubscriptionPeriodRepository periods,
            IdempotencyService idempotency,
            AuthorizationOperationService operations,
            CenterPolicyProjector centerProjector,
            ResourceMutationCoordinator coordinator,
            TeachingPolicyProjector teachingProjector,
            PersonalEntitlementProjector entitlementProjector,
            PersonalTeachingPolicyProjector personalTeachingProjector,
            AuditRecorder audit,
            ResourcePolicyProjector resourceProjector) {
        this.locks = locks;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.periods = periods;
        this.idempotency = idempotency;
        this.operations = operations;
        this.centerProjector = centerProjector;
        this.coordinator = coordinator;
        this.teachingProjector = teachingProjector;
        this.entitlementProjector = entitlementProjector;
        this.personalTeachingProjector = personalTeachingProjector;
        this.audit = audit;
        this.resourceProjector = resourceProjector;
    }

    /**
     * Xác nhận một kỳ gói cho workspace personal/center. Mốc thời gian được cắt về tròn giây trước
     * khi kiểm tra và ghi: time policy native của Keycloak chỉ có độ chính xác tới giây và cột lưu
     * trữ là TIMESTAMPTZ(0), nên giá trị đã kiểm tra phải là đúng giá trị sẽ được chiếu, không để
     * phép làm tròn ở tầng lưu trữ đổi thứ tự hai mốc.
     */
    @Transactional
    public AuthorizationOperation confirm(
            UUID operatorUserId,
            UUID workspaceId,
            Instant validFrom,
            Instant expiresAt,
            @Nullable String confirmationRef,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(operatorUserId, "operatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(expiresAt, "expiresAt");
        idempotency.requireKey(idempotencyKey);
        coordinator.lockWorkspace(workspaceId);
        lockOn("subscription-confirm:" + operatorUserId + ":" + idempotencyKey);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(operatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireAccount(operatorUserId);
        Workspace.Kind kind = workspaceKind(workspaceId);
        String ref = normalizeConfirmationRef(confirmationRef);
        Instant from = validFrom.truncatedTo(ChronoUnit.SECONDS);
        Instant until = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (!until.isAfter(from)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SUBSCRIPTION_PERIOD",
                    "Kỳ gói phải có mốc kết thúc sau bắt đầu.");
        }
        // confirmation_ref là neo audit duy nhất theo cột UNIQUE: chặn trùng ở tầng service để
        // trả 409 rõ ràng thay vì lỗi constraint thô, và một ref không bao giờ trỏ hai kỳ.
        if (periods.existsByConfirmationRef(ref)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "SUBSCRIPTION_CONFIRMATION_REF_USED",
                    "Confirmation ref đã được ghi cho một kỳ gói khác.");
        }
        UUID periodId = UUID.randomUUID();
        periods.save(
                new SubscriptionPeriod(
                        periodId, workspaceId, from, until, ref, operatorUserId, Instant.now()));
        // Các projector đọc kỳ gói bằng đường đọc riêng của chúng: flush để kỳ vừa ghi nhìn
        // thấy được trong cùng transaction trước khi dựng bundle.
        periods.flush();
        Optional<AuthorizationOperationService.PartitionChange> change =
                partitionChange(workspaceId, kind);
        if (change.isPresent()) {
            AuthorizationOperation operation =
                    operations.applyDesiredState(
                            operatorUserId,
                            workspaceId,
                            idempotencyKey,
                            payloadHash,
                            List.of(change.get()));
            audit.record(
                    operatorUserId,
                    workspaceId,
                    AuditEvent.Action.SUBSCRIPTION_CHANGE,
                    AuditEvent.TargetType.SUBSCRIPTION,
                    periodId,
                    operation.getId(),
                    AuditEvent.Outcome.PENDING);
            return operation;
        }
        // Trigger của DB luôn tạo partition cho mọi workspace; thiếu là dữ liệu hỏng,
        // không được âm thầm ghi kỳ mà không chiếu hiệu lực.
        throw new IllegalStateException(
                "Workspace authorization partition is missing: " + workspaceId);
    }

    /**
     * Thay đổi partition cần ghi cho một thay đổi gói trên workspace này: bundle trọn vẹn của
     * projector theo loại workspace trên WORKSPACE partition của chính workspace, kèm bundle của
     * mọi target giảng dạy và mọi target resource version — kỳ gói đổi hiệu lực mà các scope cổng
     * gói của chúng tham chiếu (descriptor mang chính định danh kỳ) nên chúng phải ghi lại trong
     * cùng operation. Rỗng khi partition chưa tồn tại.
     */
    private Optional<AuthorizationOperationService.PartitionChange> partitionChange(
            UUID workspaceId, Workspace.Kind kind) {
        List<UUID> partitions =
                locks.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'WORKSPACE'"
                                + " AND owner_key = ?",
                        UUID.class,
                        "workspace:" + workspaceId);
        if (partitions.isEmpty()) {
            return Optional.empty();
        }
        List<AuthorizationOperationService.DesiredProjection> projections = new ArrayList<>();
        if (kind == Workspace.Kind.CENTER) {
            projections.add(
                    new AuthorizationOperationService.DesiredProjection(
                            NativePolicyTemplates.workspaceKey(workspaceId),
                            PermissionCatalog.WorkspaceScope.ENTER.scopeName(),
                            centerProjector.bundleDescriptors(workspaceId),
                            List.of()));
            projections.addAll(teachingProjector.allBundleProjections(workspaceId));
        } else {
            projections.add(
                    new AuthorizationOperationService.DesiredProjection(
                            NativePolicyTemplates.workspaceKey(workspaceId),
                            PersonalEntitlementProjector.bundleScope(),
                            entitlementProjector.bundleDescriptors(workspaceId),
                            List.of()));
            projections.addAll(personalTeachingProjector.allBundleProjections(workspaceId));
        }
        projections.addAll(resourceProjector.allBundleProjections(workspaceId));
        return Optional.of(
                new AuthorizationOperationService.PartitionChange(
                        partitions.getFirst(), List.copyOf(projections)));
    }

    private void requireAccount(UUID userId) {
        if (!accounts.existsById(userId)) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private Workspace.Kind workspaceKind(UUID workspaceId) {
        Workspace workspace =
                workspaces
                        .findById(workspaceId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "WORKSPACE_NOT_FOUND",
                                        "Không tìm thấy workspace."));
        Workspace.Kind kind = workspace.getKind();
        if (kind != Workspace.Kind.CENTER && kind != Workspace.Kind.PERSONAL) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "SUBSCRIPTION_WORKSPACE_KIND",
                    "Kỳ gói chỉ áp dụng cho workspace cá nhân hoặc trung tâm.");
        }
        return kind;
    }

    private static String normalizeConfirmationRef(@Nullable String confirmationRef) {
        if (confirmationRef == null || confirmationRef.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CONFIRMATION_REF",
                    "Cần mã tham chiếu xác nhận của operator.");
        }
        String trimmed = confirmationRef.trim();
        if (trimmed.length() > MAX_CONFIRMATION_REF_LENGTH) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CONFIRMATION_REF",
                    "Mã tham chiếu xác nhận tối đa 255 ký tự.");
        }
        return trimmed;
    }

    /**
     * Tuần tự hoá trong phạm vi transaction hiện hành bằng advisory lock của PostgreSQL, cùng cách
     * ResourceMutationCoordinator khoá: lock tự nhả khi transaction kết thúc nên không cần đường mở
     * khoá riêng, và hai instance của ứng dụng vẫn tôn trọng nhau.
     */
    private void lockOn(String key) {
        AdvisoryLocks.lock(locks, "giaokhoa:subscription-service:", key);
    }
}
