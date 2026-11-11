package com.giaokhoa.teaching.repository;

import java.util.UUID;

/** Cặp (giáo viên, môn–khóa) tối thiểu cho projector dựng group giảng dạy mong muốn. */
public interface TeachingAssignmentPair {
    UUID getTeacherUserId();

    UUID getCourseSubjectId();
}
