package com.giaokhoa.shared.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.giaokhoa.shared.audit.AuditEvent.Action;
import com.giaokhoa.shared.audit.AuditEvent.Outcome;
import com.giaokhoa.shared.audit.AuditEvent.TargetType;
import com.giaokhoa.support.IntegrationEnvironment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

// Slice JPA của Boot 4 trên PostgreSQL thật của stack TEST: replace = NONE giữ nguyên
// datasource đã khai (không thay bằng embedded); Flyway không thuộc slice nên phải import
// tường minh để migration chạy trước khi Hibernate validate schema. Slice không nạp
// worker/scheduler và chạy trên schema dùng chung: các slice cùng cấu hình dùng chung một
// context đã cache của Spring, mỗi test chạy trong giao dịch riêng và rollback khi kết thúc
// nên dữ kiện không lẫn sang class khác.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class AuditEventSchemaIT extends IntegrationEnvironment {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;

    @DisplayName("Audit event chỉ ghi metadata tối thiểu và ánh xạ occurred_at")
    @Test
    void recordsMinimalMetadataOnly() {
        UUID target = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        entities.persist(
                new AuditEvent(
                        id,
                        null,
                        null,
                        Action.AUTHORIZATION_SYNC,
                        TargetType.AUTHORIZATION_OPERATION,
                        target,
                        null,
                        Outcome.FAILED));
        entities.flush();
        entities.clear();

        AuditEvent loaded = entities.find(AuditEvent.class, id);
        assertEquals(target, loaded.getTargetId());
        assertEquals(Action.AUTHORIZATION_SYNC, loaded.getAction());
        assertEquals(Outcome.FAILED, loaded.getOutcome());
        assertTrue(loaded.getOccurredAt().isBefore(Instant.now().plusSeconds(2)));
        assertTrue(Objects.isNull(loaded.getActorUserId()));
        assertTrue(Objects.isNull(loaded.getWorkspaceId()));

        Set<String> fields =
                jdbc
                        .queryForList(
                                """
                                SELECT column_name FROM information_schema.columns
                                WHERE table_schema = current_schema() AND table_name = 'audit_event'
                                """,
                                String.class)
                        .stream()
                        .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        "id",
                        "actor_user_id",
                        "workspace_id",
                        "action",
                        "target_type",
                        "target_id",
                        "operation_id",
                        "outcome",
                        "occurred_at"),
                fields);
    }

    @DisplayName("Lịch sử audit không thể bị ghi lại hay xóa cứng (hard delete)")
    @Test
    void auditHistoryIsImmutable() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO audit_event(id,action,target_type,target_id,outcome)
                VALUES (?,'RELATIONSHIP_CHANGE','RELATIONSHIP',?,'PENDING')
                """,
                id,
                UUID.randomUUID());
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update("UPDATE audit_event SET outcome='SUCCEEDED' WHERE id=?", id));
    }

    @Test
    void auditHistoryCannotBeDeleted() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO audit_event(id,action,target_type,target_id,outcome)
                VALUES (?,'RECOVERY','ACCOUNT',?,'FAILED')
                """,
                id,
                UUID.randomUUID());
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update("DELETE FROM audit_event WHERE id=?", id));
    }

    @Test
    void auditHistoryCannotBeTruncated() {
        // Chạy qua credential migration (chủ sở hữu, có quyền TRUNCATE) để chốt chặn duy nhất
        // còn lại là trigger append-only; role runtime của datasource ứng dụng đã bị chặn từ
        // tầng quyền trước khi tới trigger.
        assertThrows(
                DataAccessException.class, () -> migratorJdbc().execute("TRUNCATE audit_event"));
    }

    @DisplayName("Action tùy ý không thể bị lợi dụng để lưu nội dung nhạy cảm")
    @Test
    void arbitraryActionStoresNoSecrets() {
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                """
                                INSERT INTO audit_event(id,action,target_type,target_id,outcome)
                                VALUES (?,'password=secret','ACCOUNT',?,'PENDING')
                                """,
                                UUID.randomUUID(),
                                UUID.randomUUID()));
    }
}
