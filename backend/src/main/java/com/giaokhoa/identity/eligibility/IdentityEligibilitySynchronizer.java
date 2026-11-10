package com.giaokhoa.identity.eligibility;

import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.authorization.projection.ProjectionReconciler;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.PersonalEntitlementProjector;
import com.giaokhoa.tenancy.model.Workspace;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Giữ eligibility gate của danh tính khớp với danh tính Keycloak hiện hành. Origin của account là
 * dữ liệu do server kiểm soát: chỉ record do chính backend tạo mới tính là CENTER_ISSUED, mọi
 * trường hợp còn lại là SELF_REGISTERED và chỉ eligible khi email của profile hiện hành — đọc mới
 * qua Admin API, không lấy từ request của browser hay token cũ — đã verified. Khi profile vừa đọc
 * làm thay đổi input eligibility đã ghi, input mới được lưu và thay đổi được ghi thành desired
 * state trên ACCOUNT partition của actor trong cùng transaction, để worker bền chiếu lại membership
 * của group eligibility cùng mọi graph phụ thuộc sau fence; operation còn pending thì account vẫn
 * bị fence và không quyết định bảo vệ nào được dùng trạng thái cũ. Profile không đọc được, hoặc
 * subject không khớp account, đều fail-closed 503 và không ghi gì.
 *
 * <p>Dữ kiện directory (account, workspace) đọc qua repository của module identity/tenancy; dữ kiện
 * của mặt phẳng authorization (projection, partition) vẫn đọc tại đây cho tới khi repository của
 * mặt phẳng đó land cùng đợt chuyển đổi của module authorization.
 */
@Service
public class IdentityEligibilitySynchronizer {
    static final String IDENTITY_UNAVAILABLE_CODE = "IDENTITY_UNAVAILABLE";

    /**
     * Checkpoint account ghi cùng nguồn actor/eligibility cho hai scope đọc US1, mỗi scope có root
     * native riêng.
     */
    private static final String ELIGIBILITY_SCOPE =
            PermissionCatalog.ProfileScope.WORKSPACE_LIST.scopeName();

    private final JdbcTemplate jdbc;
    private final UserAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final CurrentIdentityClient identities;
    private final AuthorizationOperationService operations;
    private final PersonalEntitlementProjector entitlementProjector;

    public IdentityEligibilitySynchronizer(
            JdbcTemplate jdbc,
            UserAccountRepository accounts,
            WorkspaceRepository workspaces,
            CurrentIdentityClient identities,
            AuthorizationOperationService operations,
            PersonalEntitlementProjector entitlementProjector) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.identities = identities;
        this.operations = operations;
        this.entitlementProjector = entitlementProjector;
    }

    /**
     * Đồng bộ eligibility của một actor từ profile Keycloak hiện hành. Trả về operation đã ghi khi
     * input eligibility thay đổi; rỗng khi trạng thái đã ghi khớp profile hiện hành.
     */
    @Transactional
    public Optional<AuthorizationOperation> synchronizeEligibility(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Optional<UserAccount> found = accounts.findById(actorUserId);
        if (found.isEmpty()) {
            // Không có directory account thì không có ACCOUNT partition để đồng bộ qua.
            throw AuthorizationFence.notReady();
        }
        UserAccount account = found.get();
        String keycloakSub = account.getKeycloakSub();
        String recordedEmail = account.getEmail();
        boolean recordedVerified = account.isEmailVerified();
        // Chỉ chính record do backend tạo mới chứng minh CENTER_ISSUED; giá trị lưu khác đều là
        // origin unknown và được xử lý như SELF_REGISTERED.
        boolean backendIssued = account.getOrigin() == UserAccount.Origin.CENTER_ISSUED;

        CurrentIdentity current = readCurrent(keycloakSub);

        boolean eligible = backendIssued || current.emailVerified();
        boolean recordedEligible = backendIssued || recordedVerified;
        String targetKey = NativePolicyTemplates.profileKey(actorUserId);
        List<String> desiredSources =
                eligible
                        ? List.of(
                                ProjectionReconciler.eligibilityDescriptor(
                                        actorUserId, keycloakSub, targetKey, ELIGIBILITY_SCOPE))
                        : List.of();
        String desiredHash =
                ProjectionReconciler.desiredHash(targetKey, ELIGIBILITY_SCOPE, desiredSources);
        List<String> recordedHashes =
                jdbc.queryForList(
                        "SELECT desired_hash FROM authz_projection WHERE target_key = ?",
                        String.class,
                        targetKey);
        boolean profileChanged =
                current.emailVerified() != recordedVerified
                        || !Objects.equals(current.email(), recordedEmail)
                        || eligible != recordedEligible;
        boolean accountChanged =
                profileChanged
                        || !((!recordedHashes.isEmpty()
                                        && desiredHash.equals(recordedHashes.getFirst()))
                                || (!eligible
                                        && recordedHashes.isEmpty()
                                        && retractionWasApplied(actorUserId)));
        List<AuthorizationOperationService.PartitionChange> changes = new ArrayList<>();
        UUID accountPartition = accountPartition(actorUserId);
        // Workspace cá nhân tra qua repository directory; partition của nó thuộc mặt phẳng
        // authorization nên vẫn tra tại đây theo owner_key suy từ chính workspace đó.
        @Nullable UUID personalWorkspaceId = null;
        @Nullable UUID personalPartitionId = null;
        long personalDesiredRevision = 0;
        List<Workspace> personalWorkspaces =
                workspaces.findByKindAndOwnerUserId(Workspace.Kind.PERSONAL, actorUserId);
        if (!personalWorkspaces.isEmpty()) {
            UUID workspaceId = personalWorkspaces.getFirst().getId();
            List<Map<String, Object>> partitions =
                    jdbc.queryForList(
                            "SELECT id AS partition_id, desired_revision FROM authz_partition"
                                    + " WHERE owner_key = ? AND kind = 'WORKSPACE'",
                            "workspace:" + workspaceId);
            if (!partitions.isEmpty()) {
                personalWorkspaceId = workspaceId;
                personalPartitionId =
                        (UUID)
                                Objects.requireNonNull(
                                        partitions.getFirst().get("partition_id"), "partition_id");
                personalDesiredRevision =
                        ((Number)
                                        Objects.requireNonNull(
                                                partitions.getFirst().get("desired_revision"),
                                                "desired_revision"))
                                .longValue();
            }
        }
        if (personalWorkspaceId != null) {
            String workspaceTarget = NativePolicyTemplates.workspaceKey(personalWorkspaceId);
            if (jdbc.queryForList(
                            "SELECT id FROM authz_projection WHERE target_key = ?",
                            UUID.class,
                            workspaceTarget)
                    .isEmpty()) {
                String scope = PermissionCatalog.WorkspaceScope.ENTER.scopeName();
                changes.add(
                        new AuthorizationOperationService.PartitionChange(
                                Objects.requireNonNull(personalPartitionId, "personalPartitionId"),
                                List.of(
                                        new AuthorizationOperationService.DesiredProjection(
                                                workspaceTarget,
                                                scope,
                                                entitlementProjector.bundleDescriptors(
                                                        personalWorkspaceId),
                                                List.of(accountPartition)))));
            }
        }
        if (!accountChanged && changes.isEmpty()) {
            return Optional.empty();
        }

        if (profileChanged) {
            account.syncEmail(current.email(), current.emailVerified());
        }
        Long recordedRevision =
                jdbc.queryForObject(
                        "SELECT desired_revision FROM authz_partition WHERE id = ?",
                        Long.class,
                        accountPartition);
        if (recordedRevision == null) {
            throw AuthorizationFence.notReady();
        }
        List<AuthorizationOperationService.DesiredProjection> projections =
                List.of(
                        new AuthorizationOperationService.DesiredProjection(
                                targetKey, ELIGIBILITY_SCOPE, desiredSources, List.of()));
        // Key mang revision mà thay đổi này đưa ACCOUNT partition tới: retry cùng một thay đổi
        // đã quan sát sẽ replay đúng operation đó, còn lần lật trạng thái sau ghi operation mới.
        String idempotencyKey =
                "identity-eligibility:" + actorUserId + ":" + (recordedRevision + 1);
        if (accountChanged) {
            changes.addFirst(
                    new AuthorizationOperationService.PartitionChange(
                            accountPartition, projections));
        } else {
            idempotencyKey =
                    "identity-personal:" + actorUserId + ":" + (personalDesiredRevision + 1);
        }
        AuthorizationOperation operation =
                operations.applyDesiredState(
                        actorUserId, null, idempotencyKey, desiredHash, changes);
        return Optional.of(operation);
    }

    private boolean retractionWasApplied(UUID actorUserId) {
        // Reconcile xóa projection đã retract; revision đã áp là bằng chứng không tạo lại nó.
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT state = 'READY' AND applied_revision = desired_revision"
                                + " AND applied_revision > 0 FROM authz_partition"
                                + " WHERE kind = 'ACCOUNT' AND owner_key = ?",
                        Boolean.class,
                        "account:" + actorUserId));
    }

    private CurrentIdentity readCurrent(String keycloakSub) {
        CurrentIdentity current;
        try {
            current = identities.readCurrentIdentity(keycloakSub);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException ex) {
            throw unavailable(ex);
        }
        if (!keycloakSub.equals(current.id())
                || !current.enabled()
                || (current.emailVerified() && current.email() == null)) {
            // Profile đọc được là của subject khác: không phải input của account này.
            throw unavailable(null);
        }
        return current;
    }

    private UUID accountPartition(UUID actorUserId) {
        List<UUID> found =
                jdbc.queryForList(
                        "SELECT id FROM authz_partition WHERE kind = 'ACCOUNT' AND owner_key = ?",
                        UUID.class,
                        "account:" + actorUserId);
        if (found.isEmpty()) {
            throw AuthorizationFence.notReady();
        }
        return found.getFirst();
    }

    private static HttpProblemException unavailable(@Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                IDENTITY_UNAVAILABLE_CODE,
                "Không xác minh được danh tính hiện hành, thử lại sau.",
                cause);
    }
}
