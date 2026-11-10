package com.giaokhoa.authorization.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.fence.AuthorizationFence;
import com.giaokhoa.authorization.fence.DependencyManifestResolver;
import com.giaokhoa.authorization.fence.DependencyManifestResolver.Manifest;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import com.giaokhoa.identity.eligibility.IdentityEligibilityService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.support.RandomIds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

class ProtectedActionExecutorTest {
    private static final UUID ACTOR = RandomIds.randomId();
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final String KEY = "workspace:" + WORKSPACE;
    private static final ProtectedActionExecutor.Target TARGET =
            new ProtectedActionExecutor.Target(KEY, List.of("enter"));

    @Test
    void actorOnlyActionStillSynchronizesEligibilityAndUsesThePdpFence() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        Manifest manifest =
                new Manifest(
                        ACTOR, null, List.of(KEY), Map.of(KEY, "recorded-resource"), List.of());
        when(fixture.resolver.resolve(ACTOR, null, List.of(KEY))).thenReturn(manifest);
        when(fixture.fence.acquire(manifest))
                .thenReturn(new AuthorizationFence.Snapshot(manifest, Map.of()));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);
        assertEquals(
                "done",
                fixture.executor.executeForActor(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        List.of(TARGET),
                        () -> "done"));
        InOrder ordered =
                inOrder(fixture.eligibility, fixture.resolver, fixture.fence, fixture.decisions);
        ordered.verify(fixture.eligibility).synchronizeEligibility(ACTOR);
        ordered.verify(fixture.resolver).resolve(ACTOR, null, List.of(KEY));
        ordered.verify(fixture.fence).acquire(manifest);
        ordered.verify(fixture.decisions).isAllowed(any(), any(), any());
        ordered.verify(fixture.fence).atSnapshot(any(), any());
    }

    @Test
    void evaluateUsesTheRecordedResourceIdAndReturnsEveryPair() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);

        var result =
                fixture.executor.evaluate(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET));

        assertTrue(result.allowed(KEY, "enter"));
        verify(fixture.writer, never()).findResourceId(any());
    }

    @Test
    void evaluateDecidesOnTheSnapshotManifestWhenTheFenceReResolvedTheClosure() {
        Fixture fixture = new Fixture(Map.of(KEY, "stale-resource"));
        Manifest snapshotManifest =
                new Manifest(
                        ACTOR,
                        WORKSPACE,
                        List.of(KEY),
                        Map.of(KEY, "snapshot-resource"),
                        List.of());
        when(fixture.fence.acquire(any()))
                .thenReturn(new AuthorizationFence.Snapshot(snapshotManifest, Map.of()));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);

        var result =
                fixture.executor.evaluate(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET));

        assertTrue(result.allowed(KEY, "enter"));
        ArgumentCaptor<RequiredPermissions> asked =
                ArgumentCaptor.forClass(RequiredPermissions.class);
        verify(fixture.decisions).isAllowed(any(), any(), asked.capture());
        assertEquals("snapshot-resource", asked.getValue().permissions().getFirst().resourceId());
        verify(fixture.writer, never()).findResourceId(any());
    }

    @Test
    void evaluateFallsBackToTheNativeResourceNameAndDeniesWhenNoResourceExists() {
        Fixture fixture = new Fixture(Map.of());
        when(fixture.writer.findResourceId(KEY)).thenReturn(Optional.empty());

        var result =
                fixture.executor.evaluate(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET));

        assertFalse(result.allowed(KEY, "enter"));
        verify(fixture.decisions, never()).isAllowed(any(), any(), any());
    }

    @Test
    void executeAbortsWith403BeforeTheActionWhenAnyPairIsDenied() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean();

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                fixture.executor.execute(
                                        fixture.authentication,
                                        new MockHttpServletRequest(),
                                        WORKSPACE,
                                        List.of(TARGET),
                                        () -> {
                                            ran.set(true);
                                            return "done";
                                        }));

        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertFalse(ran.get(), "A denied action must never run");
    }

    @Test
    void evaluateAndReadKeepsFilteredMaterializationInsideTheFence() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(false);
        AtomicBoolean insideFence = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            insideFence.set(true);
                            try {
                                return runSupplier(invocation);
                            } finally {
                                insideFence.set(false);
                            }
                        })
                .when(fixture.fence)
                .atSnapshot(any(), any());

        String result =
                fixture.executor.evaluateAndRead(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET),
                        decisions -> {
                            assertTrue(insideFence.get(), "Read must run before the fence unlocks");
                            assertFalse(decisions.allowed(KEY, "enter"));
                            return "filtered";
                        });

        assertEquals("filtered", result);
        assertFalse(insideFence.get());
        verify(fixture.fence).atSnapshot(any(), any());
    }

    @Test
    void revocationCannotEnterSameFenceBeforePublishedDtoMaterializes() throws Exception {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);
        ReentrantLock partitionLock = new ReentrantLock(true);
        CountDownLatch materializing = new CountDownLatch(1);
        CountDownLatch releaseMaterialization = new CountDownLatch(1);
        CountDownLatch revokeAttempted = new CountDownLatch(1);
        AtomicBoolean revoked = new AtomicBoolean();
        doAnswer(
                        call -> {
                            partitionLock.lock();
                            try {
                                return runSupplier(call);
                            } finally {
                                partitionLock.unlock();
                            }
                        })
                .when(fixture.fence)
                .atSnapshot(any(), any());
        try (var threads = Executors.newFixedThreadPool(2)) {
            var result =
                    threads.submit(() ->
                            fixture.executor.evaluateAndRead(
                                    fixture.authentication,
                                    new MockHttpServletRequest(),
                                    WORKSPACE,
                                    List.of(TARGET),
                                    decisions -> {
                                        assertTrue(decisions.allowed(KEY, "enter"));
                                        materializing.countDown();
                                        try {
                                            assertTrue(
                                                    releaseMaterialization.await(
                                                            5, TimeUnit.SECONDS));
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException("Test interrupted", ex);
                                        }
                                        assertFalse(
                                                revoked.get(),
                                                "Revoke must not commit during DTO materialization");
                                        return "safe";
                                    }));
            assertTrue(materializing.await(5, TimeUnit.SECONDS));
            var revoke =
                    threads.submit(() -> {
                        revokeAttempted.countDown();
                        partitionLock.lock();
                        try {
                            revoked.set(true);
                        } finally {
                            partitionLock.unlock();
                        }
                    });
            assertTrue(revokeAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(revoke.isDone());
            releaseMaterialization.countDown();
            assertEquals("safe", result.get(5, TimeUnit.SECONDS));
            revoke.get(5, TimeUnit.SECONDS);
            assertTrue(revoked.get());
        }
    }

    @Test
    void executeRunsTheActionWhenEveryPairIsAllowed() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);

        String result =
                fixture.executor.execute(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET),
                        () -> "done");

        assertEquals("done", result);
    }

    @Test
    void decisionsAreMaterializedInsideTheConfirmedSnapshotWithPartialAllows() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        fixture.confirmationsToSkip.set(1);
        when(fixture.decisions.isAllowed(any(), any(), any()))
                .thenAnswer(invocation ->
                        "enter"
                                .equals(
                                        invocation
                                                .getArgument(2, RequiredPermissions.class)
                                                .permissions()
                                                .getFirst()
                                                .scope()));
        AtomicInteger materializations = new AtomicInteger();
        String result =
                fixture.executor.evaluateAndRead(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(new ProtectedActionExecutor.Target(KEY, List.of("enter", "read"))),
                        decisions -> {
                            assertTrue(
                                    fixture.snapshotActive.get(),
                                    "DTO must be read under the fence");
                            assertTrue(decisions.allowed(KEY, "enter"));
                            assertFalse(decisions.allowed(KEY, "read"));
                            materializations.incrementAndGet();
                            return "allowed rows";
                        });

        assertEquals("allowed rows", result);
        assertEquals(1, materializations.get(), "A stale snapshot must not run the reader");
        verify(fixture.resolver, times(2)).resolve(ACTOR, WORKSPACE, List.of(KEY));
        assertFalse(fixture.snapshotActive.get(), "The fence closes after materialization");
    }

    @Test
    void aRevisionChangeRestartsThePipelineWithAFreshManifest() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.decisions.isAllowed(any(), any(), any())).thenReturn(true);
        fixture.confirmationsToSkip.set(1);

        var result =
                fixture.executor.evaluate(
                        fixture.authentication,
                        new MockHttpServletRequest(),
                        WORKSPACE,
                        List.of(TARGET));

        assertTrue(result.allowed(KEY, "enter"));
        verify(fixture.resolver, times(2)).resolve(ACTOR, WORKSPACE, List.of(KEY));
    }

    @Test
    void currentEligibilityIsReadBeforeTheFenceAndPdp() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        fixture.executor.evaluate(
                fixture.authentication, new MockHttpServletRequest(), WORKSPACE, List.of(TARGET));
        InOrder order =
                inOrder(fixture.eligibility, fixture.resolver, fixture.fence, fixture.decisions);
        order.verify(fixture.eligibility).synchronizeEligibility(ACTOR);
        order.verify(fixture.resolver).resolve(ACTOR, WORKSPACE, List.of(KEY));
        order.verify(fixture.fence).acquire(any());
        order.verify(fixture.decisions).isAllowed(any(), any(), any());
    }

    @Test
    void anUnreadableCurrentIdentityStopsBeforeDecisionsAndAction() {
        Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
        when(fixture.eligibility.synchronizeEligibility(ACTOR))
                .thenThrow(
                        new HttpProblemException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "IDENTITY_UNAVAILABLE",
                                "Không đọc được danh tính."));
        AtomicBoolean ran = new AtomicBoolean();
        var failure =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                fixture.executor.execute(
                                        fixture.authentication,
                                        new MockHttpServletRequest(),
                                        WORKSPACE,
                                        List.of(TARGET),
                                        () -> ran.getAndSet(true)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertFalse(ran.get());
        verify(fixture.resolver, never()).resolve(any(), any(), any());
        verify(fixture.decisions, never()).isAllowed(any(), any(), any());
    }

    @Test
    void repeatedTargetsPreserveEveryScopeAndDecideEachPairOnce() {
        for (boolean reverse : List.of(false, true)) {
            Fixture fixture = new Fixture(Map.of(KEY, "recorded-resource"));
            when(fixture.decisions.isAllowed(any(), any(), any()))
                    .thenAnswer(invocation ->
                            "read"
                                    .equals(
                                            invocation
                                                    .getArgument(2, RequiredPermissions.class)
                                                    .permissions()
                                                    .getFirst()
                                                    .scope()));
            var denied = new ProtectedActionExecutor.Target(KEY, List.of("membership.revoke"));
            var allowed = new ProtectedActionExecutor.Target(KEY, List.of("read", "read"));
            List<ProtectedActionExecutor.Target> targets =
                    reverse ? List.of(allowed, denied) : List.of(denied, allowed);
            var result =
                    fixture.executor.evaluate(
                            fixture.authentication,
                            new MockHttpServletRequest(),
                            WORKSPACE,
                            targets);
            assertFalse(result.allowed(KEY, "membership.revoke"));
            assertTrue(result.allowed(KEY, "read"));
            assertFalse(result.allAllowed());
            verify(fixture.decisions, times(2)).isAllowed(any(), any(), any());
            AtomicBoolean ran = new AtomicBoolean();
            var failure =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    fixture.executor.execute(
                                            fixture.authentication,
                                            new MockHttpServletRequest(),
                                            WORKSPACE,
                                            targets,
                                            () -> ran.getAndSet(true)));
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
            assertFalse(ran.get());
        }
    }

    private static Object runSupplier(InvocationOnMock invocation) {
        return ((Supplier<?>) invocation.getArgument(1)).get();
    }

    private static final class Fixture {
        final DependencyManifestResolver resolver = mock(DependencyManifestResolver.class);
        final AuthorizationFence fence = mock(AuthorizationFence.class);
        final KeycloakDecisionClient decisions = mock(KeycloakDecisionClient.class);
        final PolicyGraphWriter writer = mock(PolicyGraphWriter.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final IdentityEligibilityService eligibility = mock(IdentityEligibilityService.class);
        final Authentication authentication = mock(Authentication.class);
        final AtomicInteger confirmationsToSkip = new AtomicInteger();
        final AtomicBoolean snapshotActive = new AtomicBoolean();
        final ProtectedActionExecutor executor;

        Fixture(Map<String, String> recordedResourceIds) {
            Manifest manifest =
                    new Manifest(ACTOR, WORKSPACE, List.of(KEY), recordedResourceIds, List.of());
            when(resolver.resolve(eq(ACTOR), eq(WORKSPACE), any())).thenReturn(manifest);
            when(fence.acquire(manifest))
                    .thenReturn(new AuthorizationFence.Snapshot(manifest, Map.of()));
            when(fence.atSnapshot(any(), any()))
                    .thenAnswer(invocation -> {
                        if (confirmationsToSkip.getAndDecrement() > 0) {
                            throw new AuthorizationFence.ClosureChangedException();
                        }
                        snapshotActive.set(true);
                        try {
                            return runSupplier(invocation);
                        } finally {
                            snapshotActive.set(false);
                        }
                    });
            when(jdbc.queryForList(
                            contains("FROM user_account"), eq(UUID.class), any(String.class)))
                    .thenReturn(List.of(ACTOR));
            when(authentication.getName()).thenReturn("keycloak-subject");
            executor =
                    new ProtectedActionExecutor(
                            resolver, fence, decisions, writer, jdbc, eligibility);
        }
    }
}
