package com.giaokhoa.identity.security;

import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Điểm vào xác thực cho {@code /api/**}: request chưa đăng nhập nhận 401 {@code
 * application/problem+json} cùng hình dạng như {@link
 * com.giaokhoa.shared.http.ProblemDetailsAdvice} thay vì redirect 302 về trang đăng nhập của luồng
 * trình duyệt. {@code ExceptionTranslationFilter} gọi entry point này trước khi request tới
 * DispatcherServlet nên advice không bao giờ thấy được lỗi này.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        ProblemResponses.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.UNAUTHORIZED,
                "Cần đăng nhập.");
    }
}
