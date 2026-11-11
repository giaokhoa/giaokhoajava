package com.giaokhoa.identity.service;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.identity.model.RecoveryMutation;
import com.giaokhoa.identity.model.RecoveryRequest;
import com.giaokhoa.identity.repository.RecoveryMutationRepository;
import com.giaokhoa.identity.repository.RecoveryRequestRepository;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.shared.audit.AuditEvent;
import com.giaokhoa.shared.audit.AuditRecorder;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipRole;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow hỗ trợ khôi phục danh tính cho tài khoản không tự khôi phục được qua email. Người dùng
 * chỉ gửi yêu cầu cho chính mình; OWNER/MANAGER ACTIVE của center được gửi cho thành viên trong
 * center mình quản lý. Đích ngoài phạm vi và đích không tồn tại đều trả cùng một 404 để không lộ sự
 * tồn tại của tài khoản. Chỉ platform operator được xác minh và xử lý, với quyết định PDP tại
 * endpoint.
 *
 * <p>Service ép thứ tự REQUESTED → VERIFIED → RESOLVED/REJECTED bằng chuyển trạng thái trên entity
 * đã nạp kèm kiểm tra lạc quan {@code @Version}; DB giữ tính nhất quán của verified_by/resolved_at.
 * APPROVE chỉ ghi VERIFIED và giữ yêu cầu PENDING. Operator khôi phục trực tiếp trên Keycloak và
 * bàn giao credential qua kênh riêng cho đúng người dùng trước khi xác nhận COMPLETE để ghi
 * RESOLVED. COMPLETE ghi nhận xác nhận của operator, không tự kiểm chứng thao tác credential trên
 * Keycloak.
 *
 * <p>Service không đặt lại credential hay đổi origin của danh tính dùng chung; credential không đi
 * qua response/list/poll của workflow và không trả cho center đã gửi yêu cầu. Các mutation HTTP
 * kiểm tra idempotency theo actor/key/payload và version khi resolve; lịch sử yêu cầu được DB bảo
 * vệ khỏi hard-delete hoặc đổi định danh.
 */
@Service
public class RecoveryService {
    /**
     * Độ dài tối đa của ghi chú xác minh và lý do cần hỗ trợ (khớp VARCHAR(1000) ở migration; CHECK
     * chỉ chặn chuỗi toàn khoảng trắng).
     */
    private static final int MAX_NOTE_LENGTH = 1000;

    private final RecoveryRequestRepository requests;
    private final RecoveryMutationRepository mutations;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CenterMembershipRepository memberships;

    private final IdempotencyService idempotency;

    private final AuditRecorder audit;

    public RecoveryService(
            RecoveryRequestRepository requests,
            RecoveryMutationRepository mutations,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CenterMembershipRepository memberships,
            IdempotencyService idempotency,
            AuditRecorder audit) {
        this.requests = requests;
        this.mutations = mutations;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    /** Quyết định của operator khi resolve, khớp hợp đồng resolve của platform recovery. */
    public enum Decision {
        APPROVE,
        COMPLETE,
        REJECT
    }

    /** Ảnh chụp một yêu cầu hỗ trợ tại thời điểm đọc. */
    public record RequestView(
            UUID id,
            UUID requesterUserId,
            @Nullable UUID workspaceId,
            UUID targetUserId,
            @Nullable String reason,
            RecoveryRequest.State state,
            @Nullable UUID verifiedBy,
            @Nullable Instant resolvedAt,
            @Nullable String verificationNote,
            Instant createdAt,
            long version) {}

    /**
     * Gửi yêu cầu hỗ trợ cho tài khoản đích. Không có workspace thì chỉ được gửi cho chính mình; có
     * workspace thì workspace phải là CENTER đang ACTIVE, tài khoản đích phải là thành viên của
     * center đó và người gửi phải là chính tài khoản đích hoặc OWNER/MANAGER ACTIVE của center.
     */
    @Transactional
    public RequestView submit(UUID requesterUserId, UUID targetUserId, @Nullable UUID workspaceId) {
        return submit(requesterUserId, targetUserId, workspaceId, null);
    }

    @Transactional
    public RequestView submit(
            UUID requesterUserId,
            UUID targetUserId,
            @Nullable UUID workspaceId,
            @Nullable String reason) {
        return submitRequest(UUID.randomUUID(), requesterUserId, targetUserId, workspaceId, reason);
    }

    @Transactional
    public RequestView submitOnce(
            UUID requesterUserId,
            UUID targetUserId,
            @Nullable UUID workspaceId,
            @Nullable String reason,
            String key,
            String hash) {
        UUID id = UUID.randomUUID();
        Optional<UUID> replay = replay(requesterUserId, key, hash, id, "SUBMIT");
        return replay.isPresent()
                ? loadView(replay.get())
                : submitRequest(id, requesterUserId, targetUserId, workspaceId, reason);
    }

    private RequestView submitRequest(
            UUID id,
            UUID requesterUserId,
            UUID targetUserId,
            @Nullable UUID workspaceId,
            @Nullable String reason) {
        Objects.requireNonNull(requesterUserId, "requesterUserId");
        Objects.requireNonNull(targetUserId, "targetUserId");
        requireAccount(requesterUserId);

        boolean self = requesterUserId.equals(targetUserId);
        if (!accounts.existsById(targetUserId)) {
            throw targetNotFound();
        }
        if (workspaceId == null) {
            if (!self) {
                throw targetNotFound();
            }
        } else {
            boolean centerActive =
                    workspaces
                            .findById(workspaceId)
                            .filter(workspace ->
                                    workspace.getKind() == Workspace.Kind.CENTER
                                            && "ACTIVE".equals(workspace.getState()))
                            .isPresent();
            boolean targetIsMember =
                    centerActive
                            && memberships.existsByWorkspaceIdAndUserIdAndStateIn(
                                    workspaceId,
                                    targetUserId,
                                    List.of(
                                            CenterMembership.State.PENDING,
                                            CenterMembership.State.ACTIVE));
            if (!targetIsMember) {
                throw targetNotFound();
            }
            if (!self) {
                Set<MembershipRole.Role> requesterRoles =
                        memberships
                                .findByWorkspaceIdAndUserIdAndState(
                                        workspaceId, requesterUserId, CenterMembership.State.ACTIVE)
                                .map(CenterMembership::getRoles)
                                .orElse(Set.of());
                if (!requesterRoles.contains(MembershipRole.Role.OWNER)
                        && !requesterRoles.contains(MembershipRole.Role.MANAGER)) {
                    throw targetNotFound();
                }
            }
        }

        requests.saveAndFlush(
                new RecoveryRequest(
                        id, requesterUserId, workspaceId, targetUserId, normalizeReason(reason)));
        return loadView(id);
    }

    /** Hàng đợi yêu cầu hỗ trợ cho platform operator, cũ nhất trước. */
    @Transactional(readOnly = true)
    public List<RequestView> listRequests() {
        return requests.findAllByOrderByCreatedAtAscIdAsc().stream()
                .map(RecoveryService::view)
                .toList();
    }

    /**
     * Operator xác minh danh tính ngoài hệ thống và ghi nhận: chỉ chuyển được từ REQUESTED, ghi
     * verified_by là chính operator đang xử lý kèm ghi chú xác minh tối thiểu.
     */
    @Transactional
    public RequestView verify(
            UUID operatorUserId, UUID requestId, @Nullable String verificationNote) {
        Objects.requireNonNull(operatorUserId, "operatorUserId");
        Objects.requireNonNull(requestId, "requestId");
        requireAccount(operatorUserId);
        String note = normalizeNote(verificationNote);
        RecoveryRequest request =
                requests.findById(requestId).orElseThrow(() -> stateConflict(requestId));
        if (request.getState() != RecoveryRequest.State.REQUESTED) {
            throw stateConflict(requestId);
        }
        request.verify(operatorUserId, note);
        requests.flush();
        return loadView(requestId);
    }

    /** Replay được kiểm tra trước version: retry của mutation đã commit phải trả lại biên nhận. */
    @Transactional
    public RequestView resolveOnce(
            UUID operatorUserId,
            UUID requestId,
            Decision decision,
            @Nullable String verificationNote,
            String key,
            String hash,
            @Nullable String ifMatch) {
        Optional<UUID> existing = replay(operatorUserId, key, hash, requestId, "RESOLVE");
        if (existing.isPresent()) {
            return loadView(existing.get());
        }
        RecoveryRequest request =
                requests.findForUpdateById(requestId).orElseThrow(RecoveryService::requestNotFound);
        IdempotencyService.requireIfMatch(ifMatch, request.getVersion());
        return resolve(operatorUserId, requestId, decision, verificationNote);
    }

    /** APPROVE chỉ xác minh; COMPLETE là xác nhận của operator sau khi đã khôi phục và bàn giao. */
    @Transactional
    public RequestView resolve(
            UUID operatorUserId,
            UUID requestId,
            Decision decision,
            @Nullable String verificationNote) {
        Objects.requireNonNull(operatorUserId, "operatorUserId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(decision, "decision");
        requireAccount(operatorUserId);
        String note = normalizeNote(verificationNote);
        if (decision != Decision.REJECT && note == null) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_VERIFICATION_NOTE,
                    "Cần ghi nhận việc xác minh hoặc việc khôi phục và bàn giao đã hoàn tất.");
        }
        RecoveryRequest.State current = currentState(requestId);
        if (current == RecoveryRequest.State.RESOLVED
                || current == RecoveryRequest.State.REJECTED) {
            throw invalidState(requestId);
        }
        RequestView result;
        if (decision == Decision.APPROVE) {
            result = verify(operatorUserId, requestId, note);
        } else {
            if (decision == Decision.COMPLETE && current != RecoveryRequest.State.VERIFIED) {
                throw invalidState(requestId);
            }
            if (current == RecoveryRequest.State.REQUESTED) {
                verify(operatorUserId, requestId, note);
            }
            RecoveryRequest request =
                    requests.findById(requestId).orElseThrow(RecoveryService::requestNotFound);
            request.resolve(
                    decision == Decision.COMPLETE
                            ? RecoveryRequest.State.RESOLVED
                            : RecoveryRequest.State.REJECTED,
                    Instant.now(),
                    note);
            requests.flush();
            result = loadView(requestId);
        }
        // Một dòng audit cho mỗi quyết định resolve thật: APPROVE mới tới VERIFIED nên outcome
        // còn PENDING; COMPLETE/REJECT đã kết thúc vòng đời yêu cầu nên SUCCEEDED. Replay qua
        // resolveOnce trả sớm trước khi vào đây nên không ghi trùng.
        audit.record(
                operatorUserId,
                result.workspaceId(),
                AuditEvent.Action.RECOVERY,
                AuditEvent.TargetType.ACCOUNT,
                result.targetUserId(),
                null,
                decision == Decision.APPROVE
                        ? AuditEvent.Outcome.PENDING
                        : AuditEvent.Outcome.SUCCEEDED);
        return result;
    }

    private Optional<UUID> replay(
            UUID actor, String key, String hash, UUID requestId, String kind) {
        idempotency.requireKey(key);
        // Claim bằng insert-or-ignore native qua fragment của repository: hai lượt đồng thời
        // cùng (actor, khóa) thì một lượt chèn được, lượt còn lại nhận 0 và đọc lại biên nhận
        // đã thắng ở dưới — find-then-save sẽ biến lượt thua thành vi phạm khóa chính. Hàng
        // mutation ghi trước khi request tồn tại; khóa ngoại của request_id là deferred nên
        // thứ tự ghi này hợp lệ tới cuối transaction.
        int inserted =
                mutations.insertIfAbsent(
                        new RecoveryMutation(
                                actor, key, hash, requestId, RecoveryMutation.Kind.valueOf(kind)));
        if (inserted != 0) {
            return Optional.empty();
        }
        RecoveryMutation previous =
                mutations
                        .findByActorUserIdAndIdempotencyKey(actor, key)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Biên nhận khôi phục vừa bị chặn claim nhưng không đọc lại được."));
        if (!hash.equals(previous.getPayloadHash())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of(previous.getRequestId());
    }

    private void requireAccount(UUID userId) {
        if (!accounts.existsById(userId)) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.ACCOUNT_NOT_FOUND,
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private RecoveryRequest.State currentState(UUID requestId) {
        return requests.findById(requestId)
                .orElseThrow(RecoveryService::requestNotFound)
                .getState();
    }

    private RequestView loadView(UUID requestId) {
        return requests.findById(requestId)
                .map(RecoveryService::view)
                .orElseThrow(RecoveryService::requestNotFound);
    }

    private HttpProblemException stateConflict(UUID requestId) {
        // Phân biệt yêu cầu không tồn tại với yêu cầu đã rời trạng thái cho phép.
        if (requests.findById(requestId).isEmpty()) {
            return requestNotFound();
        }
        return invalidState(requestId);
    }

    private static RequestView view(RecoveryRequest request) {
        return new RequestView(
                request.getId(),
                request.getRequesterUserId(),
                request.getWorkspaceId(),
                request.getTargetUserId(),
                request.getReason(),
                request.getState(),
                request.getVerifiedBy(),
                request.getResolvedAt(),
                request.getVerificationNote(),
                request.getCreatedAt(),
                request.getVersion());
    }

    private static HttpProblemException targetNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.RECOVERY_TARGET_NOT_FOUND,
                "Không tìm thấy tài khoản đích trong phạm vi yêu cầu hỗ trợ.");
    }

    private static HttpProblemException requestNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                ErrorCodes.RECOVERY_REQUEST_NOT_FOUND,
                "Không tìm thấy yêu cầu hỗ trợ.");
    }

    private static HttpProblemException invalidState(UUID requestId) {
        return new HttpProblemException(
                HttpStatus.CONFLICT,
                ErrorCodes.RECOVERY_INVALID_STATE,
                "Yêu cầu hỗ trợ " + requestId + " không còn ở trạng thái cho phép thao tác này.");
    }

    private static @Nullable String normalizeNote(@Nullable String verificationNote) {
        if (verificationNote == null) {
            return null;
        }
        String trimmed = verificationNote.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NOTE_LENGTH) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_VERIFICATION_NOTE,
                    "Ghi chú xác minh phải có từ 1 đến 1000 ký tự.");
        }
        return trimmed;
    }

    private static @Nullable String normalizeReason(@Nullable String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_REASON,
                    "Lý do cần hỗ trợ phải có từ 1 đến 1000 ký tự.");
        }
        return trimmed;
    }
}
