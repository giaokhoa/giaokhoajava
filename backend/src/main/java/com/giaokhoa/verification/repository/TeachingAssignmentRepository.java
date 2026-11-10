package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.TeachingAssignment;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc phân công giảng dạy qua entity chuyển tiếp; repository đầy đủ thuộc teaching. */
public interface TeachingAssignmentRepository extends JpaRepository<TeachingAssignment, UUID> {

    boolean existsByWorkspaceIdAndCourseSubjectIdAndStateIn(
            UUID workspaceId, UUID courseSubjectId, Collection<String> states);
}
