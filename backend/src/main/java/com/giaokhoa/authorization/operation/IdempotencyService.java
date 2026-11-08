package com.giaokhoa.authorization.operation;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.persistence.EntityManager;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {
    private static final JsonMapper CANONICAL_JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public record OperationReceipt(
            UUID operationId, String state, boolean completed, String statusUrl) {}

    public IdempotencyService(JdbcTemplate jdbc, EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    public String requireKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED",
                    "Thiếu Idempotency-Key.");
        }
        if (key.length() > 255 || !key.matches("[\\x21-\\x7E]+")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key không hợp lệ.");
        }
        return key;
    }

    public static void requireIfMatch(@Nullable String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "Thiếu If-Match.");
        }
        // Strong ETag của row version; dấu * và weak validator không hợp lệ cho mutation này.
        if (!ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IF_MATCH",
                    "If-Match phải là strong ETag của row version.");
        }
        try {
            if (Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)) != currentVersion) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT, "VERSION_CONFLICT", "Phiên bản dữ liệu đã thay đổi.");
            }
        } catch (NumberFormatException ex) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", "If-Match không hợp lệ.", ex);
        }
    }

    public String payloadHash(String method, String targetPath, JsonNode body) {
        if (method == null
                || method.isBlank()
                || targetPath == null
                || !targetPath.startsWith("/")) {
            throw new IllegalArgumentException("A method and target path are required");
        }
        try {
            Object canonicalBody =
                    body == null ? null : CANONICAL_JSON.convertValue(body, Object.class);
            byte[] bytes =
                    CANONICAL_JSON.writeValueAsBytes(
                            Arrays.asList(
                                    method.toUpperCase(Locale.ROOT), targetPath, canonicalBody));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (JacksonException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash request payload", ex);
        }
    }

    public Optional<AuthorizationOperation> findExisting(UUID actorId, String key, String hash) {
        requireKey(key);
        var existing =
                entities.createQuery(
                                "select o from AuthorizationOperation o where o.initiatorUserId = :actor and o.idempotencyKey = :key",
                                AuthorizationOperation.class)
                        .setParameter("actor", actorId)
                        .setParameter("key", key)
                        .getResultStream()
                        .findFirst();
        existing.ifPresent(operation -> {
            if (!operation.getPayloadHash().equals(hash)) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key đã dùng với yêu cầu khác.");
            }
        });
        return existing;
    }

    // Gọi trước mọi desired-state write, trong cùng transaction; mutation chỉ chạy nếu insert mới.
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizationOperation executeOnce(AuthorizationOperation operation, Runnable mutation) {
        requireKey(operation.getIdempotencyKey());
        UUID[] partitions = operation.getAffectedPartitions().clone();
        // UUID.compareTo() dùng signed longs, khác thứ tự uuid của PostgreSQL.
        Arrays.sort(partitions, Comparator.comparing((UUID partition) -> partition.toString()));
        for (UUID partition : partitions) {
            if (jdbc.queryForList(
                            "SELECT id FROM authz_partition WHERE id = ? FOR UPDATE",
                            UUID.class,
                            partition)
                    .isEmpty()) {
                throw new IllegalArgumentException("Unknown authorization partition");
            }
        }
        int created =
                jdbc.update(
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
                                                    "uuid", operation.getAffectedPartitions()));
                            ps.setArray(
                                    7,
                                    ps.getConnection()
                                            .createArrayOf(
                                                    "int8", operation.getAffectedRevisions()));
                            ps.setLong(8, operation.getDesiredRevision());
                        });
        if (created > 0) {
            mutation.run();
            return operation;
        }
        return findExisting(
                        operation.getInitiatorUserId(),
                        operation.getIdempotencyKey(),
                        operation.getPayloadHash())
                .orElseThrow();
    }

    public ResponseEntity<OperationReceipt> responseFor(AuthorizationOperation operation) {
        boolean completed = operation.getState() == AuthorizationOperation.State.SUCCEEDED;
        if (operation.getState() == AuthorizationOperation.State.FAILED) {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OPERATION_FAILED",
                    "Đồng bộ quyền không thành công.");
        }
        return ResponseEntity.status(completed ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(
                        new OperationReceipt(
                                operation.getId(),
                                operation.getState().name(),
                                completed,
                                "/api/me/operations/" + operation.getId()));
    }
}
