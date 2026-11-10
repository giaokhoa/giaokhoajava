package com.giaokhoa.resources.repository;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Một mục thư viện đã lưu, kèm căn cứ custody để nhận bản cập nhật giữ nguyên căn cứ đó. */
public record LibraryItemRef(
        UUID id,
        UUID workspaceId,
        UUID resourceId,
        UUID versionId,
        @Nullable UUID shareGrantId,
        String custody) {}
