package com.giaokhoa.authorization.repository;

import com.giaokhoa.authorization.model.AuthorizationOperation;

/** Phần fragment của {@link AuthorizationOperationRepository} cho điểm ghi giữ SQL native. */
public interface AuthorizationOperationRepositoryCustom {

    /**
     * Chèn operation nếu chưa tồn tại theo (initiator, idempotency key): trả 1 khi đã chèn, 0 khi
     * đã có — row-count là tín hiệu phân biệt insert mới với replay của giao thức idempotency.
     */
    int insertIfAbsent(AuthorizationOperation operation);
}
