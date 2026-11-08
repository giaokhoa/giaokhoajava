package com.giaokhoa.identity;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.identity.model.RecoveryRequest;
import com.giaokhoa.shared.http.HttpProblemException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow hỗ trợ khôi phục danh tính cho tài khoản không tự khôi phục được qua email. Người dùng
 * chỉ gửi yêu cầu cho chính mình; OWNER/MANAGER ACTIVE của center được gửi cho thành viên trong
 * center mình quản lý. Đích ngoài phạm vi và đích không tồn tại đều trả cùng một 404 để không lộ sự
 * tồn tại của tài khoản. Chỉ platform operator được xác minh và xử lý, với quyết định PDP tại
 * endpoint.
 *
 * <p>Service ép thứ tự REQUESTED → VERIFIED → RESOLVED/REJECTED bằng UPDATE có điều kiện trạng
 * thái; DB giữ tính nhất quán của verified_by/resolved_at. APPROVE chỉ ghi VERIFIED và giữ yêu cầu
 * PENDING. Operator khôi phục trực tiếp trên Keycloak và bàn giao credential qua kênh riêng cho
 * đúng người dùng trước khi xác nhận COMPLETE để ghi RESOLVED. COMPLETE ghi nhận xác nhận của
 * operator, không tự kiểm chứng thao tác credential trên Keycloak.
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

    private final JdbcTemplate jdbc;

    private final IdempotencyService idempotency;

    public RecoveryService(JdbcTemplate jdbc, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
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
        List<UUID> targets =
                jdbc.queryForList(
                        "SELECT id FROM user_account WHERE id = ?", UUID.class, targetUserId);
        if (targets.isEmpty()) {
            throw targetNotFound();
        }
        if (workspaceId == null) {
            if (!self) {
                throw targetNotFound();
            }
        } else {
            List<Map<String, Object>> workspaces =
                    jdbc.queryForList(
                            "SELECT kind, state FROM workspace WHERE id = ?", workspaceId);
            boolean centerActive =
                    !workspaces.isEmpty()
                            && "CENTER".equals(workspaces.getFirst().get("kind"))
                            && "ACTIVE".equals(workspaces.getFirst().get("state"));
            List<UUID> targetMemberships =
                    centerActive
                            ? jdbc.queryForList(
                                    "SELECT id FROM center_membership WHERE workspace_id = ?"
                                            + " AND user_id = ? AND state IN ('PENDING', 'ACTIVE')",
                                    UUID.class,
                                    workspaceId,
                                    targetUserId)
                            : List.of();
            if (targetMemberships.isEmpty()) {
                throw targetNotFound();
            }
            if (!self) {
                List<String> managerRoles =
                        jdbc.queryForList(
                                "SELECT r.role FROM center_membership m"
                                        + " JOIN membership_role r ON r.membership_id = m.id"
                                        + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                        + " AND m.state = 'ACTIVE'"
                                        + " AND r.role IN ('OWNER', 'MANAGER')",
                                String.class,
                                workspaceId,
                                requesterUserId);
                if (managerRoles.isEmpty()) {
                    throw targetNotFound();
                }
            }
        }

        jdbc.update(
                "INSERT INTO recovery_request"
                        + " (id, requester_user_id, workspace_id, target_user_id, state, reason)"
                        + " VALUES (?, ?, ?, ?, 'REQUESTED', ?)",
                id,
                requesterUserId,
                workspaceId,
                targetUserId,
                normalizeReason(reason));
        return loadView(id);
    }

    /** Hàng đợi yêu cầu hỗ trợ cho platform operator, cũ nhất trước. */
    @Transactional(readOnly = true)
    public List<RequestView> listRequests() {
        return jdbc.query(
                "SELECT id, requester_user_id, workspace_id, target_user_id, reason, state,"
                        + " verified_by, resolved_at, verification_note, created_at, version"
                        + " FROM recovery_request ORDER BY created_at ASC, id ASC",
                REQUEST_MAPPER);
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
        int updated =
                jdbc.update(
                        "UPDATE recovery_request SET state = 'VERIFIED', verified_by = ?,"
                                + " verification_note = ?, version = version + 1"
                                + " WHERE id = ? AND state = 'REQUESTED'",
                        operatorUserId,
                        note,
                        requestId);
        if (updated == 0) {
            throw stateConflict(requestId);
        }
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
        List<Long> versions =
                jdbc.queryForList(
                        "SELECT version FROM recovery_request WHERE id = ? FOR UPDATE",
                        Long.class,
                        requestId);
        if (versions.isEmpty()) {
            throw requestNotFound();
        }
        IdempotencyService.requireIfMatch(ifMatch, versions.getFirst());
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
                    "INVALID_VERIFICATION_NOTE",
                    "Cần ghi nhận việc xác minh hoặc việc khôi phục và bàn giao đã hoàn tất.");
        }
        RecoveryRequest.State current = currentState(requestId);
        if (current == RecoveryRequest.State.RESOLVED
                || current == RecoveryRequest.State.REJECTED) {
            throw invalidState(requestId);
        }
        if (decision == Decision.APPROVE) {
            return verify(operatorUserId, requestId, note);
        }
        if (decision == Decision.COMPLETE && current != RecoveryRequest.State.VERIFIED) {
            throw invalidState(requestId);
        }
        if (current == RecoveryRequest.State.REQUESTED) {
            verify(operatorUserId, requestId, note);
        }
        String state = decision == Decision.COMPLETE ? "RESOLVED" : "REJECTED";
        int updated =
                jdbc.update(
                        "UPDATE recovery_request SET state = ?, resolved_at = now(),"
                                + " verification_note = COALESCE(?, verification_note), version = version + 1"
                                + " WHERE id = ? AND state = 'VERIFIED'",
                        state,
                        note,
                        requestId);
        if (updated == 0) {
            throw stateConflict(requestId);
        }
        return loadView(requestId);
    }

    private Optional<UUID> replay(
            UUID actor, String key, String hash, UUID requestId, String kind) {
        idempotency.requireKey(key);
        int inserted =
                jdbc.update(
                        "INSERT INTO recovery_mutation(actor_user_id,idempotency_key,payload_hash,request_id,kind)"
                                + " VALUES (?,?,?,?,?) ON CONFLICT (actor_user_id,idempotency_key) DO NOTHING",
                        actor,
                        key,
                        hash,
                        requestId,
                        kind);
        if (inserted != 0) {
            return Optional.empty();
        }
        Map<String, Object> previous =
                jdbc.queryForMap(
                        "SELECT payload_hash, request_id FROM recovery_mutation WHERE actor_user_id = ? AND idempotency_key = ?",
                        actor,
                        key);
        if (!hash.equals(previous.get("payload_hash"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã dùng với yêu cầu khác.");
        }
        return Optional.of((UUID) Objects.requireNonNull(previous.get("request_id")));
    }

    private void requireAccount(UUID userId) {
        List<UUID> accounts =
                jdbc.queryForList("SELECT id FROM user_account WHERE id = ?", UUID.class, userId);
        if (accounts.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "Chưa có tài khoản cho danh tính này.");
        }
    }

    private RecoveryRequest.State currentState(UUID requestId) {
        List<String> states =
                jdbc.queryForList(
                        "SELECT state FROM recovery_request WHERE id = ?", String.class, requestId);
        if (states.isEmpty()) {
            throw requestNotFound();
        }
        return RecoveryRequest.State.valueOf(states.getFirst());
    }

    private RequestView loadView(UUID requestId) {
        List<RequestView> rows =
                jdbc.query(
                        "SELECT id, requester_user_id, workspace_id, target_user_id, reason, state,"
                                + " verified_by, resolved_at, verification_note, created_at, version"
                                + " FROM recovery_request WHERE id = ?",
                        REQUEST_MAPPER,
                        requestId);
        if (rows.isEmpty()) {
            throw requestNotFound();
        }
        return rows.getFirst();
    }

    private HttpProblemException stateConflict(UUID requestId) {
        // Phân biệt yêu cầu không tồn tại với yêu cầu đã rời trạng thái cho phép.
        List<String> states =
                jdbc.queryForList(
                        "SELECT state FROM recovery_request WHERE id = ?", String.class, requestId);
        if (states.isEmpty()) {
            return requestNotFound();
        }
        return invalidState(requestId);
    }

    private static HttpProblemException targetNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "RECOVERY_TARGET_NOT_FOUND",
                "Không tìm thấy tài khoản đích trong phạm vi yêu cầu hỗ trợ.");
    }

    private static HttpProblemException requestNotFound() {
        return new HttpProblemException(
                HttpStatus.NOT_FOUND,
                "RECOVERY_REQUEST_NOT_FOUND",
                "Không tìm thấy yêu cầu hỗ trợ.");
    }

    private static HttpProblemException invalidState(UUID requestId) {
        return new HttpProblemException(
                HttpStatus.CONFLICT,
                "RECOVERY_INVALID_STATE",
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
                    "INVALID_VERIFICATION_NOTE",
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
                    "INVALID_REASON",
                    "Lý do cần hỗ trợ phải có từ 1 đến 1000 ký tự.");
        }
        return trimmed;
    }

    // Đọc thẳng OffsetDateTime theo kiểu đích: để pgjdbc tự chuyển cột TIMESTAMPTZ thay vì
    // nhận java.sql.Timestamp qua Map rồi quy đổi thủ công.
    private static final RowMapper<RequestView> REQUEST_MAPPER =
            (rs, _) -> {
                OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
                return new RequestView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("requester_user_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("target_user_id", UUID.class),
                        rs.getString("reason"),
                        RecoveryRequest.State.valueOf(rs.getString("state")),
                        rs.getObject("verified_by", UUID.class),
                        resolvedAt == null ? null : resolvedAt.toInstant(),
                        rs.getString("verification_note"),
                        Objects.requireNonNull(rs.getObject("created_at", OffsetDateTime.class))
                                .toInstant(),
                        rs.getLong("version"));
            };
}
