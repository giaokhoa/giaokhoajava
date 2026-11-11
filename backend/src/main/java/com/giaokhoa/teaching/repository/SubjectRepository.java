package com.giaokhoa.teaching.repository;

import com.giaokhoa.teaching.model.Subject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository của danh mục môn dùng chung toàn hệ thống (mặt phẳng hệ thống, chỉ đọc ở module giảng
 * dạy). Tên môn và thứ tự theo mã được phân giải ở tầng ứng dụng từ các ID của mặt phẳng tenant.
 */
public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    /** Toàn bộ môn ở một trạng thái của danh mục, theo mã. */
    List<Subject> findByStateOrderByCodeAsc(String state);
}
