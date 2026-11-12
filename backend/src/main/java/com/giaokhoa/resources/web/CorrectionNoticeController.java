package com.giaokhoa.resources.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.service.CorrectionNotificationService;
import com.giaokhoa.resources.service.CorrectionNotificationService.RecipientNotice;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.shared.http.ScopedPageCursor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-034: workspace nhận chỉ thấy metadata correction đã được cấp riêng cho workspace, đồng thời
 * actor phải đang có workspace.enter VÀ use hoặc content.read trên version bị ảnh hưởng. Cursor
 * được ký theo actor/workspace/limit; thay đổi quyền trong lúc tải trang vẫn được fence chốt.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/correction-notices")
public class CorrectionNoticeController {
    private final ProtectedActionExecutor executor;
    private final WorkspaceTransaction workspaces;
    private final CorrectionNotificationService notifications;
    private final ScopedPageCursor tokens = new ScopedPageCursor();

    public CorrectionNoticeController(
            ProtectedActionExecutor executor,
            WorkspaceTransaction workspaces,
            CorrectionNotificationService notifications) {
        this.executor = executor;
        this.workspaces = workspaces;
        this.notifications = notifications;
    }

    public record NoticePage(
            List<RecipientNotice> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor) {
        public NoticePage {
            items = List.copyOf(items);
        }
    }

    @GetMapping
    public ResponseEntity<NoticePage> listNotices(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        UUID actor = executor.actorUserId(authentication);
        int size = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        String binding = "correction-inbox|" + actor + "|" + workspaceId + "|" + size;
        @Nullable UUID anchor = cursor == null ? null : tokens.decode(cursor, binding);
        List<RecipientNotice> candidates =
                Objects.requireNonNull(
                        workspaces.inWorkspace(
                                workspaceId, () -> notifications.receivedNotices(workspaceId)),
                        "notices");
        String workspaceKey = NativePolicyTemplates.workspaceKey(workspaceId);
        String enter = PermissionCatalog.WorkspaceScope.ENTER.scopeName();
        String content = PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName();
        String use = PermissionCatalog.ResourceVersionScope.USE.scopeName();
        List<ProtectedActionExecutor.Target> targets = new ArrayList<>();
        targets.add(new ProtectedActionExecutor.Target(workspaceKey, List.of(enter)));
        Set<String> seen = new LinkedHashSet<>();
        for (RecipientNotice row : candidates) {
            String versionKey =
                    NativePolicyTemplates.resourceVersionKey(
                            workspaceId, row.resourceId(), row.affectedVersionId());
            if (seen.add(versionKey)) {
                targets.add(new ProtectedActionExecutor.Target(versionKey, List.of(content, use)));
            }
        }
        NoticePage page =
                executor.evaluateAndRead(
                        authentication,
                        request,
                        workspaceId,
                        targets,
                        decisions -> {
                            if (!decisions.allowed(workspaceKey, enter)) {
                                throw new HttpProblemException(
                                        HttpStatus.FORBIDDEN,
                                        ErrorCodes.PERMISSION_DENIED,
                                        "Không có quyền đọc thông báo của workspace này.");
                            }
                            // Dữ kiện được tải LẠI bên trong fence; mỗi hàng phải có permission hợp
                            // lệ.
                            List<RecipientNotice> latest =
                                    Objects.requireNonNull(
                                            workspaces.inWorkspace(
                                                    workspaceId,
                                                    () ->
                                                            notifications.receivedNotices(
                                                                    workspaceId)),
                                            "notices");
                            List<RecipientNotice> allowed =
                                    latest.stream()
                                            .filter(row -> {
                                                String key =
                                                        NativePolicyTemplates.resourceVersionKey(
                                                                workspaceId,
                                                                row.resourceId(),
                                                                row.affectedVersionId());
                                                return decisions.allowed(key, content)
                                                        || decisions.allowed(key, use);
                                            })
                                            .toList();
                            int start = 0;
                            if (anchor != null) {
                                while (start < allowed.size()
                                        && !allowed.get(start).id().equals(anchor)) {
                                    start++;
                                }
                                if (start == allowed.size()) {
                                    throw new HttpProblemException(
                                            HttpStatus.BAD_REQUEST,
                                            ErrorCodes.INVALID_CURSOR,
                                            "Cursor thông báo không còn hợp lệ.");
                                }
                                start++;
                            }
                            int end = Math.min(start + size, allowed.size());
                            String next =
                                    end == allowed.size()
                                            ? null
                                            : tokens.encode(allowed.get(end - 1).id(), binding);
                            return new NoticePage(allowed.subList(start, end), next);
                        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(page);
    }
}
