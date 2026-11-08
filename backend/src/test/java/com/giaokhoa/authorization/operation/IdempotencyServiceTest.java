package com.giaokhoa.authorization.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceTest {
    private final IdempotencyService service =
            new IdempotencyService(mock(JdbcTemplate.class), mock(EntityManager.class));
    private final JsonMapper json = new JsonMapper();

    @Test
    void hashIsCanonicalForObjectFieldOrderButScopedToMethodAndTarget() {
        var first = json.readTree("{\"roles\":[\"TEACHER\"],\"nested\":{\"b\":2,\"a\":1}}");
        var reordered = json.readTree("{\"nested\":{\"a\":1,\"b\":2},\"roles\":[\"TEACHER\"]}");
        String hash = service.payloadHash("PUT", "/api/workspaces/one/memberships/two", first);

        assertEquals(64, hash.length());
        assertEquals(
                hash, service.payloadHash("put", "/api/workspaces/one/memberships/two", reordered));
        assertNotEquals(
                hash, service.payloadHash("POST", "/api/workspaces/one/memberships/two", first));
        assertNotEquals(
                hash, service.payloadHash("PUT", "/api/workspaces/two/memberships/two", first));
        assertNotEquals(
                hash,
                service.payloadHash(
                        "PUT",
                        "/api/workspaces/one/memberships/two",
                        json.readTree("{\"roles\":[\"STUDENT\"]}")));
    }

    @Test
    void keyAndStrongEtagAreRequiredAndVersionConflictIsSafe() {
        assertEquals(
                HttpStatus.PRECONDITION_REQUIRED,
                assertThrows(HttpProblemException.class, () -> service.requireKey(null))
                        .getStatus());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                HttpProblemException.class,
                                () -> service.requireKey("unsafe\r\nheader"))
                        .getStatus());
        assertEquals(
                HttpStatus.PRECONDITION_REQUIRED,
                assertThrows(
                                HttpProblemException.class,
                                () -> IdempotencyService.requireIfMatch(null, 2))
                        .getStatus());
        assertEquals(
                "INVALID_IF_MATCH",
                assertThrows(
                                HttpProblemException.class,
                                () -> IdempotencyService.requireIfMatch("W/\"2\"", 2))
                        .getCode());
        assertEquals(
                "INVALID_IF_MATCH",
                assertThrows(
                                HttpProblemException.class,
                                () -> IdempotencyService.requireIfMatch("*", 2))
                        .getCode());
        assertEquals(
                "INVALID_IF_MATCH",
                assertThrows(
                                HttpProblemException.class,
                                () ->
                                        IdempotencyService.requireIfMatch(
                                                "\"999999999999999999999\"", 2))
                        .getCode());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                HttpProblemException.class,
                                () -> IdempotencyService.requireIfMatch("\"1\"", 2))
                        .getStatus());
        assertDoesNotThrow(() -> IdempotencyService.requireIfMatch("\"2\"", 2));
    }

    @Test
    void pendingAndRetryableCannotBePresentedAsCompleted() {
        UUID actor = UUID.randomUUID();
        UUID partition = UUID.randomUUID();
        AuthorizationOperation operation =
                new AuthorizationOperation(
                        UUID.randomUUID(),
                        actor,
                        null,
                        "key",
                        "f".repeat(64),
                        new UUID[] {partition},
                        new Long[] {0L},
                        0L);

        var result = service.responseFor(operation);
        var body = Objects.requireNonNull(result.getBody());
        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertFalse(body.completed());
        assertEquals("PENDING", body.state());
        assertTrue(body.statusUrl().contains(operation.getId().toString()));
        assertEquals("private, no-store", result.getHeaders().getFirst("Cache-Control"));
    }
}
