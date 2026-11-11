package com.giaokhoa.verification.web;

import com.giaokhoa.resources.ResourceLibraryService;
import com.giaokhoa.verification.dto.ResourceAnswerView;
import com.giaokhoa.verification.dto.ResourceBodyView;
import com.giaokhoa.verification.dto.SampleActionRequest;
import com.giaokhoa.verification.dto.SampleActionResult;
import com.giaokhoa.verification.service.SampleActionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP của các sample action: chỉ gắn route, header và mã trạng thái; toàn bộ nghiệp vụ —
 * pipeline manifest/fence/PDP, giao thức biên nhận idempotency và ghi dữ kiện mẫu — nằm ở {@link
 * SampleActionService}. Hai route đọc thân bài/đáp án của resource version uỷ thẳng cho {@link
 * ResourceLibraryService} như trước.
 */
@RestController
public class SampleActionController {
    private final SampleActionService actions;
    private final ResourceLibraryService library;

    public SampleActionController(SampleActionService actions, ResourceLibraryService library) {
        this.actions = actions;
        this.library = library;
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

    @GetMapping("/api/workspaces/{workspaceId}/samples/resources/{resourceId}/versions/{versionId}")
    public ResponseEntity<ResourceBodyView> readResourceBody(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @PathVariable UUID versionId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String body =
                library.readBody(authentication, httpRequest, workspaceId, resourceId, versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ResourceBodyView(resourceId, versionId, body));
    }

    @GetMapping(
            "/api/workspaces/{workspaceId}/samples/resources/{resourceId}"
                    + "/versions/{versionId}/answer")
    public ResponseEntity<ResourceAnswerView> readResourceAnswer(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @PathVariable UUID versionId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String answer =
                library.readAnswer(authentication, httpRequest, workspaceId, resourceId, versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ResourceAnswerView(resourceId, versionId, answer));
    }
}
