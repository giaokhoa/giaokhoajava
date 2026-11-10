package com.giaokhoa.identity.eligibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.AuthorizationOperationService;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.DesiredProjection;
import com.giaokhoa.authorization.operation.AuthorizationOperationService.PartitionChange;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient;
import com.giaokhoa.identity.keycloak.CurrentIdentityClient.CurrentIdentity;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.TestIds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpServerErrorException;

class IdentityEligibilitySynchronizerTest {
    private static final UUID ACTOR = TestIds.randomId();
    private static final UUID ACCOUNT_PARTITION = TestIds.randomId();
    private static final String SUBJECT = "keycloak-subject-1";
    private static final String EMAIL = "learner@example.test";
    private static final String TARGET = "account:" + ACTOR;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentIdentityClient identities = mock(CurrentIdentityClient.class);
    private final AuthorizationOperationService operations =
            mock(AuthorizationOperationService.class);
    private final IdentityEligibilitySynchronizer synchronizer =
            new IdentityEligibilitySynchronizer(
                    jdbc,
                    identities,
                    operations,
                    mock(com.giaokhoa.subscriptions.PersonalEntitlementProjector.class));

    @Test
    void verifiedSelfRegisteredAccountRecordsTheEligibilitySourceThroughTheAccountFence() {
        account("SELF_REGISTERED", false, EMAIL);
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, true, true));
        recordedOperationSucceeds();

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isPresent());
        PartitionChange change = capturedChange();
        assertEquals(ACCOUNT_PARTITION, change.partitionId());
        assertEquals(1, change.projections().size());
        DesiredProjection projection = change.projections().getFirst();
        assertEquals(TARGET, projection.targetKey());
        assertEquals("workspace.list", projection.scope());
        assertEquals(
                List.of(
                        "IDENTITY_ELIGIBILITY|user="
                                + ACTOR
                                + "|subject="
                                + SUBJECT
                                + "|target="
                                + TARGET
                                + "|scope=workspace.list|scopes=workspace.list,history.read"),
                projection.sourceDescriptors());
        verify(jdbc).update(contains("UPDATE user_account"), eq(EMAIL), eq(true), eq(ACTOR));
    }

    @Test
    void selfRegisteredAccountLosingVerificationRecordsARetraction() {
        account("SELF_REGISTERED", true, EMAIL);
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, false, true));
        recordedOperationSucceeds();

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isPresent());
        PartitionChange change = capturedChange();
        assertEquals(1, change.projections().size());
        assertEquals(List.of(), change.projections().getFirst().sourceDescriptors());
        verify(jdbc).update(contains("UPDATE user_account"), eq(EMAIL), eq(false), eq(ACTOR));
    }

    @Test
    void centerIssuedAccountIsEligibleWithoutAVerifiedEmail() {
        account("CENTER_ISSUED", false, null);
        current(new CurrentIdentity(SUBJECT, "issued-learner", null, false, true));

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isEmpty());
        verifyNoInteractions(operations);
        verify(jdbc, never()).update(contains("UPDATE user_account"), any(), any(), any());
    }

    @Test
    void centerIssuedAccountStillSyncsAChangedVerifiedInput() {
        account("CENTER_ISSUED", false, null);
        current(new CurrentIdentity(SUBJECT, "issued-learner", EMAIL, true, true));
        recordedOperationSucceeds();

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isPresent());
        DesiredProjection projection = capturedChange().projections().getFirst();
        assertEquals(1, projection.sourceDescriptors().size());
        assertTrue(projection.sourceDescriptors().getFirst().startsWith("IDENTITY_ELIGIBILITY|"));
    }

    @Test
    void firstSyncRecordsTheProjectionEvenWhenTheProfileMatchesTheRecord() {
        account("SELF_REGISTERED", true, EMAIL);
        noRecordedProjection();
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, true, true));
        recordedOperationSucceeds();

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isPresent());
        assertEquals(1, capturedChange().projections().getFirst().sourceDescriptors().size());
    }

    @Test
    void anUnchangedProfileRecordsNothing() {
        account("SELF_REGISTERED", true, EMAIL);
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, true, true));

        Optional<AuthorizationOperation> operation = synchronizer.synchronizeEligibility(ACTOR);

        assertTrue(operation.isEmpty());
        verifyNoInteractions(operations);
        verify(jdbc, never()).update(contains("UPDATE user_account"), any(), any(), any());
    }

    @Test
    void anAppliedRetractionDoesNotCreateANewOperationOnEveryRequest() {
        account("SELF_REGISTERED", false, EMAIL);
        noRecordedProjection();
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, false, true));
        when(jdbc.queryForObject(
                        contains("applied_revision > 0"),
                        eq(Boolean.class),
                        eq("account:" + ACTOR)))
                .thenReturn(true);
        assertTrue(synchronizer.synchronizeEligibility(ACTOR).isEmpty());
        verifyNoInteractions(operations);
    }

    @Test
    void aNullAccountRevisionFailsClosedBeforeRecordingAnOperation() {
        account("SELF_REGISTERED", false, EMAIL);
        current(new CurrentIdentity(SUBJECT, "learner", EMAIL, true, true));
        when(jdbc.queryForObject(
                        contains("desired_revision"), eq(Long.class), eq(ACCOUNT_PARTITION)))
                .thenReturn(null);
        var failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> synchronizer.synchronizeEligibility(ACTOR));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        verifyNoInteractions(operations);
    }

    @Test
    void anUnreadableCurrentProfileFailsClosedAndRecordsNothing() {
        account("SELF_REGISTERED", false, EMAIL);
        when(identities.readCurrentIdentity(SUBJECT))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> synchronizer.synchronizeEligibility(ACTOR));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals("IDENTITY_UNAVAILABLE", failure.getCode());
        verifyNoInteractions(operations);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aProfileOfAnotherSubjectFailsClosed() {
        account("SELF_REGISTERED", false, EMAIL);
        current(new CurrentIdentity("another-subject", "learner", EMAIL, true, true));

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> synchronizer.synchronizeEligibility(ACTOR));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        verifyNoInteractions(operations);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void anAccountWithoutADirectoryRowFailsClosed() {
        when(jdbc.queryForList(contains("FROM user_account"), eq(ACTOR))).thenReturn(List.of());

        HttpProblemException failure =
                assertThrows(
                        HttpProblemException.class,
                        () -> synchronizer.synchronizeEligibility(ACTOR));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        verifyNoInteractions(identities);
        verifyNoInteractions(operations);
    }

    private void account(String origin, boolean emailVerified, @Nullable String email) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("keycloak_sub", SUBJECT);
        row.put("email", email);
        row.put("email_verified", emailVerified);
        row.put("origin", origin);
        when(jdbc.queryForList(contains("FROM user_account"), eq(ACTOR))).thenReturn(List.of(row));
        boolean recordedEligible = "CENTER_ISSUED".equals(origin) || emailVerified;
        String recordedHash =
                com.giaokhoa.authorization.projection.ProjectionReconciler.desiredHash(
                        TARGET,
                        "workspace.list",
                        recordedEligible
                                ? List.of(
                                        com.giaokhoa.authorization.projection.ProjectionReconciler
                                                .eligibilityDescriptor(
                                                        ACTOR, SUBJECT, TARGET, "workspace.list"))
                                : List.of());
        when(jdbc.queryForList(contains("FROM authz_projection"), eq(String.class), eq(TARGET)))
                .thenReturn(List.of(recordedHash));
        when(jdbc.queryForList(
                        contains("FROM authz_partition"), eq(UUID.class), eq("account:" + ACTOR)))
                .thenReturn(List.of(ACCOUNT_PARTITION));
        when(jdbc.queryForObject(
                        contains("desired_revision"), eq(Long.class), eq(ACCOUNT_PARTITION)))
                .thenReturn(0L);
    }

    private void noRecordedProjection() {
        when(jdbc.queryForList(contains("FROM authz_projection"), eq(String.class), eq(TARGET)))
                .thenReturn(List.of());
    }

    private void current(CurrentIdentity identity) {
        when(identities.readCurrentIdentity(SUBJECT)).thenReturn(identity);
    }

    private void recordedOperationSucceeds() {
        when(operations.applyDesiredState(eq(ACTOR), isNull(), anyString(), anyString(), any()))
                .thenReturn(mock(AuthorizationOperation.class));
    }

    private PartitionChange capturedChange() {
        ArgumentCaptor<List<PartitionChange>> captor = ArgumentCaptor.captor();
        verify(operations)
                .applyDesiredState(
                        eq(ACTOR),
                        isNull(),
                        eq("identity-eligibility:" + ACTOR + ":1"),
                        anyString(),
                        captor.capture());
        return captor.getValue().getFirst();
    }
}
