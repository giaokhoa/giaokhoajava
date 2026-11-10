package com.giaokhoa.shared.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Filter phải đặt traceId vào MDC trước khi chuỗi xử lý chạy (để log và đáp án lỗi của cùng request
 * dùng chung một giá trị) và dọn MDC sau khi request kết thúc.
 */
class TraceIdFilterTest {

    @Test
    void traceIdIsInMdcDuringChainAndClearedAfterwards() throws Exception {
        var seenInsideChain = new AtomicReference<String>();
        FilterChain chain = (request, response) -> seenInsideChain.set(MDC.get("traceId"));

        new TraceIdFilter()
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(seenInsideChain.get());
        assertNull(MDC.get("traceId"));
    }
}
