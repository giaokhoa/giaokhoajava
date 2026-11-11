package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleAttemptOpening;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy cập các lượt nộp bổ sung đã mở của hoạt động mẫu. */
public interface SampleAttemptOpeningRepository extends JpaRepository<SampleAttemptOpening, UUID> {

    /** Số lượt đã mở thêm cho đúng học sinh của hoạt động — phần cộng thêm vào trần lượt nộp. */
    long countByWorkspaceIdAndActivityIdAndStudentUserId(
            UUID workspaceId, UUID activityId, UUID studentUserId);
}
