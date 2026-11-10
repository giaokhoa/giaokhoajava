package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.Subject;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc môn học qua entity chuyển tiếp; repository đầy đủ thuộc module teaching. */
public interface SubjectRepository extends JpaRepository<Subject, UUID> {}
