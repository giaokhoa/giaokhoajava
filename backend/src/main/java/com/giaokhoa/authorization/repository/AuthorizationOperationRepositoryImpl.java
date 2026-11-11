package com.giaokhoa.authorization.repository;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthorizationOperationRepositoryImpl implements AuthorizationOperationRepositoryCustom {

    private final JdbcTemplate jdbc;

    AuthorizationOperationRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertIfAbsent(AuthorizationOperation operation) {
        // Spring Data không có insert-ignore: bắt ngoại lệ unique trong cùng transaction sẽ
        // đánh dấu transaction rollback-only và phá luồng replay/conflict, nên claim giữ
        // INSERT ... ON CONFLICT DO NOTHING lấy row-count trong fragment của repository
        // (Spring Data JPA Reference, phần custom repository implementation).
        return jdbc.update(
                """
                INSERT INTO authorization_operation
                  (id, initiator_user_id, workspace_id, idempotency_key, payload_hash,
                   affected_partitions, affected_revisions, desired_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (initiator_user_id, idempotency_key) DO NOTHING
                """,
                ps -> {
                    ps.setObject(1, operation.getId());
                    ps.setObject(2, operation.getInitiatorUserId());
                    ps.setObject(3, operation.getWorkspaceId());
                    ps.setString(4, operation.getIdempotencyKey());
                    ps.setString(5, operation.getPayloadHash());
                    ps.setArray(
                            6,
                            ps.getConnection()
                                    .createArrayOf(
                                            "uuid", operation.getAffectedPartitions().toArray()));
                    ps.setArray(
                            7,
                            ps.getConnection()
                                    .createArrayOf(
                                            "int8", operation.getAffectedRevisions().toArray()));
                    ps.setLong(8, operation.getDesiredRevision());
                });
    }
}
