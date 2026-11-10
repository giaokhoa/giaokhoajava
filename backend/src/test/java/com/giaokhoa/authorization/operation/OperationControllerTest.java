package com.giaokhoa.authorization.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.model.AuthorizationPartition;
import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

class OperationControllerTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID OPERATION = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final UUID GRANT = RandomIds.randomId();
    private static final UUID PRIMARY_PARTITION = RandomIds.randomId();

    private final ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
    private final AuthorizationOperationRepository operations =
            mock(AuthorizationOperationRepository.class);
    private final AuthzPartitionRepository partitions = mock(AuthzPartitionRepository.class);
    private final Authentication authentication = mock(Authentication.class);
    private final OperationController controller =
            new OperationController(executor, operations, partitions);

    @BeforeEach
    void actorResolvedFromSession() {
        when(executor.actorUserId(authentication)).thenReturn(ACTOR);
    }

    @Test
    void pendingAccountOperationIsReportedIncompleteForItsInitiator() {
        operationRow("PENDING", null, "ACCOUNT", "account:" + ACTOR);

        var response = controller.status(OPERATION, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        var view = Objects.requireNonNull(response.getBody());
        assertEquals(OPERATION, view.id());
        assertEquals("PENDING", view.state());
        assertFalse(view.completed());
        assertEquals("PROFILE", view.targetType());
        assertEquals(ACTOR, view.targetId());
        assertNull(view.errorCode());
    }

    @Test
    void succeededWorkspaceOperationIsTheOnlyCompletedState() {
        operationRow("SUCCEEDED", null, "WORKSPACE", "workspace:" + WORKSPACE);

        var view = Objects.requireNonNull(controller.status(OPERATION, authentication).getBody());

        assertTrue(view.completed());
        assertEquals("WORKSPACE", view.targetType());
        assertEquals(WORKSPACE, view.targetId());
    }

    @Test
    void retryableFailureStaysIncompleteAndExposesOnlyTheErrorCode() {
        operationRow("RETRYABLE_FAILURE", "KEYCLOAK_UNAVAILABLE", "GRANT", "grant:" + GRANT);

        var view = Objects.requireNonNull(controller.status(OPERATION, authentication).getBody());

        assertFalse(view.completed());
        assertEquals("KEYCLOAK_UNAVAILABLE", view.errorCode());
        assertEquals("GRANT", view.targetType());
        assertEquals(GRANT, view.targetId());
    }

    @Test
    void operationOutsideTheInitiatorScopeIsNotFound() {
        when(operations.findByIdAndInitiatorUserId(OPERATION, ACTOR)).thenReturn(Optional.empty());
        when(operations.findRecoveryStatus(OPERATION, ACTOR)).thenReturn(Optional.empty());

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> controller.status(OPERATION, authentication));

        assertEquals(HttpStatus.NOT_FOUND, problem.getStatus());
        assertEquals("NOT_FOUND", problem.getCode());
    }

    private void operationRow(
            String state, @Nullable String errorCode, String primaryKind, String primaryOwnerKey) {
        var operation =
                new AuthorizationOperation(
                        OPERATION,
                        ACTOR,
                        WORKSPACE,
                        "key",
                        "a".repeat(64),
                        List.of(PRIMARY_PARTITION),
                        List.of(1L),
                        1L);
        operation.setState(AuthorizationOperation.State.valueOf(state));
        operation.setLastErrorCode(errorCode);
        when(operations.findByIdAndInitiatorUserId(OPERATION, ACTOR))
                .thenReturn(Optional.of(operation));
        when(partitions.findById(PRIMARY_PARTITION))
                .thenReturn(
                        Optional.of(
                                new AuthorizationPartition(
                                        PRIMARY_PARTITION,
                                        AuthorizationPartition.Kind.valueOf(primaryKind),
                                        primaryOwnerKey,
                                        1,
                                        1,
                                        AuthorizationPartition.State.READY)));
    }
}
