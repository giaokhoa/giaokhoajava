package com.giaokhoa.verification.dto;

import com.giaokhoa.resources.service.CorrectionService;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Kết quả đồng bộ của một sample action: định danh target vừa ghi và trạng thái hoàn tất. Riêng
 * CORRECT_SOURCE mang thêm metadata của notice đính chính vừa phát hành; các action khác để null.
 */
public record SampleActionResult(
        UUID id,
        String state,
        boolean completed,
        String targetType,
        UUID targetId,
        CorrectionService.@Nullable CorrectionNoticeView correctionNotice) {}
