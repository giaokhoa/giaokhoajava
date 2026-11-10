package com.giaokhoa.shared.http;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

public class HttpProblemException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final HttpStatus status;
    private final String code;

    // Chỉ được đọc trong request hiện tại để dựng ProblemDetail; exception này không có đường nào
    // đi qua Java serialization nên violations không cần sống qua serialization.
    private final transient @Nullable List<Violation> violations;

    public HttpProblemException(HttpStatus status, String code, String safeDetail) {
        this(status, code, safeDetail, null, null);
    }

    public HttpProblemException(
            HttpStatus status, String code, String safeDetail, @Nullable Throwable cause) {
        this(status, code, safeDetail, cause, null);
    }

    public HttpProblemException(
            HttpStatus status,
            String code,
            String safeDetail,
            @Nullable Throwable cause,
            @Nullable List<Violation> violations) {
        super(safeDetail, cause);
        this.status = status;
        this.code = code;
        this.violations = violations == null ? null : List.copyOf(violations);
    }

    /**
     * Lỗi dữ liệu vào kiểm tay ở controller: cùng một hình dạng {@code VALIDATION_FAILED} như đường
     * Bean Validation, luôn mang {@code violations} theo field để client dựa vào được.
     */
    public static HttpProblemException validationFailed(
            String safeDetail, List<Violation> violations) {
        return validationFailed(safeDetail, violations, null);
    }

    public static HttpProblemException validationFailed(
            String safeDetail, List<Violation> violations, @Nullable Throwable cause) {
        return new HttpProblemException(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                safeDetail,
                cause,
                violations);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public @Nullable List<Violation> getViolations() {
        // Trả bản sao để không bao giờ lộ chính list đang giữ bên trong (bất biến đã chốt
        // ở constructor bằng List.copyOf, bản sao ở đây giữ nguyên tính chất đó); nhánh
        // null trả chính field đang null nên không lộ gì và giữ nguyên hợp đồng @Nullable.
        return violations == null ? violations : List.copyOf(violations);
    }
}
