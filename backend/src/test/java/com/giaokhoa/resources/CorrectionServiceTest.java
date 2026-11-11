package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Khóa chuyển đổi `created_at` của {@link CorrectionService}: driver JDBC trả timestamptz về dạng
 * {@link Timestamp} qua `queryForMap`, không phải {@link OffsetDateTime}.
 */
class CorrectionServiceTest {

    @Test
    @DisplayName("Timestamp của driver chuyển thành OffsetDateTime tại UTC, giữ nguyên thời điểm")
    void timestampConvertsToUtcOffsetDateTime() {
        Instant instant = Instant.parse("2026-11-11T06:43:47Z");
        OffsetDateTime converted =
                Objects.requireNonNull(CorrectionService.toOffsetDateTime(Timestamp.from(instant)));
        assertEquals(instant, converted.toInstant());
        assertEquals(ZoneOffset.UTC, converted.getOffset());
    }

    @Test
    @DisplayName("OffsetDateTime giữ nguyên, không đổi offset hay thời điểm")
    void offsetDateTimePassesThrough() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-11T13:43:47+07:00");
        assertEquals(createdAt, CorrectionService.toOffsetDateTime(createdAt));
    }

    @Test
    @DisplayName("Giá trị null trả về null để phía gọi báo thiếu cột created_at")
    void nullReturnsNull() {
        assertNull(CorrectionService.toOffsetDateTime(null));
    }

    @Test
    @DisplayName("Kiểu không hỗ trợ thì lỗi rõ ràng thay vì ClassCastException")
    void unsupportedTypeThrows() {
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> CorrectionService.toOffsetDateTime("2026-11-11T06:43:47Z"));
        String message = Objects.requireNonNull(thrown.getMessage(), "exception message");
        assertTrue(message.contains("java.lang.String"));
    }
}
