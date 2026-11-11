package com.giaokhoa.identity.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.identity.keycloak.IdentityProvisioningClient;
import com.giaokhoa.identity.model.UserAccount;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.identity.service.IdentityService;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.support.RandomIds;
import com.giaokhoa.tenancy.repository.CenterRepository;
import com.giaokhoa.tenancy.service.WorkspaceDirectoryService;
import com.giaokhoa.verification.SystemPracticeService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.RestClientResponseException;

class SessionControllerTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final String KEYCLOAK_SUB = RandomIds.randomId().toString();

    @Test
    @DisplayName(
            "Keycloak trả 404 khi gửi email xác minh thì đáp 404 ACCOUNT_NOT_FOUND thay vì 500")
    void keycloakNotFoundMapsToAccountNotFound() {
        SessionController controller = controllerWithKeycloakStatus(HttpStatus.NOT_FOUND);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> controller.requestVerification(mock(Authentication.class)));

        assertEquals(HttpStatus.NOT_FOUND, problem.getStatus());
        assertEquals(ErrorCodes.ACCOUNT_NOT_FOUND, problem.getCode());
    }

    @Test
    @DisplayName("Keycloak trả 400 khi gửi email xác minh thì đáp 503 IDENTITY_UNAVAILABLE")
    void keycloakBadRequestMapsToIdentityUnavailable() {
        SessionController controller = controllerWithKeycloakStatus(HttpStatus.BAD_REQUEST);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> controller.requestVerification(mock(Authentication.class)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals(ErrorCodes.IDENTITY_UNAVAILABLE, problem.getCode());
    }

    @Test
    @DisplayName("Keycloak trả 500 khi gửi email xác minh thì đáp 503 IDENTITY_UNAVAILABLE")
    void keycloakServerErrorMapsToIdentityUnavailable() {
        SessionController controller =
                controllerWithKeycloakStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> controller.requestVerification(mock(Authentication.class)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatus());
        assertEquals(ErrorCodes.IDENTITY_UNAVAILABLE, problem.getCode());
    }

    private static SessionController controllerWithKeycloakStatus(HttpStatus keycloakStatus) {
        IdentityProvisioningClient provisioning = mock(IdentityProvisioningClient.class);
        doThrow(
                        new RestClientResponseException(
                                "Keycloak error",
                                keycloakStatus.value(),
                                keycloakStatus.getReasonPhrase(),
                                null,
                                null,
                                null))
                .when(provisioning)
                .sendVerifyEmail(KEYCLOAK_SUB);
        return controller(provisioning, account());
    }

    private static SessionController controller(
            IdentityProvisioningClient provisioning, UserAccount account) {
        ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
        when(executor.actorUserId(any())).thenReturn(ACTOR);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(accounts.findById(ACTOR)).thenReturn(Optional.of(account));
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SessionController(
                mock(IdentityService.class),
                mock(WorkspaceDirectoryService.class),
                mock(SystemPracticeService.class),
                provisioning,
                executor,
                accounts,
                mock(CenterRepository.class),
                mock(SubscriptionPeriodRepository.class),
                mock(AuthzProjectionRepository.class));
    }

    private static UserAccount account() {
        return new UserAccount(
                ACTOR,
                KEYCLOAK_SUB,
                "learner-1",
                "learner@example.test",
                false,
                UserAccount.Origin.SELF_REGISTERED,
                "PENDING_VERIFICATION");
    }
}
