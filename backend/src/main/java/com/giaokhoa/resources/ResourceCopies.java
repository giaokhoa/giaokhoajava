package com.giaokhoa.resources;

import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ResourceVersion;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.VersionMetadata;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Vật chất hoá bản sao của một version vào mặt phẳng của workspace đích: resource mới thuộc
 * workspace đích giữ đúng kind và môn của nguồn, một version sao mang nội dung của version nguồn,
 * một cạnh provenance về đúng version nguồn kèm grant đã cho phép sao (nếu căn cứ là license) và
 * toàn bộ cạnh dependency mà chính version nguồn đang mang, để bản sao giữ nguyên closure phụ thuộc
 * của nguồn. Đây là phép sao dùng chung của hai luồng: fork (dấu {@code origin=FORK} trong {@code
 * usage_constraints} của cạnh provenance) và copy-on-share khi một tenant chia sẻ cho tenant khác
 * (dấu {@code origin=SHARE}) — ở copy-on-share, bản sao là tài sản mà phía nhận thật sự giữ trong
 * mặt phẳng của mình, còn cạnh provenance là căn cứ duy nhất để tra lại bản sao của một (version
 * nguồn, grant, workspace nhận) và để thu hồi qua projector khi grant đổi trạng thái.
 */
@Component
public class ResourceCopies {

    private final ResourceRepository resources;
    private final ResourceVersionRepository versions;
    private final ResourceDependencyRepository dependencies;
    private final WorkspaceTransaction workspaces;

    public ResourceCopies(
            ResourceRepository resources,
            ResourceVersionRepository versions,
            ResourceDependencyRepository dependencies,
            WorkspaceTransaction workspaces) {
        this.resources = resources;
        this.versions = versions;
        this.dependencies = dependencies;
        this.workspaces = workspaces;
    }

    /** Dữ kiện nguồn tách khỏi persistence context của tenant nguồn. */
    public record VersionView(
            String label,
            String body,
            @Nullable String answer,
            ResourceVersion.ReviewState reviewState,
            @Nullable Instant lockedAt) {}

    public Optional<VersionView> readVersion(VersionMetadata source) {
        return Objects.requireNonNull(
                workspaces.readInWorkspace(
                        source.ownerWorkspaceId(),
                        () ->
                                versions.findByResourceIdAndId(
                                                source.resourceId(), source.versionId())
                                        .map(version ->
                                                new VersionView(
                                                        version.getLabel(),
                                                        version.getBodyStub(),
                                                        version.getAnswerStub(),
                                                        version.getReviewState(),
                                                        version.getLockedAt()))));
    }

    private record DependencyRow(
            UUID resourceId, UUID versionId, @Nullable UUID grantId, String constraints) {}

    /** Định danh một bản sao đã vật chất hoá: resource sao và version sao của nó. */
    public record CopyRef(UUID resourceId, UUID versionId) {}

    /** Một bản sao kèm workspace đang sở hữu nó trong mặt phẳng của workspace đó. */
    public record CopyLocation(CopyRef copy, UUID ownerWorkspaceId) {}

    /**
     * Tạo resource sao trọn vẹn cho một version nguồn tại workspace đích, với định danh resource và
     * version do caller chốt trước (giao thức biên nhận của action sinh định danh đích trước khi
     * ghi). Trả định danh bản sao.
     */
    public CopyRef copyVersion(
            UUID copyResourceId,
            UUID copyVersionId,
            UUID createdByUserId,
            UUID destinationWorkspaceId,
            VersionMetadata source,
            String label,
            String body,
            @Nullable String answer,
            ResourceVersion.ReviewState reviewState,
            @Nullable Instant lockedAt,
            int versionNo,
            @Nullable UUID grantId,
            String usageConstraints) {
        resources.save(
                new Resource(
                        copyResourceId,
                        destinationWorkspaceId,
                        createdByUserId,
                        Resource.Kind.valueOf(source.kind()),
                        source.subjectId(),
                        "ACTIVE"));
        return appendVersion(
                copyResourceId,
                copyVersionId,
                source,
                label,
                body,
                answer,
                reviewState,
                lockedAt,
                versionNo,
                grantId,
                usageConstraints);
    }

    /**
     * Thêm một version sao mới trên một resource sao đã có (đường nhận cập nhật của copy-on-share):
     * version sao mang nội dung của version nguồn thay thế, số version là số lớn nhất hiện có của
     * resource sao cộng một, kèm cạnh provenance về version nguồn thay thế và closure dependency
     * của nó.
     */
    public CopyRef appendVersion(
            UUID copyResourceId,
            UUID copyVersionId,
            VersionMetadata source,
            String label,
            String body,
            @Nullable String answer,
            ResourceVersion.ReviewState reviewState,
            @Nullable Instant lockedAt,
            int versionNo,
            @Nullable UUID grantId,
            String usageConstraints) {
        List<DependencyRow> sourceEdges =
                Objects.requireNonNull(
                        workspaces.readInWorkspace(
                                source.ownerWorkspaceId(),
                                () ->
                                        dependencies
                                                .findByDerivedResourceIdAndDerivedVersionId(
                                                        source.resourceId(), source.versionId())
                                                .stream()
                                                .map(edge ->
                                                        new DependencyRow(
                                                                edge.getSourceResourceId(),
                                                                edge.getSourceVersionId(),
                                                                edge.getShareGrantId(),
                                                                edge.getUsageConstraints()))
                                                .toList()));
        versions.save(
                new ResourceVersion(
                        copyVersionId,
                        copyResourceId,
                        versionNo,
                        label,
                        body,
                        answer,
                        reviewState,
                        lockedAt));
        dependencies.save(
                new ResourceDependency(
                        UUID.randomUUID(),
                        copyResourceId,
                        copyVersionId,
                        source.resourceId(),
                        source.versionId(),
                        grantId,
                        usageConstraints));
        for (DependencyRow edge : sourceEdges) {
            dependencies.save(
                    new ResourceDependency(
                            UUID.randomUUID(),
                            copyResourceId,
                            copyVersionId,
                            edge.resourceId(),
                            edge.versionId(),
                            edge.grantId(),
                            edge.constraints()));
        }
        return new CopyRef(copyResourceId, copyVersionId);
    }

    /** Số version tiếp theo của một resource sao khi thêm bản cập nhật. */
    public int nextVersionNo(UUID copyResourceId) {
        return versions.maxVersionNo(copyResourceId) + 1;
    }

    /**
     * Mọi bản sao copy-on-share của một version nguồn theo một grant, kèm workspace sở hữu: tra từ
     * các cạnh provenance mang dấu {@code origin=SHARE} của chính grant đó.
     */
    public List<CopyLocation> copiesOf(UUID sourceResourceId, UUID sourceVersionId, UUID grantId) {
        return dependencies
                .findBySourceResourceIdAndSourceVersionIdAndShareGrantId(
                        sourceResourceId, sourceVersionId, grantId)
                .stream()
                .filter(edge ->
                        edge.getUsageConstraints()
                                .startsWith(ResourcePolicyProjector.shareOriginMarker()))
                .map(edge ->
                        new CopyLocation(
                                new CopyRef(
                                        edge.getDerivedResourceId(), edge.getDerivedVersionId()),
                                resources
                                        .findById(edge.getDerivedResourceId())
                                        .map(resource -> resource.getOwnerWorkspaceId())
                                        .orElseThrow(() ->
                                                new IllegalStateException(
                                                        "Copy resource is"
                                                                + " missing: "
                                                                + edge.getDerivedResourceId()))))
                .toList();
    }

    /**
     * Bản sao của một version nguồn theo một grant tại đúng workspace nhận, nếu đã vật chất hoá.
     */
    public Optional<CopyRef> findCopy(
            UUID sourceResourceId, UUID sourceVersionId, UUID grantId, UUID recipientWorkspaceId) {
        return copiesOf(sourceResourceId, sourceVersionId, grantId).stream()
                .filter(location -> location.ownerWorkspaceId().equals(recipientWorkspaceId))
                .map(location -> location.copy())
                .findFirst();
    }

    /**
     * Các grant trong danh sách đã có bản sao copy-on-share của một version nguồn tại đúng
     * workspace nhận: nạp cạnh provenance của version và các resource sao theo lô, thay cho việc
     * tra từng grant khi dựng bundle. Resource sao bị thiếu của một cạnh thuộc các grant này vẫn là
     * lỗi dữ kiện như đường tra lẻ {@link #copiesOf}.
     */
    public Set<UUID> grantsWithCopyAt(
            UUID sourceResourceId,
            UUID sourceVersionId,
            Collection<UUID> grantIds,
            UUID recipientWorkspaceId) {
        if (grantIds.isEmpty()) {
            return Set.of();
        }
        List<ResourceDependency> edges =
                dependencies
                        .findBySourceResourceIdAndSourceVersionId(sourceResourceId, sourceVersionId)
                        .stream()
                        .filter(edge ->
                                edge.getShareGrantId() != null
                                        && grantIds.contains(edge.getShareGrantId()))
                        .filter(edge ->
                                edge.getUsageConstraints()
                                        .startsWith(ResourcePolicyProjector.shareOriginMarker()))
                        .toList();
        if (edges.isEmpty()) {
            return Set.of();
        }
        Map<UUID, UUID> owners = new HashMap<>();
        resources
                .findAllById(
                        edges.stream()
                                .map(dependency -> dependency.getDerivedResourceId())
                                .distinct()
                                .toList())
                .forEach(resource -> owners.put(resource.getId(), resource.getOwnerWorkspaceId()));
        Set<UUID> copied = new HashSet<>();
        for (ResourceDependency edge : edges) {
            UUID owner = owners.get(edge.getDerivedResourceId());
            if (owner == null) {
                throw new IllegalStateException(
                        "Copy resource is" + " missing: " + edge.getDerivedResourceId());
            }
            if (owner.equals(recipientWorkspaceId)) {
                copied.add(Objects.requireNonNull(edge.getShareGrantId(), "share_grant_id"));
            }
        }
        return copied;
    }

    /** Cạnh provenance copy-on-share của một version sao: căn cứ nguồn của chính bản sao đó. */
    public Optional<ResourceDependency> shareProvenanceOf(UUID copyResourceId, UUID copyVersionId) {
        return dependencies
                .findShareProvenance(copyResourceId, copyVersionId, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    /**
     * Version sao trên một resource sao tương ứng với một version nguồn cho trước (theo cạnh
     * provenance của grant), để nhận cập nhật không vật chất hoá trùng một version đã sao.
     */
    public Optional<UUID> copyVersionIdForSourceVersion(
            UUID copyResourceId, UUID sourceResourceId, UUID sourceVersionId, UUID grantId) {
        return dependencies
                .findBySourceResourceIdAndSourceVersionIdAndShareGrantId(
                        sourceResourceId, sourceVersionId, grantId)
                .stream()
                .filter(edge -> edge.getDerivedResourceId().equals(copyResourceId))
                .filter(edge ->
                        edge.getUsageConstraints()
                                .startsWith(ResourcePolicyProjector.shareOriginMarker()))
                .map(dependency -> dependency.getDerivedVersionId())
                .findFirst();
    }
}
