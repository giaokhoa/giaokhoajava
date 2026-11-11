package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.CorrectionNotice;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository của {@code correction_notice} do module resources sở hữu. */
public interface CorrectionNoticeRepository extends JpaRepository<CorrectionNotice, UUID> {

    /** Version đã dính thông báo đính chính là lịch sử phải bảo lưu. */
    boolean existsByAffectedVersionId(UUID affectedVersionId);

    /** Các notice lấy một version làm bản thay thế — cạnh đính chính vào version đó. */
    List<CorrectionNotice> findByResourceIdAndReplacementVersionId(
            UUID resourceId, UUID replacementVersionId);
}
