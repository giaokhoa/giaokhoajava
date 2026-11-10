package com.giaokhoa.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class IntegrationEnvironmentTest {
    private static final int FIRST_CALL = 1;

    @Test
    void xoaUserThuLaiKhiKeycloakTra409RoiXoaDuoc() {
        AtomicInteger calls = new AtomicInteger();
        assertDoesNotThrow(() ->
                IntegrationEnvironment.deleteWithConflictRetry(() -> {
                    if (calls.incrementAndGet() == FIRST_CALL) {
                        throw clientError(HttpStatus.CONFLICT, "{\"error\":\"conflict\"}");
                    }
                }));
        assertEquals(2, calls.get());
    }

    @Test
    void xoaUserCoi404LaDaDonXong() {
        AtomicInteger calls = new AtomicInteger();
        assertDoesNotThrow(() ->
                IntegrationEnvironment.deleteWithConflictRetry(() -> {
                    calls.incrementAndGet();
                    throw clientError(HttpStatus.NOT_FOUND, "");
                }));
        assertEquals(1, calls.get());
    }

    @DisplayName("Xóa user gặp 409 dai dẳng thì ném lỗi kèm nội dung phản hồi của Keycloak")
    @Test
    void deleteUserPersistent409Throws() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                IntegrationEnvironment.deleteWithConflictRetry(() -> {
                                    calls.incrementAndGet();
                                    throw clientError(
                                            HttpStatus.CONFLICT, "{\"error\":\"conflict\"}");
                                }));
        assertEquals(3, calls.get());
        String message = Objects.requireNonNull(error.getMessage(), "Lỗi phải kèm mã trạng thái");
        assertTrue(message.contains("409"), message);
        assertTrue(message.contains("conflict"), message);
    }

    @Test
    void xoaUserKhongThuLaiLoiKhac409() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () ->
                        IntegrationEnvironment.deleteWithConflictRetry(() -> {
                            calls.incrementAndGet();
                            throw clientError(HttpStatus.BAD_REQUEST, "");
                        }));
        assertEquals(1, calls.get());
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return new HttpClientErrorException(
                status,
                status.getReasonPhrase(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void onlyTheTwoLocalTestLanesAreAllowed() {
        String testDb = "jdbc:postgresql://127.0.0.1:15432/giaokhoa_test";
        String testIssuer = "http://127.0.0.1:18081/realms/giaokhoa-it";
        String lane2Db = "jdbc:postgresql://127.0.0.1:15433/giaokhoa_test";
        String lane2Issuer = "http://127.0.0.1:18082/realms/giaokhoa-it";

        assertDoesNotThrow(() -> IntegrationEnvironment.validateTargets(testDb, testIssuer));
        assertDoesNotThrow(() -> IntegrationEnvironment.validateTargets(lane2Db, lane2Issuer));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegrationEnvironment.validateTargets(testDb, lane2Issuer));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegrationEnvironment.validateTargets(lane2Db, testIssuer));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationEnvironment.validateTargets(
                                testDb, "http://127.0.0.1:8081/realms/giaokhoa-it"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationEnvironment.validateTargets(
                                "jdbc:postgresql://127.0.0.1:5432/giaokhoa_test", testIssuer));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationEnvironment.validateTargets(
                                "jdbc:postgresql://127.0.0.1:15432/giaokhoa", testIssuer));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationEnvironment.validateTargets(
                                "jdbc:postgresql://app-db:5432/giaokhoa_test", testIssuer));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationEnvironment.validateTargets(
                                testDb, "http://auth.giaokhoa.localhost:8081/realms/giaokhoa-it"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegrationEnvironment.validateTargets("jdbc:h2:mem:test", testIssuer));
    }
}
