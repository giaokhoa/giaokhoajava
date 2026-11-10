package com.giaokhoa.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ghi đáp án lỗi ProblemDetail (RFC 9457) ở tầng servlet, nơi {@link ProblemDetailsAdvice} không
 * chạm tới được — điển hình là handler của Spring Security cho 401/403 ở filter chain. Hình dạng
 * thân phải khớp đúng đáp án của advice: {@code type}, {@code title}, {@code status}, {@code
 * detail}, {@code instance} cùng hai member mở rộng {@code code} và {@code traceId} theo hợp đồng
 * trong {@code contracts/http-api.md}.
 */
public final class ProblemResponses {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemResponses() {}

    /**
     * Trace id của request hiện hành: ưu tiên giá trị filter đã đặt vào MDC (khớp dòng log của cùng
     * request), sau đó tới request id của container, cuối cùng mới sinh UUID mới.
     */
    public static String traceId(HttpServletRequest request) {
        String trace = MDC.get("traceId");
        if (trace != null && !trace.isBlank()) {
            return trace;
        }
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

    /** Ghi một đáp án {@code application/problem+json} hoàn chỉnh vào response của servlet. */
    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("traceId", traceId(request));
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(response.getOutputStream(), body);
    }
}
