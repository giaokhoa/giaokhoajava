package com.giaokhoa.teaching.repository;

import java.time.Instant;
import java.util.UUID;

/** Closed projection của một dòng danh sách phân công chủ nhiệm. */
public interface HomeroomAssignmentListItem {
    UUID getId();

    Instant getCreatedAt();

    UUID getWorkspaceId();

    UUID getClassId();

    UUID getTeacherUserId();

    String getState();

    long getVersion();
}
