package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.TeachingAssignment;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc phân công giảng dạy trên entity của module teaching. */
public interface VerificationTeachingAssignmentRepository
        extends JpaRepository<TeachingAssignment, UUID> {

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);
}
