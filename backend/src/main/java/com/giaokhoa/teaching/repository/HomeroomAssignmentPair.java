package com.giaokhoa.teaching.repository;

import java.util.UUID;

/** Cặp (giáo viên, lớp) tối thiểu cho projector dựng group chủ nhiệm mong muốn. */
public interface HomeroomAssignmentPair {
    UUID getTeacherUserId();

    UUID getClassId();
}
