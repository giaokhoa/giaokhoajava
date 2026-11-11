package com.giaokhoa.resources.repository;

import java.util.UUID;

/**
 * Metadata tối thiểu của một version để dựng authorization key và kiểm invariant: chỉ các cột định
 * danh/phân loại, không bao giờ kèm cột nội dung — nội dung chỉ được đọc qua các projection riêng
 * của {@link CanonicalResourceRepository} sau quyết định PDP tương ứng.
 */
public record VersionMetadata(
        UUID resourceId,
        UUID versionId,
        UUID ownerWorkspaceId,
        String ownerKind,
        UUID createdByUserId,
        UUID subjectId,
        String kind,
        String reviewState) {}
