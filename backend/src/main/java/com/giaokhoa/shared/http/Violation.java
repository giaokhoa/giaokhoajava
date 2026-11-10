package com.giaokhoa.shared.http;

/**
 * Một vi phạm theo field trong đáp án lỗi {@code VALIDATION_FAILED}: tên field, mã vi phạm (tên
 * constraint Bean Validation như {@code NotBlank}, hoặc {@code INVALID} cho giá trị không phân tích
 * được) và thông điệp an toàn không chứa giá trị bị từ chối.
 */
public record Violation(String field, String code, String message) {

    /** Thông điệp chung cho giá trị không hợp lệ (không chứa giá trị bị từ chối). */
    private static final String INVALID_VALUE_MESSAGE = "Giá trị không hợp lệ.";

    /** Vi phạm trường bắt buộc bị null. */
    public static Violation notNull(String field) {
        return new Violation(field, "NotNull", INVALID_VALUE_MESSAGE);
    }

    /** Vi phạm trường bắt buộc bị rỗng. */
    public static Violation notBlank(String field) {
        return new Violation(field, "NotBlank", INVALID_VALUE_MESSAGE);
    }

    /** Vi phạm giá trị không phân tích được. */
    public static Violation invalid(String field) {
        return new Violation(field, "INVALID", INVALID_VALUE_MESSAGE);
    }
}
