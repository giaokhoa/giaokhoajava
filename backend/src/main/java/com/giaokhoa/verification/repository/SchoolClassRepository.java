package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.SchoolClass;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc lớp học qua entity chuyển tiếp; repository đầy đủ thuộc module teaching. */
public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {}
