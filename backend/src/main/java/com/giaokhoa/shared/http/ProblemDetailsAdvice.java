package com.giaokhoa.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(HttpProblemException.class)
    ResponseEntity<Object> knownProblem(HttpProblemException ex, WebRequest request) {
        return respond(
                ex.getStatus(),
                ex.getCode(),
                Objects.requireNonNull(ex.getMessage(), "HTTP problem detail"),
                request,
                ex.getViolations());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> staleWrite(OptimisticLockingFailureException ex, WebRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                ErrorCodes.VERSION_CONFLICT,
                "Phiên bản dữ liệu đã thay đổi.",
                request,
                null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> dataConflict(DataIntegrityViolationException ex, WebRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                ErrorCodes.DATA_CONFLICT,
                "Dữ liệu xung đột với ràng buộc hiện có.",
                request,
                null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException ex, WebRequest request) {
        return respond(
                HttpStatus.FORBIDDEN,
                ErrorCodes.FORBIDDEN,
                "Không có quyền thực hiện thao tác.",
                request,
                null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(AuthenticationException ex, WebRequest request) {
        return respond(
                HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "Cần đăng nhập.", request, null);
    }

    /**
     * Tham số lập trình sót chuyển đổi tại điểm gọi (điển hình là idiom phân tích UUID/enum): đây
     * là lỗi dữ liệu vào của client, không phải lỗi lập trình, nên trả 400 thay vì 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> illegalArgument(IllegalArgumentException ex, WebRequest request) {
        logger.warn("Unconverted illegal argument reached the API boundary", ex);
        return respond(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.BAD_REQUEST,
                "Yêu cầu không thể xử lý.",
                request,
                null);
    }

    /**
     * Trạng thái phụ thuộc chưa sẵn sàng lọt ra biên (token Keycloak không lấy được, đáp án thượng
     * nguồn rỗng…): cùng ánh xạ 503 mà các đường identity đã chủ đích dùng cho nguồn danh tính
     * không sẵn sàng, thay vì 500 lẫn với lỗi lập trình.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Object> illegalState(IllegalStateException ex, WebRequest request) {
        logger.warn("Dependency state failure reached the API boundary", ex);
        return respond(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.SERVICE_UNAVAILABLE,
                "Dịch vụ phụ thuộc không sẵn sàng, thử lại sau.",
                request,
                null);
    }

    /**
     * Lỗi từ client HTTP gọi thượng nguồn (Keycloak) sót bắt tại điểm gọi: 503 như ánh xạ chủ đích
     * ở các đường identity, không lộ thân lỗi của thượng nguồn.
     */
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Object> upstreamFailure(RestClientException ex, WebRequest request) {
        logger.warn("Upstream HTTP failure reached the API boundary", ex);
        return respond(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.SERVICE_UNAVAILABLE,
                "Dịch vụ phụ thuộc không sẵn sàng, thử lại sau.",
                request,
                null);
    }

    /**
     * Validation ở tầng ngoài MVC (tham số hoặc service được validate bằng Bean Validation): cùng
     * hình dạng {@code VALIDATION_FAILED} kèm {@code violations} như validation trong MVC.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(
            ConstraintViolationException ex, WebRequest request) {
        List<Violation> violations =
                ex.getConstraintViolations().stream()
                        .map(this::violation)
                        .sorted(
                                Comparator.comparing((Violation violation) -> violation.field())
                                        .thenComparing(violation -> violation.code()))
                        .toList();
        return respond(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                "Dữ liệu không hợp lệ.",
                request,
                violations);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        logger.error("Unexpected request failure", ex);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.INTERNAL_ERROR,
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
                ErrorCodes.VALIDATION_FAILED,
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
                ErrorCodes.VALIDATION_FAILED,
                "Dữ liệu không hợp lệ.",
                request,
                violations);
    }

    private Violation violation(FieldError error) {
        String code = error.getCode() == null ? "INVALID" : error.getCode();
        // Không echo rejected value, exception message hoặc nội dung request.
        return new Violation(error.getField(), code, "Giá trị không hợp lệ.");
    }

    private Violation violation(ConstraintViolation<?> violation) {
        String field = "request";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        String code =
                violation
                        .getConstraintDescriptor()
                        .getAnnotation()
                        .annotationType()
                        .getSimpleName();
        // Không echo rejected value, exception message hoặc nội dung request.
        return new Violation(field, code, "Giá trị không hợp lệ.");
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
                    ErrorCodes.PRECONDITION_REQUIRED,
                    "Thiếu header bắt buộc.",
                    request,
                    null);
        }
        // Mã suy ra từ chính status để 405/406/413/415… không còn mang mã BAD_REQUEST của 400.
        // 503 phải xét trước nhánh 5xx, nếu không sẽ bị gán INTERNAL_ERROR như các 5xx còn lại.
        HttpStatus httpStatus = HttpStatus.resolve(status.value());
        String code =
                httpStatus == HttpStatus.SERVICE_UNAVAILABLE
                        ? ErrorCodes.SERVICE_UNAVAILABLE
                        : status.is5xxServerError()
                                ? ErrorCodes.INTERNAL_ERROR
                                : httpStatus == null ? ErrorCodes.BAD_REQUEST : httpStatus.name();
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
            problem.setProperty("traceId", ProblemResponses.traceId(http));
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
