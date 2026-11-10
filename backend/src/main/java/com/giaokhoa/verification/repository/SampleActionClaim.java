package com.giaokhoa.verification.repository;

import java.util.UUID;

/**
 * Fragment claim biên nhận của sample action: chèn được (row-count 1) thì biên nhận chưa tồn tại và
 * mutation chạy tiếp; row-count 0 thì đã có biên nhận và phía gọi đọc lại nó để replay hoặc 409.
 * JPA không có insert-or-ignore nên điểm này là SQL native trong repository — một trong năm điểm
 * native của đợt JPA (mục K13 của Sổ), giữ đúng giao thức ON CONFLICT của V022.
 */
public interface SampleActionClaim {

    int claim(
            UUID actorUserId,
            String idempotencyKey,
            String payloadHash,
            UUID workspaceId,
            String targetType,
            UUID targetId);
}
