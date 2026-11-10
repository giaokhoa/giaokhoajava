package com.giaokhoa.resources.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Truy cập biên nhận idempotency của các action resource trên bảng {@code sample_action_mutation}
 * (bảng do module verification sở hữu, chưa có entity ở module này). Phép claim dùng đúng mẫu
 * fragment của đợt JPA: INSERT native {@code ON CONFLICT (actor_user_id, idempotency_key) DO
 * NOTHING} trên connection của transaction JPA hiện hành và lấy row-count để phân biệt claim mới
 * với biên nhận đã tồn tại.
 *
 * <p>Điểm hội tụ khi các wave land: module verification đã có fragment claim dùng chung trên
 * repository của nó; khi đó ba phép ở đây được thay bằng fragment đó và class này bị xoá, để toàn
 * ứng dụng chỉ còn một cài đặt của giao thức claim.
 */
@Repository
public class ResourceActionReceipts {

    // Container gán EntityManager qua @PersistenceContext trước mọi lời gọi nghiệp vụ;
    // NullAway không thấy đường inject này nên suppression chỉ đặt tại field này.
    @SuppressWarnings("NullAway")
    @PersistenceContext
    private EntityManager entityManager;

    /** Nội dung biên nhận đã ghi: hash của payload đầu tiên và định danh đích của nó. */
    public record Receipt(String payloadHash, UUID targetId) {}

    /**
     * Chèn biên nhận cho action: trả 1 khi biên nhận chưa tồn tại (claim mới, mutation chạy tiếp),
     * 0 khi đã có biên nhận cho cùng (actor, key) và caller phải đọc lại nó để phân biệt replay
     * cùng payload với xung đột khác payload.
     */
    public int claim(
            UUID actorUserId,
            String idempotencyKey,
            String payloadHash,
            UUID workspaceId,
            String targetType,
            UUID targetId) {
        return entityManager
                .createNativeQuery(
                        "INSERT INTO sample_action_mutation(actor_user_id, idempotency_key,"
                                + " payload_hash, workspace_id, target_type, target_id)"
                                + " VALUES (?1, ?2, ?3, ?4, ?5, ?6)"
                                + " ON CONFLICT (actor_user_id, idempotency_key) DO NOTHING")
                .setParameter(1, actorUserId)
                .setParameter(2, idempotencyKey)
                .setParameter(3, payloadHash)
                .setParameter(4, workspaceId)
                .setParameter(5, targetType)
                .setParameter(6, targetId)
                .executeUpdate();
    }

    /** Biên nhận đã ghi cho một (actor, key); rỗng khi chưa từng có claim nào cho cặp đó. */
    public Optional<Receipt> findReceipt(UUID actorUserId, String idempotencyKey) {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT payload_hash, target_id FROM sample_action_mutation"
                                        + " WHERE actor_user_id = ?1 AND idempotency_key = ?2")
                        .setParameter(1, actorUserId)
                        .setParameter(2, idempotencyKey)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(
                new Receipt(
                        Objects.requireNonNull((String) row[0], "payload_hash"),
                        Objects.requireNonNull((UUID) row[1], "target_id")));
    }

    /** Sửa định danh đích của biên nhận vừa claim về đúng dòng thật đã chèn (khe đua tra cứu). */
    public void retarget(UUID actorUserId, String idempotencyKey, UUID targetId) {
        entityManager
                .createNativeQuery(
                        "UPDATE sample_action_mutation SET target_id = ?1"
                                + " WHERE actor_user_id = ?2 AND idempotency_key = ?3")
                .setParameter(1, targetId)
                .setParameter(2, actorUserId)
                .setParameter(3, idempotencyKey)
                .executeUpdate();
    }
}
