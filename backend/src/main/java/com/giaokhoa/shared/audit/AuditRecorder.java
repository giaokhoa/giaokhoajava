package com.giaokhoa.shared.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Điểm ghi audit dùng chung theo hợp đồng data-model: một dòng {@code audit_event} cho mỗi biến
 * động đặc quyền/gói/quan hệ/khôi phục và lỗi đồng bộ, chỉ gồm actor/action/target/operation/
 * outcome — không bao giờ ghi token, password hay body nghiệp vụ. Ghi bằng cùng {@link
 * JdbcTemplate} của caller nên dòng audit nằm trong đúng transaction của nghiệp vụ: nghiệp vụ
 * rollback thì audit cũng rollback, không có dòng audit mồ côi cho thay đổi chưa từng commit.
 */
@Component
public class AuditRecorder {
    private final JdbcTemplate jdbc;

    public AuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(
            @Nullable UUID actorUserId,
            @Nullable UUID workspaceId,
            AuditEvent.Action action,
            AuditEvent.TargetType targetType,
            UUID targetId,
            @Nullable UUID operationId,
            AuditEvent.Outcome outcome) {
        jdbc.update(
                "INSERT INTO audit_event"
                        + " (id, actor_user_id, workspace_id, action, target_type, target_id,"
                        + " operation_id, outcome)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                actorUserId,
                workspaceId,
                action.name(),
                targetType.name(),
                targetId,
                operationId,
                outcome.name());
    }
}
