package com.giaokhoa.verification.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Thân của một sample action: mọi trường đều tùy chọn ở tầng hình dạng — từng action tự ép các
 * trường bắt buộc của nó ở service, và action lạ bị từ chối thay vì lặng lẽ cho qua.
 */
public record SampleActionRequest(
        String action,
        UUID courseSubjectId,
        UUID subjectId,
        String kind,
        String label,
        UUID resourceId,
        UUID versionId,
        OffsetDateTime dueAt,
        Integer attemptLimit,
        UUID activityId,
        String contentStub,
        UUID resultId,
        String scoreStub,
        String commentStub,
        UUID libraryItemId,
        UUID replacementVersionId,
        UUID grantId,
        UUID recipientWorkspaceId,
        String mode,
        List<String> actions,
        List<UUID> allowedWorkspaceIds,
        OffsetDateTime expiresAt,
        UUID affectedVersionId,
        String reason,
        UUID studentUserId) {
    // Sao chép phòng thủ theo mẫu CapabilitiesController: hai danh sách là dữ kiện đầu vào
    // từ JSON, không cho caller giữ tham chiếu mutable vào trạng thái nội bộ của request.
    // Chuẩn hoá null về danh sách rỗng: controller từ chối null và rỗng giống nhau
    // ở shareVersion nên không đổi hành vi quan sát được.
    public SampleActionRequest {
        actions = actions == null ? List.of() : List.copyOf(actions);
        allowedWorkspaceIds =
                allowedWorkspaceIds == null ? List.of() : List.copyOf(allowedWorkspaceIds);
    }
}
