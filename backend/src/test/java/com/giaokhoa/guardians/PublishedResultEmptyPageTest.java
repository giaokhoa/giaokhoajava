package com.giaokhoa.guardians;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import com.giaokhoa.tenancy.data.WorkspaceTransaction;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

/** Không có hàng PUBLISHED cũng phải qua eligibility/PDP/ACCOUNT và WORKSPACE fence. */
@ExtendWith(MockitoExtension.class)
class PublishedResultEmptyPageTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID OTHER = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();

    private record Fixture(
            PublishedResultService service,
            ProtectedActionExecutor executor,
            Authentication authentication) {}

    private Fixture fixture() {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        WorkspaceTransaction workspaces = mock(WorkspaceTransaction.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Authentication authentication = mock(Authentication.class);
        // Stub setup dùng chung cho mọi test của Fixture: từng test chỉ chạm một
        // phần, nên lenient theo đúng chỉ định của Mockito cho stubbing dùng chung.
        lenient().when(executor.actorUserId(authentication)).thenReturn(ACTOR);
        lenient()
                .doAnswer(call -> ((Supplier<?>) call.getArgument(1)).get())
                .when(workspaces)
                .inWorkspace(eq(WORKSPACE), any());
        lenient()
                .doAnswer(call -> ((Supplier<?>) call.getArgument(4)).get())
                .when(executor)
                .execute(eq(authentication), any(), any(), anyList(), any());
        return new Fixture(
                new PublishedResultService(executor, workspaces, jdbc), executor, authentication);
    }

    @Test
    void ownEmptyResultPageStillUsesAccountAndWorkspaceFence() {
        Fixture f = fixture();
        var page =
                f.service.pagePublishedResults(
                        f.authentication, new MockHttpServletRequest(), WORKSPACE, ACTOR, null, 20);
        assertEquals(0, page.items().size());
        assertNull(page.nextCursor());
        verify(f.executor).execute(eq(f.authentication), any(), eq(WORKSPACE), anyList(), any());
        verify(f.executor, never()).evaluateAndRead(any(), any(), any(), anyList(), any());
    }

    @Test
    void noPublishedResultDoesNotTurnAccountPendingIntoSuccess() {
        Fixture f = fixture();
        doAnswer(
                        call -> {
                            throw new HttpProblemException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "AUTHORIZATION_NOT_READY",
                                    "Quyền đang đồng bộ.");
                        })
                .when(f.executor)
                .execute(eq(f.authentication), any(), any(), anyList(), any());
        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                f.service.pagePublishedResults(
                                        f.authentication,
                                        new MockHttpServletRequest(),
                                        WORKSPACE,
                                        ACTOR,
                                        null,
                                        20));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void emptyPageOfAnotherStudentStillDenies() {
        Fixture f = fixture();
        HttpProblemException ex =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                f.service.pagePublishedResults(
                                        f.authentication,
                                        new MockHttpServletRequest(),
                                        WORKSPACE,
                                        OTHER,
                                        null,
                                        20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
