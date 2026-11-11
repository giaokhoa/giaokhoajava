package com.giaokhoa.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.projection.SourceRevocationCoordinator;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.service.CorrectionService;
import com.giaokhoa.resources.service.ResourceLibraryService;
import com.giaokhoa.resources.service.ResourceShareService;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.ActivityPolicyProjector;
import com.giaokhoa.verification.ResultPolicyProjector;
import com.giaokhoa.verification.dto.SampleActionRequest;
import com.giaokhoa.verification.dto.SampleActionResult;
import com.giaokhoa.verification.model.SampleActivity;
import com.giaokhoa.verification.repository.SampleActionMutationRepository;
import com.giaokhoa.verification.repository.SampleActivityRepository;
import com.giaokhoa.verification.repository.SampleAttemptOpeningRepository;
import com.giaokhoa.verification.repository.SampleResultRepository;
import com.giaokhoa.verification.repository.SampleSubmissionRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationOperationRepository;
import com.giaokhoa.verification.repository.VerificationCourseSubjectRepository;
import com.giaokhoa.verification.repository.VerificationEnrollmentRepository;
import com.giaokhoa.verification.repository.VerificationResourceDependencyRepository;
import com.giaokhoa.verification.repository.VerificationResourceVersionRepository;
import com.giaokhoa.verification.repository.VerificationSubscriptionPeriodRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Khóa ánh xạ lỗi hết kỳ gói của ba action quản lý hoạt động: kỳ gói đã hết thì PDP từ chối trước
 * khi lambda chạy, nên service phải báo SUBSCRIPTION_INACTIVE (vẫn 403) ngay từ tiền kiểm tra chứ
 * không để lọt PERMISSION_DENIED của PDP ra ngoài.
 */
class SampleActionServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ACTIVITY = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();

    private final ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ActivityPolicyProjector activityProjector = mock(ActivityPolicyProjector.class);
    private final WorkspaceTransaction workspaces = mock(WorkspaceTransaction.class);
    private final VerificationSubscriptionPeriodRepository periods =
            mock(VerificationSubscriptionPeriodRepository.class);
    private final SampleActivityRepository activities = mock(SampleActivityRepository.class);
    private final VerificationAuthorizationOperationRepository operations =
            mock(VerificationAuthorizationOperationRepository.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    @Test
    @DisplayName("Gói hết hạn: gia hạn hạn nộp bị chặn bằng SUBSCRIPTION_INACTIVE, không hỏi PDP")
    void extendDeadlineAfterExpiryReportsSubscriptionInactive() {
        when(periods.countCurrent(WORKSPACE)).thenReturn(0L);
        SampleActionService service = service();

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.act(
                                        WORKSPACE,
                                        "key-extend",
                                        extendRequest(),
                                        mock(Authentication.class),
                                        mock(HttpServletRequest.class)));

        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertEquals("SUBSCRIPTION_INACTIVE", problem.getCode());
        verify(executor, never()).execute(any(), any(), any(), any(), any());
        verify(activityProjector, never()).recordActivityChange(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gói hết hạn: mở lượt nộp bị chặn bằng SUBSCRIPTION_INACTIVE, không hỏi PDP")
    void openAttemptAfterExpiryReportsSubscriptionInactive() {
        when(periods.countCurrent(WORKSPACE)).thenReturn(0L);
        SampleActionService service = service();

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.act(
                                        WORKSPACE,
                                        "key-open",
                                        openRequest(),
                                        mock(Authentication.class),
                                        mock(HttpServletRequest.class)));

        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertEquals("SUBSCRIPTION_INACTIVE", problem.getCode());
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "Gói hết hạn: giao lại hoạt động bị chặn bằng SUBSCRIPTION_INACTIVE, không hỏi PDP")
    void reassignAfterExpiryReportsSubscriptionInactive() {
        when(periods.countCurrent(WORKSPACE)).thenReturn(0L);
        SampleActionService service = service();

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () ->
                                service.act(
                                        WORKSPACE,
                                        "key-reassign",
                                        reassignRequest(),
                                        mock(Authentication.class),
                                        mock(HttpServletRequest.class)));

        assertEquals(HttpStatus.FORBIDDEN, problem.getStatus());
        assertEquals("SUBSCRIPTION_INACTIVE", problem.getCode());
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gói còn hiệu lực: tiền kiểm tra cho qua và quyết định PDP được giữ nguyên")
    void extendDeadlineWithActivePeriodReachesPdp() {
        when(periods.countCurrent(WORKSPACE)).thenReturn(1L);
        SampleActionResult expected =
                new SampleActionResult(ACTIVITY, "SUCCEEDED", true, "ACTIVITY", ACTIVITY, null);
        when(executor.execute(any(), any(), eq(WORKSPACE), any(), any())).thenReturn(expected);
        SampleActionService service = service();

        SampleActionResult result =
                service.act(
                        WORKSPACE,
                        "key-live",
                        extendRequest(),
                        mock(Authentication.class),
                        mock(HttpServletRequest.class));

        assertSame(expected, result);
        verify(executor).execute(any(), any(), eq(WORKSPACE), any(), any());
    }

    private SampleActionService service() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(executor.actorUserId(any())).thenReturn(ACTOR);
        when(idempotency.payloadHash(any(), any(), any())).thenReturn("hash");
        when(workspaces.inWorkspace(eq(WORKSPACE), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(activities.findByWorkspaceIdAndId(WORKSPACE, ACTIVITY))
                .thenReturn(Optional.of(mock(SampleActivity.class)));
        AuthorizationOperation operation = mock(AuthorizationOperation.class);
        when(operation.getId()).thenReturn(UUID.randomUUID());
        when(activityProjector.recordActivityChange(any(), any(), any(), any(), any()))
                .thenReturn(operation);
        when(operations.findStateById(any()))
                .thenReturn(Optional.of(AuthorizationOperation.State.SUCCEEDED));
        return new SampleActionService(
                executor,
                idempotency,
                mock(SourceRevocationCoordinator.class),
                mock(ResultPolicyProjector.class),
                activityProjector,
                mock(ResourcePolicyProjector.class),
                mock(ResourceLibraryService.class),
                mock(ResourceShareService.class),
                mock(CorrectionService.class),
                workspaces,
                mock(VerificationWorkspaceRepository.class),
                periods,
                mock(VerificationCourseSubjectRepository.class),
                mock(VerificationResourceVersionRepository.class),
                mock(VerificationResourceDependencyRepository.class),
                mock(VerificationEnrollmentRepository.class),
                activities,
                mock(SampleSubmissionRepository.class),
                mock(SampleAttemptOpeningRepository.class),
                mock(SampleResultRepository.class),
                mock(SampleActionMutationRepository.class),
                operations,
                transactionManager);
    }

    // Ba factory dưới đây cố ý để null mọi trường không thuộc action của mình: record
    // SampleActionRequest coi mọi trường là tùy chọn ở tầng hình dạng, nhưng component của nó
    // không @Nullable trong package @NullMarked nên NullAway biến literal null thành lỗi biên
    // dịch; miễn trừ chỉ trong từng factory, ca test giữ nguyên.
    @SuppressWarnings("NullAway")
    private static SampleActionRequest extendRequest() {
        return new SampleActionRequest(
                "EXTEND_DEADLINE",
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(14),
                null,
                ACTIVITY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @SuppressWarnings("NullAway")
    private static SampleActionRequest openRequest() {
        return new SampleActionRequest(
                "OPEN_ATTEMPT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ACTIVITY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                STUDENT);
    }

    @SuppressWarnings("NullAway")
    private static SampleActionRequest reassignRequest() {
        return new SampleActionRequest(
                "REASSIGN_ACTIVITY",
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(14),
                null,
                ACTIVITY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
