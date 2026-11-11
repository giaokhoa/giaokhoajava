package com.giaokhoa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.giaokhoa.authorization.decision.ProtectedActionExecutor;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.infrastructure.tenancy.WorkspaceTransaction;
import com.giaokhoa.resources.ResourcePolicyProjector;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.tenancy.WorkspaceDirectoryService;
import com.giaokhoa.verification.repository.VerificationAuthorizationOperationRepository;
import com.giaokhoa.verification.repository.VerificationSubjectRepository;
import com.giaokhoa.verification.web.VerificationFixtureController;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Khóa cổng profile của route tạo dữ kiện mẫu: controller chỉ được đăng ký khi profile {@code test}
 * hoặc {@code review} đang bật, nên ở production (không bật hai profile đó) route không tồn tại.
 * Các phụ thuộc được mock vì phép kiểm chỉ quan tâm việc đăng ký bean theo profile, không quan tâm
 * hành vi tạo dữ kiện.
 */
class VerificationFixtureProfileTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withBean(
                            ProtectedActionExecutor.class,
                            () -> mock(ProtectedActionExecutor.class))
                    .withBean(
                            ResourcePolicyProjector.class,
                            () -> mock(ResourcePolicyProjector.class))
                    .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
                    .withBean(
                            WorkspaceDirectoryService.class,
                            () -> mock(WorkspaceDirectoryService.class))
                    .withBean(WorkspaceTransaction.class, () -> mock(WorkspaceTransaction.class))
                    .withBean(ResourceRepository.class, () -> mock(ResourceRepository.class))
                    .withBean(
                            ResourceVersionRepository.class,
                            () -> mock(ResourceVersionRepository.class))
                    .withBean(
                            ResourceAssignmentRepository.class,
                            () -> mock(ResourceAssignmentRepository.class))
                    .withBean(
                            VerificationSubjectRepository.class,
                            () -> mock(VerificationSubjectRepository.class))
                    .withBean(
                            VerificationAuthorizationOperationRepository.class,
                            () -> mock(VerificationAuthorizationOperationRepository.class))
                    .withBean(
                            PlatformTransactionManager.class,
                            () -> mock(PlatformTransactionManager.class))
                    // Mock của projector vẫn mang field @PersistenceContext của class thật:
                    // post-processor cần một EntityManagerFactory để gán EntityManager dùng chung,
                    // dù test không gọi tới projector.
                    .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                    .withUserConfiguration(VerificationFixtureController.class);

    @Test
    @DisplayName("Không profile kiểm chứng thì controller tạo dữ kiện mẫu không được đăng ký")
    void notRegisteredWithoutVerificationProfile() {
        runner.run(context ->
                assertEquals(
                        0,
                        context.getBeanNamesForType(VerificationFixtureController.class).length));
    }

    @Test
    @DisplayName("Profile review bật thì controller tạo dữ kiện mẫu được đăng ký đúng một bean")
    void registeredWithReviewProfile() {
        runner.withPropertyValues("spring.profiles.active=review")
                .run(context ->
                        assertEquals(
                                1,
                                context.getBeanNamesForType(VerificationFixtureController.class)
                                        .length));
    }

    @Test
    @DisplayName("Profile test bật thì controller tạo dữ kiện mẫu được đăng ký đúng một bean")
    void registeredWithTestProfile() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context ->
                        assertEquals(
                                1,
                                context.getBeanNamesForType(VerificationFixtureController.class)
                                        .length));
    }
}
