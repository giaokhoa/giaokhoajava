package com.giaokhoa.teaching.repository;

import java.util.UUID;

/**
 * Closed projection của một dòng roster trong trang danh sách. Tên học sinh không nằm ở đây vì tài
 * khoản thuộc mặt phẳng hệ thống: tầng ứng dụng phân giải theo lô từ {@code studentUserId}.
 */
public interface RosterListItem {
    UUID getId();

    UUID getWorkspaceId();

    UUID getClassId();

    UUID getStudentUserId();

    String getState();

    long getVersion();
}
