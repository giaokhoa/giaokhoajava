package com.giaokhoa.verification.service;

import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.repository.VerificationResourceDependencyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * FR-057: GUARANTEED chỉ được giao khi TỪNG version trong closure dùng nguồn sở hữu độc lập hoặc
 * grant LONG_TERM đã ACCEPTED, không có expiresAt, còn quyền use. Quyền ghi/giao hiện hành vẫn do
 * PDP quyết định độc lập; guard này KHÔNG cấp permission.
 */
@Component
public class CompletionLicenseGuard {
    private final ResourceRepository resources;
    private final ShareGrantRepository grants;
    private final VerificationResourceDependencyRepository dependencies;

    public CompletionLicenseGuard(
            ResourceRepository resources,
            ShareGrantRepository grants,
            VerificationResourceDependencyRepository dependencies) {
        this.resources = resources;
        this.grants = grants;
        this.dependencies = dependencies;
    }

    public void requireDurableSources(
            UUID workspaceId, UUID resourceId, UUID versionId, Instant now) {
        requireSources(workspaceId, resourceId, versionId, now, true);
    }

    /**
     * FR-038/044/045: khi nộp/chấm/công bố, license vẫn phải hợp lệ cho root và từng component. Gọi
     * bên TRONG authorization fence transaction, không yêu cầu gói còn hạn.
     */
    public void requireCurrentSources(
            UUID workspaceId, UUID resourceId, UUID versionId, Instant now) {
        requireSources(workspaceId, resourceId, versionId, now, false);
    }

    private void requireSources(
            UUID workspaceId, UUID resourceId, UUID versionId, Instant now, boolean guaranteed) {
        Set<UUID> visited = new HashSet<>();
        List<ResourceVersionRef> frontier = List.of(new ResourceVersionRef(resourceId, versionId));
        while (!frontier.isEmpty()) {
            List<UUID> versionIds = frontier.stream().map(ResourceVersionRef::versionId).toList();
            List<ResourceDependency> edges = dependencies.findByDerivedVersionIdIn(versionIds);
            List<ResourceVersionRef> next = new ArrayList<>();
            for (ResourceVersionRef version : frontier) {
                if (!visited.add(version.versionId())) continue;
                Resource resource =
                        resources
                                .findById(version.resourceId())
                                .orElseThrow(() -> missingSource(guaranteed));
                List<ResourceDependency> provenance =
                        edges.stream()
                                .filter(edge ->
                                        version.versionId().equals(edge.getDerivedVersionId())
                                                && edge.getShareGrantId() != null)
                                .toList();
                boolean provenanceDurable =
                        provenance.stream()
                                .allMatch(edge ->
                                        grants.findById(
                                                        Objects.requireNonNull(
                                                                edge.getShareGrantId(),
                                                                "shareGrantId"))
                                                .filter(grant ->
                                                        permitted(
                                                                grant,
                                                                workspaceId,
                                                                now,
                                                                guaranteed))
                                                .isPresent());
                boolean owned = workspaceId.equals(resource.getOwnerWorkspaceId());
                boolean directLongTerm =
                        grants
                                .findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionIdAndStateOrderByCreatedAtAscIdAsc(
                                        workspaceId,
                                        version.resourceId(),
                                        version.versionId(),
                                        ShareGrant.State.ACCEPTED)
                                .stream()
                                .anyMatch(grant -> permitted(grant, workspaceId, now, guaranteed));
                if (!provenanceDurable || !(owned || directLongTerm)) {
                    throw missingSource(guaranteed);
                }
            }
            for (ResourceDependency edge : edges) {
                if (!visited.contains(edge.getSourceVersionId())) {
                    next.add(
                            new ResourceVersionRef(
                                    edge.getSourceResourceId(), edge.getSourceVersionId()));
                }
            }
            frontier = next;
        }
    }

    static boolean durable(ShareGrant grant, UUID workspaceId, Instant now) {
        return permitted(grant, workspaceId, now, true);
    }

    private static boolean permitted(
            ShareGrant grant, UUID workspaceId, Instant now, boolean guaranteed) {
        return grant.getState() == ShareGrant.State.ACCEPTED
                && (!guaranteed
                        || (grant.getMode() == ShareGrant.Mode.LONG_TERM
                                && grant.getExpiresAt() == null))
                && (guaranteed
                        || grant.getExpiresAt() == null
                        || now.isBefore(grant.getExpiresAt()))
                && !grant.getValidFrom().isAfter(now)
                && workspaceId.equals(grant.getRecipientWorkspaceId())
                && (grant.getAllowedWorkspaceIds().isEmpty()
                        || grant.getAllowedWorkspaceIds().contains(workspaceId))
                && ResourcePolicyProjector.mapActions(grant.getActions())
                        .contains(PermissionCatalog.ResourceVersionScope.USE.scopeName());
    }

    private static HttpProblemException missingSource(boolean guaranteed) {
        if (guaranteed) return notDurable();
        return new HttpProblemException(
                HttpStatus.FORBIDDEN,
                "RESOURCE_LICENSE_INACTIVE",
                "Một nguồn tài nguyên của bài đã bị thu hồi hoặc hết thời hạn sử dụng.");
    }

    private static HttpProblemException notDurable() {
        return new HttpProblemException(
                HttpStatus.CONFLICT,
                "COMPLETION_POLICY_SOURCE_NOT_DURABLE",
                "Nguồn tài nguyên không bảo đảm quyền dùng tới khi hoàn tất chấm và công bố.");
    }

    private record ResourceVersionRef(UUID resourceId, UUID versionId) {}
}
