package com.giaokhoa.teaching.repository;

import java.time.Instant;
import java.util.UUID;

/** Closed projection của một dòng danh sách lớp: chỉ các cột tầng đọc trang cần. */
public interface ClassListItem {
    UUID getId();

    Instant getCreatedAt();

    UUID getWorkspaceId();

    String getName();

    String getState();

    long getVersion();
}
