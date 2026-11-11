package com.giaokhoa.verification.repository;

import com.giaokhoa.teaching.model.Subject;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Điểm đọc môn học qua entity chuyển tiếp; repository đầy đủ thuộc module teaching. */
public interface VerificationSubjectRepository extends JpaRepository<Subject, UUID> {

    /** Môn đầu tiên theo mã — dữ kiện mẫu kiểm chứng luôn gắn vào đúng một môn cố định này. */
    Optional<Subject> findFirstByOrderByCodeAsc();
}
