package com.giaokhoa.resources.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.permission.PermissionCatalog;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.service.CorrectionNotificationService;
import com.giaokhoa.resources.service.CorrectionNotificationService.RecipientNotice;
import com.giaokhoa.shared.http.HttpProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

class CorrectionNoticeControllerTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID AFFECTED = UUID.randomUUID();
    private static final UUID REPLACEMENT = UUID.randomUUID();

    private final ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
    private final WorkspaceTransaction workspaces = mock(WorkspaceTransaction.class);
    private final CorrectionNotificationService notifications =
            mock(CorrectionNotificationService.class);
    private final CorrectionNoticeController controller =
            new CorrectionNoticeController(executor, workspaces, notifications);
    private final Authentication auth = mock(Authentication.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ProtectedActionExecutor.Decisions decisions =
            mock(ProtectedActionExecutor.Decisions.class);

    private void setup(List<RecipientNotice> rows) {
        when(executor.actorUserId(auth)).thenReturn(ACTOR);
        when(notifications.receivedNotices(WORKSPACE)).thenReturn(rows);
        when(workspaces.inWorkspace(eq(WORKSPACE), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(executor.evaluateAndRead(eq(auth), eq(request), eq(WORKSPACE), anyList(), any()))
                .thenAnswer(invocation -> {
                    Function<ProtectedActionExecutor.Decisions, ?> read = invocation.getArgument(4);
                    return read.apply(decisions);
                });
        when(decisions.allowed(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName()))
                .thenReturn(true);
    }

    private RecipientNotice notice(String reason) {
        return new RecipientNotice(
                UUID.randomUUID(),
                RESOURCE,
                AFFECTED,
                REPLACEMENT,
                reason,
                OffsetDateTime.parse("2026-11-12T00:00:00Z"),
                null);
    }

    @Test
    @DisplayName("Chỉ phát metadata của notice thuộc recipient và đã PDP cấp quyền phiên bản cũ")
    void filtersMetadataByActorRights() {
        RecipientNotice permitted = notice("Đã duyệt");
        RecipientNotice denied =
                new RecipientNotice(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        REPLACEMENT,
                        "Không được nhìn",
                        permitted.createdAt(),
                        null);
        setup(List.of(permitted, denied));
        String sourceKey = NativePolicyTemplates.resourceVersionKey(WORKSPACE, RESOURCE, AFFECTED);
        when(decisions.allowed(
                        sourceKey, PermissionCatalog.ResourceVersionScope.CONTENT_READ.scopeName()))
                .thenReturn(true);
        var result = controller.listNotices(WORKSPACE, null, null, auth, request);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(List.of(permitted), Objects.requireNonNull(result.getBody()).items());
        ArgumentCaptor<List<ProtectedActionExecutor.Target>> targets = ArgumentCaptor.captor();
        verify(executor)
                .evaluateAndRead(eq(auth), eq(request), eq(WORKSPACE), targets.capture(), any());
        assertFalse(
                targets.getValue().stream()
                        .anyMatch(item ->
                                item.targetKey()
                                        .equals(
                                                NativePolicyTemplates.resourceVersionKey(
                                                        WORKSPACE, RESOURCE, REPLACEMENT))));
    }

    @Test
    @DisplayName("Cursor không tái dùng được khi actor đổi và người rời workspace bị 403")
    void cursorBoundToActorAndEnterRight() {
        RecipientNotice one = notice("Một");
        RecipientNotice two = notice("Hai");
        setup(List.of(one, two));
        String key = NativePolicyTemplates.resourceVersionKey(WORKSPACE, RESOURCE, AFFECTED);
        when(decisions.allowed(key, PermissionCatalog.ResourceVersionScope.USE.scopeName()))
                .thenReturn(true);
        var first =
                Objects.requireNonNull(
                        controller.listNotices(WORKSPACE, null, 1, auth, request).getBody());
        assertEquals(List.of(one), first.items());
        assertEquals(
                List.of(two),
                Objects.requireNonNull(
                                controller
                                        .listNotices(
                                                WORKSPACE, first.nextCursor(), 1, auth, request)
                                        .getBody())
                        .items());
        when(executor.actorUserId(auth)).thenReturn(UUID.randomUUID());
        HttpProblemException invalid =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                controller.listNotices(
                                        WORKSPACE, first.nextCursor(), 1, auth, request));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatus());
        when(executor.actorUserId(auth)).thenReturn(ACTOR);
        when(decisions.allowed(
                        NativePolicyTemplates.workspaceKey(WORKSPACE),
                        PermissionCatalog.WorkspaceScope.ENTER.scopeName()))
                .thenReturn(false);
        HttpProblemException forbidden =
                assertThrows(
                        HttpProblemException.class,
                        () -> controller.listNotices(WORKSPACE, null, 1, auth, request));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
    }
}
