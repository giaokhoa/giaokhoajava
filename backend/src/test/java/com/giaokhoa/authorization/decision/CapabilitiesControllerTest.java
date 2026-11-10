package com.giaokhoa.authorization.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.CapabilitiesController.CapabilitiesRequest;
import com.giaokhoa.authorization.decision.CapabilitiesController.TargetInput;
import com.giaokhoa.authorization.decision.ProtectedActionExecutor.Decisions;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

class CapabilitiesControllerTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID OTHER = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();

    @Test
    void aPlatformTargetWithoutIdIsEvaluatedAndReturnedWithoutInventingAnId() {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        Authentication authentication = mock(Authentication.class);
        when(executor.actorUserId(authentication)).thenReturn(ACTOR);
        when(executor.evaluate(any(), any(), eq(WORKSPACE), any()))
                .thenReturn(
                        new Decisions(
                                Map.of(
                                        NativePolicyTemplates.platformKey(),
                                        Map.of("center.list", true))));
        CapabilitiesController controller = new CapabilitiesController(executor);

        var response =
                controller.capabilities(
                        WORKSPACE,
                        new CapabilitiesRequest(
                                List.of(
                                        new TargetInput(
                                                "PLATFORM", null, null, List.of("center.list")))),
                        authentication,
                        new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var target = Objects.requireNonNull(response.getBody()).targets().getFirst();
        assertEquals(
                new CapabilitiesController.TargetDecision(
                        "PLATFORM",
                        null,
                        null,
                        List.of(
                                new CapabilitiesController.ScopeDecision(
                                        "center.list", true, "ALLOWED"))),
                target);
    }

    @Test
    void anIdRequiredTargetWithoutIdIsRejectedBeforeEvaluation() {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        Authentication authentication = mock(Authentication.class);
        when(executor.actorUserId(authentication)).thenReturn(ACTOR);
        CapabilitiesController controller = new CapabilitiesController(executor);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                controller.capabilities(
                                        WORKSPACE,
                                        new CapabilitiesRequest(
                                                List.of(
                                                        new TargetInput(
                                                                "CLASS",
                                                                null,
                                                                null,
                                                                List.of("class.read")))),
                                        authentication,
                                        new MockHttpServletRequest()));

        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals("INVALID_TARGET", problem.getCode());
        verify(executor, never()).evaluate(any(), any(), any(), any());
    }

    @Test
    void aProfileTargetOfAnotherUserIsRejectedAsAnInvalidTarget() {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        Authentication authentication = mock(Authentication.class);
        when(executor.actorUserId(authentication)).thenReturn(ACTOR);
        CapabilitiesController controller = new CapabilitiesController(executor);
        CapabilitiesRequest request =
                new CapabilitiesRequest(
                        List.of(new TargetInput("PROFILE", OTHER, null, List.of("profile.read"))));

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                controller.capabilities(
                                        WORKSPACE,
                                        request,
                                        authentication,
                                        new MockHttpServletRequest()));

        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatus());
        assertEquals("INVALID_TARGET", problem.getCode());
        verify(executor, never()).evaluate(any(), any(), any(), any());
    }

    @Test
    void aProfileTargetOfTheActorIsEvaluated() {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        Authentication authentication = mock(Authentication.class);
        when(executor.actorUserId(authentication)).thenReturn(ACTOR);
        when(executor.evaluate(any(), any(), eq(WORKSPACE), any()))
                .thenReturn(
                        new Decisions(Map.of("account:" + ACTOR, Map.of("profile.read", true))));
        CapabilitiesController controller = new CapabilitiesController(executor);
        CapabilitiesRequest request =
                new CapabilitiesRequest(
                        List.of(new TargetInput("PROFILE", ACTOR, null, List.of("profile.read"))));

        var response =
                controller.capabilities(
                        WORKSPACE, request, authentication, new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(
                Objects.requireNonNull(response.getBody())
                        .targets()
                        .getFirst()
                        .scopes()
                        .getFirst()
                        .allowed());
    }
}
