package com.giaokhoa.teaching.repository;

import java.util.UUID;

/** Closed projection của một dòng danh sách phân công giảng dạy. */
public interface TeachingAssignmentListItem {
    UUID getId();

    UUID getWorkspaceId();

    UUID getCourseSubjectId();

    UUID getTeacherUserId();

    String getState();

    long getVersion();
}
