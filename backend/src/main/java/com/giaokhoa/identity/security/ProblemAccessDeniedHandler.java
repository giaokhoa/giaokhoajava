package com.giaokhoa.identity.security;

import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Xử lý 403 phát sinh ngay trong filter chain (điển hình là CSRF token thiếu/sai): trả thân
 * ProblemDetail có {@code code} và {@code traceId} như mọi lỗi khác thay vì thân mặc định của
 * container vốn không mang hai member này.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemResponses.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                ErrorCodes.FORBIDDEN,
                "Không có quyền thực hiện thao tác.");
    }
}
