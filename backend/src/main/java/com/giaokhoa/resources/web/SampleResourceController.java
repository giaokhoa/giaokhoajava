package com.giaokhoa.resources.web;

import com.giaokhoa.resources.service.ResourceLibraryService;
import com.giaokhoa.verification.dto.ResourceAnswerView;
import com.giaokhoa.verification.dto.ResourceBodyView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hai route đọc mẫu của hợp đồng kiểm chứng quyền: thân bài và đáp án của một resource version tại
 * workspace ngữ cảnh. Hai route tách hẳn nhau vì hai scope tách hẳn nhau — thân bài được quyết bằng
 * {@code content.read} và DTO của nó không bao giờ chứa đáp án; đáp án được quyết bằng {@code
 * answer.read} riêng qua route riêng, nên quyền đọc/giao/soạn không bao giờ tự mở quyền xem đáp án.
 * Cả hai route chỉ uỷ quyền cho {@link ResourceLibraryService}, nơi giữ pipeline projection/PDP của
 * đường đọc contextual.
 */
@RestController
public class SampleResourceController {

    private final ResourceLibraryService library;

    public SampleResourceController(ResourceLibraryService library) {
        this.library = library;
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
