package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.CorrectionNotice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository của {@code correction_notice} do module resources sở hữu. */
public interface CorrectionNoticeRepository extends JpaRepository<CorrectionNotice, UUID> {

    /** Version đã dính thông báo đính chính là lịch sử phải bảo lưu. */
    boolean existsByAffectedVersionId(UUID affectedVersionId);
}
