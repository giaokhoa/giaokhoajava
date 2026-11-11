package com.giaokhoa.teaching.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Closed projection của một dòng danh sách ghi danh, kèm học sinh của dòng roster của nó qua
 * association cùng mặt phẳng — thay join SQL cũ mà không lộ entity ra tầng trên.
 */
public interface EnrollmentListItem {
    UUID getId();

    Instant getCreatedAt();

    UUID getClassStudentId();

    UUID getCourseSubjectId();

    String getState();

    long getVersion();

    ClassStudentRef getClassStudent();

    /** Chiếu tối thiểu của dòng roster phía ghi danh: chỉ định danh học sinh. */
    interface ClassStudentRef {
        UUID getStudentUserId();
    }
}
