package com.giaokhoa.verification.dto;

import java.util.UUID;

/** Kết quả đồng bộ của một sample action: định danh target vừa ghi và trạng thái hoàn tất. */
public record SampleActionResult(
        UUID id, String state, boolean completed, String targetType, UUID targetId) {}
