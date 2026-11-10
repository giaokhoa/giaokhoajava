package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.Course;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc khóa học qua entity chuyển tiếp; repository đầy đủ thuộc module teaching. */
public interface VerificationCourseRepository extends JpaRepository<Course, UUID> {}
