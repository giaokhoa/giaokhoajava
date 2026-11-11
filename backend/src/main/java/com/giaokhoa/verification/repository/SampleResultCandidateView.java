package com.giaokhoa.verification.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Chiếu đóng của một kết quả đã công bố cho đường quét keyset phía đọc: chỉ id và thời điểm công bố
 * — đúng hai cột mà giao thức phân trang của PublishedResultService được phép resolve trước khi hỏi
 * PDP (xem Spring Data JPA Reference, "Projections": closed projection chỉ chọn đúng cột cần).
 */
public interface SampleResultCandidateView {

    UUID getId();

    Instant getPublishedAt();
}
