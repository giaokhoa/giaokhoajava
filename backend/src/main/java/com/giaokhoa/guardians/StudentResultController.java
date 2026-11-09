package com.giaokhoa.guardians;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt HTTP đọc kết quả đã công bố của một học sinh. Ủy thác toàn bộ quyết quyền cho
 * PublishedResultService: PDP xét scope read.published trên từng hàng kết quả, và khi tập được phép
 * rỗng thì học sinh tự đọc nhận danh sách rỗng còn actor khác nhận 403 (B56).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/students/{studentUserId}/results")
public class StudentResultController {
    private final PublishedResultService results;

    public StudentResultController(PublishedResultService results) {
        this.results = results;
    }

    /** Trang kết quả đã công bố: chỉ items, không totalCount. */
    public record PublishedResultPage(List<PublishedResultView> items) {
        public PublishedResultPage {
            items = List.copyOf(items);
        }
    }

    @GetMapping
    public ResponseEntity<PublishedResultPage> listResults(
            @PathVariable UUID workspaceId,
            @PathVariable UUID studentUserId,
            Authentication authentication,
            HttpServletRequest request) {
        List<PublishedResultView> items =
                results.listPublishedResults(authentication, request, workspaceId, studentUserId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new PublishedResultPage(items));
    }
}
