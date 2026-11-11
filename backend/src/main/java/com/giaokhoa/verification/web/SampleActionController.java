package com.giaokhoa.verification.web;

import com.giaokhoa.verification.dto.SampleActionRequest;
import com.giaokhoa.verification.dto.SampleActionResult;
import com.giaokhoa.verification.service.SampleActionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của các sample action: chỉ gắn route, header và mã trạng thái; toàn bộ nghiệp vụ —
 * pipeline manifest/fence/PDP, giao thức biên nhận idempotency và ghi dữ kiện mẫu — nằm ở {@link
 * SampleActionService}. Discriminator {@code action} thuộc danh mục cố định của hợp đồng: mọi giá
 * trị trong danh mục đều có đường dispatch riêng ở service, giá trị ngoài danh mục bị từ chối. Hai
 * route đọc thân bài/đáp án của resource version nằm ở controller riêng của gói {@code resources}
 * cùng hai scope đọc tách biệt của chúng.
 */
@RestController
public class SampleActionController {
    private final SampleActionService actions;

    public SampleActionController(SampleActionService actions) {
        this.actions = actions;
    }

    @PostMapping("/api/workspaces/{workspaceId}/samples/actions")
    public ResponseEntity<SampleActionResult> act(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SampleActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        SampleActionResult result =
                actions.act(workspaceId, idempotencyKey, request, authentication, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(result);
    }
}
