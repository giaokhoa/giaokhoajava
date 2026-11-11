package com.giaokhoa.verification.repository;

import java.util.UUID;

/**
 * Fragment claim biên nhận của sample action. Chèn được (row-count 1) thì biên nhận chưa tồn tại và
 * mutation chạy tiếp. Row-count 0 thì đã có biên nhận, và phía gọi đọc lại nó để replay hoặc 409.
 *
 * <p>JPA không có insert-or-ignore nên điểm này là SQL native trong repository, giữ đúng giao thức
 * ON CONFLICT của V022.
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
