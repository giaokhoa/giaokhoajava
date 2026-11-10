package com.giaokhoa.shared.http;

/**
 * Một vi phạm theo field trong đáp án lỗi {@code VALIDATION_FAILED}: tên field, mã vi phạm (tên
 * constraint Bean Validation như {@code NotBlank}, hoặc {@code INVALID} cho giá trị không phân tích
 * được) và thông điệp an toàn không chứa giá trị bị từ chối.
 */
public record Violation(String field, String code, String message) {}
