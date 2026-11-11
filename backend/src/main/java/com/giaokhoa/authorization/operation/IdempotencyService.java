package com.giaokhoa.authorization.operation;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final AuthorizationOperationRepository operations;
    private final AuthzPartitionRepository partitions;

    public record OperationReceipt(
            UUID operationId, String state, boolean completed, String statusUrl) {}

    public IdempotencyService(
            AuthorizationOperationRepository operations, AuthzPartitionRepository partitions) {
        this.operations = operations;
        this.partitions = partitions;
    }

    public String requireKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ErrorCodes.PRECONDITION_REQUIRED,
                    "Thiếu Idempotency-Key.");
        }
        if (key.length() > 255 || !key.matches("[\\x21-\\x7E]+")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key không hợp lệ.");
        }
        return key;
    }

    public static void requireIfMatch(@Nullable String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new HttpProblemException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ErrorCodes.PRECONDITION_REQUIRED,
                    "Thiếu If-Match.");
        }
        // Strong ETag của row version; dấu * và weak validator không hợp lệ cho mutation này.
        if (!ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_IF_MATCH,
                    "If-Match phải là strong ETag của row version.");
        }
        try {
            if (Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)) != currentVersion) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.VERSION_CONFLICT,
                        "Phiên bản dữ liệu đã thay đổi.");
            }
        } catch (NumberFormatException ex) {
            throw new HttpProblemException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.INVALID_IF_MATCH,
                    "If-Match không hợp lệ.",
                    ex);
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
        var existing = operations.findByInitiatorUserIdAndIdempotencyKey(actorId, key);
        existing.ifPresent(operation -> {
            if (!operation.getPayloadHash().equals(hash)) {
                throw new HttpProblemException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key đã dùng với yêu cầu khác.");
            }
        });
        return existing;
    }

    // Gọi trước mọi desired-state write, trong cùng transaction; mutation chỉ chạy nếu insert mới.
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizationOperation executeOnce(AuthorizationOperation operation, Runnable mutation) {
        requireKey(operation.getIdempotencyKey());
        // UUID.compareTo() dùng signed longs, khác thứ tự uuid của PostgreSQL: sắp xếp theo
        // chuỗi trước khi khóa để mọi transaction khóa partition theo cùng một thứ tự.
        var partitionIds =
                operation.getAffectedPartitions().stream()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList();
        Set<UUID> locked =
                partitions.lockAllForUpdate(partitionIds).stream()
                        .map(partition -> partition.getId())
                        .collect(Collectors.toSet());
        for (UUID partition : partitionIds) {
            if (!locked.contains(partition)) {
                throw new IllegalArgumentException("Unknown authorization partition");
            }
        }
        int created = operations.insertIfAbsent(operation);
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
                    ErrorCodes.OPERATION_FAILED,
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
