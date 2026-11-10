package com.giaokhoa.shared.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đặt {@code traceId} vào MDC ngay từ đầu mỗi request để mọi dòng log của request và member {@code
 * traceId} trong đáp án lỗi ProblemDetail dùng chung một giá trị tra cứu được lẫn nhau. Filter chạy
 * trước toàn bộ chuỗi filter còn lại (kể cả Spring Security) và dọn MDC khi request kết thúc để
 * không rò sang request khác trên cùng luồng.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
