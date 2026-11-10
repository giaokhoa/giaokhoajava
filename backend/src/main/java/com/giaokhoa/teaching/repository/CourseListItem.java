package com.giaokhoa.teaching.repository;

import java.util.UUID;

/** Closed projection của một dòng danh sách khóa: chỉ các cột tầng đọc trang cần. */
public interface CourseListItem {
    UUID getId();

    UUID getWorkspaceId();

    UUID getClassId();

    String getName();

    String getState();

    long getVersion();
}
