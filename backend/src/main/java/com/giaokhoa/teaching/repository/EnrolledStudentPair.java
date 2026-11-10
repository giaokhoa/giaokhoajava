package com.giaokhoa.teaching.repository;

import java.util.UUID;

/** Cặp (học sinh, môn–khóa) tối thiểu cho projector dựng group học sinh mong muốn. */
public interface EnrolledStudentPair {
    UUID getStudentUserId();

    UUID getCourseSubjectId();
}
