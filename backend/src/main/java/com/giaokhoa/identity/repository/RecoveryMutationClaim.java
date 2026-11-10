package com.giaokhoa.identity.repository;

import com.giaokhoa.identity.model.RecoveryMutation;

/**
 * Mẫu claim insert-or-ignore của bảng biên nhận khôi phục: ngữ nghĩa "chèn nếu vắng, row-count
 * phân biệt tạo mới/replay" không có tương đương JPA — {@code save()} không có insert-ignore, và
 * bắt vi phạm unique trong cùng transaction khiến transaction bị đánh dấu rollback-only, phá
 * luồng replay/conflict mà điểm này phục vụ. Câu INSERT giữ native trong fragment này, chạy trên
 * cùng connection của transaction JPA hiện hành.
 */
public interface RecoveryMutationClaim {
    /** Chèn biên nhận nếu chưa có; trả 1 khi chèn được, 0 khi (actor, khóa) đã tồn tại. */
    int insertIfAbsent(RecoveryMutation mutation);
}
