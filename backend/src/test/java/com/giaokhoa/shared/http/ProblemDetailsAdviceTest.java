package com.giaokhoa.shared.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsAdviceTest {
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Probe())
                    .setControllerAdvice(new ProblemDetailsAdvice())
                    .build();

    @RestController
    static class Probe {
        record Payload(@NotBlank String name) {}

        @PostMapping("/probe")
        void validate(
                @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody Payload payload) {}

        @PostMapping("/pending")
        void unavailable() {
            throw new HttpProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROJECTION_PENDING",
                    "Đồng bộ quyền đang xử lý.");
        }

        @PostMapping("/query")
        void invalidParam(@RequestParam @NotBlank String code) {}

        @PostMapping("/forbidden")
        void forbidden() {
            throw new AccessDeniedException("internal security details");
        }

        @PostMapping("/unauthorized")
        void unauthorized() {
            throw new BadCredentialsException("secret credentials");
        }

        @PostMapping("/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException(
                    "constraint uq_user_username, username=private");
        }

        @PostMapping("/stale")
        void stale() {
            throw new OptimisticLockingFailureException("row version private info");
        }

        @PostMapping("/internal")
        void unexpected() {
            throw new IllegalArgumentException("credential super-secret must not appear");
        }
    }

    @DisplayName("Thiếu If-Match trả 428 kèm no-cache và trace")
    @Test
    void missingIfMatchReturns428() throws Exception {
        mvc.perform(
                        post("/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"ok\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"))
                .andExpect(jsonPath("$.instance").value("/probe"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @DisplayName("Field không hợp lệ sinh các violation an toàn mà không lộ dữ liệu bị từ chối")
    @Test
    void invalidFieldYieldsSafeViolations() throws Exception {
        mvc.perform(
                        post("/probe")
                                .header("If-Match", "\"0\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[0].code").value("NotBlank"))
                .andExpect(jsonPath("$.violations[0].message").value("Giá trị không hợp lệ."));
    }

    @DisplayName("Projection đang pending trả 503 mà không lộ bí mật")
    @Test
    void projectionPendingReturns503() throws Exception {
        mvc.perform(post("/pending"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROJECTION_PENDING"));
    }

    @Test
    void methodValidationIncludesFieldViolation() throws Exception {
        mvc.perform(post("/query").param("code", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("code"))
                .andExpect(jsonPath("$.violations[0].message").value("Giá trị không hợp lệ."));
    }

    @Test
    void accessFailuresHaveStableCodes() throws Exception {
        mvc.perform(post("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @DisplayName("Integrity violation mang mã data conflict mà không lộ chi tiết constraint")
    @Test
    void integrityViolationHidesConstraint() throws Exception {
        mvc.perform(post("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Dữ liệu xung đột với ràng buộc hiện có."));
    }

    @Test
    void optimisticLockHasDistinctVersionCode() throws Exception {
        mvc.perform(post("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Phiên bản dữ liệu đã thay đổi."));
    }

    @Test
    void unexpectedErrorDoesNotEchoException() throws Exception {
        mvc.perform(post("/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Không thể xử lý yêu cầu."))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
