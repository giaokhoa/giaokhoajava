package com.giaokhoa.identity;

import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.eligibility.IdentityEligibilitySynchronizer;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.infrastructure.tenancy.TenantSchemaProvisioner;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
    static final String IDENTITY_UNAVAILABLE_CODE = "IDENTITY_UNAVAILABLE";

    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CurrentIdentityClient identities;

    private final ResourceMutationCoordinator coordinator;
    private final IdentityEligibilitySynchronizer eligibility;
    private final TenantSchemaProvisioner tenantProvisioner;

    public IdentityService(
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CurrentIdentityClient identities,
            ResourceMutationCoordinator coordinator,
            IdentityEligibilitySynchronizer eligibility,
            TenantSchemaProvisioner tenantProvisioner) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.identities = identities;
        this.coordinator = coordinator;
        this.eligibility = eligibility;
        this.tenantProvisioner = tenantProvisioner;
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
        var existing = accounts.findByKeycloakSub(keycloakSub);
        if (existing.isPresent()) {
            UserAccount account = existing.get();
            account.syncEmail(current.email(), current.emailVerified());
            return new BootstrapResult(
                    account.getId(), personalWorkspaceOf(account.getId()), false);
        }

        if (accounts.existsByUsername(current.username())) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    "USERNAME_TAKEN",
                    "Tên đăng nhập đã thuộc về một danh tính khác.");
        }
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        accounts.save(
                new UserAccount(
                        userId,
                        keycloakSub,
                        current.username(),
                        current.email(),
                        current.emailVerified(),
                        UserAccount.Origin.SELF_REGISTERED,
                        "ACTIVE"));
        workspaces.save(
                new Workspace(workspaceId, Workspace.Kind.PERSONAL, userId, null, "ACTIVE"));
        tenantProvisioner.provisionAfterCommit(workspaceId);
        return new BootstrapResult(userId, workspaceId, true);
    }

    /** Hồ sơ hiện hành của actor kèm personal workspace; chưa bootstrap thì 404. */
    @Transactional(readOnly = true)
    public CurrentUser currentUser(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        UserAccount account =
                accounts.findById(actorUserId)
                        .orElseThrow(() ->
                                new HttpProblemException(
                                        HttpStatus.NOT_FOUND,
                                        "ACCOUNT_NOT_FOUND",
                                        "Chưa có tài khoản cho danh tính này."));
        return new CurrentUser(
                actorUserId,
                account.getUsername(),
                account.getEmail(),
                account.isEmailVerified(),
                account.getOrigin(),
                account.getIdentityState(),
                personalWorkspaceOf(actorUserId));
    }

    private UUID personalWorkspaceOf(UUID userId) {
        List<Workspace> found =
                workspaces.findByKindAndOwnerUserId(Workspace.Kind.PERSONAL, userId);
        if (found.isEmpty()) {
            // Bất biến DB đáng lẽ ngăn trạng thái này; thấy nó là dữ liệu hỏng, không đoán.
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    IDENTITY_UNAVAILABLE_CODE,
                    "Không xác minh được danh tính hiện hành, thử lại sau.");
        }
        return found.getFirst().getId();
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
                IDENTITY_UNAVAILABLE_CODE,
                "Không xác minh được danh tính hiện hành, thử lại sau.",
                cause);
    }
}
