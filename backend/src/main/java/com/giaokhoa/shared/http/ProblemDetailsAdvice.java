package com.giaokhoa.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {
    public record Violation(String field, String code, String message) {}

    @ExceptionHandler(HttpProblemException.class)
    ResponseEntity<Object> knownProblem(HttpProblemException ex, WebRequest request) {
        return respond(
                ex.getStatus(),
                ex.getCode(),
                Objects.requireNonNull(ex.getMessage(), "HTTP problem detail"),
                request,
                null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> staleWrite(OptimisticLockingFailureException ex, WebRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Phiên bản dữ liệu đã thay đổi.",
                request,
                null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> dataConflict(DataIntegrityViolationException ex, WebRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "Dữ liệu xung đột với ràng buộc hiện có.",
                request,
                null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException ex, WebRequest request) {
        return respond(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Không có quyền thực hiện thao tác.",
                request,
                null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(AuthenticationException ex, WebRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập.", request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        logger.error("Unexpected request failure", ex);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Không thể xử lý yêu cầu.",
                request,
                null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Violation> violations =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(this::violation)
                        .sorted(
                                Comparator.comparing((Violation violation) -> violation.field())
                                        .thenComparing(violation -> violation.code()))
                        .toList();
        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Dữ liệu không hợp lệ.",
                request,
                violations);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Violation> violations =
                ex.getParameterValidationResults().stream()
                        .flatMap(result ->
                                result.getResolvableErrors().stream()
                                        .map(_ ->
                                                new Violation(
                                                        result.getMethodParameter()
                                                                                .getParameterName()
                                                                        == null
                                                                ? "request"
                                                                : result.getMethodParameter()
                                                                        .getParameterName(),
                                                        "INVALID",
                                                        "Giá trị không hợp lệ.")))
                        .toList();
        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Dữ liệu không hợp lệ.",
                request,
                violations);
    }

    private Violation violation(FieldError error) {
        String code = error.getCode() == null ? "INVALID" : error.getCode();
        // Không echo rejected value, exception message hoặc nội dung request.
        return new Violation(error.getField(), code, "Giá trị không hợp lệ.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && ("If-Match".equalsIgnoreCase(missing.getHeaderName())
                        || "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName()))) {
            return respond(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED",
                    "Thiếu header bắt buộc.",
                    request,
                    null);
        }
        String code =
                switch (status.value()) {
                    case 401 -> "UNAUTHORIZED";
                    case 403 -> "FORBIDDEN";
                    case 404 -> "NOT_FOUND";
                    case 503 -> "SERVICE_UNAVAILABLE";
                    default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "BAD_REQUEST";
                };
        return respond(
                status,
                code,
                status.value() == 404 ? "Không tìm thấy tài nguyên." : "Yêu cầu không thể xử lý.",
                request,
                null);
    }

    private ResponseEntity<Object> respond(
            HttpStatusCode status,
            String code,
            String detail,
            WebRequest request,
            @Nullable List<Violation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        problem.setProperty("code", code);
        if (request instanceof ServletWebRequest servlet) {
            HttpServletRequest http = servlet.getRequest();
            problem.setInstance(URI.create(http.getRequestURI()));
            String trace = MDC.get("traceId");
            String requestId = http.getRequestId();
            problem.setProperty(
                    "traceId",
                    trace != null && !trace.isBlank()
                            ? trace
                            : requestId != null && !requestId.isBlank()
                                    ? requestId
                                    : UUID.randomUUID().toString());
        } else {
            problem.setProperty("traceId", UUID.randomUUID().toString());
        }
        if (violations != null) {
            problem.setProperty("violations", violations);
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
