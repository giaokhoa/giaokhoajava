package com.giaokhoa.resources;

import com.giaokhoa.resources.repository.CorrectionNoticeRepository;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesSampleActivityRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Guard bảo lưu lịch sử của module resources. Một version đã được lưu vào kho, đã giao làm bài, đã
 * làm nguồn cho bản phái sinh hoặc đã dính thông báo đính chính là canonical: không được xóa hay
 * ghi đè. Một resource đã có version như vậy hoặc đã từng được chia sẻ cũng không được xóa.
 *
 * <p>Module hiện không có đường xóa resource hay version. Version bất biến theo thiết kế, mục thư
 * viện không bị gỡ, thu hồi share chỉ đóng grant và giữ nguyên lịch sử. Vì vậy guard này là định
 * nghĩa "có lịch sử" dùng chung của module, và là điểm kiểm tra bắt buộc cho mọi đường xóa hoặc gỡ
 * phát sinh về sau.
 *
 * <p>Hai bất biến liên quan giữ bằng cấu trúc chứ không bằng kiểm tra tại đây. Hết hạn gói của
 * người chia sẻ không tự hủy share, vì không đường nào của module đọc kỳ gói của grantor để thu hồi
 * grant. Gia hạn không cấp lại quan hệ đã thu hồi, vì các projector chỉ dựng nguồn từ quan hệ còn
 * hiệu lực hiện hành.
 */
@Component
public class ResourceHistoryGuard {

    private final ResourcesSampleActivityRepository activities;
    private final LibraryItemRepository libraryItems;
    private final ResourceDependencyRepository dependencies;
    private final CorrectionNoticeRepository corrections;
    private final ShareGrantRepository grants;
    private final ResourceVersionRepository versions;

    public ResourceHistoryGuard(
            ResourcesSampleActivityRepository activities,
            LibraryItemRepository libraryItems,
            ResourceDependencyRepository dependencies,
            CorrectionNoticeRepository corrections,
            ShareGrantRepository grants,
            ResourceVersionRepository versions) {
        this.activities = activities;
        this.libraryItems = libraryItems;
        this.dependencies = dependencies;
        this.corrections = corrections;
        this.grants = grants;
        this.versions = versions;
    }

    public boolean versionHasHistory(UUID resourceId, UUID versionId) {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(versionId, "versionId");
        return activities.existsByResourceIdAndVersionId(resourceId, versionId)
                || libraryItems.existsByResourceIdAndVersionId(resourceId, versionId)
                || dependencies.existsBySourceVersionId(versionId)
                || corrections.existsByAffectedVersionId(versionId);
    }

    public boolean resourceHasHistory(UUID resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        if (grants.existsBySourceResourceId(resourceId)) {
            return true;
        }
        return versions.findIdsByResourceId(resourceId).stream()
                .anyMatch(versionId -> versionHasHistory(resourceId, versionId));
    }

    public void requireVersionDeletable(UUID resourceId, UUID versionId) {
        if (versionHasHistory(resourceId, versionId)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.RESOURCE_VERSION_HAS_HISTORY,
                    "Phiên bản đã được lưu, giao bài hoặc phái sinh nên không xóa được; nội"
                            + " dung canonical và lịch sử của phiên bản được giữ nguyên.");
        }
    }

    public void requireResourceDeletable(UUID resourceId) {
        if (resourceHasHistory(resourceId)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.RESOURCE_HAS_HISTORY,
                    "Tài nguyên đã phát sinh lịch sử sử dụng hoặc chia sẻ nên không xóa được;"
                            + " dữ liệu và lịch sử của tài nguyên được giữ nguyên.");
        }
    }
}
