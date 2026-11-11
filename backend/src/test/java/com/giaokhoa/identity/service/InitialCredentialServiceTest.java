package com.giaokhoa.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.giaokhoa.identity.keycloak.IdentityProvisioningClient;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

class InitialCredentialServiceTest {
    private static final UUID OPERATOR = RandomIds.randomId();
    private static final UUID CENTER = RandomIds.randomId();

    private final IdentityProvisioningClient provisioning = mock(IdentityProvisioningClient.class);
    private final IssuedStudentRegistrationService registrar =
            mock(IssuedStudentRegistrationService.class);
    private final InitialCredentialService service =
            new InitialCredentialService(provisioning, registrar);

    @DisplayName("Keycloak báo trùng email thì trả EMAIL_TAKEN thay vì USERNAME_TAKEN")
    @Test
    void duplicateEmailReturnsEmailTaken() {
        when(provisioning.createUser(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(
                        conflict(
                                "{\"errorMessage\":\"User with email 'hs1@example.test'"
                                        + " already exists\"}"));

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.issueNewStudent(
                                        OPERATOR, CENTER, "hs1", "Hoc sinh 1", "hs1@example.test"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(ErrorCodes.EMAIL_TAKEN, failure.getCode());
        verifyNoInteractions(registrar);
    }

    @DisplayName("Keycloak báo trùng username thì vẫn trả USERNAME_TAKEN")
    @Test
    void duplicateUsernameReturnsUsernameTaken() {
        when(provisioning.createUser(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(
                        conflict(
                                "{\"errorMessage\":\"User with username 'hs1'"
                                        + " already exists\"}"));

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.issueNewStudent(
                                        OPERATOR, CENTER, "hs1", "Hoc sinh 1", "hs1@example.test"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(ErrorCodes.USERNAME_TAKEN, failure.getCode());
        verifyNoInteractions(registrar);
    }

    @DisplayName("Thân lỗi 409 không nhận ra trường nào thì giữ hành vi cũ USERNAME_TAKEN")
    @Test
    void unrecognizedConflictBodyKeepsUsernameTaken() {
        when(provisioning.createUser(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(conflict("{\"errorMessage\":\"Something else went wrong\"}"));

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.issueNewStudent(
                                        OPERATOR, CENTER, "hs1", "Hoc sinh 1", "hs1@example.test"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals(ErrorCodes.USERNAME_TAKEN, failure.getCode());
        verifyNoInteractions(registrar);
    }

    private static RestClientResponseException conflict(String body) {
        return new RestClientResponseException(
                "Conflict",
                HttpStatus.CONFLICT,
                "Conflict",
                null,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
