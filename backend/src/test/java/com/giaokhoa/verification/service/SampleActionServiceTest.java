package com.giaokhoa.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.giaokhoa.verification.model.SampleResult;
import com.giaokhoa.verification.model.SampleSubmission;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID SUBMISSION = UUID.randomUUID();

    private final ProtectedActionExecutor executor = mock(ProtectedActionExecutor.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ActivityPolicyProjector activityProjector = mock(ActivityPolicyProjector.class);
    private final ResultPolicyProjector resultProjector = mock(ResultPolicyProjector.class);
    private final CompletionLicenseGuard completionLicense = mock(CompletionLicenseGuard.class);
    private final SampleSubmissionRepository submissions = mock(SampleSubmissionRepository.class);
    private final SampleResultRepository results = mock(SampleResultRepository.class);
    private final SampleActionMutationRepository mutations =
            mock(SampleActionMutationRepository.class);
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

    @Test
    @DisplayName(
            "Sau thu hồi nguồn, submit/grade/publish đều bị chặn bên trong fence; không ghi một phần")
    void licenseRevocationBlocksEveryCompletionMutation() {
        SampleActionService service = service();
        SampleActivity activity = mock(SampleActivity.class);
        when(activity.getState()).thenReturn("ASSIGNED");
        when(activity.acceptsSubmissionAt(any(Instant.class))).thenReturn(true);
        when(activity.getResourceId()).thenReturn(RESOURCE);
        when(activity.getVersionId()).thenReturn(VERSION);
        when(activities.findByWorkspaceIdAndId(WORKSPACE, ACTIVITY))
                .thenReturn(Optional.of(activity));
        SampleResult result = mock(SampleResult.class);
        when(result.getSubmissionId()).thenReturn(SUBMISSION);
        when(results.findByWorkspaceIdAndIdForUpdate(WORKSPACE, ACTIVITY))
                .thenReturn(Optional.of(result));
        SampleSubmission submission = mock(SampleSubmission.class);
        when(submission.getActivityId()).thenReturn(ACTIVITY);
        when(submissions.findByWorkspaceIdAndId(WORKSPACE, SUBMISSION))
                .thenReturn(Optional.of(submission));
        when(mutations.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(executor.execute(any(), any(), eq(WORKSPACE), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        doThrow(
                        new HttpProblemException(
                                HttpStatus.FORBIDDEN,
                                "RESOURCE_LICENSE_INACTIVE",
                                "Nguồn tài nguyên không còn hiệu lực."))
                .when(completionLicense)
                .requireCurrentSources(
                        eq(WORKSPACE), eq(RESOURCE), eq(VERSION), any(Instant.class));

        for (String action : List.of("SUBMIT_ACTIVITY", "GRADE_RESULT", "PUBLISH_RESULT")) {
            HttpProblemException error =
                    assertThrows(
                            HttpProblemException.class,
                            () ->
                                    service.act(
                                            WORKSPACE,
                                            "license-" + action,
                                            completionRequest(action),
                                            mock(Authentication.class),
                                            mock(HttpServletRequest.class)));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
            assertEquals("RESOURCE_LICENSE_INACTIVE", error.getCode());
        }
        verify(completionLicense, times(3))
                .requireCurrentSources(
                        eq(WORKSPACE), eq(RESOURCE), eq(VERSION), any(Instant.class));
        verify(submissions, never()).save(any());
        verify(results, never()).flush();
        verify(periods, never()).countCurrent(any());
    }

    @SuppressWarnings("NullAway")
    private static SampleActionRequest completionRequest(String action) {
        return new SampleActionRequest(
                action,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ACTIVITY,
                "nội dung",
                ACTIVITY,
                "9",
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
        when(resultProjector.recordResultChange(any(), any(), any(), any(), any()))
                .thenReturn(operation);
        when(operations.findStateById(any()))
                .thenReturn(Optional.of(AuthorizationOperation.State.SUCCEEDED));
        return new SampleActionService(
                executor,
                idempotency,
                mock(SourceRevocationCoordinator.class),
                resultProjector,
                activityProjector,
                mock(ResourcePolicyProjector.class),
                completionLicense,
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
                submissions,
                mock(SampleAttemptOpeningRepository.class),
                results,
                mutations,
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
                STUDENT,
                null);
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
                null,
                null);
    }
}
