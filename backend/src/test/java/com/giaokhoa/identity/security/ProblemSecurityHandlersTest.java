package com.giaokhoa.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lỗi phát sinh trong filter chain (chưa tới DispatcherServlet) phải mang cùng hình dạng
 * ProblemDetail như lỗi trong MVC: status đúng, content-type problem+json, code ổn định và traceId
 * để đối chiếu log.
 */
class ProblemSecurityHandlersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void entryPointAnswers401ProblemJson() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/me/workspaces");
        var response = new MockHttpServletResponse();

        new ProblemAuthenticationEntryPoint()
                .commence(request, response, new BadCredentialsException("hidden"));

        assertEquals(401, response.getStatus());
        assertTrue(
                response.getContentType() != null
                        && response.getContentType()
                                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals(401, body.get("status").intValue());
        assertEquals("UNAUTHORIZED", body.get("code").stringValue());
        assertEquals("Cần đăng nhập.", body.get("detail").stringValue());
        assertEquals("/api/me/workspaces", body.get("instance").stringValue());
        assertFalse(body.get("traceId").stringValue().isBlank());
    }

    @Test
    void deniedHandlerAnswers403ProblemJson() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/logout");
        var response = new MockHttpServletResponse();

        new ProblemAccessDeniedHandler()
                .handle(request, response, new AccessDeniedException("hidden"));

        assertEquals(403, response.getStatus());
        assertTrue(
                response.getContentType() != null
                        && response.getContentType()
                                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals(403, body.get("status").intValue());
        assertEquals("FORBIDDEN", body.get("code").stringValue());
        assertEquals("Không có quyền thực hiện thao tác.", body.get("detail").stringValue());
        assertFalse(body.get("traceId").stringValue().isBlank());
    }
}
