package com.giaokhoa.identity;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Bootstrap profile/user/personal workspace từ danh tính Keycloak hiện hành. Nguồn danh tính duy
 * nhất là profile đọc mới qua Admin API: không bao giờ nhận email, trạng thái verified hay origin
 * từ request của browser hoặc claim của token cũ. Origin là dữ liệu do server kiểm soát và không
 * bao giờ bị bootstrap đổi: account đã có giữ nguyên origin dù profile hiện hành khác đi. Không
 * email không bị ép bổ sung: email nullable, chỉ verified mới đòi email.
 */
@Service
public class IdentityService {

    private final JdbcTemplate jdbc;
    private final CurrentIdentityClient identities;

    private final ResourceMutationCoordinator coordinator;
    private final IdentityEligibilityService eligibility;

    public IdentityService(
            JdbcTemplate jdbc,
            CurrentIdentityClient identities,
            ResourceMutationCoordinator coordinator,
            IdentityEligibilityService eligibility) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.coordinator = coordinator;
        this.eligibility = eligibility;
    }

    /** Kết quả bootstrap: user và personal workspace của actor, kèm cờ account mới tạo. */
    public record BootstrapResult(UUID userId, UUID personalWorkspaceId, boolean created) {}

    /**
     * Hồ sơ hiện hành của actor. Nghề nghiệp không có trong hồ sơ này: role chỉ có nghĩa trong một
     * workspace cụ thể qua membership_role, không bao giờ suy thành nghề nghiệp toàn cục của
     * account.
     */
    public record CurrentUser(
            UUID userId,
            String username,
            @Nullable String email,
            boolean emailVerified,
            UserAccount.Origin origin,
            String identityState,
            UUID personalWorkspaceId) {}

    /**
     * Bootstrap account cho Keycloak subject này từ profile hiện hành. Account chưa có được tạo
     * cùng personal workspace trong cùng một transaction (bất biến DB: mỗi account có đúng một
     * personal workspace); account đã có chỉ đồng bộ email/trạng thái verified, giữ nguyên origin,
     * username và identity_state. Profile không đọc được, subject không khớp, account bị vô hiệu
     * hay verified mà không có email đều fail-closed 503 và không ghi gì.
     */
    public BootstrapResult bootstrap(String keycloakSub) {
        Objects.requireNonNull(keycloakSub, "keycloakSub");
        if (keycloakSub.isBlank()) {
            throw new IllegalArgumentException("Keycloak subject is required");
        }
        CurrentIdentity current = readCurrent(keycloakSub);
        if (!current.enabled()) {
            throw unavailable(null);
        }
        if (current.emailVerified() && current.email() == null) {
            // Verified mà không có email là profile không nhất quán: không ghi gì.
            throw unavailable(null);
        }

        return coordinator.withResourceLock(
                "identity-bootstrap:" + keycloakSub,
                () -> {
                    BootstrapResult result = bootstrapCurrent(keycloakSub, current);
                    eligibility.synchronizeEligibility(result.userId());
                    return result;
                });
    }

    private BootstrapResult bootstrapCurrent(String keycloakSub, CurrentIdentity current) {
        List<Map<String, Object>> accounts =
                jdbc.queryForList(
                        "SELECT id, username, origin FROM user_account WHERE keycloak_sub = ?",
                        keycloakSub);
        if (!accounts.isEmpty()) {
            Map<String, Object> account = accounts.getFirst();
            UUID userId = (UUID) Objects.requireNonNull(account.get("id"), "id");
            jdbc.update(
                    "UPDATE user_account SET email = ?, email_verified = ? WHERE id = ?",
                    current.email(),
                    current.emailVerified(),
                    userId);
            return new BootstrapResult(userId, personalWorkspaceOf(userId), false);
        }

        List<String> takenUsernames =
                jdbc.queryForList(
                        "SELECT keycloak_sub FROM user_account WHERE username = ?",
                        String.class,
                        current.username());
        if (!takenUsernames.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.USERNAME_TAKEN,
                    "Tên đăng nhập đã thuộc về một danh tính khác.");
        }
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account"
                        + " (id, keycloak_sub, username, email, email_verified, origin,"
                        + " identity_state) VALUES (?, ?, ?, ?, ?, 'SELF_REGISTERED', 'ACTIVE')",
                userId,
                keycloakSub,
                current.username(),
                current.email(),
                current.emailVerified());
        jdbc.update(
                "INSERT INTO workspace (id, kind, owner_user_id, state)"
                        + " VALUES (?, 'PERSONAL', ?, 'ACTIVE')",
                workspaceId,
                userId);
        return new BootstrapResult(userId, workspaceId, true);
    }

    /** Hồ sơ hiện hành của actor kèm personal workspace; chưa bootstrap thì 404. */
    @Transactional(readOnly = true)
    public CurrentUser currentUser(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        List<Map<String, Object>> accounts =
                jdbc.queryForList(
                        "SELECT username, email, email_verified, origin, identity_state"
                                + " FROM user_account WHERE id = ?",
                        actorUserId);
        if (accounts.isEmpty()) {
            throw new HttpProblemException(
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.ACCOUNT_NOT_FOUND,
                    "Chưa có tài khoản cho danh tính này.");
        }
        Map<String, Object> account = accounts.getFirst();
        return new CurrentUser(
                actorUserId,
                (String) Objects.requireNonNull(account.get("username"), "username"),
                (String) account.get("email"),
                Boolean.TRUE.equals(account.get("email_verified")),
                UserAccount.Origin.valueOf(
                        (String) Objects.requireNonNull(account.get("origin"), "origin")),
                (String) Objects.requireNonNull(account.get("identity_state"), "identity_state"),
                personalWorkspaceOf(actorUserId));
    }

    private UUID personalWorkspaceOf(UUID userId) {
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = ?",
                        UUID.class,
                        userId);
        if (found.isEmpty()) {
            // Bất biến DB đáng lẽ ngăn trạng thái này; thấy nó là dữ liệu hỏng, không đoán.
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.IDENTITY_UNAVAILABLE,
                    "Không xác minh được danh tính hiện hành, thử lại sau.");
        }
        return found.getFirst();
    }

    private CurrentIdentity readCurrent(String keycloakSub) {
        CurrentIdentity current;
        try {
            current = identities.readCurrentIdentity(keycloakSub);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException ex) {
            throw unavailable(ex);
        }
        if (!keycloakSub.equals(current.id())) {
            // Profile đọc được là của subject khác: không phải input của account này.
            throw unavailable(null);
        }
        return current;
    }

    private static HttpProblemException unavailable(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.IDENTITY_UNAVAILABLE,
                "Không xác minh được danh tính hiện hành, thử lại sau.",
                cause);
    }
}
