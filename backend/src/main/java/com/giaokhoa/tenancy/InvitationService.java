package com.giaokhoa.tenancy;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vòng đời lời mời vào center: tạo lời mời một đích kèm token một lần, và chấp nhận lời mời để tạo
 * hoặc bổ sung membership. Chỉ hash SHA-256 hex của token được lưu (theo thiết kế của migration
 * V012); token thô sinh bằng SecureRandom 32 byte, chỉ tồn tại trong kết quả của lần tạo đầu tiên
 * để tầng endpoint chuyển tới người được mời, không bao giờ được lưu hay ghi log ở đây. Chấp nhận
 * tra lời mời theo hash của token trình ra: không thấy là 404 không lộ tồn tại, thấy thì người chấp
 * nhận phải đúng đích đã mời — đúng user khi mời theo userId, hoặc account có email đã xác minh
 * trùng email đích khi mời theo email; tên trùng không bao giờ đủ để ghép tài khoản. Chấp nhận
 * không đụng vào user_account nên origin của danh tính được mời không đổi.
 *
 * <p>Role đích của lời mời không chứa OWNER (DB cũng chặn ở CHECK) và MANAGER chỉ do owner của
 * center mời: đó là quy tắc nghiệp vụ của service vì CHECK tĩnh không khóa được người mời, còn
 * quyết định cho phép ở endpoint vẫn do Keycloak PDP xét qua scope membership.invite/manager.assign
 * trước khi gọi vào đây. Tạo lời mời chưa tạo membership; membership chỉ xuất hiện khi chấp nhận.
 * Cả tạo và chấp nhận đều kết thúc bằng operation ghi lại bundle của workspace qua {@link
 * MembershipPolicyProjector} — chấp nhận đổi dữ kiện membership, còn tạo lời mời tuy chưa đổi nguồn
 * quyền nhưng hợp đồng mutation vẫn đòi một operation hoàn tất được, và operation đó đồng thời bảo
 * đảm partition của workspace được reconcile về trạng thái nguồn hiện hành.
 *
 * <p>Idempotency theo đúng mẫu operation dùng chung: mỗi mutation tuần tự hoá trên (actor,
 * idempotency key) hoặc trên token bằng advisory lock trong transaction hiện hành rồi mới kiểm tra
 * replay, nên replay cùng key và payload trả lại đúng operation đã ghi — token thô không tái tạo
 * được ở lần replay, kết quả replay vì vậy không mang token.
 */
@Service
public class InvitationService {
    /** Thời hạn của một lời mời; hết hạn được ép tại lúc chấp nhận theo mốc này. */
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private static final String PENDING_STATE = "PENDING";
    private static final int SINGLE_ROW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final MembershipPolicyProjector projector;

    public InvitationService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            MembershipPolicyProjector projector) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.projector = projector;
    }

    /**
     * Kết quả tạo lời mời. Token thô chỉ có ở lần tạo đầu tiên; replay theo idempotency key trả
     * operation đã ghi với cả invitationId và token đều vắng.
     */
    public record InvitationIssued(
            AuthorizationOperation operation,
            @Nullable UUID invitationId,
            @Nullable String token) {}

    /**
     * Tạo lời mời vào một center cho đúng một đích: {@code targetUserId} của account đã có, hoặc
     * {@code targetEmail} sẽ được chứng minh khi chấp nhận. Người mời phải là OWNER hoặc MANAGER
     * ACTIVE của center — tiền điều kiện dữ kiện của thao tác ghi, không thay quyết định PDP của
     * endpoint.
     */
    @Transactional
    public InvitationIssued invite(
            UUID initiatorUserId,
            UUID workspaceId,
            @Nullable String targetEmail,
            @Nullable UUID targetUserId,
            Set<MembershipRole.Role> roles,
            String idempotencyKey,
            String payloadHash) {
        Objects.requireNonNull(initiatorUserId, "initiatorUserId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(roles, "roles");
        idempotency.requireKey(idempotencyKey);
        lockOn("invitation-create:" + initiatorUserId + ":" + idempotencyKey);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(initiatorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return new InvitationIssued(existing.get(), null, null);
        }
        if ((targetEmail == null) == (targetUserId == null)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INVITATION_TARGET",
                    "Lời mời phải có đúng một đích: email hoặc người dùng đã có.");
        }
        if (roles.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INVITATION_ROLES",
                    "Lời mời phải có ít nhất một role đích.");
        }
        if (roles.contains(MembershipRole.Role.OWNER)) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "OWNER_NOT_INVITABLE",
                    "Lời mời không được cấp role OWNER.");
        }
        requireCenter(workspaceId);
        Set<MembershipRole.Role> initiatorRoles = activeRolesOf(initiatorUserId, workspaceId);
        if (!initiatorRoles.contains(MembershipRole.Role.OWNER)
                && !initiatorRoles.contains(MembershipRole.Role.MANAGER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "INVITER_NOT_AUTHORIZED",
                    "Chỉ chủ sở hữu hoặc quản lý của trung tâm mới được mời thành viên.");
        }
        if (roles.contains(MembershipRole.Role.MANAGER)
                && !initiatorRoles.contains(MembershipRole.Role.OWNER)) {
            throw new HttpProblemException(
                    HttpStatus.FORBIDDEN,
                    "MANAGER_REQUIRES_OWNER",
                    "Chỉ chủ sở hữu trung tâm mới được mời quản lý.");
        }
        String email;
        if (targetUserId != null) {
            requireAccount(targetUserId);
            email = null;
        } else {
            email = normalizeEmail(Objects.requireNonNull(targetEmail, "targetEmail"));
        }

        String token = generateToken();
        UUID invitationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(INVITATION_TTL);
        String[] roleNames =
                roles.stream().map(MembershipRole.Role::name).sorted().toArray(String[]::new);
        String tokenHash = tokenHash(token);
        jdbc.update(
                "INSERT INTO invitation"
                        + " (id, workspace_id, target_email, target_user_id, token_hash,"
                        + " intended_roles, expires_at, state)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                ps -> {
                    ps.setObject(1, invitationId);
                    ps.setObject(2, workspaceId);
                    ps.setString(3, email);
                    ps.setObject(4, targetUserId);
                    ps.setString(5, tokenHash);
                    ps.setArray(6, ps.getConnection().createArrayOf("text", roleNames));
                    ps.setObject(7, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
                });
        AuthorizationOperation operation =
                projector.recordMembershipChange(
                        initiatorUserId, workspaceId, idempotencyKey, payloadHash);
        return new InvitationIssued(operation, invitationId, token);
    }

    /**
     * Chấp nhận lời mời bằng token thô được trình ra. Token chỉ dùng được một lần: chấp nhận xong
     * trạng thái là ACCEPTED và mọi lần dùng lại đều bị từ chối. Membership được tạo ACTIVE ngay
     * với các role đích, hoặc bổ sung role vào membership hiện hành nếu người được mời đã là thành
     * viên — không tạo membership trùng; membership đã thu hồi không bị phục hồi ngầm, lần chấp
     * nhận tạo một quan hệ hiện hành mới theo đúng bất biến một quan hệ hiện hành mỗi
     * user/workspace.
     */
    @Transactional
    public AuthorizationOperation accept(
            UUID acceptorUserId, String rawToken, String idempotencyKey, String payloadHash) {
        Objects.requireNonNull(acceptorUserId, "acceptorUserId");
        Objects.requireNonNull(rawToken, "rawToken");
        idempotency.requireKey(idempotencyKey);
        String hash = tokenHash(rawToken);
        lockOn("invitation-accept:" + hash);
        Optional<AuthorizationOperation> existing =
                idempotency.findExisting(acceptorUserId, idempotencyKey, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, workspace_id, target_email, target_user_id, intended_roles,"
                                + " expires_at, state FROM invitation WHERE token_hash = ?"
                                + " FOR UPDATE",
                        hash);
        if (rows.isEmpty()) {
            // Token lạ và token không tồn tại trả cùng một 404: không lộ lời mời nào.
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Không tìm thấy lời mời.");
        }
        Map<String, Object> invitation = rows.getFirst();
        UUID invitationId = (UUID) Objects.requireNonNull(invitation.get("id"), "id");
        UUID workspaceId =
                (UUID) Objects.requireNonNull(invitation.get("workspace_id"), "workspace_id");
        if (!PENDING_STATE.equals(invitation.get("state"))) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "INVITATION_NOT_PENDING",
                    "Lời mời không còn chờ chấp nhận.");
        }
        Instant expiresAt = expiresAtOf(invitation.get("expires_at"));
        if (!expiresAt.isAfter(Instant.now())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT, "INVITATION_EXPIRED", "Lời mời đã hết hạn.");
        }
        requireTargetMatch(invitation, acceptorUserId);

        MembershipActivation activation = activateMembership(workspaceId, acceptorUserId);
        UUID membershipId = activation.membershipId();
        boolean addedNewRole = false;
        for (String role : stringArray(invitation.get("intended_roles"))) {
            addedNewRole |=
                    jdbc.update(
                                    "INSERT INTO membership_role (membership_id, role)"
                                            + " VALUES (?, ?)"
                                            + " ON CONFLICT (membership_id, role) DO NOTHING",
                                    membershipId,
                                    role)
                            > 0;
        }
        if (activation.preExisting() && addedNewRole) {
            // Role nằm ở bảng riêng nên phải chạm row membership thì version mới tăng: nếu
            // không, một writer khác giữ If-Match cũ vẫn ghi đè được và làm mất role vừa cộng.
            jdbc.update(
                    "UPDATE center_membership SET version = version + 1, updated_at = now()"
                            + " WHERE id = ?",
                    membershipId);
        }
        int accepted =
                jdbc.update(
                        "UPDATE invitation SET state = 'ACCEPTED', version = version + 1"
                                + " WHERE id = ? AND state = 'PENDING'",
                        invitationId);
        if (accepted != SINGLE_ROW) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "INVITATION_NOT_PENDING",
                    "Lời mời không còn chờ chấp nhận.");
        }
        return projector.recordMembershipChange(
                acceptorUserId, workspaceId, idempotencyKey, payloadHash);
    }

    // pgjdbc trả java.sql.Timestamp cho cột timestamptz khi đọc qua Map của JdbcTemplate,
    // không phải OffsetDateTime: ép kiểu thẳng sẽ vỡ mọi lần chấp nhận.
    private static Instant expiresAtOf(@Nullable Object value) {
        Objects.requireNonNull(value, "expires_at");
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unexpected expires_at value: " + value.getClass());
    }

    private void requireTargetMatch(Map<String, Object> invitation, UUID acceptorUserId) {
        UUID targetUserId = (UUID) invitation.get("target_user_id");
        if (targetUserId != null) {
            if (!targetUserId.equals(acceptorUserId)) {
                throw targetMismatch();
            }
            return;
        }
        String targetEmail =
                Objects.requireNonNull((String) invitation.get("target_email"), "target_email");
        List<Map<String, Object>> accounts =
                jdbc.queryForList(
                        "SELECT email, email_verified FROM user_account WHERE id = ?",
                        acceptorUserId);
        if (accounts.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "Chưa có tài khoản cho danh tính này.");
        }
        Map<String, Object> account = accounts.getFirst();
        String email = (String) account.get("email");
        boolean verified = Boolean.TRUE.equals(account.get("email_verified"));
        // Email đích chỉ ghép được khi account hiện hành mang đúng email đó và email đã xác
        // minh: khớp tên hay email chưa xác minh đều không chứng minh được quyền sở hữu đích.
        if (!verified || email == null || !email.equalsIgnoreCase(targetEmail)) {
            throw targetMismatch();
        }
    }

    /** Membership sau bước kích hoạt: id của row và cờ cho biết row đã tồn tại từ trước. */
    private record MembershipActivation(UUID membershipId, boolean preExisting) {}

    private MembershipActivation activateMembership(UUID workspaceId, UUID userId) {
        List<Map<String, Object>> current =
                jdbc.queryForList(
                        "SELECT id, state FROM center_membership WHERE workspace_id = ?"
                                + " AND user_id = ? AND state IN ('PENDING', 'ACTIVE')",
                        workspaceId,
                        userId);
        if (!current.isEmpty()) {
            Map<String, Object> membership = current.getFirst();
            UUID membershipId = (UUID) Objects.requireNonNull(membership.get("id"), "id");
            if (PENDING_STATE.equals(membership.get("state"))) {
                // Chỉ đổi state, giữ nguyên joined_at vì trigger guard_directory_history
                // cấm sửa lịch sử tham gia.
                jdbc.update(
                        "UPDATE center_membership SET state = 'ACTIVE', version = version + 1,"
                                + " updated_at = now() WHERE id = ? AND state = 'PENDING'",
                        membershipId);
            }
            return new MembershipActivation(membershipId, true);
        }
        UUID membershipId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO center_membership (id, workspace_id, user_id, state, joined_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', now())",
                membershipId,
                workspaceId,
                userId);
        return new MembershipActivation(membershipId, false);
    }

    private void requireCenter(UUID workspaceId) {
        List<Map<String, Object>> workspaces =
                jdbc.queryForList("SELECT kind, state FROM workspace WHERE id = ?", workspaceId);
        if (workspaces.isEmpty()
                || !"CENTER".equals(workspaces.getFirst().get("kind"))
                || !"ACTIVE".equals(workspaces.getFirst().get("state"))) {
            // Không phân biệt thiếu/không phải center/không ACTIVE: không lộ sự tồn tại.
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND",
                    "Không tìm thấy trung tâm hợp lệ.");
        }
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

    private Set<MembershipRole.Role> activeRolesOf(UUID userId, UUID workspaceId) {
        return Set.copyOf(
                jdbc
                        .queryForList(
                                "SELECT r.role FROM center_membership m"
                                        + " JOIN membership_role r ON r.membership_id = m.id"
                                        + " WHERE m.workspace_id = ? AND m.user_id = ?"
                                        + " AND m.state = 'ACTIVE'",
                                String.class,
                                workspaceId,
                                userId)
                        .stream()
                        .map(MembershipRole.Role::valueOf)
                        .toList());
    }

    private static HttpProblemException targetMismatch() {
        return new HttpProblemException(
                HttpStatus.FORBIDDEN,
                "INVITATION_TARGET_MISMATCH",
                "Lời mời này không dành cho tài khoản hiện hành.");
    }

    private static String normalizeEmail(String email) {
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > 320 || !trimmed.contains("@")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TARGET_EMAIL",
                    "Email đích của lời mời không hợp lệ.");
        }
        return trimmed;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hash tra cứu của token: SHA-256 hex, đúng định dạng cột token_hash của V012. */
    private static String tokenHash(String rawToken) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static List<String> stringArray(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        Object[] raw;
        if (value instanceof java.sql.Array array) {
            try {
                raw = (Object[]) array.getArray();
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("Cannot read invitation roles", ex);
            }
        } else if (value instanceof Object[] objects) {
            raw = objects;
        } else {
            throw new IllegalStateException(
                    "Unexpected invitation roles value: " + value.getClass());
        }
        List<String> out = new java.util.ArrayList<>(raw.length);
        for (Object item : raw) {
            out.add(String.valueOf(item));
        }
        return List.copyOf(out);
    }

    /**
     * Tuần tự hoá trong phạm vi transaction hiện hành bằng advisory lock của PostgreSQL, cùng cách
     * CenterService khoá: lock tự nhả khi transaction kết thúc nên không cần đường mở khoá riêng,
     * và hai instance của ứng dụng vẫn tôn trọng nhau.
     */
    private void lockOn(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", _ -> {}, lockId(key));
    }

    private static long lockId(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("giaokhoa:invitation-service:" + key)
                                            .getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
